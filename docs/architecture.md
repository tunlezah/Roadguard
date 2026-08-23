# Roadguard architecture

Roadguard is one Gradle module (`:app`) containing a hand-wired dependency graph, a
foreground-service-owned recorder, and a set of deliberately Android-free policy objects that
hold every decision worth arguing about.

The organising idea is simple: **the recorder must never be blocked, cancelled or reconfigured
by anything else in the app.** Everything below follows from that.

---

## 1. Layers

```
                       ┌──────────────────────────────────────────────┐
   Compose UI          │ MainActivity → RoadguardApp → screens        │
   (may die at         │ MainViewModel, SettingsViewModel, …          │
    any moment)        └───────────────────┬──────────────────────────┘
                                           │ observes StateFlow, sends intents
                       ┌───────────────────▼──────────────────────────┐
   Process-lifetime    │ RoadguardContainer (service locator)         │
   singletons          │ SettingsRepository  StorageManager           │
                       │ ThermalMonitor      LocationEngine           │
                       │ PowerMonitor        MapRepository            │
                       │ WeatherRepository   DiagnosticsCollector     │
                       │ ProtectionCoordinator  DeviceCapabilityProbe │
                       └───────────────────┬──────────────────────────┘
                                           │
                       ┌───────────────────▼──────────────────────────┐
   The recorder        │ RecordingService (LifecycleService, FGS)     │
   (owns the camera)   │   └── RecordingController                    │
                       │         └── CameraSession → CameraX          │
                       └───────────────────┬──────────────────────────┘
                                           │ asks
                       ┌───────────────────▼──────────────────────────┐
   Pure policy         │ ThermalPolicy  StorageBudget  SegmentPlanner │
   (no Android at all) │ RecordingProfileSelector  DeviceTierScorer   │
                       │ ImpactDetector  ProtectionPlanner  PreviewFit│
                       │ SpeedFilter  PowerPolicy                     │
                       └──────────────────────────────────────────────┘
```

The bottom layer is the important one. `ThermalPolicy`, `StorageBudget`,
`RecordingProfileSelector`, `DeviceTierScorer`, `ImpactDetector`, `ProtectionPlanner`,
`SegmentPlanner`, `PreviewFit`, `SpeedFilter` and `PowerPolicy` import nothing from
`android.*`. They take value types in and return value types out. That is why 264 pure-policy
unit tests can exercise the whole decision surface of the app — the thermal ladder, the storage
arithmetic, the event discriminators — on a JVM, in 18 seconds, with no device.

## 2. Why a hand-written container instead of Hilt

`core/RoadguardContainer.kt` is about 200 lines of `by lazy`. This is a deliberate choice, not
an omission:

* the app is a **single module** with roughly a dozen process-lifetime singletons and no
  runtime scoping finer than "the process";
* **start-up order matters** in one place that a DI graph would obscure: storage must be
  reconciled before the recorder can index a segment. Explicit code says that; a set of
  generated bindings does not;
* an annotation processor costs build time and APK size for a graph this shape;
* everything is `lazy`, so opening the app to look at the map does not construct the camera
  capability probe, and constructing the container has no side effects beyond creating a
  coroutine scope.

The scope uses `SupervisorJob`, so a failure in weather, maps or diagnostics cannot cancel the
recorder.

## 3. Recording: who owns the camera

**The service owns the camera, not the Activity.** `RecordingService` is a
`LifecycleService`, and `CameraSession` binds CameraX use cases to *the service's* lifecycle.
The Activity is a viewer: it attaches a surface provider when it wants a preview and detaches
it when it does not. Rotating the phone, backgrounding the app or turning the screen off does
not touch the recording.

Two platform facts shape this (`docs/research/android-platform-restrictions.md`):

1. A `camera`-typed foreground service can keep the camera open with the screen off, but it
   **must be promoted from a resumed, visible Activity**. That visibility at promotion time is
   what latches the process's while-in-use camera grant for the life of the service.
2. Consequently there is no legitimate way to start recording from `BOOT_COMPLETED` on
   targetSdk 35+. Roadguard does not pretend otherwise; auto-start is "start when the app is
   opened, or when power is connected while the app is open", and the UI says so.

A `camera` FGS keeps the *process* important but does not keep the *CPU* awake, so the
controller holds a `PARTIAL_WAKE_LOCK` (12-hour timeout) for the duration of a recording.

