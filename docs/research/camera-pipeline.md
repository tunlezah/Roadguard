# Roadguard — CameraX / Camera2 recording pipeline, orientation and capability probing

## Bottom line

Build Roadguard's recorder on **CameraX 1.6.1** (`androidx.camera:camera-*:1.6.1`) with exactly **two** bound use cases — `Preview` + `VideoCapture<Recorder>` — bound to a **foreground-service-owned `LifecycleRegistry`**, never to an Activity lifecycle, and **never** inside a `UseCaseGroup`/`SessionConfig` that carries a `ViewPort`. A `ViewPort` provably crops the *recorded* stream and forces an extra OpenGL pass, so all UI-driven crop/zoom must be display-only (`PreviewView.ScaleType.FILL_CENTER`, or `CameraXViewfinder(contentScale = ContentScale.Crop)`), and `CameraControl.setZoomRatio()` must be treated as a *recording-affecting* control, not a preview control. Orientation is solved with the plain, boring, normal-camera-app model: an `OrientationEventListener` → `UseCase.snapToSurfaceRotation(degrees)` → `videoCapture.setTargetRotation(...)` + `preview.setTargetRotation(...)`; because the output container is MPEG-4, CameraX writes the rotation as an **MP4 composition-matrix orientation hint and does not rotate pixels**, so the encoder always sees the same sensor-natural landscape frame size (no reconfiguration when the phone rotates) and `Recorder` latches the rotation at the start of each segment. Segment rollover must be done by calling `recording.stop()` and then **synchronously, on the same thread, immediately** calling `recorder.prepareRecording(...).start(...)`: the `Recorder` state machine explicitly queues a start issued while it is in `STOPPING` and auto-services it on finalize, which is the minimum-gap path AndroidX offers (a small gap is unavoidable — the video `MediaCodec` is stopped and the next segment must wait for a fresh keyframe). Storage safety has a hard floor you do not control: `Recorder` aborts any recording with `ERROR_INSUFFICIENT_STORAGE` once free space drops below **50 MiB**, so Roadguard's loop-delete must maintain a far larger reserve. Skip HDR (`HLG10`), skip video stabilization on the Moto G04 baseline, skip concurrent front+rear as a shipping requirement, and probe all three at runtime.

## Evidence key

- **[DOCUMENTED]** — stated in official Android/AndroidX documentation, an official AndroidX API signature file (`api/*.txt`), official AOSP/AndroidX source javadoc, Google Maven metadata, or an OEM's own spec page. URL given.
- **[INFERRED]** — a conclusion reasoned from documented facts; the reasoning chain is written out.
- **[UNVERIFIED]** — plausible but not confirmed by a source I read; needs a test.
- **NOT VERIFIED — needs on-device measurement** — requires running code on a real Moto G04 / Edge 60 Fusion. No measurements were performed in this research session.

A note on source classes used below. Three source types are treated as **[DOCUMENTED]**:
1. developer.android.com / source.android.com pages (URLs cited inline).
2. **AndroidX API signature files** — e.g. `https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-video/api/1.6.0-beta01.txt`. These are the frozen, machine-generated public-API descriptions for the 1.6 release branch and are the authoritative answer to "does this method exist in 1.6.x?".
3. **AndroidX / AOSP source + javadoc** — e.g. `https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-video/src/main/java/androidx/camera/video/Recorder.java`. Note that `androidx-main` is the **tip** (1.7-alpha), so every behavioural claim taken from source is cross-checked against the 1.6 signature file before being relied on. Where tip and 1.6 differ, this document says so explicitly.

---

## 1. Exact artifact set and versions (as of 2026-08-22)

**[DOCUMENTED]** https://developer.android.com/jetpack/androidx/releases/camera — CameraX channel state: **Stable 1.6.1**, no RC, no Beta, Alpha `1.7.0-alpha03`. `1.6.0` released **2026-03-25**; `1.6.1` released **2026-05-06**.

**[DOCUMENTED]** Google Maven group index `https://dl.google.com/dl/android/maven2/androidx/camera/group-index.xml` confirms `1.6.1` is published for camera-core, camera-camera2, camera-camera2-pipe, camera-lifecycle, camera-video, camera-view, camera-compose, camera-effects, camera-extensions, camera-mlkit-vision.

### Use these exactly

```kotlin
// version catalog
val cameraX = "1.6.1"

implementation("androidx.camera:camera-core:1.6.1")
implementation("androidx.camera:camera-camera2:1.6.1")      // pulls camera-camera2-pipe:1.6.1
implementation("androidx.camera:camera-lifecycle:1.6.1")
implementation("androidx.camera:camera-video:1.6.1")

// pick ONE viewfinder path:
implementation("androidx.camera:camera-view:1.6.1")          // View path: PreviewView
// or
implementation("androidx.camera:camera-compose:1.6.1")       // Compose path: CameraXViewfinder
implementation("androidx.camera.viewfinder:viewfinder-core:1.6.1")
implementation("androidx.camera.viewfinder:viewfinder-compose:1.6.1")
```

### Artifact traps

| Trap | Fact | Evidence |
|---|---|---|
| Viewfinder group ID changed | The old `androidx.camera:camera-viewfinder` tops out at `1.4.0-alpha07` and `androidx.camera:camera-viewfinder-compose` at `1.0.0-alpha02`. Current artifacts are **`androidx.camera.viewfinder:viewfinder-view` / `viewfinder-core` / `viewfinder-compose`, all `1.6.1` stable (2026-05-06)**. Relocation happened in `1.5.0-alpha08` (2024-09-04); no code changes required. | **[DOCUMENTED]** https://developer.android.com/jetpack/androidx/releases/camera-viewfinder + `https://dl.google.com/dl/android/maven2/androidx/camera/viewfinder/group-index.xml` |
| The CameraX releases page still lists the *old* viewfinder coordinates in its artifact table | It lists `camera-viewfinder:1.3.0-beta02` and `camera-viewfinder-compose:1.0.0-alpha02` — those are the last versions published under the retired group ID. Do not use them. | **[DOCUMENTED]** cross-check of the two release pages above against Google Maven |
| `camera-feature-combination-query` is **not** at 1.6.1 | Google Maven shows it only up to `1.5.0-alpha06`. Not usable as a stable dependency. | **[DOCUMENTED]** Google Maven group index |
| Do **not** add `camera-extensions` | Extensions are documented to work only with `Preview` + `ImageCapture`; `VideoCapture` cannot be used with Extensions. | **[DOCUMENTED]** https://developer.android.com/media/camera/camerax/architecture |

### 1.6 stack change you must know about

**[DOCUMENTED]** CameraX 1.6.0 release notes: *"CameraX now uses `CameraPipe` — the same modern, high-performance stack powering the Pixel camera."* and *"CameraX now integrates the Media3 Muxer by default within the `VideoCapture` API"* (fixing video corruption on unexpected interruption/app termination — issues b/433649708, b/264812009, b/475750115).

**[INFERRED]** The muxer change is a direct reliability win for a dashcam: the documented fix is *"video corruption during unexpected interruptions or app termination"*, which is precisely the failure mode of a phone that loses power in a car. This alone justifies 1.6.1 over 1.5.x.

**[INFERRED]** `androidx.camera:camera-camera2-pipe` first appears at `1.6.0-alpha01` and there is **no** separately published `camera-camera2-pipe-integration` artifact on Google Maven. Chain: CameraPipe ships inside the `camera-camera2` dependency graph → `Camera2Config.defaultConfig()` and the `androidx.camera.camera2.interop.*` classes still live in `camera-camera2:1.6.1` (confirmed present in `camera/camera-camera2/api/1.6.0-beta01.txt`) → Camera2 interop should keep working. **[UNVERIFIED]** whether `Camera2CameraInfo.from(cameraInfo)` throws against a CameraPipe-backed `CameraInfo` in 1.6.1. Test in §7.4.

---

## 2. Architecture: the exact object graph

### 2.1 Provider

**[DOCUMENTED]** `camera/camera-lifecycle/api/1.6.0-beta01.txt`:

```java
// androidx.camera.lifecycle.ProcessCameraProvider
static ListenableFuture<ProcessCameraProvider> getInstance(Context context)
List<CameraInfo>            getAvailableCameraInfos()
List<List<CameraInfo>>      getAvailableConcurrentCameraInfos()
@MainThread boolean         isConcurrentCameraModeOn()
boolean                     hasCamera(CameraSelector) throws CameraInfoUnavailableException
@MainThread Camera          bindToLifecycle(LifecycleOwner, CameraSelector, UseCase?... useCases)
@MainThread Camera          bindToLifecycle(LifecycleOwner, CameraSelector, UseCaseGroup)
            Camera          bindToLifecycle(LifecycleOwner, CameraSelector, SessionConfig)
@MainThread ConcurrentCamera bindToLifecycle(List<ConcurrentCamera.SingleCameraConfig?>)
boolean                     isBound(UseCase) / isBound(SessionConfig)
@MainThread void            unbind(UseCase?...) / unbind(SessionConfig) / unbindAll()
@ExperimentalCameraProviderConfiguration static void configureInstance(CameraXConfig)
```

Kotlin note: `getAvailableCameraInfos()` / `getAvailableConcurrentCameraInfos()` / `isConcurrentCameraModeOn()` are Kotlin properties (`availableCameraInfos`, `availableConcurrentCameraInfos`, `isConcurrentCameraModeOn`) — the `get*` forms are `@InaccessibleFromKotlin`. **[DOCUMENTED]** same signature file.

`bindToLifecycle` is `@MainThread` for the vararg/`UseCaseGroup`/concurrent overloads, and documented to throw:
- `IllegalStateException` if a use case is already bound to another lifecycle, or off the main thread;
- `IllegalArgumentException` if the selector resolves no camera for the given use cases;
- `UnsupportedOperationException` if the camera is in concurrent mode.

**[DOCUMENTED]** `ProcessCameraProvider.kt` javadoc, androidx-main.

### 2.2 Startup latency knob — use it

**[DOCUMENTED]** https://developer.android.com/media/camera/camerax/configuration — *"During the first invocation of `ProcessCameraProvider.getInstance()`, CameraX enumerates and queries characteristics of the cameras available on the device. Because CameraX needs to communicate with hardware components, this process can take a non-trivial amount of time for each camera, **particularly on low-end devices**."* Fix: `CameraXConfig.Builder.setAvailableCamerasLimiter(CameraSelector)`; a camera filtered out behaves *as if it does not exist*.

```kotlin
class RoadguardApp : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .setMinimumLoggingLevel(Log.WARN)   // default is Log.DEBUG
            .build()
}
```
Also available: `setCameraExecutor(Executor)` and `setSchedulerHandler(Handler)` — both documented to require a non-main-thread executor/handler. **[DOCUMENTED]** same page.

> **Warning.** `setAvailableCamerasLimiter(DEFAULT_BACK_CAMERA)` makes concurrent front+rear (§10) impossible, because the front camera is invisible. If dual-cam is a product feature, limit to a selector that admits both, or do not limit at all.

### 2.3 The video use case

**[DOCUMENTED]** `camera/camera-video/api/1.6.0-beta01.txt` — the **complete** public surface in 1.6.x:

```java
// androidx.camera.video.VideoCapture<T extends VideoOutput> extends UseCase
static <T extends VideoOutput> VideoCapture<T> withOutput(T output)
T                 getOutput()
DynamicRange      getDynamicRange()
int               getMirrorMode()
ResolutionInfo?   getResolutionInfo()
Quality?          getSelectedQuality()
Range<Integer>    getTargetFrameRate()
int               getTargetRotation()
boolean           isVideoStabilizationEnabled()
void              setTargetRotation(int rotation)      // Surface.ROTATION_*

// VideoCapture.Builder<T>
Builder(T output)
Builder<T> setDynamicRange(DynamicRange)
Builder<T> setMirrorMode(int)                          // MirrorMode.MIRROR_MODE_*
Builder<T> setTargetFrameRate(Range<Integer>)
Builder<T> setTargetRotation(int)
Builder<T> setVideoStabilizationEnabled(boolean)
VideoCapture<T> build()
```

> **There is no `setTargetRotationDegrees(int)` / `getTargetRotationDegrees()` in CameraX.** Neither the 1.6 signature file nor `camera-video/api/current.txt` (1.7-alpha tip) contains any `TargetRotationDegrees` member. Some secondary write-ups claim these exist — they do not. Use `Surface.ROTATION_*` + `UseCase.snapToSurfaceRotation(int)`. **[DOCUMENTED]** grep over both signature files.

Also **absent in 1.6.x** (tip-only, do not code against): `Quality.QHD`, `Recorder.Builder.setVideoMimeType`, `setTargetAudioChannelCount`, `setTargetAudioEncodingBitRate`, `Recorder.getVideoCapabilities(CameraInfo, String mimeType)`, `SessionConfig.Builder.setAutoRotationEnabled(boolean)`, `ConcurrentCamera.setCompositionSettings(...)`, `CompositionSettings.Builder.setZOrder/setRoundedCornerRatio/setBorderWidthRatio`, `PreviewView.setFrameUpdateListener`, and the extended 14-parameter `CameraXViewfinder` overload with tap-to-focus/pinch-to-zoom. **[DOCUMENTED]** diff of `api/1.6.0-beta01.txt` vs `api/current.txt` for camera-core / camera-video / camera-compose.

### 2.4 Recorder / PendingRecording / Recording

**[DOCUMENTED]** `camera/camera-video/api/1.6.0-beta01.txt`:

```java
// androidx.camera.video.Recorder implements VideoOutput
static final int VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE  = 0;
static final int VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES = 1;
static final QualitySelector DEFAULT_QUALITY_SELECTOR;

static VideoCapabilities  getVideoCapabilities(CameraInfo)
static VideoCapabilities  getVideoCapabilities(CameraInfo, int videoCapabilitiesSource)
static VideoCapabilities? getHighSpeedVideoCapabilities(CameraInfo)

PendingRecording prepareRecording(Context, FileOutputOptions)
PendingRecording prepareRecording(Context, MediaStoreOutputOptions)
@RequiresApi(26) PendingRecording prepareRecording(Context, FileDescriptorOutputOptions)

int getAspectRatio(); Executor? getExecutor(); QualitySelector getQualitySelector();
int getTargetVideoEncodingBitRate(); int getVideoCapabilitiesSource();

// Recorder.Builder — the FULL 1.6.x setter list
Builder setQualitySelector(QualitySelector)
Builder setExecutor(Executor)
Builder setTargetVideoEncodingBitRate(@IntRange(from=1) int)
Builder setAspectRatio(int)                    // AspectRatio.RATIO_4_3 = 0, RATIO_16_9 = 1
Builder setVideoCapabilitiesSource(int)
Recorder build()
```

**[DOCUMENTED]** `Recorder.java` (androidx-main, line ~394) — the default quality selector is exactly:
```java
public static final QualitySelector DEFAULT_QUALITY_SELECTOR =
        QualitySelector.fromOrderedList(
                asList(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.FHD));
```

