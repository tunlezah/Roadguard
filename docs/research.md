# Research summary

Roadguard was built from evidence rather than from recollection. This document is the index and
the bottom lines; the full working notes, with the API signatures actually checked and the pages
actually fetched, are in [`research/`](research/).

The method was deliberate: where a design decision depended on how a library or the platform
*actually* behaves, the answer came from `javap` on the shipped AAR, `strings` on the shipped
native library, `maven-metadata.xml`, or a build spike — not from memory or from a blog post.
Several findings below contradict what the documentation implies, which is exactly why.

---

## 1. The findings that changed the design

### 1.1 A `ViewPort` crops the recording

`javap` on `camera-core-1.6.1.aar` shows `ViewPort` propagating into
`VideoCapture.setViewPortCropRect(Rect)`. So a `ViewPort` set to make the preview fill a
half-height panel would **crop the recorded video** — the exact thing §74 forbids.

**Consequence:** Roadguard binds its use cases in a `UseCaseGroup` with **no `ViewPort` at
all**, and preview fitting is a pure display transform. This is the single most load-bearing
finding in the project.

### 1.2 CameraX 1.6 cannot choose a codec

`Recorder.Builder` in 1.6.1 exposes **no** codec setter. `setVideoMimeType` exists only in the
1.7 alpha line, annotated `@ExperimentalMimeTypeApi`.

**Consequence:** the codec setting was removed rather than shipped as a lie. `chooseCodec`
became `predictCodec`; the real codec is read back from the file and shown in Diagnostics. See
[`research/codecs-and-encoding.md`](research/codecs-and-encoding.md).

### 1.3 MP4 rotation is a container hint, not a pixel rotation

Because the output container is MPEG-4, CameraX writes rotation as a composition-matrix
orientation hint and **does not rotate pixels**. The encoder therefore always sees the same
sensor-natural frame size.

**Consequence:** rotating the phone costs nothing — no reconfiguration, no rebind, no
interruption. Orientation is `OrientationEventListener` → `UseCase.snapToSurfaceRotation` and
nothing more, which is exactly the "boring normal camera app" model §3 demands. See
[`research/camera-pipeline.md`](research/camera-pipeline.md).

### 1.4 A camera FGS must be promoted from a *visible* Activity

The while-in-use camera grant that lets recording continue with the screen off is latched at the
moment `startForeground` is called, and only if a resumed Activity is visible then.

**Consequence:** there is no legitimate way to start recording from `BOOT_COMPLETED` on
targetSdk 35+. Roadguard does not claim to, and the UI explains what auto-start actually means.
See [`research/android-platform-restrictions.md`](research/android-platform-restrictions.md).

### 1.5 A camera FGS does not keep the CPU awake

The service type keeps the *process* important. Video-only capture (no microphone) still needs a
`PARTIAL_WAKE_LOCK` or the encode loop can be starved with the screen off.

**Consequence:** `RecordingController` holds one for the duration of a recording, with a 12-hour
timeout so a bug cannot pin the CPU forever.

### 1.6 `Recorder` aborts below 50 MiB free

A hard, non-configurable floor inside CameraX: `ERROR_INSUFFICIENT_STORAGE`.

**Consequence:** the storage reserve is `max(1 GiB, 4% of volume)` — orders of magnitude above
the floor, because by the time you are near it you have already lost. See
[`docs/storage.md`](storage.md).

### 1.7 The overlay canvas is in camera-buffer coordinates

`OverlayEffect`'s canvas is transformed by the same texture matrix as the camera image, so
naively drawn text comes out sideways whenever `Frame.getRotationDegrees() != 0`.

**Consequence:** `OverlayRenderer.buildDisplayMatrix` inverts `frame.rotationDegrees` and
`frame.isMirroring` relative to `frame.cropRect`, and the HUD is laid out in that upright space.
No sensor-angle arithmetic — the frame's own declared transform, inverted. See
[`research/overlay-embedding.md`](research/overlay-embedding.md).

### 1.8 MapLibre's shipped `.so` speaks PMTiles

`strings libmaplibre.so` lists the supported URI schemes as `asset://`, `file://`, `mbtiles://`
and `pmtiles://`, and contains a complete `PMTilesFileSource` (PMTiles v3).

**Consequence:** the offline map is a single PMTiles file read directly as
`pmtiles://file:///…` — no SQLite, no tile server, no custom source implementation. See
[`research/offline-maps.md`](research/offline-maps.md).