### 3.1 The segment loop

```
startSegment() ──► Recorder.start() ──► VideoRecordEvent.Status … ──► rollover due?
      ▲                                                                    │ yes
      │                                                                    ▼
      └──────────────── onFinalize(previous) ◄──── stop() then start() immediately
```

Rollover is `recording.stop()` followed **synchronously, on the same thread, immediately** by
`recorder.prepareRecording(...).start(...)`. CameraX's `Recorder` state machine explicitly
queues a start issued while it is `STOPPING` and services it on finalize; that is the
minimum-gap path AndroidX offers. A small gap remains unavoidable — the video `MediaCodec` is
stopped and the next segment needs a fresh keyframe — and `docs/testing.md` records that this
gap has not been measured on hardware.

`SegmentPlanner` decides *whether* to roll over, from one prioritised set of reasons:

| Priority | Reason | Why it wins |
| --- | --- | --- |
| 1 | `RecorderError` | something is already wrong; act now |
| 2 | `SegmentComplete` | the ordinary case |
| 3 | `Reconfiguration` | a queued profile change (thermal, settings) |
| 4 | `StorageCleanup` | trimming can wait for a boundary |

`MIN_SEGMENT_MS = 20_000` guards against a pathological loop of 1-second files.

**Reconfiguration only ever happens at a segment boundary.** A thermal step-down or a settings
change is *queued*, and applied when the current file closes. Nothing in Roadguard rebinds the
camera mid-segment, because rebinding mid-segment means a truncated file.

### 3.2 Failure handling

`RecordingController.handleFinalizeError` classifies every `VideoRecordEvent.Finalize.ERROR_*`
value separately rather than treating "an error happened" as one case: insufficient storage
triggers a trim and retry, an encoding error retries with a reduced profile, a source-inactive
error rebinds the camera, and an unknown error backs off. `restartWithBackoff` gives up after
`MAX_CONSECUTIVE_FAILURES = 5` and surfaces the reason rather than spinning.

## 4. Camera orientation — the boring, normal way

The specification is emphatic (§3): do not invent a camera-angle system. Roadguard does not.

* An `OrientationEventListener` reports device rotation; after a `SETTLE_MS = 700`
  debounce it is fed to `UseCase.snapToSurfaceRotation(degrees)` and thence to
  `setTargetRotation(...)` on both `Preview` and `VideoCapture`.
* Because the container is MPEG-4, **CameraX writes rotation as a composition-matrix hint and
  does not rotate pixels.** The encoder therefore always sees the same sensor-natural frame
  size, and no rotation ever causes a reconfiguration. `Recorder` latches the rotation at the
  start of each segment, which is exactly the segment-boundary rule above.
* Portrait phone → portrait video. Landscape phone → landscape video. That is the whole model.

## 5. Preview versus recording: a structural guarantee

Preview zoom is display-only, and this is enforced by construction rather than by care:

* `PreviewFit` returns **pure numbers** — a scale, two biases, and some reporting fields. They
  are consumed only by the composable that draws the viewfinder, as a `graphicsLayer` scale
  plus a clip.
* Roadguard **never sets a `ViewPort`** on its `UseCaseGroup`. `javap` on
  `camera-core-1.6.1.aar` confirms `ViewPort` propagates into
  `VideoCapture.setViewPortCropRect(Rect)`, which crops the recorded stream. So there is no
  `ViewPort` to leak.
* `CameraControl.setZoomRatio()` **does** change the sensor crop and therefore the recording.
  It is wired exclusively to the separate, advanced "recording zoom" setting, which defaults
  to 1.0× and warns about the reduced field of view.

`PreviewFit.Auto` fills the panel up to `AUTO_MAX_FILL_ZOOM = 1.35f` and then stops, preferring
a letterbox to discarding more than about a quarter of one dimension. When it does crop
vertically it biases the visible window `ROAD_BIAS = 0.30f` down the hidden height, because a
windscreen-mounted phone spends the top of its frame on sky.

The main screen shows the effective preview zoom and, when Auto is cropping, says so — so the
user can see the difference between what they are watching and what is being written.

## 6. Overlays burned into the video

`overlay/VideoOverlayEffect.kt` uses `androidx.camera.effects.OverlayEffect` targeting
**`VIDEO_CAPTURE` only**, with `QUEUE_DEPTH = 0`. Targeting only video means the effect never
touches the preview stream and never forces stream sharing.