```java
// androidx.camera.video.PendingRecording — 1.6.x
@RequiresPermission(RECORD_AUDIO) PendingRecording withAudioEnabled()
@RequiresPermission(RECORD_AUDIO) PendingRecording withAudioEnabled(boolean initialMuted)
@ExperimentalPersistentRecording  PendingRecording asPersistentRecording()
@CheckResult Recording start(Executor listenerExecutor, Consumer<VideoRecordEvent> listener)

// androidx.camera.video.Recording implements AutoCloseable
void stop(); void close(); void pause(); void resume(); void mute(boolean)
@ExperimentalPersistentRecording boolean isPersistent()
```

**[DOCUMENTED]** `PendingRecording.kt` javadoc:
- `withAudioEnabled()` throws `SecurityException` if `RECORD_AUDIO` is denied, and `IllegalStateException` if the `Recorder` does not support audio.
- `start()` throws `IllegalStateException` *"if the associated Recorder currently has an unfinished active recording"*.
- *"If the returned `Recording` is garbage collected, the recording will be automatically stopped… the `VideoRecordEvent.Finalize` event will contain error `ERROR_RECORDING_GARBAGE_COLLECTED`."* → **hold a hard reference to the current `Recording` for its entire life.**
- *"The `Recording` will be stopped automatically if the `VideoCapture` its `Recorder` is attached to is unbound unless it's created as a persistent recording."*

### 2.5 Quality, QualitySelector, FallbackStrategy

**[DOCUMENTED]** `camera/camera-video/api/1.6.0-beta01.txt` + `Quality.java`:

| `Quality` constant (1.6.x) | Backing `CamcorderProfile` | Documented resolution(s) |
|---|---|---|
| `Quality.SD` | `QUALITY_480P` | `720x480` or `640x480` |
| `Quality.HD` | `QUALITY_720P` | `1280x720` |
| `Quality.FHD` | `QUALITY_1080P` | `1920x1080` |
| `Quality.UHD` | `QUALITY_2160P` | `3840x2160` |
| `Quality.LOWEST` | `QUALITY_LOW` | producer-defined |
| `Quality.HIGHEST` | `QUALITY_HIGH` | producer-defined |

```java
// QualitySelector — 1.6.x
static QualitySelector from(Quality)
static QualitySelector from(Quality, FallbackStrategy)
static QualitySelector fromOrderedList(List<Quality>)
static QualitySelector fromOrderedList(List<Quality>, FallbackStrategy)
static Size?           getResolution(CameraInfo, Quality)
@Deprecated static List<Quality> getSupportedQualities(CameraInfo)
@Deprecated static boolean       isQualitySupported(CameraInfo, Quality)

// FallbackStrategy — all four factories
static FallbackStrategy higherQualityOrLowerThan(Quality)
static FallbackStrategy higherQualityThan(Quality)
static FallbackStrategy lowerQualityOrHigherThan(Quality)
static FallbackStrategy lowerQualityThan(Quality)
```

> `QualitySelector.getSupportedQualities` and `isQualitySupported` are **deprecated in 1.6.x**. Use `Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR)` instead. **[DOCUMENTED]** signature file marks both `@Deprecated`.

```java
// androidx.camera.video.VideoCapabilities — 1.6.x
Set<DynamicRange> getSupportedDynamicRanges()
List<Quality>     getSupportedQualities(DynamicRange)
boolean           isQualitySupported(Quality, DynamicRange)
default boolean   isStabilizationSupported()
```

### 2.6 VideoRecordEvent

**[DOCUMENTED]** `camera/camera-video/api/1.6.0-beta01.txt` — `VideoRecordEvent` in 1.6.x exposes only `getOutputOptions()` and `getRecordingStats()`; **there is no `getEventType()` and no `EVENT_TYPE_*` constants**. Dispatch by type:

```kotlin
when (event) {
    is VideoRecordEvent.Start    -> ...
    is VideoRecordEvent.Status   -> ...   // see the warning below
    is VideoRecordEvent.Pause    -> ...
    is VideoRecordEvent.Resume   -> ...
    is VideoRecordEvent.Finalize -> ...   // getError(), getCause(), getOutputResults(), hasError()
}
```

> **Performance landmine.** `Recorder.writeVideoData()` calls `updateInProgressStatusEvent(encodedData.isKeyFrame())` for **every encoded video frame**, and the boolean argument only gates *logging* — `updateVideoRecordEvent(...)` dispatches to the app listener unconditionally, with no throttling. At 30 fps your `Consumer<VideoRecordEvent>` is invoked ~30 times per second for the whole drive. **[DOCUMENTED]** `Recorder.java` androidx-main, `writeVideoData()` ≈ line 2390 → `updateInProgressStatusEvent()` ≈ line 3089 → `RecordingRecord.updateVideoRecordEvent()` ≈ line 3721. Consequence: pass a **dedicated single-thread executor** (not the main executor) to `PendingRecording.start(executor, listener)`, keep the `Status` branch to a plain field write, and sample/throttle before touching UI state.

`RecordingStats` (from every event): `getRecordedDurationNanos()`, `getNumBytesRecorded()`, `getAudioStats()`. `AudioStats`: `getAudioState()`, `getAudioAmplitude()`, `hasAudio()`, `hasError()`, `getErrorCause()`, with states `AUDIO_STATE_ACTIVE=0`, `DISABLED=1`, `SOURCE_SILENCED=2`, `ENCODER_ERROR=3`, `SOURCE_ERROR=4`, `MUTED=5`. **[DOCUMENTED]** 1.6 signature file.

---

## 3. ORIENTATION — the whole story

This is the highest-risk area for Roadguard, so this section states the mechanism, not just the API calls.

### 3.1 Vocabulary (use these exact meanings)

**[DOCUMENTED]** https://developer.android.com/media/camera/camerax/orientation-rotation

| Term | Meaning |
|---|---|
| Display orientation | Which side of the device is up (portrait / landscape / reverse portrait / reverse landscape). |
| Display rotation | The value from `Display.getRotation()` — degrees the device is rotated **counter-clockwise** from its natural orientation. One of `Surface.ROTATION_0/90/180/270`. |
| **Target rotation** | Degrees the device must rotate **clockwise** to reach its natural orientation. This is what you pass to `setTargetRotation()`. |
| Image rotation | Degrees the image must rotate clockwise to appear upright (i.e. to match target rotation). |
| Sensor orientation | A device constant (0/90/180/270) — the sensor's rotation relative to the top of the device in its natural position. |

**[DOCUMENTED]** AOSP `CameraCharacteristics.SENSOR_ORIENTATION` (`Key<Integer> "android.sensor.orientation"`): *"Units: Degrees of clockwise rotation; always a multiple of 90. Range of valid values: 0, 90, 180, 270. This key is available on all devices."* — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CameraCharacteristics.java

### 3.2 The API surface, exactly

```java
// androidx.camera.core.UseCase — static helper, 1.6.x public API
public static int snapToSurfaceRotation(@IntRange(from = 0, to = 359) int orientation)
```
**[DOCUMENTED]** `camera/camera-core/api/1.6.0-beta01.txt` and `UseCase.java` (androidx-main, lines 398–409). Its exact implementation — memorise these thresholds, they are the whole orientation contract:

```java
if (orientation >= 315 || orientation < 45)  return Surface.ROTATION_0;
else if (orientation >= 225)                 return Surface.ROTATION_90;
else if (orientation >= 135)                 return Surface.ROTATION_180;
else                                         return Surface.ROTATION_270;
// throws IllegalArgumentException if orientation not in [0, 359]
```

`VideoCapture.setTargetRotation(int)` javadoc, verbatim on the two points that matter most **[DOCUMENTED]** (`VideoCapture.java`, androidx-main, lines ~279–319):

> *"If not set, the target rotation will default to the value of `Display.getRotation()` of the default display at the time the use case is bound."*

> *"For a `Recorder` output, calling this method **has no effect on the ongoing recording**, but will affect recordings started after calling this method. The final rotation degrees of the video, including the degrees set by this method and the orientation of the camera sensor, will be reflected by several possibilities, 1) the rotation degrees is written into the video metadata, 2) the video content is directly rotated, 3) both… CameraX will choose a strategy according to the use case."*

> *"In general, it is best to use an `android.view.OrientationEventListener` to set the target rotation… `UseCase.snapToSurfaceRotation(int)` is a helper function to convert the orientation of the `OrientationEventListener` to a rotation value."*

### 3.3 Which strategy does CameraX actually choose for MP4? (metadata, not pixels)

This is the single most load-bearing fact in this document. The decision is made in `VideoCapture.createPipeline()`:

**[DOCUMENTED]** `VideoCapture.java` (androidx-main, lines 775–783):
```java
mRotationDegrees = getCompensatedRotation(camera);
...
boolean isBufferRotationRequired =
        mRotationDegrees != 0 && !MediaConfigUtil.canWriteOrientationMetadata(
                mediaInfo.getContainerInfo().getOutputFormat());
```

**[DOCUMENTED]** `androidx/camera/video/internal/config/MediaConfigUtil.kt` (lines 203–209):
```kotlin
public fun canWriteOrientationMetadata(@OutputFormat outputFormat: Int): Boolean =
    when (outputFormat) {
        OUTPUT_FORMAT_MPEG_4 -> true
        OUTPUT_FORMAT_WEBM   -> false
        else                 -> false
    }
```

**[INFERRED]** Chain: Roadguard records MPEG-4 → `canWriteOrientationMetadata == true` → `isBufferRotationRequired == false` → the `shouldRotateBuffer` argument to `isCreateNodeNeeded(...)` is `false` → **rotation alone never creates a `SurfaceProcessorNode`**, i.e. never adds an OpenGL pass. The encoded frames stay in sensor-natural orientation.

Where the rotation actually lands: **[DOCUMENTED]** `Recorder.java` (androidx-main, line ~1880), executed when the first video data is written for the recording:
```java
SurfaceRequest.TransformationInfo transformationInfo = mSourceTransformationInfo;
if (transformationInfo != null) {
    setInProgressTransformationInfo(transformationInfo);
    try {
        muxer.setOrientationDegrees(transformationInfo.getRotationDegrees());
    } catch (IllegalArgumentException e) { /* → ERROR_INVALID_OUTPUT_OPTIONS */ }
}
```

And what that means in the file, **[DOCUMENTED]** verbatim from AOSP `MediaMuxer.setOrientationHint(int degrees)` javadoc (https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/media/java/android/media/MediaMuxer.java):

> *"Sets the orientation hint for output video playback. This method should be called before `start`. **Calling this method will not rotate the video frame when muxer is generating the file, but add a composition matrix containing the rotation angle in the output video** if the output format is `MUXER_OUTPUT_MPEG_4` so that a video player can choose the proper orientation for playback. **Note that some video players may choose to ignore the composition matrix in a video during playback.** By default, the rotation degree is 0. … The supported angles are 0, 90, 180, and 270 degrees."*

### 3.4 The rotation value CameraX computes

**[DOCUMENTED]** `UseCase.getRelativeRotation(CameraInternal)` (androidx-main, lines 564–587):
```java
int rotation = cameraInternal.getCameraInfoInternal()
                   .getSensorRotationDegrees(getTargetRotationInternal());
```
and **[DOCUMENTED]** `CameraInfo.getSensorRotationDegrees(int relativeRotation)`: *"Returns the sensor rotation, in degrees, relative to the given rotation value. Valid values for the relative rotation are `Surface.ROTATION_0` (natural), `ROTATION_90`, `ROTATION_180`, `ROTATION_270`."*

**[INFERRED]** Worked example for a typical rear camera with `SENSOR_ORIENTATION = 90` on a phone whose natural orientation is portrait:

| Phone held | `Display.getRotation()` / target rotation | Orientation hint written to MP4 | Encoded frame size | What a compliant player shows |
|---|---|---|---|---|
| Portrait (upright) | `ROTATION_0` | 90 | 1920×1080 | 1080×1920 portrait |
| Landscape, rotated CCW 90° | `ROTATION_90` | 0 | 1920×1080 | 1920×1080 landscape |
| Reverse portrait | `ROTATION_180` | 270 | 1920×1080 | 1080×1920 portrait |
| Landscape, rotated CW 90° | `ROTATION_270` | 180 | 1920×1080 | 1920×1080 landscape |

**[UNVERIFIED]** the concrete `SENSOR_ORIENTATION` value on the Moto G04 and Edge 60 Fusion. Do not hardcode 90; read `CameraInfo.getSensorRotationDegrees()` at runtime. This affects nothing in the code (CameraX does the arithmetic) but affects your test expectations.

**Two consequences that make this the right design for a dashcam:**

1. **[INFERRED]** The **encoded resolution never changes with device orientation** — the buffer is always the sensor-natural landscape size (e.g. 1920×1080). Rotating the phone therefore does *not* force a `MediaCodec` reconfigure or a stream-spec change. This is essential for recording reliability #1 on a Unisoc T606.
2. **[INFERRED]** Because `Recorder` reads the transformation info once, at the moment the muxer is created for a recording, and because `setTargetRotation` is documented to have "no effect on the ongoing recording", **each 3-minute segment is stamped with the rotation that was current when that segment started.** That is precisely how a stock camera app behaves (it latches orientation at record start), applied per segment.

### 3.5 "Phone portrait → portrait video, phone landscape → landscape video": the implementation

**Do not put the camera in the Activity.** See §12.1. Bind to a service-owned `LifecycleOwner`. The Activity then only draws the viewfinder.

**Activity manifest** — the activity is *not* locked to one orientation, and it swallows config changes so a rotation never destroys it (which would otherwise tear down the UI mid-drive):

```xml
<activity
    android:name=".ui.MainActivity"
    android:screenOrientation="fullSensor"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density|layoutDirection|navigation" />
```

**[DOCUMENTED]** https://developer.android.com/media/camera/camerax/orientation-rotation states that with `android:screenOrientation="fullSensor"` the device rotates to **all four** orientations (without it, the device *"may not rotate to reverse portrait/landscape"*), and that in the `configChanges`-overridden case *"the system does NOT destroy and recreate the Activity on rotation"* and a listener is **required** to update target rotation.

**Orientation driver** — owned by the recording service, not the Activity, so it keeps working with the screen off:

```kotlin
class RotationTracker(
    context: Context,
    private val onRotation: (Int) -> Unit,      // Surface.ROTATION_*
) {
    // Hysteresis: a car mount jiggles. Require the new value to persist before applying.
    private var pending: Int = -1
    private var pendingSinceMs: Long = 0L
    private var applied: Int = -1

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return          // device lying flat
            val r = UseCase.snapToSurfaceRotation(orientation)       // [0,359] -> Surface.ROTATION_*
            val now = SystemClock.elapsedRealtime()
            if (r != pending) { pending = r; pendingSinceMs = now; return }
            if (r != applied && now - pendingSinceMs >= STABLE_MS) { applied = r; onRotation(r) }
        }
    }

    fun canDetect(): Boolean = listener.canDetectOrientation()
    fun start() = listener.enable()
    fun stop()  = listener.disable()

    private companion object { const val STABLE_MS = 600L }
}
```

Wire it to **both** use cases (cheap, and removes an entire class of "why is preview upright but video sideways" bug):

```kotlin
rotationTracker = RotationTracker(appContext) { rotation ->
    preview.targetRotation = rotation        // Preview.setTargetRotation(int)
    videoCapture.targetRotation = rotation   // VideoCapture.setTargetRotation(int) — next segment
}
```

Seed the initial value at bind time from the display, and fall back to it whenever `canDetectOrientation()` is false:

```kotlin
val initial = ContextCompat.getSystemService(ctx, DisplayManager::class.java)!!
    .getDisplay(Display.DEFAULT_DISPLAY).rotation
val videoCapture = VideoCapture.Builder(recorder)
    .setTargetRotation(initial)
    .build()
```

**Also register a `DisplayManager.DisplayListener`** as a secondary source. **[DOCUMENTED]** the orientation-rotation page gives the exact pattern (`onDisplayChanged(displayId)` → compare against `rootView.display.displayId` → read `rootView.display.rotation`). It is the documented option for multi-window + config-changes-overridden; treat it as a belt for the `OrientationEventListener` braces, but let the `OrientationEventListener` win, since the display rotation can be pinned by a system setting while the physical device is not.

### 3.6 Rotation edge cases and the honest answers

| Situation | Behaviour | Evidence |
|---|---|---|
| User rotates the phone **mid-segment** | The in-flight segment keeps its original rotation; the *next* segment picks up the new one. Maximum "wrong-orientation" exposure = one segment (≤3 min). | **[DOCUMENTED]** `VideoCapture.setTargetRotation` javadoc: "no effect on the ongoing recording" |
| Should we force a segment cut on rotation? | **No, by default.** Cutting on rotation means a windshield-mount jiggle can shred segments. Offer it only as an opt-in, and only after the hysteresis in §3.5 has fired. | **[INFERRED]** from the previous row + reliability-first constraint |
| Phone lying flat on the dash | `OrientationEventListener` delivers `ORIENTATION_UNKNOWN`; the code above ignores it and keeps the last good rotation. | **[DOCUMENTED]** `OrientationEventListener.ORIENTATION_UNKNOWN` is in the documented sample on the orientation-rotation page |
| Device has no accelerometer | `OrientationEventListener.canDetectOrientation()` returns false → fall back to `Display.getRotation()` only. | **[DOCUMENTED]** `OrientationEventListener` API. The Moto G04's sensor list includes an accelerometer, so this path should be unreachable on target hardware — see §8.3. |
| Player ignores the MP4 matrix | Some players will show portrait clips as sideways landscape. This is inherent to metadata rotation and identical to every stock Android camera app. | **[DOCUMENTED]** `MediaMuxer.setOrientationHint` javadoc: *"some video players may choose to ignore the composition matrix"* |
| Do we ever want pixel rotation instead? | Only if a specific downstream consumer demands it — and it costs a full OpenGL pass per frame (`SurfaceProcessorNode`), which is exactly the thermal cost we are trying to avoid on a T606. **Do not do it.** | **[INFERRED]** from `isCreateNodeNeeded` (§4.1) |
| `DisplayOrientedMeteringPointFactory` | Exists for converting **display** coordinates to metering points (tap-to-focus). It has **nothing to do with recorded video rotation** and must not be used for it. In 1.6.x, prefer `PreviewView.getMeteringPointFactory()` (View) or `MutableCoordinateTransformer` (Compose), which already account for scale type / content scale. | **[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt` (`MeteringPointFactory` hierarchy) and `camera-view/api/1.6.0-beta01.txt` (`PreviewView.getMeteringPointFactory()`) |

### 3.7 `MirrorMode`

**[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`: `MirrorMode.MIRROR_MODE_OFF = 0`, `MIRROR_MODE_ON = 1`, `MIRROR_MODE_ON_FRONT_ONLY = 2`. Default for `VideoCapture` is `MIRROR_MODE_OFF` (**[DOCUMENTED]** `VideoCapture.setMirrorMode` javadoc: *"If not set, it defaults to `MirrorMode.MIRROR_MODE_OFF`"*).

For Roadguard's rear camera: leave it `OFF`. **[INFERRED]** Mirroring is one of the conditions in `isCreateNodeNeeded` (`shouldMirror(camera)`), so turning it on would add an OpenGL pass. If a front-facing interior cam is ever added, `MIRROR_MODE_ON_FRONT_ONLY` matches what the user sees in preview — but note that **in concurrent composition mode `VideoCapture`'s mirrorMode is ignored** and the recording inherits the preview's mirroring. **[DOCUMENTED]** `ProcessCameraProvider.bindToLifecycle(List<SingleCameraConfig>)` javadoc.

---

## 4. Preview vs recording independence — does a `ViewPort` crop the recording?

### 4.1 Yes. A `ViewPort` crops the recorded stream. Do not use one.

The `ViewPort` class javadoc only mentions `Preview`, `ImageAnalysis` and `ImageCapture`, which makes this easy to get wrong. The source is unambiguous.

**[DOCUMENTED]** `VideoCapture.java` (androidx-main), `calculateCropRect()` ≈ line 740:
```java
private Rect calculateCropRect(Size surfaceResolution, VideoEncoderInfo videoEncoderInfo) {
    Rect cropRect;
    if (getViewPortCropRect() != null) {
        cropRect = getViewPortCropRect();                       // <-- ViewPort wins
    } else {
        cropRect = new Rect(0, 0, surfaceResolution.getWidth(), surfaceResolution.getHeight());
    }
    ...
}
```

**[DOCUMENTED]** `VideoCapture.isCreateNodeNeeded(...)` ≈ line 1160:
```java
return getEffect() != null
        || shouldRotateBuffer
        || shouldEnableSurfaceProcessingByConfig(camera, config)
        || shouldEnableSurfaceProcessingByQuirk(camera)
        || shouldEnableSurfaceProcessingBasedOnDynamicRangeByQuirk(camera, dynamicRange)
        || shouldCrop(cropRect, resolution)        // <-- true whenever cropRect != full resolution
        || shouldMirror(camera)
        || shouldCompensateTransformation(camera);
```
with
```java
private static boolean shouldCrop(Rect cropRect, Size resolution) {
    return resolution.getWidth() != cropRect.width() || resolution.getHeight() != cropRect.height();
}
```
and `createNodeIfNeeded(...)` returning `new SurfaceProcessorNode(camera, DefaultSurfaceProcessor.Factory.newInstance(dynamicRange), TAG)` when that is true, logging `"Surface processing is enabled."`.

**[INFERRED]** Therefore: binding `VideoCapture` inside a `UseCaseGroup`/`SessionConfig` that carries a `ViewPort` (including the very convenient `PreviewView.getViewPort()`) causes CameraX to (a) physically crop the recorded frames to the viewport rect and (b) insert a full OpenGL render pass into the recording path. Both violate Roadguard's constraints: the recorded video must not be cropped for UI reasons, and thermal budget on a T606 must not be spent on an avoidable per-frame GPU pass.

**Rule for Roadguard code review: no `ViewPort`, anywhere.**

```kotlin
// CORRECT — no UseCaseGroup, no ViewPort, no SessionConfig with a viewPort
val camera = cameraProvider.bindToLifecycle(
    serviceLifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview,
    videoCapture,
)
```
```kotlin
// WRONG — silently crops the recording and adds a GPU pass
val group = UseCaseGroup.Builder()
    .addUseCase(preview).addUseCase(videoCapture)
    .setViewPort(previewView.viewPort!!)     // <-- NEVER
    .build()
```

**[DOCUMENTED]** For completeness, the `ViewPort` API that we are deliberately not using (`camera-core/api/1.6.0-beta01.txt`): `ViewPort.Builder(Rational aspectRatio, int rotation)`, `setScaleType(int)`, `setLayoutDirection(int)`; scale types `FILL_START = 0`, `FILL_CENTER = 1`, `FILL_END = 2`, `FIT = 3`. Injected via `UseCaseGroup.Builder.setViewPort(ViewPort)` or `SessionConfig.Builder.setViewPort(ViewPort)`.

### 4.2 Verifying at runtime that the recording is uncropped and GPU-free

**[INFERRED]** from the source above — two cheap assertions worth shipping behind a debug flag:

```kotlin
val info = videoCapture.resolutionInfo          // ResolutionInfo(Size, Rect cropRect, int rotationDegrees)
check(info != null)
val res = info.resolution
val crop = info.cropRect
// If this fails, something injected a ViewPort or a quirk forced cropping.
check(crop.width() == res.width && crop.height() == res.height) {
    "Recording is being cropped: res=$res crop=$crop"
}
```
and in logcat, `VideoCapture` logs one line per pipeline creation containing `originalCropRect`, `mCropRect`, `mRotationDegrees`, `isBufferRotationRequired`, `hasGlProcessing`; and `"Surface processing is enabled."` appears **only** when a `SurfaceProcessorNode` was created. **[DOCUMENTED]** `VideoCapture.createPipeline()` logging, androidx-main ≈ lines 799–811 and 1195.

**NOT VERIFIED — needs on-device measurement:** whether any device quirk (`shouldEnableSurfaceProcessingByQuirk`, `SizeCannotEncodeVideoQuirk`) forces surface processing on the Moto G04 / Edge 60 Fusion. Test: bind Preview+VideoCapture, grep logcat for `"Surface processing is enabled."`.

### 4.3 Cropping the preview only — View path

**[DOCUMENTED]** `camera-view/api/1.6.0-beta01.txt` — `PreviewView.ScaleType` has exactly six values: `FILL_START`, `FILL_CENTER`, `FILL_END`, `FIT_START`, `FIT_CENTER`, `FIT_END`. Default is `FILL_CENTER` (**[DOCUMENTED]** `PreviewView.java` javadoc on `setScaleType`/`getScaleType`: *"The default value is `ScaleType#FILL_CENTER`."*).

- `FILL_*` — *"Scale the preview, maintaining the source aspect ratio, so it fills the entire `PreviewView`… This may cause the preview to be cropped if the camera preview aspect ratio does not match that of its container."*
- `FIT_*` — *"…so it is entirely contained within the `PreviewView`… The background area not covered by the preview stream will be black or the background of the `PreviewView`."*

**[DOCUMENTED]** both quotes verbatim from `PreviewView.ScaleType` javadoc.

For Roadguard's 50/50 split, the video pane is roughly half the screen, so a 16:9 stream will not match the pane. Choose:
- `FILL_CENTER` — no letterbox, but the driver sees less of the road than is being recorded. Use only if you show a "recorded area is wider than shown" affordance.
- `FIT_CENTER` — WYSIWYG: what the driver sees is exactly what is recorded. **Recommended default for a dashcam**, because a driver checking framing should be seeing the true recorded field of view.

Also relevant on `PreviewView`: `ImplementationMode.PERFORMANCE` vs `COMPATIBLE` (i.e. `SurfaceView` vs `TextureView`). **[INFERRED]** For a permanently-on viewfinder on a low-end SoC, `PERFORMANCE` (`SurfaceView`) is the right default because it avoids the `TextureView` GPU composite path; but a `SurfaceView` cannot be arbitrarily transformed by the view system, so if you want a *view-transform* zoom (§6) you must use `COMPATIBLE`. Pick one and be deliberate.

Other `PreviewView` members you will need: `getSurfaceProvider()`, `getPreviewStreamState(): LiveData<StreamState>` (`IDLE` / `STREAMING`), `getMeteringPointFactory()`, `getSensorToViewTransform(): Matrix?`, `getBitmap(): Bitmap?`. **[DOCUMENTED]** `camera-view/api/1.6.0-beta01.txt`.

### 4.4 Cropping the preview only — Compose path

**[DOCUMENTED]** `camera/camera-compose/api/1.6.0-beta01.txt` — the **only** overload in 1.6.x:

```kotlin
@Composable
public fun CameraXViewfinder(
    surfaceRequest: androidx.camera.core.SurfaceRequest,
    modifier: Modifier = Modifier,
    implementationMode: androidx.camera.viewfinder.core.ImplementationMode =
        CameraImplementationModeCompat.chooseCompatibleMode(surfaceRequest.camera.cameraInfo),
    coordinateTransformer: androidx.camera.viewfinder.compose.MutableCoordinateTransformer? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
)
```

`contentScale` / `alignment` are the display-only crop/fit control and mirror `androidx.compose.foundation.Image` semantics. **[DOCUMENTED]** https://developer.android.com/jetpack/androidx/releases/camera-viewfinder — *"Supports `ContentScale` and `Alignment` for scaling and placing the camera stream (mirrors `androidx.compose.foundation.Image` behavior)."*

- `ContentScale.Crop` (the default) ≈ `PreviewView.FILL_CENTER`.
- `ContentScale.Fit` ≈ `PreviewView.FIT_CENTER` — **use this for Roadguard's WYSIWYG default.**

`ImplementationMode` (from `androidx.camera.viewfinder.core`) is `EXTERNAL` (`SurfaceView`, high performance) or `EMBEDDED` (`TextureView`, broader compatibility); the default `chooseCompatibleMode(...)` prefers `EXTERNAL` and falls back to `EMBEDDED` on older APIs / quirky devices, and *"Explicitly setting a mode will override this compatibility logic and may have performance or correctness implications."* **[DOCUMENTED]** `CameraXViewfinder.kt` javadoc + the camera-viewfinder release notes.

Wiring: get a `SurfaceRequest` from `Preview.setSurfaceProvider { request -> ... }` and hoist it into Compose state; changing `implementationMode` while a request is live causes CameraX to `invalidate()` the request so a fresh surface is issued. **[DOCUMENTED]** `CameraXViewfinder.kt`.

`MutableCoordinateTransformer` maps viewfinder offsets back to source coordinates — that is the correct tool for tap-to-focus in Compose (**not** any rotation maths of your own). **[DOCUMENTED]** `CameraXViewfinder.kt` javadoc.

> The lower-level `androidx.camera.viewfinder.compose.Viewfinder` composable (in `androidx.camera.viewfinder:viewfinder-compose:1.6.1`) takes `surfaceRequest: ViewfinderSurfaceRequest`, `implementationMode`, `transformationInfo: TransformationInfo`, `modifier`, `coordinateTransformer`, `alignment`, `contentScale`, plus a trailing `ViewfinderInitScope` lambda for surface-session lifecycle. **[DOCUMENTED]** https://developer.android.com/jetpack/androidx/releases/camera-viewfinder. Use `CameraXViewfinder` unless you need Camera2-direct control; `CameraXViewfinder` does the `SurfaceRequest` → `ViewfinderSurfaceRequest` + `TransformationInfo` adaptation for you.

---

## 5. Frame rate, resolution and aspect ratio selection

```java
// androidx.camera.core.AspectRatio — 1.6.x
RATIO_4_3   = 0
RATIO_16_9  = 1
RATIO_DEFAULT = -1
```
**[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`.

```java
// androidx.camera.core.resolutionselector — 1.6.x
AspectRatioStrategy(int preferredAspectRatio, int fallbackRule)
  FALLBACK_RULE_NONE = 0, FALLBACK_RULE_AUTO = 1
  RATIO_16_9_FALLBACK_AUTO_STRATEGY, RATIO_4_3_FALLBACK_AUTO_STRATEGY
ResolutionStrategy
  FALLBACK_RULE_NONE = 0, CLOSEST_HIGHER_THEN_LOWER = 1, CLOSEST_HIGHER = 2,
  CLOSEST_LOWER_THEN_HIGHER = 3, CLOSEST_LOWER = 4
ResolutionSelector / ResolutionSelector.Builder
```
**[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`. Note `VideoCapture.Builder` in 1.6.x does **not** expose `setResolutionSelector` — video resolution is driven by `Recorder`'s `QualitySelector` + `setAspectRatio`. `Preview.Builder` does expose `setResolutionSelector`.

Frame rate: `VideoCapture.Builder.setTargetFrameRate(Range<Integer>)`. **[DOCUMENTED]** `VideoCapture.java` javadoc: *"It is not guaranteed that this target frame rate will be the final range, as other use cases as well as frame rate restrictions of the device may affect the outcome… For supported frame rates, see `CameraInfo.getSupportedFrameRateRanges()`."*

`Recorder.Builder.setVideoCapabilitiesSource(int)`:
- `VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE = 0` (default) — what the device's `CamcorderProfile`/`EncoderProfiles` advertise.
- `VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES = 1` — derived from `MediaCodecInfo`, which can expose combinations the camera HAL cannot actually sustain.

**[INFERRED]** For reliability-first on a Unisoc T606, keep the default `CAMCORDER_PROFILE`: it is the set the OEM's own camera app uses and therefore the set the HAL is tuned for. Use `CODEC_CAPABILITIES` only as a diagnostic during bring-up.

### Recommended Roadguard video config

```kotlin
private val qualitySelector = QualitySelector.fromOrderedList(
    listOf(Quality.FHD, Quality.HD, Quality.SD),
    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
)

val recorder = Recorder.Builder()
    .setQualitySelector(qualitySelector)
    .setExecutor(recorderExecutor)                 // dedicated single-thread executor
    .setAspectRatio(AspectRatio.RATIO_16_9)
    // .setVideoCapabilitiesSource(...) — leave default (CAMCORDER_PROFILE)
    // .setTargetVideoEncodingBitRate(...) — leave default; see must-measure list
    .build()

val videoCapture = VideoCapture.Builder(recorder)
    .setTargetRotation(initialDisplayRotation)
    .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
    .setDynamicRange(DynamicRange.SDR)             // explicit; see §11
    .setTargetFrameRate(Range(30, 30))
    .setVideoStabilizationEnabled(false)           // see §9
    .build()
```

**[DOCUMENTED]** Every method above is present in `camera-video/api/1.6.0-beta01.txt` / `camera-core/api/1.6.0-beta01.txt`.

**[DOCUMENTED]** Moto G04's rear camera maxes out at `1080p@30fps` — Motorola's own spec page lists the rear camera as *"16 MP (f/2.2, 1,0 µm) | PDAF"* on `Android™ 14` with the `UNISOC T606` (https://en-us.support.motorola.com/app/answers/detail/a_id/178144/), and GSMArena's spec sheet states main-camera video `1080p@30fps` (https://www.gsmarena.com/motorola_moto_g04-12803.php). So `Quality.FHD` should be the top of the ladder on the baseline device; `Quality.UHD` is intentionally absent from the ordered list.

**[DOCUMENTED]** Edge 60 Fusion: Dimensity 7300 (4 nm), 50 MP f/1.9 main with multi-directional PDAF and **OIS**, 13 MP 120° ultrawide, video `4K@30fps, 1080p@30/60/120/240fps` with gyro-EIS, Android 15, sensors include a gyro (https://www.gsmarena.com/motorola_edge_60_fusion-13752.php). **[INFERRED]** 1080p30 is comfortably within its envelope, so a single quality ladder works for both targets; UHD is left out deliberately to keep thermal behaviour uniform and storage bounded.

---

## 6. Zoom — camera zoom is NOT display-only

```java
// androidx.camera.core.CameraControl — 1.6.x
ListenableFuture<Void> setZoomRatio(float ratio)
ListenableFuture<Void> setLinearZoom(@FloatRange(from = 0.0f, to = 1.0f) float linearZoom)
ListenableFuture<Void> enableTorch(boolean)
ListenableFuture<Integer> setExposureCompensationIndex(int)
ListenableFuture<FocusMeteringResult> startFocusAndMetering(FocusMeteringAction)
ListenableFuture<Void> cancelFocusAndMetering()

// androidx.camera.core.ZoomState (via CameraInfo.getZoomState(): LiveData<ZoomState>)
float getZoomRatio()      // 1.0 by default
float getMinZoomRatio()   // "Typically 1.0, but can be less than 1.0 if the camera device
                          //  supports zoom-out (only on android 11 or later)"
float getMaxZoomRatio()
float getLinearZoom()     // [0..1]
```
**[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`; quoted javadoc from `ZoomState.java` and `CameraControl.java`, androidx-main.

Documented semantics **[DOCUMENTED]** `CameraControl.java` javadoc: `setZoomRatio` and `setLinearZoom` *"modif[y] both current zoomRatio and linearZoom"*; out-of-range values fail the future with `IllegalArgumentException` **without** changing zoom; `setLinearZoom` *"ensures the field of view (FOV) varies linearly with the linearZoom value, for use with slider UI elements (while `setZoomRatio(float)` works well for pinch-zoom gestures)"*.

### 6.1 Confirmed: camera zoom changes the recorded frames

**[DOCUMENTED]** AOSP `CaptureRequest.CONTROL_ZOOM_RATIO` javadoc (https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CaptureRequest.java). The javadoc works through a hypothetical device with an active array of 2000×1500 and **two output streams** (`#1` 640×480 VGA and `#2` 1280×720), then states the effect of `zoomRatio = 2.0` on **both**:

> *"Case #1: 4:3 crop region with 2.0x zoom ratio — Zoomed field of view: 1/4 of original field of view … `640x480` **stream source area**: `(0, 0, 2000, 1500)` (equal to crop region) … `1280x720` **stream source area**: `(0, 187, 2000, 1312)` (letterboxed)"*

**[INFERRED]** Chain: `CONTROL_ZOOM_RATIO` is a per-request control applied by the camera/ISP that redefines the *source area of every output stream* in the session. In CameraX, `Preview` and `VideoCapture` are two output streams of one capture session. Therefore `CameraControl.setZoomRatio()` changes the field of view of the recorded video, not just the preview. This is a hard product answer:

> **"Preview zoom" for UI purposes MUST be implemented as a view transform, never as `CameraControl.setZoomRatio()`.** If the user is allowed to zoom the camera, the UI must say so — the recording zooms too.

View-transform recipes:
- **Compose**: `CameraXViewfinder(..., contentScale = ContentScale.Crop)` plus `Modifier.graphicsLayer(scaleX = k, scaleY = k, transformOrigin = ...)` and `Modifier.clipToBounds()`. Display-only.
- **View**: `PreviewView` with `ImplementationMode.COMPATIBLE` (`TextureView`) so the view hierarchy can transform it; or wrap it and scale the wrapper. Note `ImplementationMode.PERFORMANCE` uses a `SurfaceView`, which cannot be arbitrarily transformed. **[INFERRED]** from the documented `PERFORMANCE`=`SurfaceView` / `COMPATIBLE`=`TextureView` mapping.

### 6.2 Concurrent-mode zoom restriction

**[DOCUMENTED]** AOSP `CameraManager.getConcurrentCameraIds()` javadoc: *"For concurrent operation, if a camera device has a non null zoom ratio range as specified by `CONTROL_ZOOM_RATIO_RANGE`, its complete zoom ratio range may not apply. Applications can use `CONTROL_ZOOM_RATIO >= 1` and `<= SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` during concurrent operation."* So no zoom-out (<1.0×) while both cameras run.

---

## 7. Capability probing

### 7.1 Prefer CameraX's own capability APIs where they exist

These describe what CameraX **will actually do**, which is what you need, and they need no interop opt-in.

```kotlin
// After you have a CameraInfo (from cameraProvider.availableCameraInfos, filtered by selector)
val caps: VideoCapabilities = Recorder.getVideoCapabilities(cameraInfo)
val dynamicRanges: Set<DynamicRange> = caps.supportedDynamicRanges
val sdrQualities: List<Quality>     = caps.getSupportedQualities(DynamicRange.SDR)
val fhdOk: Boolean                  = caps.isQualitySupported(Quality.FHD, DynamicRange.SDR)
val stabOk: Boolean                 = caps.isStabilizationSupported()
val fhdSize: Size?                  = QualitySelector.getResolution(cameraInfo, Quality.FHD)

val fpsRanges: Set<Range<Int>>      = cameraInfo.supportedFrameRateRanges
val sensorDeg: Int                  = cameraInfo.sensorRotationDegrees
val isLogicalMulti: Boolean         = cameraInfo.isLogicalMultiCameraSupported
val physicals: Set<CameraInfo>      = cameraInfo.physicalCameraInfos
val previewStab: Boolean            = Preview.getPreviewCapabilities(cameraInfo).isStabilizationSupported
val zoom: ZoomState?                = cameraInfo.zoomState.value
val hdrProbe: Set<DynamicRange>     = cameraInfo.querySupportedDynamicRanges(setOf(DynamicRange.HLG_10_BIT))
```
**[DOCUMENTED]** every member above appears in `camera-core/api/1.6.0-beta01.txt` or `camera-video/api/1.6.0-beta01.txt`.

**Best 1.6-only probe:** `CameraInfo.isSessionConfigSupported(SessionConfig): Boolean` — ask the device whether the exact combination you intend to bind is supported **before** binding it. **[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`, and CameraX 1.6.0 release notes: *"A new `isSessionConfigSupported` API is introduced, allowing developers to query whether a specific combination of use cases and features is supported by the device before binding to the lifecycle."* This is the single most valuable pre-flight check Roadguard can run at startup.

`SessionConfig` in 1.6.x (stabilized): `SessionConfig(useCases, viewPort?, effects, frameRateRange, requiredFeatureGroup, preferredFeatureGroup)` plus `SessionConfig.Builder` with `addEffect`, `setFrameRateRange`, `setPreferredFeatureGroup(vararg GroupableFeature)`, `setRequiredFeatureGroup(vararg)`, `setViewPort`, and `setFeatureSelectionListener(...)`. **[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`. Video-related `GroupableFeatures` in 1.6.x: `SD_RECORDING`, `HD_RECORDING`, `FHD_RECORDING`, `UHD_RECORDING`, `VIDEO_STABILIZATION` (**[DOCUMENTED]** `camera-video/api/1.6.0-beta01.txt`; `QHD_RECORDING` is tip-only). **[INFERRED]** For Roadguard, `setPreferredFeatureGroup(GroupableFeatures.FHD_RECORDING)` + `isSessionConfigSupported` is a clean way to ask "can this device do FHD in the exact configuration I want?" without trial-and-error binds — but note that using `SessionConfig` at all means being careful **not** to pass a `viewPort` (§4.1).

### 7.2 Camera2 interop for everything CameraX doesn't surface

```java
// androidx.camera.camera2.interop — 1.6.x, all @ExperimentalCamera2Interop
static Camera2CameraInfo Camera2CameraInfo.from(CameraInfo cameraInfo)
<T> T? getCameraCharacteristic(CameraCharacteristics.Key<T> key)
String getCameraId()                      // Kotlin property: cameraId

static Camera2CameraControl Camera2CameraControl.from(CameraControl)
ListenableFuture<Void?> setCaptureRequestOptions(CaptureRequestOptions)
ListenableFuture<Void?> addCaptureRequestOptions(CaptureRequestOptions)
ListenableFuture<Void?> clearCaptureRequestOptions()
CaptureRequestOptions   getCaptureRequestOptions()

Camera2Interop.Extender<T>(ExtendableBuilder<T> baseBuilder)
  <V> setCaptureRequestOption(CaptureRequest.Key<V>, V)
  setDeviceStateCallback(CameraDevice.StateCallback)
  setSessionStateCallback(CameraCaptureSession.StateCallback)
  setSessionCaptureCallback(CameraCaptureSession.CaptureCallback)
  @RequiresApi(28) setPhysicalCameraId(String)
  @RequiresApi(33) setStreamUseCase(long)
```
**[DOCUMENTED]** `camera/camera-camera2/api/1.6.0-beta01.txt`. All of these require `@OptIn(ExperimentalCamera2Interop::class)`.

Documented usage pattern **[DOCUMENTED]** https://developer.android.com/media/camera/camerax/architecture:
```kotlin
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun isBackCameraLevel3Device(cameraProvider: ProcessCameraProvider): Boolean =
    CameraSelector.DEFAULT_BACK_CAMERA
        .filter(cameraProvider.availableCameraInfos)
        .firstOrNull()
        ?.let { Camera2CameraInfo.from(it) }
        ?.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
```

### 7.3 The probe table

All key types verified against AOSP `CameraCharacteristics.java` (https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CameraCharacteristics.java). **[DOCUMENTED]**

| Key (exact) | Java type | Min API | Why Roadguard cares |
|---|---|---|---|
| `INFO_SUPPORTED_HARDWARE_LEVEL` | `Key<Integer>` | 21 | Values `LEGACY`(2)/`LIMITED`(0)/`FULL`(1)/`LEVEL_3`(3)/`EXTERNAL`(4). Gates guaranteed stream combinations; `FULL`-or-lower + 3 use cases triggers stream sharing. |
| `SENSOR_ORIENTATION` | `Key<Integer>` | 21 | Sanity-check the rotation model of §3.4. Documented range 0/90/180/270. |
| `SENSOR_INFO_ACTIVE_ARRAY_SIZE` | `Key<Rect>` | 21 | Denominator for FOV/crop reasoning; base for `SCALER_CROP_REGION`. |
| `SCALER_STREAM_CONFIGURATION_MAP` | `Key<StreamConfigurationMap>` | 21 | `getOutputSizes(...)`, `getOutputMinFrameDuration(...)`, `getHighSpeedVideoFpsRanges()`. Ground truth for what the HAL will emit. |
| `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` | `Key<Range<Integer>[]>` | 21 | Does the device offer a locked `[30,30]`? A `[7,30]` range means night-time frame-rate collapse — a real dashcam problem. |
| `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` | `Key<float[]>` | 21 | Distinguish wide from ultrawide (§8). |
| `LENS_INFO_MINIMUM_FOCUS_DISTANCE` | `Key<Float>` | 21 | A macro lens has an unusually large value (i.e. very short min focus); useful to exclude macro cams. |
| `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` | `Key<int[]>` | 21 | Values `OFF=0`, `ON=1`, `PREVIEW_STABILIZATION=2` (API 33+). §9. |
| `REQUEST_AVAILABLE_CAPABILITIES` | `Key<int[]>` | 21 | Look for `..._LOGICAL_MULTI_CAMERA` (API 28) and `..._DYNAMIC_RANGE_TEN_BIT` (API 33). |
| `LOGICAL_MULTI_CAMERA_PHYSICAL_IDS` | `Key<byte[]>` | 28 | Prefer the typed `CameraCharacteristics.getPhysicalCameraIds(): Set<String>` (API 28) over parsing this. |
| `CONTROL_ZOOM_RATIO_RANGE` | `Key<Range<Float>>` | 30 | Does the device allow zoom-out (<1.0×)? Also see the concurrent-mode restriction in §6.2. |
| `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` | `Key<Float>` | 21 | The concurrent-mode zoom ceiling. |
| `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES` | `Key<DynamicRangeProfiles>` | 33 | `getSupportedProfiles().contains(DynamicRangeProfiles.HLG10)`. §11. |
| `SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS` | `Key<MandatoryStreamCombination[]>` | 30 | Exactly which stream combos are guaranteed while two cameras run. §10. |

Encoder/profile probing:

| API | Signature | Min API | Note |
|---|---|---|---|
| `CamcorderProfile.getAll` | `@Nullable static EncoderProfiles getAll(@NonNull String cameraId, @Quality int quality)` | 31 | Returns null if the quality is unsupported for that camera. **[DOCUMENTED]** AOSP `CamcorderProfile.java` |
| `CamcorderProfile.hasProfile` | `static boolean hasProfile(int cameraId, int quality)` | 21 | Cheap existence check. |
| `EncoderProfiles` | `getDefaultDurationSeconds()`, `getRecommendedFileFormat()`, `getVideoProfiles(): List<VideoProfile>`, `getAudioProfiles(): List<AudioProfile>` | 31 | **[DOCUMENTED]** AOSP `EncoderProfiles.java` |
| `EncoderProfiles.VideoProfile` | `getCodec()`, `getMediaType()`, `getBitrate()`, `getFrameRate()`, `getWidth()`, `getHeight()`, `getProfile()`, `getBitDepth()`, `getChromaSubsampling()`, `getHdrFormat()` | 31 | `HdrFormat`: `HDR_NONE=0`, `HDR_HLG=1`, `HDR_HDR10=2`, `HDR_HDR10PLUS=3`, `HDR_DOLBY_VISION=4`. Chroma: `YUV_420`, `YUV_422`, `YUV_444`. |
| `MediaCodecInfo` | `getCanonicalName()`, `isAlias()`, `isSoftwareOnly()`, `isHardwareAccelerated()` | 29 | **Reject software-only H.264 encoders outright for a dashcam.** **[DOCUMENTED]** AOSP `MediaCodecInfo.java` |
| `CodecCapabilities` | `getMaxSupportedInstances()`, `isFormatSupported(MediaFormat)`, `getVideoCapabilities()` | 21/23 | `getMaxSupportedInstances()` bounds concurrent front+rear encoding. |
| `VideoCapabilities` | `getBitrateRange()`, `getSupportedWidths()`, `getSupportedHeights()`, `getWidthAlignment()`, `getHeightAlignment()`, `getSupportedFrameRates()`, `getSupportedFrameRatesFor(w,h): Range<Double>`, `getAchievableFrameRatesFor(w,h)`, `areSizeAndRateSupported(w,h,fps)`, `isSizeSupported(w,h)`, `getSupportedPerformancePoints(): List<PerformancePoint>?` | 21 / 23 / 29 | `getSupportedPerformancePoints()` is the real capability answer. |

**[DOCUMENTED]** `MediaCodecInfo.VideoCapabilities.getSupportedPerformancePoints()` javadoc, verbatim on the two caveats that matter:

> *"May return `null` if the codec did not publish any performance point information (e.g. the vendor codecs have not been updated to the latest android release). May return an empty list if the codec published that if does not guarantee any performance points. … This is a performance guarantee provided by the device manufacturer for hardware codecs based on hardware capabilities of the device. … **Performance points assume a single active codec. For use cases where multiple codecs are active, should use that highest pixel count, and add the frame rates of each individual codec.**"*

**[INFERRED]** That last sentence is the correct feasibility test for concurrent front+rear recording (§10): if you want 2 × 720p30 you must find a performance point at ≥720p and ≥60 fps, not two separate 720p30 points. Convenient predefined points to compare against include `PerformancePoint.HD_30` (1280×720@30), `HD_60`, `FHD_30` (1920×1080@30), `FHD_60`, `UHD_30`. **[DOCUMENTED]** AOSP `MediaCodecInfo.PerformancePoint` constants.

### 7.4 Startup probe to run once and cache

```kotlin
@OptIn(ExperimentalCamera2Interop::class)
suspend fun probe(context: Context): CameraProbe {
    val provider = ProcessCameraProvider.getInstance(context).await()
    val back = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).first()

    // Camera2 interop is best-effort: guard it (see §1, CameraPipe question).
    val camera2 = runCatching { Camera2CameraInfo.from(back) }.getOrNull()
    val cameraId = camera2?.cameraId
    val hwLevel = camera2?.getCameraCharacteristic(
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    val stabModes = camera2?.getCameraCharacteristic(
        CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)

    val caps = Recorder.getVideoCapabilities(back)
    return CameraProbe(
        cameraId          = cameraId,                                     // may be null
        hardwareLevel     = hwLevel,                                      // may be null
        sensorOrientation = back.sensorRotationDegrees,
        sdrQualities      = caps.getSupportedQualities(DynamicRange.SDR),
        dynamicRanges     = caps.supportedDynamicRanges,
        videoStabSupported= caps.isStabilizationSupported(),
        previewStabSupported = Preview.getPreviewCapabilities(back).isStabilizationSupported,
        stabModes         = stabModes?.toList().orEmpty(),
        fpsRanges         = back.supportedFrameRateRanges,
        isLogicalMulti    = back.isLogicalMultiCameraSupported,
        physicalIds       = back.physicalCameraInfos.size,
        concurrentSets    = provider.availableConcurrentCameraInfos.size,
        maxZoom           = back.zoomState.value?.maxZoomRatio,
        minZoom           = back.zoomState.value?.minZoomRatio,
    )
}
```

**[UNVERIFIED]** whether `Camera2CameraInfo.from(back)` succeeds in 1.6.1 given the CameraPipe migration. **Test that settles it:** run the snippet above on a Moto G04 with CameraX 1.6.1 and log whether `camera2` is non-null and whether `hwLevel` is populated. If it is null, fall back to the framework `CameraManager` directly (`getCameraIdList()` + `getCameraCharacteristics(id)`), matching the CameraX-selected camera by `LENS_FACING` + `SENSOR_ORIENTATION`.

---

## 8. Choosing the "best appropriate rear camera"

### 8.1 What the platform guarantees

**[DOCUMENTED]** Android 14 CDD §7.5 (https://source.android.com/docs/compatibility/14/android-14-cdd): *"The primary rear-facing camera is the rear-facing camera with the **lowest camera ID**."* (and the same rule for the primary front-facing camera).

**[DOCUMENTED]** AOSP `CameraManager.getCameraIdList()` javadoc: *"Non-removable cameras use integers starting at 0 for their identifiers… **This list doesn't contain physical cameras that can only be used as part of a logical multi-camera device.**"*

**[INFERRED]** Chain: the CDD pins the primary rear camera to the lowest rear-facing ID; `getCameraIdList()` already hides pure sub-cameras of a logical device; `CameraSelector.DEFAULT_BACK_CAMERA` = `Builder().requireLensFacing(LENS_FACING_BACK).build()` and `CameraSelector.select()` takes the **first** camera surviving the filters (**[DOCUMENTED]** `CameraSelector.java`, androidx-main, lines 73–77 and 102–103). Therefore, on a CDD-compliant device, `CameraSelector.DEFAULT_BACK_CAMERA` resolves to the primary rear camera — the same one the stock camera app opens.

**[UNVERIFIED]** that CameraX's `availableCameraInfos` preserves `getCameraIdList()` ordering. This matters only if a device exposes ultrawide/macro as *separate top-level* back-facing IDs (which the Edge 60 Fusion, with an ultrawide+macro, plausibly does). **Test that settles it:** log `provider.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId to it.lensFacing }` on the Edge 60 Fusion and assert that the first `LENS_FACING_BACK` entry has the lowest numeric id.

### 8.2 Defensive selection

**Default: use `CameraSelector.DEFAULT_BACK_CAMERA` and add a verification filter, not a clever heuristic.** The product constraint is "behave like a normal camera app"; a normal camera app opens the primary rear camera.

If you must guard against a non-compliant device handing you an ultrawide, the documented discriminator is focal length. **[DOCUMENTED]** https://developer.android.com/media/camera/camera2/multi-camera uses `CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS` (`Key<float[]>`) exactly this way — it computes the focal-length spread across a physical pair to identify the short (wide/ultrawide) and long (tele) member. Ultrawide modules report the smallest focal lengths of a device's back-facing set.

```kotlin
@OptIn(ExperimentalCamera2Interop::class)
fun pickPrimaryRear(provider: ProcessCameraProvider): CameraInfo {
    val backs = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos)
    require(backs.isNotEmpty()) { "No rear camera" }
    val primary = backs.first()                       // CDD: lowest rear-facing camera ID
    if (backs.size == 1) return primary

    // Sanity check only: never let the shortest-focal-length (ultrawide) module win.
    fun minFocal(ci: CameraInfo): Float =
        runCatching {
            Camera2CameraInfo.from(ci)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
        }.getOrNull() ?: Float.NaN

    val focals = backs.associateWith { minFocal(it) }.filterValues { !it.isNaN() }
    if (focals.size < 2) return primary               // can't reason; trust the CDD
    val shortest = focals.minByOrNull { it.value }!!.key
    return if (shortest === primary && focals.size > 1) {
        // Primary is the widest of several back cameras — suspicious. Take the next-shortest.
        focals.entries.sortedBy { it.value }[1].key
    } else primary
}
```

**[UNVERIFIED]** whether this fallback branch is ever taken on real hardware. Keep it behind a log line so bring-up tells you.

### 8.3 Physical camera IDs — available, but don't

**[DOCUMENTED]** `CameraSelector.Builder.setPhysicalCameraId(String)` javadoc, androidx-main, verbatim on the constraints:

> *"If we want to open one physical camera, for example ultra wide, we just need to set physical camera id in `CameraSelector` and bind to lifecycle. All CameraX features will work normally when only a single physical camera is used. … Currently only two physical cameras for the same logical camera id are allowed and the device needs to support physical cameras by checking `CameraInfo.isLogicalMultiCameraSupported()`. In addition, **there is no guarantee or API to query whether the device supports multiple physical camera opening or not.** Internally the library checks `CameraDevice.isSessionConfigurationSupported(SessionConfiguration)`, if the device does not support the multiple physical camera configuration, `IllegalArgumentException` will be thrown when binding to lifecycle."*

**[INFERRED]** "No guarantee or API to query" + "throws at bind time" is disqualifying for the #1 priority (recording reliability). Roadguard should not pin a physical camera ID. Multi-camera guidance itself points the same way: open the **logical** camera by default and reach for physical IDs only for depth/bokeh/zoom features (**[DOCUMENTED]** https://developer.android.com/media/camera/camera2/multi-camera).

**[DOCUMENTED]** The Moto G04 has a **single** rear camera (Motorola's spec page lists one rear camera: *"16 MP (f/2.2, 1,0 µm) | PDAF"*). So on the baseline device, wrong-camera selection is structurally impossible; the risk exists only on the Edge 60 Fusion, which has a 50 MP main + 13 MP ultrawide/macro (**[DOCUMENTED]** GSMArena spec sheet).

---

## 9. Video stabilization

### 9.1 API

```java
// CameraX 1.6.x
VideoCapture.Builder<T>.setVideoStabilizationEnabled(boolean)   // maps to CONTROL_VIDEO_STABILIZATION_MODE_ON
VideoCapture.isVideoStabilizationEnabled(): boolean
VideoCapabilities.isStabilizationSupported(): boolean            // gate: query before enabling

Preview.Builder.setPreviewStabilizationEnabled(boolean)         // maps to ..._PREVIEW_STABILIZATION
Preview.isPreviewStabilizationEnabled(): boolean
Preview.getPreviewCapabilities(CameraInfo): PreviewCapabilities
PreviewCapabilities.isStabilizationSupported(): boolean
```
**[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`, `camera-video/api/1.6.0-beta01.txt`.

**[DOCUMENTED]** Both builders carry the same warning: *"It is recommended to query the device capability via `VideoCapabilities.isStabilizationSupported()` / `PreviewCapabilities.isStabilizationSupported()` before enabling this feature, **otherwise HAL error might be thrown**."* (`VideoCapture.java` ≈ line 2402, `Preview.java` ≈ line 1380.)

### 9.2 The interaction matrix (verbatim from both javadocs)

| Preview | VideoCapture | Result |
|---|---|---|
| ON | ON | *"Both Preview and VideoCapture will be stabilized, VideoCapture quality might be worse than only VideoCapture stabilized"* |
| ON | OFF | *"None of Preview and VideoCapture will be stabilized"* |
| ON | **NOT SPECIFIED** | *"Both Preview and VideoCapture will be stabilized"* |
| OFF | ON | *"None of Preview and VideoCapture will be stabilized"* |
| OFF | OFF | *"None of Preview and VideoCapture will be stabilized"* |
| OFF | NOT SPECIFIED | *"None of Preview and VideoCapture will be stabilized"* |
| **NOT SPECIFIED** | ON | *"Only VideoCapture will be stabilized, Preview might be stabilized depending on devices"* |
| NOT SPECIFIED | OFF | *"None of Preview and VideoCapture will be stabilized"* |

**[DOCUMENTED]** identical table in `VideoCapture.Builder.setVideoStabilizationEnabled` and `Preview.Builder.setPreviewStabilizationEnabled` javadoc. Note the counter-intuitive rows: `Preview=ON, VideoCapture=OFF` yields **nothing** stabilized. Set both or neither; never mix explicit ON/OFF.

### 9.3 Camera2 ground truth and the FOV / resolution cost

```java
// android.hardware.camera2.CameraMetadata — CONTROL_VIDEO_STABILIZATION_MODE values
CONTROL_VIDEO_STABILIZATION_MODE_OFF                   = 0
CONTROL_VIDEO_STABILIZATION_MODE_ON                    = 1
CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION = 2   // API 33+
```
**[DOCUMENTED]** AOSP `CameraMetadata.java` lines 3284–3302.

**[DOCUMENTED]** AOSP `CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE` javadoc, verbatim on the three critical constraints:

> *"It is guaranteed that an output targeting a MediaRecorder or MediaCodec will be stabilized **if the recording resolution is less than or equal to 1920 x 1080 … and the recording frame rate is less than or equal to 30fps. At other sizes, the CaptureResult … field will return OFF** if the recording output is not stabilized."*

> *"The application is strongly recommended to call `SessionConfiguration.setSessionParameters` with the desired video stabilization mode **before creating the capture session**. Video stabilization mode is a session parameter on many devices. Specifying it at session creation time helps avoid **reconfiguration delay** caused by difference between the default value and the first CaptureRequest."*

> *"If a camera device supports both this mode and OIS (`android.lens.opticalStabilizationMode`), turning both modes on may produce undesirable interaction, so it is **recommended not to enable both at the same time**."*

And on `PREVIEW_STABILIZATION` **[DOCUMENTED]** (`CameraMetadata.java` lines 3292–3299, echoed in `VideoCapture.Builder` javadoc): *"the FoV reduction will be a maximum of 20 % both horizontally and vertically (10% from left, right, top, bottom) for the given zoom ratio / crop region."*

### 9.4 Roadguard decision

**Ship with stabilization OFF by default on both use cases; expose it as an advanced opt-in gated on capability.**

Reasoning:
1. **[DOCUMENTED]** A dashcam is legally/evidentially about field of view. `PREVIEW_STABILIZATION` costs *up to 20% of FOV in each axis* — a real loss of road, plate and shoulder coverage.
2. **[DOCUMENTED]** GSMArena's Moto G04 sensor list is *"Fingerprint (side-mounted), accelerometer, proximity"* — **no gyroscope**, and no OIS on the 16 MP module. **[INFERRED]** Gyro-based EIS without a gyro is not a thing, so `isStabilizationSupported()` on the G04 is very likely `false`. Tagged **[UNVERIFIED]** because GSMArena is a third-party aggregator and sensor lists are the kind of detail they get wrong; must be measured.
3. **[INFERRED]** Stabilization is a per-frame warp. On a Unisoc T606 with a permanently-on encoder, that is thermal budget spent on cosmetics, against priority #2.
4. **[DOCUMENTED]** The Edge 60 Fusion *does* have OIS on the main camera plus a gyro, and OIS runs regardless of `CONTROL_VIDEO_STABILIZATION_MODE`. Combined with the AOSP warning against enabling EIS+OIS together, the Edge already gets the stabilization that matters for free.
5. **[DOCUMENTED]** *"otherwise HAL error might be thrown"* — an unguarded enable is a recording-reliability hazard.

If it is ever enabled, do it via `VideoCapture.Builder.setVideoStabilizationEnabled(true)` (never `Camera2Interop.setCaptureRequestOption`), so CameraX can apply it as a session parameter, per the "avoid reconfiguration delay" guidance.

---

## 10. Concurrent front + rear

### 10.1 CameraX API (present and stable in 1.6.x)

```java
// androidx.camera.lifecycle.ProcessCameraProvider
List<List<CameraInfo>>  getAvailableConcurrentCameraInfos()   // Kotlin: availableConcurrentCameraInfos
@MainThread ConcurrentCamera bindToLifecycle(List<ConcurrentCamera.SingleCameraConfig?>)
@MainThread boolean     isConcurrentCameraModeOn()

// androidx.camera.core.ConcurrentCamera
ConcurrentCamera(List<Camera>)
List<Camera> getCameras()

// androidx.camera.core.ConcurrentCamera.SingleCameraConfig
SingleCameraConfig(CameraSelector, UseCaseGroup, LifecycleOwner)
SingleCameraConfig(CameraSelector, UseCaseGroup, CompositionSettings, LifecycleOwner)
CameraSelector getCameraSelector(); UseCaseGroup getUseCaseGroup();
CompositionSettings getCompositionSettings(); LifecycleOwner getLifecycleOwner()

// androidx.camera.core.CompositionSettings
static final CompositionSettings DEFAULT
float getAlpha(); Pair<Float,Float> getOffset(); Pair<Float,Float> getScale()
CompositionSettings.Builder: setAlpha(0..1), setOffset(-1..1, -1..1), setScale(float, float)
```
**[DOCUMENTED]** `camera-lifecycle/api/1.6.0-beta01.txt`, `camera-core/api/1.6.0-beta01.txt`.

> `ConcurrentCamera.setCompositionSettings(List<CompositionSettings>)`, `CompositionSettings.Builder.setZOrder/setRoundedCornerRatio/setBorderWidthRatio/setBorderColor` are **tip-only (1.7-alpha)**, not in 1.6.1. **[DOCUMENTED]** diff of `camera-core/api/1.6.0-beta01.txt` vs `camera-core/api/current.txt`.

### 10.2 The two documented modes

**[DOCUMENTED]** `ProcessCameraProvider.bindToLifecycle(List<SingleCameraConfig?>)` javadoc, verbatim:

> *"This function only supports combinations that are available via `availableConcurrentCameraInfos`. If the input list of `SingleCameraConfig`s does not match any of the supported combinations returned by `availableConcurrentCameraInfos`, `IllegalArgumentException` will be thrown. If cameras are already used by other `UseCase`s, `UnsupportedOperationException` will be thrown."*

> *"1. **Non-Composition mode**: These `SingleCameraConfig`s have different preview and video capture use cases and there is no `CompositionSettings`. In this mode, these previews and video captures can stream separately. CameraX doesn't perform any composition. You can also bind an extra image capture along with the preview and the video capture use cases."*

> *"2. **Composition mode**: If the concurrent cameras are binding the **same instances** of preview and video capture use cases, the concurrent cameras video recording is supported. The concurrent camera preview stream will be shared with video capture and record the concurrent cameras streams as a **composited stream**. … The composition mode only supports preview and video capture. ImageCapture is currently not supported. `CameraEffect` can be applied on the composited stream. However, **the mirrorMode of VideoCapture will be ignored.** This means the recorded video will have the same mirrorMode as the preview."*

### 10.3 Documented limits

**[DOCUMENTED]** https://developer.android.com/reference/androidx/camera/core/ConcurrentCamera (as summarised in CameraX docs): concurrent camera is an Android 11 (API 30) platform feature; CameraX supports **dual** concurrent cameras only, with **at most two `UseCase`s bound per camera**, and *"the max resolution is 720p or 1440p"*.

**[DOCUMENTED]** AOSP `CameraDevice` "Concurrent stream guaranteed configurations" table (https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CameraDevice.java) — for `BACKWARD_COMPATIBLE` devices listed by `getConcurrentCameraIds()`, per camera:

| Target 1 | Max size | Target 2 | Max size | Sample use case |
|---|---|---|---|---|
| `YUV` | `s1440p` | — | — | In-app video / image processing |
| `PRIV` | `s1440p` | — | — | In-app viewfinder analysis |
| `JPEG` | `s1440p` | — | — | No-viewfinder still capture |
| `YUV`/`PRIV` | `s720p` | `JPEG` | `s1440p` | Standard still imaging |
| `YUV`/`PRIV` | **`s720p`** | `YUV`/`PRIV` | **`s1440p`** | **In-app video / processing with preview** |

with the definitions, verbatim: *"`s720p` refers to the camera device's maximum resolution for that format from `StreamConfigurationMap.getOutputSizes` or 720p (1280X720) whichever is lower"* and *"`s1440p` … or 1440p (1920X1440) whichever is lower"*. Also: *"Devices which are not backwards-compatible, support a mandatory single stream of size sVGA with image format `DEPTH16` during concurrent operation."*

**[INFERRED]** Preview + VideoCapture per camera = two `PRIV` streams per camera, so the guaranteed envelope for dual-cam Roadguard is **one stream ≤1280×720 and one ≤1920×1440 per camera**. Practically: plan for **720p per camera**, not 1080p.

**[DOCUMENTED]** `getConcurrentCameraIds()` javadoc adds the ordering requirement: *"Applications must first close any open cameras that have sessions configured, using `CameraDevice.close()`. All camera devices intended to be operated concurrently, must be opened using `openCamera`, **before configuring sessions on any of the camera devices**."* And: *"Concurrent camera extension sessions … are not currently supported"*; *"The set of combinations doesn't contain physical cameras that can only be used as part of a logical multi-camera device."*

**[DOCUMENTED]** `CameraState.Type.PENDING_OPEN` javadoc: outside concurrent mode, *"the maximum number of cameras allowed to be open at the same time in CameraX … is currently set to 1."*

### 10.4 Runtime detection and Roadguard decision

```kotlin
val combos: List<List<CameraInfo>> = provider.availableConcurrentCameraInfos
val dualCamPossible = combos.any { set ->
    set.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
    set.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
}
```
An **empty** list means the device does not support it. **[DOCUMENTED]** `getConcurrentCameraIds()` javadoc: *"The set of combinations will be empty if no such combinations are supported by the camera subsystem."*

**[INFERRED]** Recommendation: treat concurrent front+rear as an **optional, capability-gated feature**, never a shipping requirement.
1. Nothing in the Moto G04's documented spec suggests concurrent-camera support (a 12 nm Unisoc T606 entry SoC); **[UNVERIFIED]** and must be probed.
2. It doubles the encoder load and halves the achievable resolution to 720p — directly against priorities #1 and #2.
3. If it is offered, the code must handle `IllegalArgumentException` (combo mismatch) and `UnsupportedOperationException` (cameras already in use) at bind time and fall back to single-camera without dropping recording.
4. Note that `setAvailableCamerasLimiter(DEFAULT_BACK_CAMERA)` from §2.2 **disables** this path.
5. **[INFERRED]** Feasibility for concurrent H.264 encode should be checked with `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()` plus the "add the frame rates" performance-point rule from §7.3 (i.e. look for ≥720p@60, not two 720p@30 points).

---

## 11. HDR video (`DynamicRange`) — skip it

```java
// androidx.camera.core.DynamicRange — 1.6.x, complete
ENCODING_UNSPECIFIED = 0, ENCODING_SDR = 1, ENCODING_HDR_UNSPECIFIED = 2,
ENCODING_HLG = 3, ENCODING_HDR10 = 4, ENCODING_HDR10_PLUS = 5, ENCODING_DOLBY_VISION = 6
BIT_DEPTH_UNSPECIFIED = 0, BIT_DEPTH_8_BIT = 8, BIT_DEPTH_10_BIT = 10

DynamicRange.UNSPECIFIED, SDR, HDR_UNSPECIFIED_10_BIT,
DynamicRange.HLG_10_BIT, HDR10_10_BIT, HDR10_PLUS_10_BIT,
DynamicRange.DOLBY_VISION_10_BIT, DOLBY_VISION_8_BIT
DynamicRange(int encoding, int bitDepth); getEncoding(); getBitDepth()
```
**[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`.

Setting and probing:
```kotlin
// probe
val ranges = Recorder.getVideoCapabilities(cameraInfo).supportedDynamicRanges       // Set<DynamicRange>
val hlgQualities = Recorder.getVideoCapabilities(cameraInfo)
                       .getSupportedQualities(DynamicRange.HLG_10_BIT)
val narrowed = cameraInfo.querySupportedDynamicRanges(setOf(DynamicRange.HLG_10_BIT))

// apply (both use cases can be set independently in 1.6)
VideoCapture.Builder(recorder).setDynamicRange(DynamicRange.HLG_10_BIT)
Preview.Builder().setDynamicRange(DynamicRange.HLG_10_BIT)
```
**[DOCUMENTED]** all four in the 1.6 signature files. Camera2 equivalent: `CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES` containing `REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT`, then `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES.getSupportedProfiles().contains(DynamicRangeProfiles.HLG10)`, then `OutputConfiguration.setDynamicRangeProfile(...)`. **[DOCUMENTED]** https://developer.android.com/media/camera/camera2/hdr-video-capture

### Documented pitfalls

**[DOCUMENTED]** https://developer.android.com/media/camera/camera2/hdr-video-capture:
- 10-bit HDR video capture targets **Android 13 (API 33)**; *"Starting in Android 13, camera devices with 10-bit output capabilities must support HLG10 for HDR capture and playback"* — i.e. HLG10 is the baseline **only on devices that have 10-bit output at all**.
- *"Camera apps cannot access HDR metadata via Camera2 APIs or Media APIs in Android 13."*
- *"Failing to correctly set color transfer functions causes quality issues (washout, color clipping)."*
- A separate SDR code path is required as a fallback.

**[DOCUMENTED]** CameraX 1.6.0 release notes list a fix for *"a crash that occurs on certain Android 17 (API 37) devices or higher when these devices expose new dynamic range profiles that were unknown to CameraX version 1.5.1 and earlier."* — evidence that dynamic-range handling is still a live source of crashes and another reason to be on 1.6.1 rather than 1.5.x.

### Roadguard decision: `DynamicRange.SDR`, explicitly

**[INFERRED]** Reasoning chain:
1. 10-bit HLG in practice means HEVC Main10 encoding. The Moto G04's documented ceiling is 1080p30 on a 12 nm entry SoC; a 10-bit HEVC encode is materially heavier than 8-bit AVC. Priorities #1 (reliability) and #2 (thermal) both lose.
2. Evidence value depends on a clip being *readable by anyone* — a police report, an insurer, a browser. Players that mishandle HLG produce washed-out or clipped footage, and Android 13 does not even let the app read back the HDR metadata to verify.
3. Roadguard is offline-first with no cloud transcode to normalise the output.

Set it explicitly (`.setDynamicRange(DynamicRange.SDR)`) rather than leaving it unspecified, so the choice is visible in code and immune to a future default change.

---

## 12. Long-running and segmented recording

### 12.1 The lifecycle problem, and the only correct answer

**[DOCUMENTED]** `PendingRecording.start()` javadoc: *"The `Recording` will be stopped automatically if the `VideoCapture` its `Recorder` is attached to is unbound unless it's created as a persistent recording."*

**[INFERRED]** Chain: `ProcessCameraProvider.bindToLifecycle` ties use-case attachment to the `LifecycleOwner`. If that owner is the Activity, then every `onStop()` — screen off, a phone call, the user opening Maps, a rotation *without* `configChanges` — unbinds `VideoCapture` and terminates the recording. **A dashcam bound to an Activity lifecycle is broken by construction.**

Correct architecture:
1. A **foreground service** owns a `androidx.lifecycle.LifecycleRegistry` (or extends `LifecycleService`) and holds it at `STARTED`/`RESUMED` for the whole recording session.
2. `bindToLifecycle(serviceLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)` — bind once; the Activity only attaches/detaches the `Preview` surface provider.
3. When the UI goes away, call `preview.setSurfaceProvider(null)` (the `@UiThread` nullable overload exists in 1.6.x — **[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt`) rather than unbinding anything.

**[DOCUMENTED]** Foreground-service requirements at `minSdk = 34` (https://developer.android.com/develop/background-work/services/fgs/service-types and .../fgs/declare):
- Manifest: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>` **and** `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA"/>`; add `FOREGROUND_SERVICE_MICROPHONE` if audio is recorded. Service declares `android:foregroundServiceType="camera"` (plus `microphone`).
- *"Apps that target Android 14 (API level 34) or higher must request the appropriate permissions for the foreground service type… the system checks for the appropriate permissions and throws `SecurityException` if the app is missing any."*
- *"The `CAMERA` runtime permission is subject to while-in-use restrictions. For this reason, **you cannot create a camera foreground service while your app is in the background**, with a few exceptions."*

**[INFERRED]** Consequence for Roadguard's product design: auto-start-on-boot or auto-start-on-charger-connect **cannot** launch the camera foreground service directly, because the app is in the background at that moment. Recording must be armed from a visible Activity (the user opens Roadguard, or a full-screen-intent/launch flow brings it forward first). This needs to be designed for, not discovered.

**Do we need `asPersistentRecording()`?** **[DOCUMENTED]** its javadoc: a persistent recording *"will only be stopped by explicitly calling `Recording.stop` or `Recording.close` and will ignore events that would normally cause recording to stop, such as lifecycle events or explicit unbinding of a `VideoCapture`"* — **but** *"it will still stop the camera from producing data… the recording will keep waiting for new data to be recorded until the activity is back to foreground."* **[INFERRED]** So it converts "recording ends" into "recording stalls", which for a dashcam is not better — it produces a clip with a time hole and no error. It is designed for *switching cameras mid-recording*. It is `@ExperimentalPersistentRecording`. **Do not use it for lifecycle robustness; use the service-owned lifecycle.** It *is* the right tool if Roadguard ever adds "swap to front camera without cutting the clip".

### 12.2 Is there an AndroidX auto-split? No.

**[DOCUMENTED]** The complete set of limit APIs in `camera-video/api/1.6.0-beta01.txt` is:
```java
OutputOptions.FILE_SIZE_UNLIMITED = 0
OutputOptions.DURATION_UNLIMITED  = 0
long getFileSizeLimit(); long getDurationLimitMillis(); Location? getLocation()
// on FileOutputOptions.Builder / MediaStoreOutputOptions.Builder / FileDescriptorOutputOptions.Builder:
setFileSizeLimit(@IntRange(from=0) long)
setDurationLimitMillis(@IntRange(from=0) long)
setLocation(Location?)
```
There is **no** `setMaxSegmentDuration`, no rollover callback, no split API. When a limit is hit, the recording is **finalized** with `ERROR_FILE_SIZE_LIMIT_REACHED` (2) or `ERROR_DURATION_LIMIT_REACHED` (9), and *"data before the limit is saved to file"*. **[DOCUMENTED]** `VideoRecordEvent.java` javadoc.

Two non-obvious implementation details of those limits **[DOCUMENTED]** from `Recorder.java` (androidx-main):
- The file-size limit is applied at **95%** of the value you set: `mFileSizeLimitInBytes = Math.round(getFileSizeLimit() * 0.95)`, with the comment *"Use %95 of the given file size limit as the criteria, which refers to the MPEG4Writer.cpp in libstagefright."* (≈ line 1981). So `setFileSizeLimit(N)` actually cuts at `0.95 × N`.
- If the very first video+audio payload already exceeds the limit, the recording is failed immediately with `ERROR_FILE_SIZE_LIMIT_REACHED` — with the comment *"Make sure we can write the first audio and video data without hitting the file size limit. Otherwise we will be left with a malformed (empty) track on stop."* (≈ line 1846).

### 12.3 The minimum-gap rollover: `stop()` then immediately `start()`

This is the highest-value finding in this document. **You do not wait for the `Finalize` event.**

**[DOCUMENTED]** `Recorder.stop(Recording, int, Throwable)` (androidx-main ≈ lines 1195–1248) — in the `RECORDING`/`PAUSED` cases it does, **synchronously inside `synchronized (mLock)` before returning**:
```java
case PAUSED:  // fall-through
case RECORDING:
    setState(State.STOPPING);
    long explicitlyStopTimeUs = mTimeProvider.uptimeUs();
    RecordingRecord finalActiveRecordingRecord = mActiveRecordingRecord;
    mSequentialExecutor.execute(() -> stopInternal(finalActiveRecordingRecord,
            explicitlyStopTimeUs, error, errorCause));
    break;
```

**[DOCUMENTED]** `Recorder.start(PendingRecording)` (androidx-main ≈ lines 1018–1102) — the `STOPPING` state is explicitly a *queueing* case, not an error case:
```java
switch (mState) {
    case PAUSED: case RECORDING:                  // -> throws IllegalStateException
        alreadyInProgressRecording = mActiveRecordingRecord; break;
    case PENDING_PAUSED: case PENDING_RECORDING:  // -> throws IllegalStateException
        alreadyInProgressRecording = checkNotNull(mPendingRecordingRecord); break;
    case RESETTING: case STOPPING: case CONFIGURING: case ERROR: case IDLING:
        ...
        mPendingRecordingRecord = recordingRecord;
        ...
        } else {
            setState(State.PENDING_RECORDING);
            // The recording will automatically start once the initialization completes.
        }
        break;
}
```

**[DOCUMENTED]** `Recorder.onRecordingFinalized(RecordingRecord)` (androidx-main ≈ lines 2849–2904) then services it with zero further app involvement:
```java
case PENDING_PAUSED: startRecordingPaused = true;  // fall-through
case PENDING_RECORDING:
    if (mSourceState == SourceState.INACTIVE) { ... ERROR_SOURCE_INACTIVE ... }
    else if (mVideoEncoder != null) {
        recordingToStart = makePendingRecordingActiveLocked(mState);
    }
    break;
```

**[INFERRED]** Therefore the correct rollover is:

```kotlin
// Called from the recorder's own single-thread executor, on the app's 3-minute timer.
@MainThread   // or: consistently on one thread; the key is that stop() and start() are adjacent
fun rollSegment() {
    val previous = currentRecording          // hard reference held for the whole segment
    val next = recorder
        .prepareRecording(appContext, nextSegmentOutputOptions())
        .apply { if (audioEnabled) withAudioEnabled() }

    previous?.stop()                          // -> State.STOPPING, synchronously, under mLock
    currentRecording = next.start(recordEventExecutor, ::onRecordEvent)  // queued as PENDING_RECORDING
}
```
Calling `start()` while `mState == STOPPING` does **not** throw and does **not** need the `Finalize` callback: it lands in `PENDING_RECORDING` and CameraX starts it the instant the previous muxer finalises.

### 12.4 How large is the gap? (honest answer)

**[DOCUMENTED]** `Recorder.stopInternal()` (≈ line 2568) calls `mVideoEncoder.stop(explicitlyStopTime)`. **[DOCUMENTED]** the new-segment path drops non-keyframe data and asks for a new one (≈ line 2138): *"The first video frame must be key frame, otherwise drop it"* → `mVideoEncoder.requestKeyFrame();`. **[DOCUMENTED]** `onRecordingFinalized` reuses the **existing** encoder (`else if (mVideoEncoder != null)`), i.e. the codec instance is not released and reconfigured between segments in the happy path.

**[INFERRED]** So the inter-segment gap ≈ (time to flush + finalise the outgoing muxer) + (time for the encoder to emit a fresh IDR after `requestKeyFrame()`). At 30 fps a requested sync frame typically arrives within one or two frames (≈33–67 ms), but the muxer finalise cost depends on file system and file size.

**NOT VERIFIED — needs on-device measurement.** No number is asserted here. **Test that settles it:** record 20 consecutive 3-minute segments on a Moto G04; for each boundary compute `gap = firstPresentationTime(segment N+1) − (startTime(segment N) + duration(segment N))` from `MediaExtractor`/`MediaMetadataRetriever`, and separately log wall-clock deltas between the `Finalize` event of segment N and the `Start` event of segment N+1. Report min/median/p95/max.

**[INFERRED]** Truly gapless recording is not achievable through `androidx.camera.video` in 1.6.x: only one `VideoCapture` can be bound at a time, `Recorder` allows only one active recording, and the muxer/keyframe handoff above is unavoidable. Gapless would require dropping to `MediaCodec` + a custom muxer with segment-boundary IDR alignment — a large, risky rewrite that abandons every CameraX quirk workaround. **Recommendation: accept a sub-second gap, and record the measured value in the app's own metadata so timeline reconstruction is exact.**

### 12.5 Belt-and-braces limits

```kotlin
private const val SEGMENT_MS = 3 * 60 * 1000L                 // 180_000 — product requirement
private const val SEGMENT_HARD_MS = SEGMENT_MS + 15_000L       // 195_000 — watchdog only

FileOutputOptions.Builder(segmentFile)
    .setDurationLimitMillis(SEGMENT_HARD_MS)   // safety net if the app timer stalls
    .setFileSizeLimit(expectedSegmentBytes * 3)  // remember: enforced at 0.95x
    .build()
```
**[INFERRED]** The app timer drives normal rollover (giving a clean `stop()`/`start()` pair with no error code); the `Recorder`'s own duration limit exists only so a hung timer cannot produce an unbounded file. When `ERROR_DURATION_LIMIT_REACHED` (9) is observed, log it as a **bug in Roadguard**, not a device problem, and start the next segment as normal (the data before the limit is saved).

---

## 13. Output options and storage safety

### 13.1 The hard 50 MiB floor you cannot configure

**[DOCUMENTED]** `Recorder.java` (androidx-main, lines 444–449):
```java
private static final long REQUIRED_FREE_STORAGE_UNSET = -1L;
private static final long REQUIRED_FREE_STORAGE_DEFAULT_BYTES = 50L * 1024L * 1024L; // 50MB
private static final String INSUFFICIENT_STORAGE_ERROR_MSG =
        "Insufficient storage space. The available storage (%d bytes) is below the required "
                + "threshold of %d bytes.";
```
and the enforcement, at recording start (≈ line 1970) and again during writing (≈ line 2391):
```java
if (hasInsufficientStorage(availableBytes)) {
    finalizeInProgressRecording(ERROR_INSUFFICIENT_STORAGE, new IOException(...));
    return;
}
mAvailableBytesAboveRequired = availableBytes - mRequiredFreeStorageBytes;
...
if (newRecordingBytes > mAvailableBytesAboveRequired) {   // re-check only when headroom is consumed
    long availableBytes = mOutputStorage.getAvailableBytes();
    if (hasInsufficientStorage(availableBytes)) {
        onInProgressRecordingInternalError(recording, ERROR_INSUFFICIENT_STORAGE, ...);
```
with `private boolean hasInsufficientStorage(long availableStorageBytes) { return availableStorageBytes < mRequiredFreeStorageBytes; }`.

**[DOCUMENTED]** There is **no public setter** for this threshold — `RequiredFreeStorage` appears nowhere in `camera-video/api/1.6.0-beta01.txt` **or** `camera-video/api/current.txt`.

**[INFERRED]** Therefore Roadguard's loop-delete policy must keep free space comfortably above 50 MiB *at all times*, and the reserve must be sized so that a whole segment can be written without crossing the floor even if deletion is briefly behind. Concrete policy:

```
reserve = 50 MiB (CameraX floor)
        + 3 × maxSegmentBytes   (in-flight segment + rollover slack + one deletion cycle)
        + 200 MiB               (OS/other-app headroom on a 4 GB-class device)
```
Enforce it **before** starting each segment (not only on a timer), and delete oldest-first until satisfied.

**[DOCUMENTED]** CameraX 1.5 release notes: *"`VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE`: Now triggered for insufficient storage during recording"* — the mid-recording check is a recent addition, another reason not to target 1.4.x.

### 13.2 `FileOutputOptions` vs `MediaStoreOutputOptions`

```java
FileOutputOptions.Builder(File)                    // + setFileSizeLimit / setDurationLimitMillis / setLocation
MediaStoreOutputOptions.Builder(ContentResolver, Uri collectionUri)
    setContentValues(ContentValues)                // MediaStoreOutputOptions.EMPTY_CONTENT_VALUES default
FileDescriptorOutputOptions.Builder(ParcelFileDescriptor)   // @RequiresApi(26) on prepareRecording
```
**[DOCUMENTED]** `camera-video/api/1.6.0-beta01.txt`.

**[DOCUMENTED]** https://developer.android.com/training/data-storage/shared/media — on Android 10+ *"you don't need storage-related permissions to access and modify media files that your app owns"*; deleting an owned file can still throw `RecoverableSecurityException`, whose remedy is `startIntentSenderForResult(...)` (API 29+) or `MediaStore.createDeleteRequest(...)` (API 30+) — i.e. **a user consent dialog**.

**[INFERRED]** A loop recorder must never depend on a consent dialog to free space, because the driver is driving. Recommendation:

| Concern | `FileOutputOptions` → `context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)` | `MediaStoreOutputOptions` |
|---|---|---|
| Delete an old segment | `File.delete()` — synchronous, no permission, no dialog, ever | `ContentResolver.delete()`, may throw `RecoverableSecurityException` → dialog |
| Per-segment cost | direct file I/O | a `ContentResolver` insert + `IS_PENDING` transition per segment |
| Media scanner / Gallery | not scanned (good: 500 dashcam clips do not pollute the user's Gallery) | scanned |
| Offline-first / no-telemetry posture | nothing leaves the app sandbox | entries visible to other apps and to cloud-backup photo apps |
| Uninstall | deleted with the app | survives |
| Free-space measurement | `File.getUsableSpace()` on the same volume the Recorder writes to | indirect |

**Decision: `FileOutputOptions` into app-specific external storage for the loop; a separate, explicit "Save / Export" action that copies a protected clip into `MediaStore` (with `IS_PENDING=1` → `0`, `DISPLAY_NAME`, `RELATIVE_PATH`, and never the `DATA` column).** **[DOCUMENTED]** the `IS_PENDING` / `DISPLAY_NAME` / `RELATIVE_PATH` / "don't use `DATA`" rules are all from the shared-media page above.

`setLocation(Location?)` is available on every `OutputOptions.Builder`. **[INFERRED]** For a dashcam this is genuinely useful evidence, and it stays offline (it is written into the MP4, not uploaded). Note `Recorder` finalises with `ERROR_INVALID_OUTPUT_OPTIONS` if `muxer.setLocation(lat, lon)` throws `IllegalArgumentException` (**[DOCUMENTED]** `Recorder.java` ≈ line 1887) — so validate coordinates before setting them.

---

## 14. Error handling and recovery

### 14.1 `VideoRecordEvent.Finalize` — all 11 codes

Values and descriptions **[DOCUMENTED]** from `camera-video/api/1.6.0-beta01.txt` (values) and `VideoRecordEvent.java` javadoc (semantics). Actions are **[INFERRED]** for Roadguard.

| Code | Value | Documented meaning | Roadguard action |
|---|---|---|---|
| `ERROR_NONE` | 0 | *"The recording succeeded with no error."* | Index the segment; start the next one (already queued by §12.3). |
| `ERROR_UNKNOWN` | 1 | Unknown error; output file may or may not be generated; app should clean up. | Validate the file (see §14.4); delete if unplayable. Retry next segment. Count toward a backoff. |
| `ERROR_FILE_SIZE_LIMIT_REACHED` | 2 | Recording stopped due to file size limit; **data before the limit is saved**. | Keep the file. Log as a Roadguard bug if it fires before the duration limit (bitrate estimate wrong). Continue. |
| `ERROR_INSUFFICIENT_STORAGE` | 3 | Storage full before/during recording; file may or may not be generated. | **Highest-priority recovery.** Free space aggressively (oldest-first, past the §13.1 reserve), then retry. If still failing after N attempts, surface a persistent, loud notification — the dashcam is not recording. |
| `ERROR_SOURCE_INACTIVE` | 4 | Source became inactive; output contains frames before the source stopped. | Keep the file. Means the camera stopped producing — almost always a lifecycle/binding bug or camera eviction. Check `CameraState`, re-bind, restart. |
| `ERROR_INVALID_OUTPUT_OPTIONS` | 5 | *"Invalid output options have been used"*; no output file generated. | Programming error (bad path, bad `Location`, bad orientation degrees). Fall back to a known-good directory and a null location; alert. |
| `ERROR_ENCODING_FAILED` | 6 | Video/audio codec error during encoding; output file not properly constructed. | Delete the file. Retry once at the same quality; on a second failure step **down** the quality ladder (FHD→HD→SD) and rebuild the `Recorder`. |
| `ERROR_RECORDER_ERROR` | 7 | *"Recorder in unrecoverable error state; **requires new Recorder instance**."* | Mandatory: build a **new** `Recorder`, a **new** `VideoCapture`, and rebind. Do not reuse the old objects. |
| `ERROR_NO_VALID_DATA` | 8 | Essential data missing (e.g. no key frame); app must clean up the file. | Delete the file. Common if a segment is stopped almost immediately — guard against sub-second segments. |
| `ERROR_DURATION_LIMIT_REACHED` | 9 | Recording stopped due to duration limit; data before the limit is saved. | Keep the file. Log as a Roadguard bug (the app timer should have rolled first — §12.5). Continue. |
| `ERROR_RECORDING_GARBAGE_COLLECTED` | 10 | Recording stopped because the `Recording` object was garbage collected. | **Never acceptable.** A pure bug: you dropped the hard reference. Assert loudly in debug builds. |

The escalation ladder in one place, **[INFERRED]**:
```
Finalize error
  ├─ 3  (storage)     -> free space -> retry (loop, with loud notification if persistent)
  ├─ 7  (recorder)    -> new Recorder + new VideoCapture + rebind
  ├─ 6  (encoding)    -> retry once -> step down quality -> new Recorder
  ├─ 4  (source)      -> inspect CameraState -> unbindAll + rebind
  ├─ 1,8 (unknown/no data) -> delete file, retry, count toward backoff
  ├─ 5  (options)     -> fall back to safe output options
  ├─ 2,9 (limits)     -> keep file, log app bug, continue
  └─ 10 (GC)          -> assert; fix the reference bug
```

### 14.2 `CameraState` — all 8 errors and their severity

**[DOCUMENTED]** `camera-core/api/1.6.0-beta01.txt` + `CameraState.java` javadoc. Observe via `cameraInfo.getCameraState(): LiveData<CameraState>`; `CameraState` has `getType(): Type` and `getError(): StateError?`, and `StateError` has `getCode(): int`, `getCause(): Throwable?`, `getType(): ErrorType`.

`CameraState.Type`: `PENDING_OPEN`, `OPENING`, `OPEN`, `CLOSING`, `CLOSED`.
`CameraState.ErrorType`: `RECOVERABLE`, `CRITICAL`.

| Error | Value | Severity | Documented meaning | Roadguard action |
|---|---|---|---|---|
| `ERROR_MAX_CAMERAS_IN_USE` | 1 | RECOVERABLE | Limit of open cameras reached. | Wait for `PENDING_OPEN`; CameraX retries. Close your own extra cameras. |
| `ERROR_CAMERA_IN_USE` | 2 | RECOVERABLE | *"camera device is already in use… could be due to the camera device being used by a higher-priority camera client."* | The classic "user opened the stock camera app" case. Show a clear "not recording — camera in use" notification; CameraX will reopen. |
| `ERROR_OTHER_RECOVERABLE_ERROR` | 3 | RECOVERABLE | *"CameraX will attempt to recover… otherwise the camera will move to a `PENDING_OPEN` state."* Maps to Camera2 `ERROR_CAMERA_DEVICE`. | Let CameraX retry; restart the segment on recovery. |
| `ERROR_STREAM_CONFIG` | 4 | CRITICAL | *"configuring the camera has failed."* | Your use-case combination was rejected. Step down (quality, then drop stabilization/HDR) and rebind. Pre-empt with `CameraInfo.isSessionConfigSupported()`. |
| `ERROR_CAMERA_DISABLED` | 5 | CRITICAL | *"could not be opened due to a device policy"* (`DevicePolicyManager.setCameraDisabled`). | Cannot recover. Tell the user their admin/policy disabled the camera. |
| `ERROR_CAMERA_FATAL_ERROR` | 6 | CRITICAL | *"closed due to a fatal error… may require the Android device to be shut down and restarted."* Maps to Camera2 `ERROR_CAMERA_SERVICE`. | Stop retrying in a tight loop. Exponential backoff + a loud notification instructing a reboot. |
| `ERROR_DO_NOT_DISTURB_MODE_ENABLED` | 7 | CRITICAL | Android 9 (API 28) LEGACY-hardware bug. *"CameraX will not attempt to reopen the camera device."* | Unreachable at `minSdk = 34`. Log-only. |
| `ERROR_CAMERA_REMOVED` | 8 | CRITICAL | *"...a USB camera is unplugged. This is a terminal state… the associated `Camera` and `CameraInfo` objects are no longer valid. Attempting to call methods on them may result in exceptions."* Action: *"unbind all use cases from the invalid camera and switch to another available camera"* via `getAvailableCameraInfos()` / `hasCamera()`. | Not expected on a phone's built-in camera, but the "objects are no longer valid" warning means you must not cache `CameraInfo` across this event. |

**[DOCUMENTED]** `CameraState.Type.PENDING_OPEN` javadoc also gives the practical hook: *"Developers may rely on this state to close any other open cameras in the app, or request their user close an open camera in another app."*

### 14.3 Disconnect / reopen strategy

**[INFERRED]** from the above:
```
observe cameraInfo.cameraState
  RECOVERABLE error -> do nothing but notify; CameraX retries and lands in PENDING_OPEN/OPENING
  PENDING_OPEN      -> the camera is contended; keep the FGS alive, keep notifying
  OPEN (after error)-> re-arm: start a fresh segment
  CRITICAL error    -> unbindAll(); backoff (1s, 2s, 5s, 15s, 60s cap); rebuild Recorder +
                       VideoCapture; rebind; if 4 or 5, stop retrying and tell the user why
```
Never tear the foreground service down on a camera error — that would surrender the ability to recover at all, and (per §12.1) the service cannot be restarted from the background.

### 14.4 Validate every finalized segment

**[INFERRED]** Codes 1, 6 and 8 all admit "the file exists but may be garbage". Since Roadguard's whole value is that the file plays back later, validate cheaply at finalize: open the URI with `MediaMetadataRetriever`, require non-null `METADATA_KEY_DURATION > 0` and a non-null `METADATA_KEY_VIDEO_WIDTH`, and also read `METADATA_KEY_VIDEO_ROTATION` — which is exactly the orientation hint written in §3.3, and therefore the cheapest possible regression test that the orientation model is behaving.

---

## 15. The recommended Roadguard configuration, in one place

```kotlin
// ── Application ───────────────────────────────────────────────────────────────
class RoadguardApp : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.WARN)
            // Omit the limiter if concurrent front+rear is a product feature (§2.2, §10).
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .build()
}

// ── Foreground service owns the lifecycle (§12.1) ─────────────────────────────
// AndroidManifest.xml:
//   <uses-permission android:name="android.permission.CAMERA"/>
//   <uses-permission android:name="android.permission.RECORD_AUDIO"/>          (if audio)
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA"/>
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/> (if audio)
//   <service android:name=".RecordingService"
//            android:foregroundServiceType="camera|microphone"
//            android:exported="false"/>
//   <activity android:name=".ui.MainActivity"
//             android:screenOrientation="fullSensor"
//             android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|
//                                    keyboardHidden|uiMode|density|layoutDirection|navigation"/>

// ── Use cases ─────────────────────────────────────────────────────────────────
val preview = Preview.Builder()
    .setTargetRotation(initialRotation)
    .build()
    .also { it.setSurfaceProvider(previewView.surfaceProvider) }   // or Compose SurfaceRequest

val recorder = Recorder.Builder()
    .setQualitySelector(QualitySelector.fromOrderedList(
        listOf(Quality.FHD, Quality.HD, Quality.SD),
        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
    .setExecutor(recorderExecutor)                    // dedicated single thread
    .setAspectRatio(AspectRatio.RATIO_16_9)
    .build()

val videoCapture = VideoCapture.Builder(recorder)
    .setTargetRotation(initialRotation)
    .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
    .setDynamicRange(DynamicRange.SDR)
    .setTargetFrameRate(Range(30, 30))
    .setVideoStabilizationEnabled(false)
    .build()

// ── Bind: exactly two use cases, no UseCaseGroup, no ViewPort ─────────────────
val camera = cameraProvider.bindToLifecycle(
    serviceLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
camera.cameraInfo.cameraState.observe(serviceLifecycleOwner, ::onCameraState)
```

### Why exactly two use cases

**[DOCUMENTED]** https://developer.android.com/media/camera/camerax/architecture: *"On devices with camera hardware level `FULL` or lower, combining `Preview`, `VideoCapture`, and either `ImageCapture` or `ImageAnalysis` may force CameraX to duplicate the camera's `PRIV` stream for `Preview` and `VideoCapture`"*, and stream sharing carries *"increased processing demands, higher latency, reduced battery life"*. Also: *"If an incompatible use case combination is created, a runtime error throws on first `createCaptureSession()` call."*

**[INFERRED]** A Unisoc T606 is very unlikely to be `LEVEL_3` (must be probed — §7.4), so adding a third use case would likely trigger stream sharing and an extra GPU copy on the baseline device. If Roadguard needs still photos, take them by extracting a frame from the recorded video or from `PreviewView.getBitmap()` rather than binding `ImageCapture`. If it needs frame analysis (e.g. lane/collision heuristics), gate `ImageAnalysis` behind a device-tier check and accept that it may be off on the G04.

### The thermally-optimal path, and how to confirm you are on it

**[INFERRED]** With Preview + VideoCapture, MP4 output, no `ViewPort`, no `CameraEffect`, `MIRROR_MODE_OFF`, `DynamicRange.SDR` and no device quirk matching, **every** disjunct of `isCreateNodeNeeded(...)` (§4.1) is false, so no `SurfaceProcessorNode` is created and the camera writes directly into the encoder's input surface — zero OpenGL passes in the recording path. Confirm with the two checks in §4.2. If `"Surface processing is enabled."` ever appears in logcat during normal recording, treat it as a thermal regression and find out which disjunct fired.

---

## 16. Open questions / must-measure-on-device

Every item below is a real gap, with the exact test that closes it. None of these were measured in this session.

### Blocking — must be answered before writing the recorder

1. **Inter-segment gap at 3-minute rollover.** How many milliseconds of road are lost between segments using the `stop()`-then-immediate-`start()` pattern of §12.3?
   *Test:* record 20 consecutive segments on a Moto G04 and on an Edge 60 Fusion. For each boundary compute the presentation-time gap with `MediaExtractor`, and log wall-clock delta between segment N's `Finalize` and segment N+1's `Start`. Report min/median/p95/max. Also repeat with a 90%-full filesystem, since muxer finalise cost is I/O bound.

2. **Does `Camera2CameraInfo.from()` work in CameraX 1.6.1 after the CameraPipe migration?** (§1, §7.4)
   *Test:* run the §7.4 probe on both devices; assert `Camera2CameraInfo.from(cameraInfo)` returns non-null and `INFO_SUPPORTED_HARDWARE_LEVEL` is populated. If not, implement the `CameraManager`-direct fallback before any capability code ships.

3. **Is any surface processing forced on the target devices?** (§4.2)
   *Test:* bind Preview + VideoCapture with the §15 config; grep logcat for `"Surface processing is enabled."` and for the `VideoCapture` pipeline line; assert `videoCapture.resolutionInfo!!.cropRect` equals the full resolution.

4. **Moto G04 hardware level and `Quality.FHD` availability.**
   *Test:* log `INFO_SUPPORTED_HARDWARE_LEVEL` and `Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR)`. If FHD is absent, the quality ladder and all storage/bitrate budgeting change.

5. **Orientation end-to-end.** Does the produced MP4 actually carry the expected rotation, and does the encoded frame size stay constant across all four device orientations?
   *Test:* for each of the four orientations, record a 10-second segment, then read `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION`, `..._VIDEO_WIDTH`, `..._VIDEO_HEIGHT`. Expect rotation ∈ {0, 90, 180, 270} varying with orientation while width/height stay at the sensor-natural landscape size. Then confirm playback orientation in at least: the device Gallery, VLC for Android, Chrome desktop `<video>`, QuickTime/macOS, and Windows Media Player — the MP4 matrix is documented to be ignorable by players (§3.3).

6. **Does rotation ever fire mid-segment in a real car mount?** Is the 600 ms hysteresis in §3.5 enough, or does vibration cause flapping?
   *Test:* log every `snapToSurfaceRotation` transition during a 30-minute drive with the phone in a windshield mount, in both portrait and landscape mounting.

### High priority

7. **`isStabilizationSupported()` on both devices, and whether the Moto G04 really lacks a gyroscope.** The no-gyro claim rests on a third-party aggregator (§9.4).
   *Test:* `SensorManager.getSensorList(Sensor.TYPE_ALL)`, plus `VideoCapabilities.isStabilizationSupported()`, `PreviewCapabilities.isStabilizationSupported()`, and `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`.

8. **Concurrent front+rear support.** Is `availableConcurrentCameraInfos` non-empty on either device, and does it contain a back+front pair?
   *Test:* log the full nested list with camera IDs and lens facings; if non-empty, attempt a 720p+720p bind and measure sustained fps and skin temperature. Cross-check with `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()` and the "add the frame rates" performance-point rule (§7.3).

9. **HDR availability, if the decision is ever revisited.**
   *Test:* `Recorder.getVideoCapabilities(cameraInfo).supportedDynamicRanges` and `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES`. Expected to be SDR-only on the G04.

10. **Sustained-thermal behaviour of the chosen encode.** How long can each device hold 1080p30 (and 720p30) before frame drops or thermal throttling?
    *Test:* 60-minute continuous recording with the screen on at typical brightness, logging `RecordingStats.getRecordedDurationNanos()` vs wall clock (to detect dropped frames), `PowerManager.getCurrentThermalStatus()`, and per-segment file sizes. Do it at 25 °C ambient and again in a hot car. This is the input to the thermal-management design, which is a separate research topic.

11. **Actual bitrate per quality**, so §12.5's `setFileSizeLimit` and §13.1's storage reserve are sized from data.
    *Test:* record 10 segments at each quality in mixed driving; report bytes/second min/median/max. Note that `Recorder.Builder.setTargetVideoEncodingBitRate` is available if the device default is unreasonable.

12. **Does `CameraSelector.DEFAULT_BACK_CAMERA` pick the main (not ultrawide) camera on the Edge 60 Fusion?** (§8.1)
    *Test:* log `availableCameraInfos` with camera IDs, lens facings, and `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`; confirm the selected camera has the larger focal length and a `SENSOR_INFO_ACTIVE_ARRAY_SIZE` consistent with the 50 MP module.

### Worth confirming

13. **`Status` event cost.** Confirm empirically that `VideoRecordEvent.Status` really arrives ~30×/s (§2.6) and measure the CPU cost of the listener.
    *Test:* count `Status` events per second over 60 s; then verify no main-thread frames are dropped with the listener on a background executor.

14. **`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` at night.** Does either device drop below 30 fps in low light, and does `setTargetFrameRate(Range(30, 30))` actually pin it?
    *Test:* log the available ranges, then record at night and compute actual fps from frame presentation times.

15. **`ImplementationMode` choice.** Which of `PERFORMANCE`/`COMPATIBLE` (View) or `EXTERNAL`/`EMBEDDED` (Compose) does the compat chooser select on each device, and what does it cost?
    *Test:* log the resolved mode; measure preview-path GPU/CPU with each mode forced.

16. **`FileOutputOptions` free-space accounting.** Does `File.getUsableSpace()` on `getExternalFilesDir(...)` agree with what `Recorder`'s internal `OutputStorage.getAvailableBytes()` sees (i.e. same volume, same quota view)?
    *Test:* fill the volume to just above and just below the 50 MiB floor and confirm `ERROR_INSUFFICIENT_STORAGE` fires exactly when the app's own accounting predicts.

17. **`CameraInfo.isSessionConfigSupported()` fidelity.** Does it correctly predict `ERROR_STREAM_CONFIG` on these devices, i.e. can it be trusted as the pre-flight gate in §7.1?
    *Test:* build several `SessionConfig`s (including deliberately unsupported ones: UHD, stabilization on the G04, three use cases) and compare the API's answer with the actual bind result.