### 1.9 Segment rollover has a documented minimum-gap path

`Recorder`'s state machine explicitly queues a `start()` issued while it is `STOPPING` and
services it on finalize.

**Consequence:** rollover is `stop()` followed synchronously and immediately by `start()` on the
same thread. A gap remains — the video `MediaCodec` stops and the next segment needs a fresh
keyframe — and Roadguard says so rather than claiming "seamless" (see
[`docs/testing.md`](testing.md)).

### 1.10 The Bureau of Meteorology blocks automated access

Fetching `bom.gov.au` returns a block page stating the site *"does not support web scraping"*
and pointing programmatic users at a registered service where *"charges apply to most data
products"*.

**Consequence:** §22's preferred source is unusable. Open-Meteo qualifies (no key, no
registration, no payment, CC-BY 4.0), so weather ships on Open-Meteo with coordinates rounded to
~1.1 km. See [`research/weather-australia.md`](research/weather-australia.md).

### 1.11 The baseline GPU is single-core

The Moto G04's Mali-G57 is an **MP1** part. Published vector map styles run to 200–324 layers,
each at least one draw call per tile per frame.

**Consequence:** an 18-layer style, with the whole road network as two layers using `match` on the
schema's `kind` attribute.

## 2. Toolchain findings

Each of these cost a build failure, so they are recorded in [`docs/build.md`](build.md) §3
rather than left as folklore:

* AGP 9 has **built-in Kotlin support**; applying `org.jetbrains.kotlin.android` is a hard
  error.
* Compose 1.12.0 requires `compileSdk` ≥ 37 and AGP ≥ 9.1.0.
* `jvmToolchain(17)` fails when only JDK 21 is installed; `compileOptions` does not.
* `android.defaults.buildfeatures.buildconfig` was removed in AGP 9.
* aapt2 rejects `--` inside an XML comment.
* `android:Theme.DeviceDefault.DayNight.NoActionBar` does not exist.
* **Concurrent Gradle builds corrupt Kotlin's incremental cache**, and the symptom is dozens of
  bogus "unresolved reference" errors in untouched files — which is why `tools/gradle-serial.sh`
  exists.

Every dependency version in `gradle/libs.versions.toml` was checked against the publishing
repository's `maven-metadata.xml` and then proven by assembling both APKs and running the tests.

## 3. Decisions taken on reasoning, and labelled as such

Where research could not settle a number, the number is a documented starting point and the code
says so:

| Decision | Basis | Where it is stated |
| --- | --- | --- |
| Thermal thresholds (0.80 / 0.92 / 0.99 headroom) | reasoning about `getThermalHeadroom` semantics and rebind cost | [`thermal-management.md`](thermal-management.md) |
| Event thresholds (2.5 g medium, etc.) | published telematics figures and the physics of the discriminators | [`event-detection.md`](event-detection.md) |
| Device-tier thresholds | chosen to sit between common hardware tiers | [`device-profiles.md`](device-profiles.md) |
| Bitrate model (0.10 / 0.07 bpp) | mid-range values for a moving scene | [`benchmarking.md`](benchmarking.md) |
| `AUTO_MAX_FILL_ZOOM = 1.35` | the point past which Auto is discarding too much road scene | `camera/PreviewFit.kt` |
| 18-layer map style | draw-call reasoning about an MP1 GPU | [`offline-maps.md`](offline-maps.md) |

## 4. Research that was planned and not done

Three of the background research agents died mid-flight. Nine planned notes were never written,
and they are listed individually in [`research/README.md`](research/README.md) rather than
quietly dropped — most consequentially the collision-and-telematics note behind the event
thresholds, and the dashcam feature survey (whose product conclusions are replaced by
[`feature-research.md`](feature-research.md), with its sources honestly described).

## 5. What research could not answer, and nothing else can

No physical device and no emulator were available. So every question of the form "how does this
actually behave on a phone" is open: sustained thermal behaviour, real bitrates, the rollover
gap in milliseconds, battery drain, map frame rate, GNSS time-to-first-fix, and whether the
event thresholds fire correctly in a real car.

[`docs/benchmarking.md`](benchmarking.md) §4 lists those gaps explicitly and §5 is the procedure
for closing them. No figure has been invented to fill any of them.