The one hard part is orientation. The overlay canvas lives in *camera buffer* coordinates and
is transformed by the same texture matrix as the camera image, so naively drawn text comes out
sideways. `OverlayRenderer.buildDisplayMatrix` builds a matrix that undoes
`frame.rotationDegrees` and `frame.isMirroring` relative to `frame.cropRect`, and the HUD is
laid out in that upright space. No sensor-angle arithmetic is involved; it is the frame's own
declared transform, inverted.

Redraw happens only when the *content* changes, so a HUD that ticks once a second costs one
`lockCanvas` per second rather than one per frame.

The on-screen HUD is separate: plain Compose widgets over the viewfinder. Free, and immune to
the preview/recording distinction.

## 7. Thermal management

A dedicated engine, not scattered checks. `ThermalMonitor` gathers signals, `ThermalPolicy`
turns a reading into a `ThermalLevel` with hysteresis, and `ThermalPolicy.planFor` maps a level
to a complete `ThermalPlan` of ceilings.

Principles, in order:

1. **Recording is never stopped for heat.** Even at `Critical` the plan keeps recording.
2. **Spend the cheapest thing first.** Display, map and preview are shed before recording
   quality is touched — they cost real power and contribute nothing to the evidence. At
   `Elevated`, recording quality is untouched by design.
3. **Never flap.** Escalation is immediate; de-escalation needs the cooler reading to hold for
   `DEESCALATE_HOLD_MS = 90_000` and then steps down one level at a time.
4. **Act before the platform throttles.** `getThermalHeadroom()` is used as an early warning at
   0.80 even when the coarse status still says everything is fine.

Every `ThermalPlan` field is a *ceiling*, never a command: the recorder combines it with the
user's settings and the device profile and only ever reduces. Full detail in
`docs/thermal-management.md`.

## 8. Device capability, and what "Auto" means

`DeviceCapabilityProbe` reads runtime facts — `isLowRamDevice`, total RAM, the `cpufreq` clock
ceiling, Camera2 hardware level, whether a *hardware* encoder reports 1080p30, plus a short CPU
probe — and `DeviceTierScorer` turns them into `Baseline` / `Standard` / `Capable` **with the
reasons attached**, which Diagnostics shows verbatim.

No model name appears anywhere in this path. The Moto G04 is expected to land in `Baseline`
because of its RAM, core mix and clock ceiling — not because it is a Moto G04.

`RecordingProfileSelector` then answers the specification's actual question: not "the highest
thing the hardware admits to" but **the highest quality this device can sustain for hours**.
So `AUTO_CEILING` is `HD` for `Baseline` and `FHD` for both `Standard` *and* `Capable`: 4K on a
phone is a well-known thermal cliff, and a slightly lower-quality recording that survives the
drive beats a better one that stops. 4K remains available manually, with a warning.

Selection order: requested → thermal step-down → what the camera and a hardware encoder
actually support → frame-rate caps → bitrate (only overridden when deliberately reducing).
Every step appends a plain-English line to `rationale`, which Diagnostics displays, so "why did
Auto pick 720p?" always has an answer on the device.

## 9. No codec setting

CameraX 1.6.x gives an application **no way to choose the video encoder**. `Recorder` derives
it from the device's own encoder profiles, and the only public override,
`Recorder.Builder.setVideoMimeType`, exists solely in the 1.7 alpha line behind
`@ExperimentalMimeTypeApi`.

Roadguard will not put an alpha camera stack in the path of the one thing it must not get
wrong, and it will not ship a setting it cannot honour. So:

* there is no codec setting;
* `RecordingProfileSelector.predictCodec` **predicts** the codec (for bitrate arithmetic and
  for display) rather than selecting it;
* the codec actually used is read back from the produced file and shown on Diagnostics.

## 10. Storage

`StorageLayout` puts everything under the app's own external files directory: no storage
permission on any supported API level, a plain `File` write path with no SAF round trips, a
`.nomedia` so thousands of loop segments stay out of the user's gallery, and — the reason it is
a `Context.getExternalFilesDirs()` entry rather than a fixed path — the ability to sit on the
Moto G04's microSD card by choosing a different volume. The trade-off (uninstalling deletes the
footage) is stated in the UI and in `docs/storage.md`.

