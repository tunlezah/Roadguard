# Research notes

These are the working research documents written before and during implementation. They are
kept verbatim rather than summarised, because the value of a research note is the evidence
trail — the API signature that was actually checked, the page that was actually fetched — and
that survives summarising badly.

Each note leads with a **bottom line** so you do not have to read 1,000 lines to find the
decision.

## Completed notes

| Note | Lines | What it settled |
| --- | --- | --- |
| [`camera-pipeline.md`](camera-pipeline.md) | 1,484 | CameraX 1.6.1 as the recording stack; two bound use cases and **no `ViewPort`** (a `ViewPort` propagates into `VideoCapture.setViewPortCropRect` and crops the *recording*); orientation solved with `OrientationEventListener` → `UseCase.snapToSurfaceRotation`, with MP4 rotation written as a container hint so the encoder never reconfigures on rotation; segment rollover by `stop()` then an immediate same-thread `start()`, which the `Recorder` state machine queues; `Recorder` aborts below **50 MiB** free. |
| [`codecs-and-encoding.md`](codecs-and-encoding.md) | 1,284 | H.264 vs HEVC vs AV1 for a dashcam; storage-per-hour arithmetic; and the discovery that **CameraX 1.6.x exposes no codec setter at all** (`Recorder.Builder.setVideoMimeType` exists only in the 1.7 alpha line, behind `@ExperimentalMimeTypeApi`), which is why Roadguard ships no codec setting and *predicts* rather than selects. |
| [`thermal-apis.md`](thermal-apis.md) | 865 | Which thermal signals exist and how much to trust each (`getCurrentThermalStatus`, `getThermalHeadroom`, battery temperature); a ranked model of what actually heats a phone while recording; and the split between mitigations that are safe mid-recording and those that need a new recording session. |
| [`android-platform-restrictions.md`](android-platform-restrictions.md) | 1,025 | A single `camera\|location` (plus `microphone` when enabled) foreground service, promoted with `ServiceCompat.startForeground` **from a resumed, visible Activity** — that visibility is what latches the while-in-use camera grant that lets recording survive the screen going off. Also: why nothing can auto-start the recorder from `BOOT_COMPLETED` on targetSdk 35+. |
| [`overlay-embedding.md`](overlay-embedding.md) | 855 | `androidx.camera.effects.OverlayEffect` targeting **`VIDEO_CAPTURE` only**, with `queueDepth = 0`; why the cost is one extra texture fetch inside a pass CameraX already inserts; and the one thing that must not be got wrong — the overlay canvas is in camera-buffer coordinates, so text needs a matrix that undoes `frame.rotationDegrees` and `frame.isMirroring`. |
| [`offline-maps.md`](offline-maps.md) | 917 | MapLibre Native + PMTiles + the Shortbread 1.0 schema; proof from `strings libmaplibre.so` that the shipped `.so` contains a full `PMTilesFileSource` and accepts `pmtiles://file:///…`; why the style is cut to 18 layers rather than the 200-plus of published styles (the Moto G04's Mali-G57 is **MP1** — one shader core). |
| [`weather-australia.md`](weather-australia.md) | 111 | Whether §22's weather feature may be built at all. BoM blocks automated access and points at a charged service; Open-Meteo needs no key, no account and no payment, so weather ships on Open-Meteo with coordinates rounded to ~1.1 km before they leave the device. |

## Notes that were planned and **not** written

Research was fanned out across background agents. Three of those agents died mid-flight, and
the notes below were never produced. They are listed rather than quietly dropped, because the
absence is the honest signal about where the design rests on reasoning instead of on a written
evidence trail:

* `location-and-gnss.md` — GNSS fix behaviour, Australian A-GNSS availability offline, speed
  filtering.
* `event-detection.md` — published collision and telematics g-force figures.
* `storage.md` — filesystem behaviour under continuous write on low-end eMMC.
* `device-hardware.md` — Moto G04 / Edge 60 Fusion silicon, encoder and sensor detail.
* `dashcam-feature-survey.md` — what commercial dashcams do, and what their defaults are.
* `architecture-stack.md` — dependency-injection and module-boundary options.
* `night-recording-and-ui.md` — low-light recording behaviour and night-legible UI.
* `resolution-aspect-and-preview-fit.md` — aspect-ratio and preview-fit survey.

Where a decision would have depended on one of these, the code says so in its KDoc and the
top-level document says so in prose:

* event-detection thresholds are marked as **starting points from published figures and from
  the physics of the discriminators, not from Roadguard measurements** — see
  [`../event-detection.md`](../event-detection.md);
* device-tier thresholds are marked the same way, with the exact on-device figures needed to
  replace them — see [`../device-profiles.md`](../device-profiles.md);
* the defaults survey that `dashcam-feature-survey.md` would have provided is replaced by
  [`../feature-research.md`](../feature-research.md), which states plainly which defaults come
  from the specification, which from platform constraints, and which are reasoned choices.

## Reading order

If you are new to the codebase, read [`../architecture.md`](../architecture.md) first, then
`camera-pipeline.md`, then `android-platform-restrictions.md`. Those three cover the parts
where a wrong assumption costs a recording.