**Segments are never moved or renamed after they are written.** Protection is a sidecar file
plus a Room row, so there is no window in which a half-finished move loses the very footage an
event was protecting — and protection survives loss of the index.

`StorageBudget` is pure arithmetic: a reserve of `max(1 GiB, 4% of volume)` capped at 4 GiB,
trimming at 0.97 of the effective budget down to 0.90, and `planCleanup` that keeps the newest
two segments no matter what the budget says. See `docs/storage.md`.

`StorageReconciler` runs at start-up and handles five documented divergence cases between the
filesystem and the index. Its bias is always to **keep footage**: unreadable or unexpected
files are quarantined, never deleted.

## 11. Events

`ImpactDetector` is a multi-stage detector, not a threshold: rolling 4-second history →
candidate window → feature extraction (peak, energy, duration above half peak, horizontal
fraction) → discrimination (vertical-dominated inputs are road surface; a moving gravity vector
before the peak means the phone was handled) → GNSS speed context → weighted confidence against
a per-sensitivity bar, plus a cooldown.

`ProtectionPlanner` maps an event timestamp plus the pre/post window onto segments by
**overlap, not containment**, so an event near a boundary protects both files; in-progress
segments are considered only up to `now`.

Detail, and an honest statement of what is and is not validated, in `docs/event-detection.md`.

## 12. Maps

MapLibre Native, rendering a bundled style from a local PMTiles archive — `pmtiles://file://…`,
a scheme proven present in the shipped `libmaplibre.so`. Glyphs and sprites are bundled as
assets under `asset://map/…`, so **nothing at runtime contacts a tile or font server**. The
style JSON carries a `__PMTILES_URI__` placeholder that `MapStyleProvider` substitutes with the
installed archive's absolute path.

The style is cut to 18 layers. Published vector styles run to 200–324; the Moto G04's Mali-G57
is an **MP1** part (a single shader core), and the whole road network is expressible as two
layers using `match` on the Protomaps Basemap `kind` attribute, so width and colour select the
road hierarchy without a layer per class. See `docs/offline-maps.md`.

## 13. UI

Compose with Material 3. `RoadguardWindowInfo` uses `currentWindowAdaptiveInfo()` and the
actual window dimensions — never a hard-coded aspect ratio — to choose the split: video above
map in portrait, video left of map in landscape, each about half the window.

No `android:screenOrientation` in the manifest. The activity applies the user's orientation
mode at runtime, and declares `configChanges` so a rotation does not blink the preview. The
recording is owned by the service and is unaffected either way.

Icons are stock Material icons, fetched verbatim from `google/material-design-icons` by
`tools/fetch_material_icons.py` and checked by `tools/check_material_icons.py`. Nothing is
invented; §41 is explicit about that. Only `material-icons-core` is a dependency —
`material-icons-extended` would add several MB.

Four themes: Light, Dark, System and OLED-black.

## 14. What is intentionally absent

* **No cloud anything.** No upload, no backup, no accounts, no analytics, no crash reporting.
  CI fails the build if a dependency matching a list of known telemetry SDKs appears on the
  release runtime classpath.
* **No auto-start on boot** — see §3.
* **No codec setting** — see §9.
* **No HDR.** It narrows encoder support and complicates playback of evidence footage for no
  evidential gain.
* **No `ViewPort`** — see §5.
* **No background location permission.** A location-typed foreground service covers
  while-in-use access.

## 15. Where to look

| Concern | Start here |
| --- | --- |
| Recording loop and failure handling | `recording/RecordingController.kt` |
| Camera binding, orientation | `camera/CameraSession.kt`, `camera/CameraOrientationTracker.kt` |
| Preview versus recording | `camera/PreviewFit.kt` |
| Thermal ladder | `thermal/ThermalPolicy.kt` |
| Storage arithmetic | `storage/StorageBudget.kt` |
| Start-up repair | `storage/StorageReconciler.kt`, `storage/Mp4Inspector.kt` |
| Auto quality | `capability/RecordingProfile.kt`, `capability/DeviceTier.kt` |
| Event detection | `event/ImpactDetector.kt`, `event/ProtectionPlanner.kt` |
| Offline map install | `map/MapRepository.kt`, `map/MapDownloader.kt`, `map/MapInstaller.kt` |
| Dependency graph | `core/RoadguardContainer.kt` |
