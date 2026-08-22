# Burning overlays (speed / GPS / time / weather) into the recorded video — Roadguard research

## Bottom line

Use **`androidx.camera.effects.OverlayEffect` from `androidx.camera:camera-effects:1.6.1` (stable), constructed as `OverlayEffect(CameraEffect.VIDEO_CAPTURE, /* queueDepth= */ 0, glHandler, errorListener)` and attached with `SessionConfig.Builder.addEffect(...)` (or `UseCaseGroup.Builder.addEffect(...)`)**, targeting **only** `VIDEO_CAPTURE`. This is the cheapest reliable burn-in path on the Moto G04 class of hardware because (a) CameraX *already* inserts one full-frame OpenGL pass into the `VideoCapture` path the moment any effect, crop, mirror or device quirk is present, and the overlay costs only **one extra `samplerExternalOES` fetch plus one multiply-add per output pixel inside that same pass** — no additional render pass, no additional buffer copy when `queueDepth == 0`; (b) the expensive part (rasterising text) happens **only when your `onDrawListener` touches `Frame.getOverlayCanvas()`**, so a HUD that changes at ~1 Hz costs one `Surface.lockCanvas` + one full-surface clear + one small blit **per second**, not per frame; and (c) the on-screen HUD should be plain Compose/`View` widgets drawn over `PreviewView` — free, and immune to the "preview may be cropped, recording must not be" constraint, because a `VIDEO_CAPTURE`-only effect never touches the preview stream and never forces stream sharing. The one thing you **must** get right is orientation: the overlay canvas lives in *camera buffer* coordinates and is transformed by exactly the same texture matrix as the camera image, so text drawn naively comes out **sideways** whenever `Frame.getRotationDegrees() != 0`. The fix is a single documented, non-invented rule: build a `Matrix` that undoes `frame.rotationDegrees` (and `frame.isMirroring`) relative to `frame.cropRect`, apply it with `Canvas.setMatrix`, and lay the HUD out in that upright space. Fallback when the primary path errors on a device: rebind with no effect, keep recording, and always write a `.jsonl` telemetry sidecar so the burn-in can be re-created on demand with `androidx.media3:media3-transformer` at export time (never in the background loop — post-processing every 3-minute segment is thermally and storage-wise unaffordable).

## Evidence key

- **[DOCUMENTED]** — stated in official docs, official release notes, or read directly out of the authoritative source tree (AOSP / androidx / androidx-media). A URL is cited.
- **[INFERRED]** — a conclusion reasoned from documented facts; the reasoning chain is stated inline.
- **[UNVERIFIED]** — plausible but not confirmed by a source I read; treat as a hypothesis.
- **[MEASURED]** — *not used anywhere in this document.* No measurement was performed in this session. Anything needing a number is tagged `NOT VERIFIED — needs on-device measurement` with the exact test that would settle it.

---

## 0. Target-device context (for cost reasoning)

| Device | SoC | GPU | RAM | OS | Source |
|---|---|---|---|---|---|
| Moto G04 (baseline) | UNISOC T606, 2×Cortex-A75 @1.6 GHz + 6×Cortex-A55 @1.6 GHz | **Arm Mali-G57 MP1 @ 650 MHz** (single shader core) | 4 GB (8 GB with "RAM extension") | Android 14 | [DOCUMENTED] Motorola official spec sheet, [en-us.support.motorola.com a_id/178144](https://en-us.support.motorola.com/app/answers/detail/a_id/178144/~/moto-g04%C2%A0--sp%C3%A9cifications%C2%A0/) |
| Edge 60 Fusion (upper target) | MediaTek Dimensity 7400, 4×A78 @2.5 GHz + 4×A55 @2.0 GHz | Arm Mali-G615 MC2 | 8 GB LPDDR4X | Android 15 | [DOCUMENTED] Motorola official spec sheet, [en-us.support.motorola.com a_id/184937](https://en-us.support.motorola.com/app/answers/detail/a_id/184937/~/specifications---motorola-edge-60-fusion) |

**Key consequence:** the baseline device has a **single-core Mali GPU**. Every full-frame GPU pass you add is a real cost there, and the same GPU is simultaneously compositing the 50/50 video+map UI. This is why the design goal is *zero extra GPU passes*, not *a cheap extra pass*. [INFERRED from the two documented GPU configurations.]

1080p30 arithmetic used below: 1920 × 1080 × 30 = **62.2 M output pixels/s**. A single-pass camera→encoder blit therefore samples ~62.2 M texels/s; adding the overlay sampler makes it ~124.4 M texel fetches/s. Whether a Mali-G57 MP1 absorbs that while the encoder, ISP and UI compositor run is `NOT VERIFIED — needs on-device measurement` (see §14).

---

## 1. Option matrix (summary)

| # | Approach | Min API | Extra GPU passes vs. "no effect" | Extra full-frame buffers | CPU per second (1 Hz HUD) | Can it stall/kill recording? | Orientation risk | Verdict |
|---|---|---|---|---|---|---|---|---|
| 1 | **CameraX `OverlayEffect`, target `VIDEO_CAPTURE`, `queueDepth=0`** | none declared (GLES 2.0; artifact is stable at 1.6.1) | **+1 pass total** (the pass CameraX inserts for *any* effect); the overlay itself adds **0 passes**, only +1 texture fetch/pixel | 1 overlay `SurfaceTexture` at input size (+ its BufferQueue) | 1 × `lockCanvas` + 1 × full-surface clear + 1 small blit | Yes, but bounded and recoverable: listener returning `false` **drops that frame**; a thrown error goes to your `errorListener`; GL thread blocks ≤30 ms per canvas update | Real but fully solvable with `frame.rotationDegrees` | **PRIMARY** |
| 2 | Custom `CameraEffect` + own `SurfaceProcessor` (own GLES) | none declared | +1 pass (same as #1) but you own all of it | whatever you allocate | whatever you write | **Yes, fatally**: "Once the implementation throws an exception, CameraX will treat it as an unrecoverable error and abort the pipeline" | You own crop/rotate/mirror maths entirely | Only if #1 is unavailable; high risk |
| 3 | Camera2 + `MediaCodec.createInputSurface()` + own EGL + `MediaMuxer` | 21 for the APIs; you reimplement everything | +1 pass | your own | your own | **Yes** — you now own segment rollover, timestamping, muxer errors, storage-full, orientation, quirks | You own everything | **No.** Reimplements CameraX's device quirk database for zero overlay benefit |
| 4 | Post-process each segment with `androidx.media3:media3-transformer` `OverlayEffect`/`TextOverlay` | media3 `minSdk 23` | full extra decode + GL + **re-encode** per segment | n/a | 100% duty-cycle transcode alongside 100% duty-cycle recording | Indirectly, badly: competes for the same hardware encoder/decoder, doubles storage churn | Handled by media3 (NDC anchors), but re-encode loses quality | **No** for the loop. **Yes** as a user-initiated export/share action |
| 4b | Live `androidx.camera.media3:media3-effect` `Media3Effect` + media3 `OverlayEffect` | alpha artifact (`1.0.0-alpha04`, Aug 2025) | +1 pass minimum, plus media3's own `DefaultVideoFrameProcessor` chain | media3-internal | per-frame `getBitmap()`/`getTextureId()` calls (cached on change) | Alpha + `@UnstableApi`; unmaintained for ~1 year | media3 handles it | **No** — alpha stack in the #1-reliability path |
| 5 | `MediaMuxer` metadata track / subtitle track | metadata track: **MP4 only, API 26+** | 0 | 0 | trivial | No | n/a | **Not a burn-in.** Not playable in normal players. Useful only as the *sidecar* concept — and a plain `.jsonl` file next to the MP4 is simpler |

---

## 2. Option 1 — CameraX `OverlayEffect` (recommended). Exact API

### 2.1 Artifact and versions

[DOCUMENTED] CameraX release page, [developer.android.com/jetpack/androidx/releases/camera](https://developer.android.com/jetpack/androidx/releases/camera):

| Artifact | Stable | Latest alpha |
|---|---|---|
| `androidx.camera:camera-core` | **1.6.1** (2026-05-06) | 1.7.0-alpha03 (2026-08-12) |
| `androidx.camera:camera-camera2` | 1.6.1 | 1.7.0-alpha03 |
| `androidx.camera:camera-video` | 1.6.1 | 1.7.0-alpha03 |
| `androidx.camera:camera-view` | 1.6.1 | 1.7.0-alpha03 |
| `androidx.camera:camera-lifecycle` | 1.6.1 | 1.7.0-alpha03 |
| **`androidx.camera:camera-effects`** | **1.6.1** | 1.7.0-alpha03 |

Use **1.6.1** across all `androidx.camera:*` artifacts (they are versioned in lockstep). Rationale for not going older: [DOCUMENTED] 1.5.1 release notes fix *"the crash when effect is being activated after `SurfaceProcessor` is shut down"* (`I2c450`, `b/414150174`) and 1.5.0-beta02 fixes *"the memory leak that happens when `PreviewView` is used, `CameraEffect` is enabled or binding 4 use cases(StreamSharing)"* (`I87468`) — both are exactly our configuration.

History, [DOCUMENTED] same page:
- **1.3.0-alpha05 (2023-03-22)**: *"Allow VIDEO_CAPTURE and PREVIEW|VIDEO_CAPTURE as effects target. Effects that targets VIDEO_CAPTURE will be applied to the VideoCapture UseCase; Effects that targets PREVIEW|VIDEO_CAPTURE will be applied to a shared stream before copying to Preview and VideoCapture stream."* (`Iee6f3`) ← **this is the release note that authorises our design.**
- **1.4.0-alpha04 (2024-01-24)**: *"New artifact: camera-effects: A library for applying real time effects to CameraX output, including Preview, VideoCapture and/or ImageCapture. This artifact contains OpenGL implementations of the CameraEffect API that manipulates the camera output efficiently. OverlayEffect: for drawing overlays with Android's Canvas API."*
- **1.4.0-alpha (same cycle)**: *"Add an error listener to CameraEffect to handle unrecoverable errors"* (`Ice471`).
- **1.7.0-alpha03 (2026-08-12)**: *"Fixed an issue where binding multiple `Preview` use cases with an `OverlayEffect` caused one Preview stream delivery failure."* (`I7a383`, `b/532577546`) — does not affect us (single `Preview`, and the effect does not target `PREVIEW`).

### 2.2 Complete public API surface

[DOCUMENTED] verbatim from `camera/camera-effects/api/current.txt` ([raw source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-effects/api/current.txt)):

```
package androidx.camera.effects {

  @com.google.auto.value.AutoValue public abstract class Frame {
    ctor public Frame();
    method public abstract android.graphics.Rect getCropRect();
    method public android.graphics.Canvas getOverlayCanvas();
    method @IntRange(from=0, to=359) public abstract int getRotationDegrees();
    method public abstract android.graphics.Matrix getSensorToBufferTransform();
    method public abstract android.util.Size getSize();
    method public abstract long getTimestampNanos();
    method public abstract boolean isMirroring();
  }

  public class OverlayEffect extends androidx.camera.core.CameraEffect implements java.lang.AutoCloseable {
    ctor public OverlayEffect(int, int, android.os.Handler, androidx.core.util.Consumer<java.lang.Throwable!>);
    method public void clearOnDrawListener();
    method public void close();
    method public com.google.common.util.concurrent.ListenableFuture<java.lang.Integer!> drawFrameAsync(long);
    method public android.os.Handler getHandler();
    method public int getQueueDepth();
    method public void setOnDrawListener(androidx.arch.core.util.Function<androidx.camera.effects.Frame!,java.lang.Boolean!>);
    field public static final int RESULT_CANCELLED_BY_CALLER = 4; // 0x4
    field public static final int RESULT_FRAME_NOT_FOUND = 2;     // 0x2
    field public static final int RESULT_INVALID_SURFACE = 3;     // 0x3
    field public static final int RESULT_SUCCESS = 1;             // 0x1
  }
}
```

Notes on the surface: **there is no `@RequiresApi` and no experimental/opt-in annotation anywhere in `androidx.camera.effects`** — this is plain stable API. [DOCUMENTED] `camera/camera-effects/build.gradle` declares only `compileSdk 36` and `namespace = "androidx.camera.effects"`; there is no module-level `minSdk` override, so it inherits CameraX's (21). Our `minSdk = 34` is comfortably above. [DOCUMENTED] `camera/camera-effects/build.gradle`, [raw source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-effects/build.gradle).

Constructor semantics, [DOCUMENTED] `OverlayEffect.java` javadoc ([raw source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-effects/src/main/java/androidx/camera/effects/OverlayEffect.java)):

| Param | Meaning (verbatim, condensed) |
|---|---|
| `targets` | *"The targets the effect applies to. For example, `CameraEffect#PREVIEW` \| `CameraEffect#VIDEO_CAPTURE`. See `UseCaseGroup.Builder#addEffect` for supported targets combinations."* |
| `queueDepth` | *"how many frames can be queued before the oldest frame being automatically released. `OverlayEffect` allocates an array of OpenGL 2D textures that matches this size … **If the queue depth is 0, the input frames are rendered immediately without queuing.**"* |
| `handler` | *"The Handler for listening for the input Surface updates and for performing OpenGL operations."* |
| `errorListener` | *"invoked if the effect runs into unrecoverable errors … For example, `ProcessingException`. This is invoked on the provided Handler."* |

`setOnDrawListener(Function<Frame, Boolean>)`, [DOCUMENTED] verbatim: *"Once the drawing is done, the listener should return **true** for the OverlayEffect to draw it to the output Surface. **If it returns false, the frame will be dropped.**"* and *"OverlayEffect invokes the listener on the `getHandler()` thread."*

`drawFrameAsync(long timestampNs)` exists **only** for deferred rendering with `queueDepth > 0` (the QR-code use case: hold the frame until `ImageAnalysis` produces a result, then release it by timestamp). **Roadguard must never call it.** With `queueDepth = 0` there is nothing to dequeue.

### 2.3 Target bitmask — exact values and legal combinations

[DOCUMENTED] `androidx.camera.core.CameraEffect` ([raw source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/CameraEffect.java)):

```java
public static final int PREVIEW       = 1;      // 0x1
public static final int VIDEO_CAPTURE = 1 << 1; // 0x2
public static final int IMAGE_CAPTURE = 1 << 2; // 0x4
```

Two allowlists exist and they differ — this matters:

```java
// CameraEffect.java — accepted by the SurfaceProcessor constructor
private static final List<Integer> SURFACE_PROCESSOR_TARGETS = Arrays.asList(
        PREVIEW,
        VIDEO_CAPTURE,
        PREVIEW | VIDEO_CAPTURE,
        PREVIEW | VIDEO_CAPTURE | IMAGE_CAPTURE);
```

```java
// UseCaseGroup.Builder — accepted at build()/bind time
private static final List<Integer> SUPPORTED_TARGETS = Arrays.asList(
        PREVIEW,
        VIDEO_CAPTURE,
        IMAGE_CAPTURE,
        PREVIEW | VIDEO_CAPTURE,
        PREVIEW | VIDEO_CAPTURE | IMAGE_CAPTURE);
```

[DOCUMENTED] `UseCaseGroup.Builder#addEffect` javadoc also states: *"The targets must be mutually exclusive of each other, otherwise, the `build()` method will throw `IllegalArgumentException`."* ([raw source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/UseCaseGroup.java))

**Therefore, for Roadguard:**
- `targets = CameraEffect.VIDEO_CAPTURE` (value `2`) is legal and applies the overlay **only to the recorded stream**. [DOCUMENTED] by both the allowlist above and the 1.3.0-alpha05 release note.
- Do **not** use `PREVIEW | VIDEO_CAPTURE`: that provably routes both use cases through one shared stream ("*applied to a shared stream before copying to Preview and VideoCapture stream*" — [DOCUMENTED] release note). Preview and video would then share one resolution/crop, which directly conflicts with the product rule *"preview may be cropped/zoomed to fit the UI; the RECORDED video must NOT be cropped for UI reasons."*
- If you later want an overlay on the preview *as well* and it must be pixel-identical, add a **second, separate** `OverlayEffect` with `targets = PREVIEW`. That is legal (the two targets are mutually exclusive) but it costs a second GL pass on the preview path. **Do not do this** — draw the on-screen HUD as ordinary Compose/`View` content over `PreviewView`. It is free, sharper (device DPI, not video resolution), and unaffected by preview cropping.

### 2.4 Attaching the effect

Two supported paths. Prefer the first on 1.6.1.

```kotlin
// CameraX 1.5+ SessionConfig path (androidx.camera.core.SessionConfig)
val sessionConfig = SessionConfig.Builder(preview, videoCapture)
    .addEffect(overlayEffect)          // public fun addEffect(effect: CameraEffect): Builder
    .build()
cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, sessionConfig)
```

[DOCUMENTED] `androidx.camera.core.SessionConfig` exposes `public val effects: List<CameraEffect>` and `Builder.addEffect(effect: CameraEffect)` ([raw source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/SessionConfig.kt)). [DOCUMENTED] CameraX 1.5 release note: *"Binding a SessionConfig to a LifecycleOwner opens the camera session, configures it using the specified use cases and session parameters, and applies the designated CameraEffect and ViewPort. When updating a new SessionConfig to the same LifecycleOwner, you can just bind a new Sessionconfig without the need of invoking unbind or unbindAll first."*

Legacy path (still supported, `LegacySessionConfig` wraps it):

```kotlin
val group = UseCaseGroup.Builder()
    .addUseCase(preview)
    .addUseCase(videoCapture)
    .addEffect(overlayEffect)
    .build()
cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
```

`SessionConfig` also carries `isAutoRotationEnabled` — [DOCUMENTED] *"When enabled, CameraX will monitor the device motion sensor and set the target rotation for `ImageCapture`, `androidx.camera.video.VideoCapture` and `ImageAnalysis`."* That is the "behave like a normal camera app" switch; see §5 for how it interacts with the overlay.

### 2.5 What actually happens inside — the mechanics that determine cost

All of the following is [DOCUMENTED] by direct reading of `androidx.camera.effects.internal.SurfaceProcessorImpl`, `androidx.camera.effects.opengl.GlRenderer`, `GlProgramOverlay`, `GlProgramCopy`, `GlContext` and `androidx.camera.effects.internal.Utils` on `androidx-main`.

**a) One GL pass, one extra texture fetch.** The fragment shader is generated by `GlProgramOverlay.createFragmentShader()`:

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;              // = (uTexMatrix * aTextureCoord).xy
uniform samplerExternalOES /*or sampler2D*/ samplerInputTexture;
uniform samplerExternalOES samplerOverlayTexture;
void main() {
    vec4 inputColor   = texture2D(samplerInputTexture,   vTextureCoord);
    vec4 overlayColor = texture2D(samplerOverlayTexture, vTextureCoord);
    gl_FragColor = inputColor * (1.0 - overlayColor.a) + overlayColor;
}
```

Facts that fall out of this shader:
- The overlay is composited **inside the same pass** that copies camera → output surface. There is **no extra render pass** for the overlay. [DOCUMENTED]
- The blend is `src + dst*(1-src.a)`, i.e. **source-over with premultiplied alpha**. Android's `Canvas`/`Surface.lockCanvas` produces premultiplied ARGB, so ordinary `Paint` alpha, antialiased text and `PorterDuff.Mode.CLEAR` all behave as expected. [INFERRED from the shader form + Android's premultiplied canvas convention.]
- **Both samplers use the *same* `uTexMatrix`-transformed coordinates.** This is the single most important fact in this document: the overlay is treated *exactly* as if it had been painted onto the camera buffer, so every downstream crop/rotate/mirror applies to the overlay too. See §5.
- The input sampler is `samplerExternalOES` when `queueDepth == 0` and `sampler2D` when `queueDepth > 0` (`GlProgramOverlay(int queueDepth)` picks the sampler type). [DOCUMENTED]

**b) `queueDepth` is a hard cost switch.** `GlRenderer.createBufferTextureIds(Size)`:

```java
mQueueTextureIds = new int[mQueueDepth];
if (mQueueDepth == 0) { return mQueueTextureIds; }   // no textures at all
...
GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
        size.getWidth(), size.getHeight(), 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null);
```

With `queueDepth > 0`, `SurfaceProcessorImpl.onFrameAvailable()` calls `mGlRenderer.renderInputToQueueTexture(...)`, which is a **second full-frame GL pass per frame** (`GlProgramCopy`, external-OES → 2D FBO), plus `queueDepth` textures of `width × height × GL_RGB`. At 1920×1080 that is ~6.2 MB each (driver may pad to 4 bytes/texel → ~8.3 MB). The official CameraX 1.4.0 blog sample uses `queueDepth = 5` — that would be ~31–41 MB of GPU memory and double the fill rate, for a feature (deferred rendering keyed on `ImageAnalysis` results) Roadguard does not use.

> **Rule: `queueDepth = 0`, always.** The library's own javadoc says so for this exact case: *"If the app doesn't render real-time analysis results, set the queue depth to 0 to avoid unnecessary buffer copies. For example, when laying over a static watermark."* [DOCUMENTED]

**c) The overlay is a `SurfaceTexture` you paint with a *software* `Canvas`.** `SurfaceProcessorImpl` creates one overlay `SurfaceTexture` sized to the input resolution:

```java
mOverlayTexture = new SurfaceTexture(mGlRenderer.getOverlayTextureId());
mOverlaySurface = new Surface(mOverlayTexture);
...
mOverlayTexture.setDefaultBufferSize(mInputSize.getWidth(), mInputSize.getHeight());
```

and `Frame.getOverlayCanvas()` goes through `androidx.camera.effects.internal.Utils.lockCanvas()`:

```java
public static @NonNull Canvas lockCanvas(@NonNull Surface surface) {
    // TODO(b/186120366): Investigate how widespread the lockHardwareCanvas is and re-enable
    //  it when possible.
    return surface.lockCanvas(null);
}
```

[DOCUMENTED] So: **software rasterisation into a CPU-mapped graphics buffer**, `lockHardwareCanvas()` is deliberately not used (bug `b/186120366`), and the dirty rect is `null` — which per `Surface.lockCanvas` javadoc means *"the entire surface should be redrawn"* ([AOSP `Surface.java`](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/Surface.java)). At 1080p that is an 1920×1080×4 B ≈ **8.3 MB** buffer you are expected to fully repaint on every update. At 1 Hz: ~8 MB/s of CPU writes. At 30 Hz: ~250 MB/s — unaffordable on a T606 while encoding. **This is the arithmetic that forces the 1 Hz design.** [INFERRED from the documented buffer size + documented full-redraw contract.]

**d) Touching the canvas blocks the GL thread; not touching it is free.** `SurfaceProcessorImpl.drawOverlay()`:

```java
Frame frame = Frame.of(mOverlaySurface, timestampNs, mInputSize, mTransformationInfo);
boolean shouldRender = mOnDrawListener.apply(frame);
if (frame.isOverlayDirty()) {              // true only if getOverlayCanvas() was called
    blockAndPostOverlay(frame.getOverlayCanvas());
}
return shouldRender;
```

and

```java
// The semaphore usually releases within 2ms. We wait for 30ms since it's the FPS.
// At maximum, we wait until the next frame is ready.
private static final long OVERLAY_UPDATE_TIMEOUT_MILLIS = 30L;
...
private void blockAndPostOverlay(@NonNull Canvas canvas) {
    Semaphore semaphore = new Semaphore(0);
    mOverlayTexture.setOnFrameAvailableListener(st -> semaphore.release(), mOverlayHandler);
    mOverlaySurface.unlockCanvasAndPost(canvas);
    boolean ok = semaphore.tryAcquire(OVERLAY_UPDATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    if (!ok) { Logger.e(TAG, "Timed out waiting canvas post"); }
    mOverlayTexture.updateTexImage();
}
```

[DOCUMENTED]. Consequences, all load-bearing:
1. If your listener **does not** call `getOverlayCanvas()`, the frame costs literally nothing extra beyond the shader's second texture fetch — the previously uploaded overlay texture is still bound and is re-blended. **The HUD persists between updates for free.**
2. If your listener **does** call `getOverlayCanvas()`, the GL thread synchronously waits (typically ~2 ms per the library's own comment, capped at 30 ms) for the overlay texture to become available. That wait sits on the thread that feeds the encoder's input surface.
3. Therefore: **call `getOverlayCanvas()` only when the HUD content actually changed.** At 1 Hz on 30 fps video that is 1 blocking wait per ~30 frames.
4. The 30 ms cap means a pathological stall costs at most ~one dropped frame per update, and the library logs `"Timed out waiting canvas post"` rather than failing. Grep logcat for that string during bring-up.

**e) Frame timestamps are preserved into the encoder.** `GlContext.drawAndSwap()` calls `EGLExt.eglPresentationTimeANDROID(mEglDisplay, eglSurface, timestampNs)` before `EGL14.eglSwapBuffers`, and the EGL config requests `EGLExt.EGL_RECORDABLE_ANDROID = EGL_TRUE`. [DOCUMENTED] ([`GlContext.java`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-effects/src/main/java/androidx/camera/effects/opengl/GlContext.java)) So inserting the effect does not corrupt PTS / frame pacing in the MP4 — a genuine correctness win over hand-rolled pipelines where this is the classic bug.

**f) The camera-facing surface is still tagged as an encoder surface.** In `VideoCapture.createPipeline()` the deferrable surface handed to camera2 is the effect's input surface when a node exists, and CameraX still executes:

```java
// Since VideoCapture is in video module and can't be recognized by core module, use
// MediaCodec class instead.
mDeferrableSurface.setContainerClass(MediaCodec.class);
```

[DOCUMENTED] ([`VideoCapture.java`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-video/src/main/java/androidx/camera/video/VideoCapture.java)) So CameraX still identifies that stream as the recording stream for stream-use-case / quirk purposes. Whether the camera HAL on the T606 nevertheless treats a `SurfaceTexture` differently from a `MediaCodec` surface (power, stabilisation, noise reduction tuning) is `NOT VERIFIED — needs on-device measurement`. CameraX itself warns about this in `VideoCapture.Builder.setSurfaceProcessingForceEnabled()`: *"Surface processing creates additional processing through the OpenGL pipeline, affecting performance and memory usage. **Camera service may treat the surface differently, potentially impacting video quality and stabilization.** So it is generally not recommended to enable it."* [DOCUMENTED]

### 2.6 You may already be paying for the GL pass anyway

[DOCUMENTED] `VideoCapture.isCreateNodeNeeded(...)`:

```java
if (sessionType == SESSION_TYPE_HIGH_SPEED) {
    return false;                        // effects are silently ignored in high-speed sessions
}
return getEffect() != null
        || shouldRotateBuffer
        || shouldEnableSurfaceProcessingByConfig(camera, config)
        || shouldEnableSurfaceProcessingByQuirk(camera)
        || shouldEnableSurfaceProcessingBasedOnDynamicRangeByQuirk(camera, dynamicRange)
        || shouldCrop(cropRect, resolution)
        || shouldMirror(camera)
        || shouldCompensateTransformation(camera);
```

**[INFERRED]** from this: if Roadguard sets a `ViewPort` (which crops), uses the front camera (mirror), or runs on a device matching one of CameraX's surface-processing quirks, the GL node is created **whether or not you add an overlay**. In those configurations the *entire marginal cost* of burning in the HUD is one extra `samplerExternalOES` fetch per output pixel plus the 1 Hz canvas work. That is as cheap as this problem gets on Android.

Two direct design orders follow:
- **Do not set a `ViewPort`** on the `UseCaseGroup`/`SessionConfig` — it would crop the recorded stream, violating the product rule. Crop the *preview* for the UI with `PreviewView.ScaleType.FILL_CENTER`, which is a display-only transform.
- **Effects are silently dropped in `SESSION_TYPE_HIGH_SPEED` sessions.** If Roadguard ever adds a slow-motion mode via `HighSpeedVideoSessionConfig`, the overlay will not be burned in and there will be **no error** — you must disable the HUD-burn-in claim in that mode explicitly. [DOCUMENTED by the code above.]

### 2.7 Memory budget for the effect

| Allocation | Size at 1920×1080 | Notes |
|---|---|---|
| Input `SurfaceTexture` BufferQueue (camera → effect) | implementation-defined (`PRIVATE`) format, typically 3–4 buffers | Existed in some form before; now consumer is GL instead of MediaCodec |
| Overlay `SurfaceTexture` BufferQueue (RGBA_8888) | ~8.3 MB per buffer; expect 2–3 buffers ⇒ **~17–25 MB** | `NOT VERIFIED — needs on-device measurement` (dump with `adb shell dumpsys gfxinfo <pkg>` / `dumpsys SurfaceFlinger --list` + `meminfo`) |
| Queue textures | **0 with `queueDepth = 0`** | ~6.2–8.3 MB × N otherwise |
| Your HUD bitmap | e.g. 1024×96 ARGB_8888 ≈ 393 KB × 2 (double-buffered) | Tiny |

On a 4 GB device this is acceptable but not free; it is one more reason to keep `queueDepth = 0`.

### 2.8 Failure modes and exact recovery

| Failure | Symptom | Detection | Recovery |
|---|---|---|---|
| Listener returns `false` | **That frame is dropped from the recording** (documented) | You control it | **Never return `false`.** Return `true` unconditionally, even on internal error |
| Listener throws | Propagates on the GL Handler thread → likely crash | Wrap the entire listener body in `try/catch(Throwable)` and `return true` | Log and drop the HUD update, keep recording |
| Effect hits an unrecoverable GL error | `errorListener` invoked with the `Throwable` (e.g. `ProcessingException`) | `Consumer<Throwable>` passed to the constructor | Post to main thread, rebind the `SessionConfig` **without** the effect, persist "overlay disabled on this device" (see §11) |
| Overlay texture post times out | logcat `SurfaceProcessorImpl: Timed out waiting canvas post`; up to ~1 frame lost | logcat / a counter you increment yourself is not possible (internal) — watch frame count vs. duration in `RecordingStats` | Reduce HUD update rate; shrink the cleared area |
| Output surface replaced/unbound mid-flight | `RESULT_INVALID_SURFACE` (only surfaces through `drawFrameAsync`, which we don't call) | n/a with `queueDepth = 0` | n/a |
| Drawing outside the frame bounds | On some devices: repeating/ghosted overlay, white flashing, alpha stacking | Visual | Clip every draw to `frame.cropRect`. [DOCUMENTED] CameraX maintainer Xi Zhang on the `camerax-developers` group: drawing rectangles outside frame boundaries triggers `SurfaceTexture` wrap behaviour and was the likely cause of repetition/flashing on a Pixel 2 — [thread `k3eVmhXejpk`](https://groups.google.com/a/android.com/g/camerax-developers/c/k3eVmhXejpk) |
| Overlay misaligned across devices | Blue box cut off on tablet / Galaxy S20+ in the same thread; root cause was drawing in the wrong coordinate space | Visual, per device | Use `frame.cropRect` + `frame.rotationDegrees` (§5); never assume the canvas is in UI space |
| `IllegalArgumentException` at bind | Illegal target bitmask, or two effects claiming the same target | Thrown from `build()`/`bindToLifecycle` | Fix the bitmask; it is a programming error, not a device issue |
| High-speed session | Overlay silently absent | You know the session type | Disable the feature claim in that mode |

---

## 3. Option 2 — Custom `CameraEffect` + your own `SurfaceProcessor`

`CameraEffect` has a public `protected` constructor for this:

```java
protected CameraEffect(
        @Targets int targets,
        @NonNull Executor executor,
        @NonNull SurfaceProcessor surfaceProcessor,
        @NonNull Consumer<Throwable> errorListener)
```

You implement `SurfaceProcessor.onInputSurface(SurfaceRequest)` and `onOutputSurface(SurfaceOutput)`, create your own EGL context + `SurfaceTexture`, and must call `SurfaceOutput.updateTransformMatrix(float[] updated, float[] original)` on every frame to obtain the crop/rotate/mirror matrix CameraX computed for you. [DOCUMENTED] ([`SurfaceProcessor.java`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/SurfaceProcessor.java), [`SurfaceOutput.java`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/SurfaceOutput.java))

Why this is the wrong choice for a reliability-first dashcam, in the framework's own words [DOCUMENTED]:

> *"Once the implementation throws an exception, CameraX will treat it as an unrecoverable error and abort the pipeline."* — `SurfaceProcessor` javadoc

> *"Since camera session closure is a complicated asynchronous process, some devices may pass a few frames for drawing even after the `resultListener` … reports that surface is safe for releasing. Users should ignore any new frames that come after CameraX reports surface can be released."* — `SurfaceProcessor` javadoc

That second paragraph is a device-specific lifetime hazard that `camera-effects` already handles for you (`SurfaceProcessorImpl` tracks `mIsReleased`, unregisters output surfaces, and quits its overlay `HandlerThread` safely). Re-implementing it buys **nothing** for a text overlay: `OverlayEffect` already gives you a `Canvas`, and the GPU work is identical (one pass, one extra sampler). The only legitimate reasons to write your own processor would be a shader effect `OverlayEffect` cannot express (LUTs, blur, dewarping) — none of which Roadguard needs.

**Verdict: use `OverlayEffect`. Keep a custom `SurfaceProcessor` out of the codebase entirely** so it can never regress recording reliability.

---

## 4. Option 3 — Camera2 + `MediaCodec` + your own EGL pipeline

Shape of it: `CameraDevice.createCaptureSession` with a `SurfaceTexture` target → your EGL context samples the external texture, draws a fullscreen quad, blends a texture-mapped overlay quad → renders into `MediaCodec.createInputSurface()` → `MediaMuxer`.

Hard documented constraints:

- [DOCUMENTED] `MediaCodec.createInputSurface()` javadoc: *"The Surface must be rendered with a hardware-accelerated API, such as OpenGL ES. `android.view.Surface#lockCanvas(android.graphics.Rect)` may fail or produce unexpected results."* ([AOSP `MediaCodec.java`](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/media/java/android/media/MediaCodec.java)) → **there is no `Canvas`-only shortcut into the encoder.** You must write GLES either way, so this option's GPU cost is *identical* to Option 1, not lower.
- You must call `EGLExt.eglPresentationTimeANDROID()` per frame yourself (Option 1 does it for you — §2.5e) and set `EGLExt.EGL_RECORDABLE_ANDROID` in your EGL config, or the muxer output gets wrong/absent timestamps.
- You inherit, from scratch: segment rollover without frame loss, `MediaMuxer.setOrientationHint`, encoder width/height alignment (CameraX resolves this via its internal `VideoEncoderInfo`), storage-full handling, `MediaCodec.CodecException` recovery, audio/video timebase reconciliation (CameraX's `resolveTimebase()` explicitly deals with `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` vs `UPTIME` — [DOCUMENTED] `VideoCapture.java`), and the ~dozens of device quirks CameraX ships (`SizeCannotEncodeVideoQuirk`, `ExcludedSupportedSizesQuirk`, …).

**Cost/benefit: identical GPU cost, strictly worse reliability, months of work.** Rejected outright given "recording reliability is the #1 priority".

---

## 5. Orientation — the part that will bite you (and the exact fix)

### 5.1 What CameraX actually does with rotation when an effect is present

This is the subtlest and most important finding in this document, established by reading the pipeline:

1. `VideoCapture.createPipeline()` builds `mCameraEdge` with `mRotationDegrees` and then, **when a node exists**, wires it as `OutConfig outConfig = OutConfig.of(mCameraEdge);` [DOCUMENTED] `VideoCapture.java`.
2. `OutConfig.of(SurfaceEdge inputEdge)` sets the output's `rotationDegrees` to `inputEdge.getRotationDegrees()` and the output **size** to `getRotatedSize(inputEdge.getCropRect(), inputEdge.getRotationDegrees())` [DOCUMENTED] ([`OutConfig.java`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/processing/util/OutConfig.java)).
3. `SurfaceProcessorNode` then propagates `output rotation = input.getRotationDegrees() - outConfig.getRotationDegrees()` [DOCUMENTED] ([`SurfaceProcessorNode.java`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/processing/SurfaceProcessorNode.java)) — i.e. **0** in this configuration.
4. `Recorder` writes the *output* edge's rotation into the container: `muxer.setOrientationDegrees(transformationInfo.getRotationDegrees());` [DOCUMENTED] ([`Recorder.java`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/camera-video/src/main/java/androidx/camera/video/Recorder.java)).

**[INFERRED], with the chain above:** with an `OverlayEffect` on `VIDEO_CAPTURE`, the GL node **rotates the pixels**, the encoder surface has the **already-upright** dimensions (e.g. **1080×1920** for a portrait recording, not 1920×1080), and the MP4 **orientation metadata is 0**. Without any effect/crop/mirror/quirk, the opposite happens: pixels stay in sensor orientation and the rotation lives in MP4 metadata.

This is consistent with the documented contract in `VideoCapture.setTargetRotation(int)` [DOCUMENTED]:

> *"For a `Recorder` output, calling this method has no effect on the ongoing recording, but will affect recordings started after calling this method. The final rotation degrees of the video, including the degrees set by this method and the orientation of the camera sensor, will be reflected by several possibilities, 1) the rotation degrees is written into the video metadata, 2) the video content is directly rotated, 3) both, i.e. rotation metadata and rotated video content which combines to the target rotation. CameraX will choose a strategy according to the use case."*

**Practical consequences for Roadguard — these are product-visible:**
- Enabling the overlay **changes the pixel geometry of the recorded file** (portrait recordings become truly 1080×1920 instead of 1920×1080 + rotation flag). This is arguably *better* (no players mis-handle the rotation flag), but it must be consistent: **do not toggle the effect on and off between segments**, or your loop will contain files of two different geometries.
- The encoder must accept the rotated dimensions. CameraX resolves alignment via `VideoEncoderInfo` and `adjustCropRectByQuirk`, but whether the T606's H.264 encoder is happy at 1080×1920@30 is `NOT VERIFIED — needs on-device measurement` (test: record 3 min portrait with the effect, then `ffprobe`/`MediaExtractor` the result and check `width/height`, `rotation`, frame count vs. duration).

### 5.2 The rule for drawing the overlay

The documented instruction is explicit — [DOCUMENTED] `Frame.getRotationDegrees()` javadoc:

> *"This is a clockwise rotation in degrees that needs to be applied to the frame. The rotation will be determined by camera sensor orientation and UseCase configuration such as `Preview#setTargetRotation`. **The app must draw the overlay according to the rotation degrees to ensure it is displayed correctly to the end users. For example, to overlay a text, make sure the text's orientation is aligned with the rotation degrees.** … The rotation is applied after the cropping but before the mirroring. The order of the operations is as follows: 1) cropping, 2) rotating and 3) mirroring."*

And `Frame.getCropRect()` [DOCUMENTED]:

> *"The crop rect specifies the region of valid pixels in the frame, using coordinates from (0, 0) to the (width, height) of `getSize()`. **Only the overlay drawn within the bound of the crop rect will be visible to the end users.** The crop rect is applied before the rotating and mirroring."*

Combined with §2.5a (the shader applies the *same* texture matrix to the overlay sampler as to the camera sampler), the total transform applied to your overlay pixels is exactly `crop → rotate(rotationDegrees) → mirror(isMirroring)` — whether CameraX realises the rotation in pixels or in metadata (§5.1 step 3/4 shows the two always sum to `rotationDegrees`). **So: counter-transform by `frame.rotationDegrees` / `frame.isMirroring`, relative to `frame.cropRect`, and nothing else.** No sensor-orientation lookups, no `Display.getRotation()`, no `CameraCharacteristics.SENSOR_ORIENTATION` — none of that appears in the correct solution. This satisfies the product rule "NO invented 'dashcam sensor angle' maths".

### 5.3 Exact code

```kotlin
private val overlayMatrix = Matrix()
private val boundsScratch = RectF()

/**
 * Configures [canvas] so that (0,0)..(w,h) of the returned Size is the *upright, as-the-viewer-
 * will-see-it* frame area, and returns that size. Draw the HUD in those coordinates.
 *
 * Undoes exactly the transform the pipeline will apply downstream:
 *   buffer --crop--> --rotate(cw, rotationDegrees)--> --mirror(if isMirroring)--> displayed
 */
private fun beginUpright(canvas: Canvas, frame: Frame): Size {
    val crop = frame.cropRect                       // in buffer coords, size == frame.size
    val rot  = frame.rotationDegrees                // 0/90/180/270, clockwise
    val swap = rot % 180 != 0
    val dw = if (swap) crop.height() else crop.width()
    val dh = if (swap) crop.width()  else crop.height()

    overlayMatrix.reset()
    // mirror is applied LAST downstream, so undo it FIRST here (in displayed space)
    if (frame.isMirroring) overlayMatrix.postScale(-1f, 1f, dw / 2f, dh / 2f)
    // then undo the clockwise rotation
    overlayMatrix.postRotate(-rot.toFloat())
    // finally translate the rotated box back onto the crop rect's origin in buffer space
    boundsScratch.set(0f, 0f, dw.toFloat(), dh.toFloat())
    overlayMatrix.mapRect(boundsScratch)
    overlayMatrix.postTranslate(crop.left - boundsScratch.left, crop.top - boundsScratch.top)

    canvas.setMatrix(overlayMatrix)
    return Size(dw, dh)
}
```

Then the listener:

```kotlin
overlayEffect.setOnDrawListener { frame ->
    try {
        val hud = pendingHud.getAndSet(null)   // AtomicReference<HudBitmap?>, published at ~1 Hz
            ?: return@setOnDrawListener true   // nothing new -> DO NOT touch the canvas (free)

        val canvas = frame.overlayCanvas       // triggers lockCanvas + a blocking post on unlock
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)  // ignores the matrix; clears clip
        val displayed = beginUpright(canvas, frame)
        // lay out in `displayed` coords; e.g. bottom-left strip with a 16px margin
        canvas.drawBitmap(
            hud.bitmap,
            16f,
            displayed.height - hud.bitmap.height - 16f,
            null                                // null Paint == no filtering, cheapest blit
        )
    } catch (t: Throwable) {
        Log.e(TAG, "overlay draw failed", t)   // never let this escape
    }
    true                                       // NEVER return false: false drops the frame
}
```

Correctness notes:
- `Canvas.drawColor` fills the current **clip**, independent of the matrix, so clearing before `setMatrix` is safe and is what `SurfaceProcessorImpl.createBufferAndOverlay()` itself does (`canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)`). [DOCUMENTED]
- `Frame.getSensorToBufferTransform()` is for mapping *sensor/`ImageAnalysis`* coordinates (bounding boxes) into the frame. **Do not use it for a HUD** — it deliberately does not include the display rotation. Using it is exactly the mistake that produced the "blue box cut off" reports in the `camerax-developers` thread.
- Mirroring: for the rear camera `frame.isMirroring` will be `false`, so that branch is `[UNVERIFIED]` in practice. Assert it (`check(!frame.isMirroring)`) during bring-up if Roadguard is rear-camera only, so a future front-camera mode fails loudly instead of silently mirroring text.

### 5.4 Rotation changes mid-recording

- [DOCUMENTED] `VideoCapture.setTargetRotation`: *"calling this method has no effect on the ongoing recording"*. The container's orientation and the node's baked-in rotation are fixed when the pipeline/recording starts.
- But the *camera edge's* transformation info is pushed to the effect live (`surfaceRequest.setTransformationInfoListener(...)` in `SurfaceProcessorImpl.onInputSurface`), and `VideoCapture.getCompensatedRotation()` deliberately compensates while a recording is in progress. [DOCUMENTED] `VideoCapture.java`.
- **Design rule:** treat orientation as a *segment-level* property, matching how a normal camera app behaves. Latch the target rotation at segment start; queue any `OrientationEventListener` change and apply it at the next 3-minute segment boundary via `UseCase.snapToSurfaceRotation(int)` / `videoCapture.targetRotation`. Do **not** change target rotation mid-segment. Whether a mid-segment change actually desynchronises HUD orientation from video orientation on the T606 is `NOT VERIFIED — needs on-device measurement` (test: start recording portrait, rotate to landscape at t=30 s, inspect the file).

---

## 6. Option 4 — Post-processing with Media3 `Transformer`

### 6.1 API

[DOCUMENTED] `androidx.media3` stable is **1.11.0** (2026-08-05); `minSdk` is **23**. Artifacts: `androidx.media3:media3-transformer:1.11.0`, `androidx.media3:media3-effect:1.11.0`. ([media3 releases](https://developer.android.com/jetpack/androidx/releases/media3))

The overlay classes, read from [androidx/media `release` branch](https://raw.githubusercontent.com/androidx/media/release/libraries/effect/src/main/java/androidx/media3/effect/OverlayEffect.java):

```java
@UnstableApi
public final class OverlayEffect implements GlEffect {
  public OverlayEffect(List<TextureOverlay> textureOverlays) { ... }
}
```
> *"Applies a list of `TextureOverlay`s to a frame in FIFO order (the last overlay in the list is displayed on top). … To modify the list of `TextureOverlay`s, one must recreate a new `OverlayEffect` with the updated list."*

```java
@UnstableApi
public abstract class TextOverlay extends BitmapOverlay {
  public static TextOverlay createStaticTextOverlay(SpannableString overlayText);
  public static TextOverlay createStaticTextOverlay(SpannableString overlayText,
                                                    StaticOverlaySettings overlaySettings);
  public static final int TEXT_SIZE_PIXELS = 100;
  public abstract SpannableString getText(long presentationTimeUs);
  @Override public Bitmap getBitmap(long presentationTimeUs) { /* StaticLayout -> Bitmap, cached */ }
}
```

`BitmapOverlay.getTextureId(long)` re-uploads via `GlUtil.setTexture(...)` only when the `Bitmap` instance or its `generationId` changed — so media3's own overlay path is already "upload on change", same idea as §7. All of these classes are **`@UnstableApi`**.

Positioning uses `OverlaySettings` / `StaticOverlaySettings` with **Normalised Device Coordinates** anchors (`setBackgroundFrameAnchor`, `setOverlayFrameAnchor`, `setAlphaScale`, `setScale`, `setRotationDegrees`) — i.e. media3 positions overlays in *output* space, so it does not have the buffer-orientation trap of §5.

### 6.2 Cost — why this cannot be the loop

[DOCUMENTED] Google's own benchmark figures ([Android Developers Blog, 2025-03](https://android-developers.googleblog.com/2025/03/media-processing-performance-jetpack-media3-transformer.html)), on a **Pixel 9 Pro XL**, with the caveat *"the numbers below should be taken as rough estimates"*:

| Input | Transcode | Trim | Resize |
|---|---|---|---|
| 10 s 720p H.264 | ~1300 ms | ~2300 ms | ~1200 ms |
| 25 s 360p VP8 | ~3400 ms | ~1700 ms | ~4800 ms |
| 4 s 8K H.265 | ~2300 ms | ~1800 ms | ~3700 ms |

**[INFERRED]:** ~7.7× realtime for 720p transcode on a 2024-class flagship. Roadguard's loop is 1080p and runs at **100% duty cycle** — every second of wall-clock produces a second of video to process. Any GL effect forces the transcode path, not transmux ([DOCUMENTED]: transmuxing is *"Only applicable to basic operations, such as rotating, trimming, or container conversion"*), so the burn-in job would have to sustain >1× realtime on a Mali-G57 MP1 **while the same SoC's encoder and camera are already saturated recording the next segment**. Plus:

- **Double encode** → quality loss on top of the original encode ([DOCUMENTED]: *"Loss in quality due to re-encoding"*).
- **Double storage churn** and a peak requirement of (source segment + output segment) free space simultaneously — directly hostile to "storage safety #3" and to a loop recorder that deletes the oldest segment to make room.
- **Double thermal load** in the exact configuration (long drive, hot cabin, phone on a windscreen mount) where the baseline device is least able to absorb it.
- Contention for a single hardware encoder instance: whether the T606 can hold two concurrent H.264 encoder instances at 1080p is `NOT VERIFIED — needs on-device measurement` (test: `MediaCodecList` + attempt to configure two AVC encoders at 1920×1080@30 and log `CodecException`).

### 6.3 Where `Transformer` *does* belong

As a **user-initiated export/share action**: keep the pristine recording plus the `.jsonl` telemetry sidecar; when the user taps "export with overlay" on a specific incident clip, run `Transformer` with `Effects(listOf(), listOf(OverlayEffect(listOf(myTelemetryTextOverlay))))` on that one clip, foreground, ideally while charging. This is exactly what the CameraX team recommended for this problem shape [DOCUMENTED], Xi Zhang on [`camerax-developers` thread `64eahzvdY4U`](https://groups.google.com/a/android.com/g/camerax-developers/c/64eahzvdY4U):

> *"After the video is captured, run a post-processing job then generate all the augmented videos"* … *"Store the original video on disk, Only generate the augmented video at the time of sharing"*

and in the same thread, on the CPU alternative:

> *"you can use `ImageAnalysis` to get a CPU buffer, convert it to a Bitmap and draw on it with Canvas before writing it to the MediaCodec Surface. This drawback is that the performance will suffer."* — **do not do this**; it is a full YUV→Bitmap→upload per frame.

### 6.4 Option 4b — live media3 effects via the CameraX adapter

[DOCUMENTED] `androidx.camera.media3:media3-effect:1.0.0-alpha04` (released **2025-08-13**; earlier alphas 2024-12-11 and 2025-05-07). ([camera-media3 releases](https://developer.android.com/jetpack/androidx/releases/camera-media3))

```kotlin
// androidx.camera.media3.effect.Media3Effect
public class Media3Effect(
    context: Context,
    @Targets targets: Int,
    executor: Executor,
    errorListener: Consumer<Throwable>,
) : CameraEffect(...), AutoCloseable {
    public fun setEffects(effects: List<Effect>)   // applied immediately, no camera restart
    public override fun close()
}
```
[DOCUMENTED] ([`Media3Effect.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/camera/media3/media3-effect/src/main/java/androidx/camera/media3/effect/Media3Effect.kt)). Internally it wraps media3's `DefaultVideoFrameProcessor` and registers the input stream as `listOf(cameraXTransformEffect, *effects)` [DOCUMENTED] `Media3SurfaceProcessor.kt` — i.e. the CameraX transform becomes one more effect in media3's shader chain.

Attractive property: `setEffects()` changes the overlay list **without restarting the camera**, which would be a clean thermal lever. Disqualifying properties for Roadguard:
- Artifact is **alpha** and has had no release in ~12 months (latest 1.0.0-alpha04, Aug 2025), with release notes that are literally "*Fixed crash with media3 1.7 or later*" / "*Fixed crash when using with media3 1.6 dependency*" — i.e. a moving-target dependency contract.
- Every overlay class it would use is `@UnstableApi`.
- It adds media3's whole frame-processing chain (≥1 extra shader program beyond the CameraX transform) on the single-core Mali-G57.
- `Media3Effect`'s own `CameraEffect` superclass constructor passes an **empty error listener** (`{}`) for the CameraEffect-level errors, routing errors only through the `Media3SurfaceProcessor`'s listener — a subtle error-handling difference to audit if it were ever adopted.

**Verdict: not in the recording path.** Reconsider only if `OverlayEffect` proves unusable *and* burn-in is judged mandatory.

---

## 7. Option 5 — `MediaMuxer` metadata / subtitle tracks

[DOCUMENTED] AOSP `MediaMuxer` javadoc ([source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/media/java/android/media/MediaMuxer.java)):

> *"Per-frame metadata carries information that correlates with video or audio to facilitate offline processing. For example, gyro signals from the sensor can help video stabilization when doing offline processing. **Metadata tracks are only supported when multiplexing to the MP4 container format.** When adding a new metadata track, the MIME type format must start with prefix `"application/"` (for example, `"application/gyro"`). The format of the metadata is application-defined. **Metadata timestamps must be in the same time base as video and audio timestamps.** The generated MP4 file uses `TextMetaDataSampleEntry` (defined in section 12.3.3.2 of the ISOBMFF specification) to signal the metadata's MIME type."*

Feature/API table in the same javadoc: **"Muxing Metadata Tracks" = "Only Supported in MP4", from SDK 26+.** Our `minSdk 34` is fine.

The `addTrack` format table only defines keys for **"All Tracks" / "Audio Tracks" / "Video Tracks"** — **there is no subtitle-track category**. [INFERRED] `MediaMuxer` cannot author a tx3g/WebVTT subtitle track, so "ship the HUD as burned-in-looking subtitles" is not available on the platform muxer.

Playability verdict:
- A custom `application/*` metadata track is **invisible in every normal player** (Photos, VLC, desktop players). It is a machine-readable sidecar that happens to live inside the MP4.
- Even if a subtitle track were possible, subtitles are toggled off by default in most players and are worthless as tamper-resistant evidence.

Blocking practical issue: **CameraX's `Recorder` owns its muxer and exposes no API to add a track.** You would have to abandon `Recorder` (→ Option 3) or remux each finished segment (→ storage churn + a rewrite window where the file is half-written). 

**Recommended shape of this idea instead:** write a plain **`<segment>.jsonl`** sidecar next to each MP4, one line per second, containing `{monotonicNs, ptsUs, epochMs, lat, lon, speedMps, bearing, accuracyM, altM, weather…}` where `ptsUs` is anchored to the segment start. It is offline-first, trivially durable (append + `fsync` on segment close), costs nothing, survives the MP4 being copied off separately, and is what makes §6.3 export-time burn-in possible. Treat it as **mandatory alongside** the live burn-in, not as an alternative.

---

## 8. Cheapest way to rasterise the HUD at ~1 Hz

The whole game is: **do the text work on a worker thread; on the GL thread do nothing but `clear` + one axis-aligned `drawBitmap`; and only on the seconds where something changed.**

### 8.1 The producer (worker thread, ~1 Hz)

```kotlin
// One HandlerThread (or a coroutine on Dispatchers.Default) at THREAD_PRIORITY_BACKGROUND.
private val pendingHud = AtomicReference<HudBitmap?>(null)
private val pool = arrayOfNulls<Bitmap>(2)   // double-buffer; never allocate in steady state
private var slot = 0

private val paint = TextPaint().apply {
    isAntiAlias = true
    isSubpixelText = false        // subpixel positioning costs, buys nothing at video scale
    isLinearText = false
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    color = Color.WHITE
    textSize = hudTextPx          // derived from frame height, see below
}
private val shadow = Paint().apply { color = 0x99000000.toInt() }   // legibility plate

fun rebuildHud(rotationDegrees: Int, displayedW: Int, displayedH: Int, state: HudState) {
    val w = /* fixed, e.g. */ (displayedW * 0.62f).toInt()
    val h = (hudTextPx * 2.6f).toInt()
    val bmp = pool[slot] ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { pool[slot] = it }
    slot = slot xor 1
    val c = Canvas(bmp)
    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 8f, 8f, shadow)
    // Pre-formatted, pre-measured strings; NO String.format in steady state.
    c.drawText(state.line1, 12f, hudTextPx * 1.1f, paint)     // "112 km/h   14:32:07"
    c.drawText(state.line2, 12f, hudTextPx * 2.2f, paint)     // "51.5074 N  0.1278 W   +18 C"
    pendingHud.set(HudBitmap(bmp, rotationDegrees))
}
```

Cheapness rules, in descending order of payoff:

1. **Never allocate in the steady state.** Reuse two `Bitmap`s and one `Canvas` per bitmap; reuse `StringBuilder`s; avoid `String.format` (it allocates a `Formatter`, a `Locale` lookup and several intermediate strings every call). For numbers, write digits into a reused `char[]` and use `Canvas.drawText(char[] text, int index, int count, float x, float y, Paint)`.
2. **Avoid `StaticLayout` unless you need wrapping/bidi/spans.** `StaticLayout.Builder.obtain(...).build()` allocates and runs line-breaking. For fixed one- or two-line HUD text, `Canvas.drawText` is strictly cheaper. (For contrast, media3's `TextOverlay` *does* build a `StaticLayout` per text change — acceptable there, unnecessary here.)
3. **Avoid re-measuring.** Call `Paint.measureText` / `getTextBounds` only when the *layout* changes (font size, rotation, frame size), not when the values change. Right-align numeric fields at fixed x positions so the width never depends on the digits.
4. **Use tabular/monospaced digits or fixed-width fields** so the HUD does not visually jitter between updates — which also means the plate size is constant, which enables rule 6.
5. **`ARGB_8888` only.** `Bitmap.Config.HARDWARE` cannot be drawn into; `RGB_565` has no alpha; `ALPHA_8` cannot carry the black legibility plate.
6. **Pre-rotate on the worker thread.** `frame.rotationDegrees` is constant for the whole segment (§5.4), so render the HUD bitmap already in *buffer* orientation and let the GL-thread call be `canvas.drawBitmap(bmp, x, y, null)` with an **identity matrix and integer offsets** — the fastest path Skia has (a straight blit, no resampling). Keep §5.3's matrix code as the general/correct fallback and for the first frame after a rotation change.
7. **Text size from the frame, not from dp.** The overlay canvas is `frame.size` (video pixels), not screen dp. Use e.g. `hudTextPx = frame.cropRect.height() * 0.030f` (in *displayed* orientation), floor to an integer, and cache the `Paint` per size. `NOT VERIFIED — needs on-device measurement` for legibility on the G04's HD+ screen when reviewing 1080p footage.

### 8.2 The consumer (GL thread, only on change)

The full-surface clear is the only unavoidable per-update cost (§2.5c: `lockCanvas(null)` ⇒ *"the entire surface should be redrawn"*). At 1080p that is ~8.3 MB of writes, once per second.

**Optional optimisation, [INFERRED] and [UNVERIFIED]:** clip the clear to a rect that is a strict superset of everything you ever draw:

```kotlin
canvas.save()
canvas.clipRect(hudDirtyBufferRect)                          // buffer coords, constant per segment
canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)   // clears only the clip
// ... draw ...
canvas.restore()
```
This is only safe once **every buffer in the overlay `Surface`'s BufferQueue has been fully cleared at least once**, because `lockCanvas` hands back a recycled buffer whose contents are a previous post (`SurfaceProcessorImpl` clears exactly *one* buffer at setup). Prime it by doing 4–5 consecutive **full** clears at the start of each segment, then switch to clipped clears. Whether the queue depth is ≤4 on the T606 is `NOT VERIFIED — needs on-device measurement` (test: clipped-clear mode with a deliberately moving HUD; look for ghost trails or stale text, which prove more buffers exist than you primed). **Ship the full-clear version first; only add this if measurement says the clear is a real cost.**

---

## 9. Keeping overlay work off the encoder's critical path

The `Handler` you pass to `OverlayEffect` **is** the GL thread that renders into the encoder's input surface. Everything below follows from that.

| Rule | Why | Evidence |
|---|---|---|
| **Pass a dedicated `HandlerThread`, never `Looper.getMainLooper()`** | GL work + a ≤30 ms blocking semaphore on the main thread means UI jank *and* HUD-triggered frame stalls. Note that the official CameraX 1.4.0 blog sample uses `Handler(Looper.getMainLooper())` — fine for a demo, wrong for a dashcam | [DOCUMENTED] constructor javadoc ("*for performing OpenGL operations*") + §2.5d; [blog sample](https://android-developers.googleblog.com/2024/12/whats-new-in-camerax-140-and-jetpack-compose-support.html) |
| Set that thread's priority to `Process.THREAD_PRIORITY_DISPLAY` (or `URGENT_DISPLAY`) | It is on the recording path; it must not lose the CPU to background work on a 2+6 little-core SoC | [INFERRED] |
| `queueDepth = 0` | Removes a whole extra full-frame GL copy per frame and all queue textures | [DOCUMENTED] §2.5b |
| **Only call `frame.overlayCanvas` when content changed** | Not touching it skips `lockCanvas`, the full repaint, `unlockCanvasAndPost`, and the blocking semaphore entirely; the previous overlay texture keeps being blended for free | [DOCUMENTED] §2.5d (`frame.isOverlayDirty()` gate) |
| Do **zero** I/O, GPS/sensor reads, formatting, allocation, logging or locking inside the listener | Every microsecond there is on the encoder feed path | [INFERRED] |
| Publish HUD bitmaps via a single `AtomicReference` (`getAndSet`) — no locks, no queues | Lock-free handoff; the GL thread never blocks on the producer | [INFERRED] |
| **Always `return true`**; wrap the listener body in `try/catch (Throwable)` | `false` ⇒ *"the frame will be dropped"*; an escaping throw is a crash on the GL thread | [DOCUMENTED] `setOnDrawListener` javadoc |
| Never call `drawFrameAsync()` | Only meaningful with `queueDepth > 0`; misuse yields `RESULT_FRAME_NOT_FOUND`/`RESULT_INVALID_SURFACE` and can drop frames | [DOCUMENTED] `drawFrameAsync` javadoc |
| Clip all drawing to `frame.cropRect` | Out-of-bounds drawing has produced repeating/flashing artefacts on real devices | [DOCUMENTED] camerax-developers thread `k3eVmhXejpk` |
| Keep the listener's worst case under ~3 ms | Budget: 33.3 ms per frame at 30 fps, shared with the GL blit and `eglSwapBuffers` | [INFERRED] |
| Watch logcat for `SurfaceProcessorImpl: Timed out waiting canvas post` | Direct evidence the overlay update exceeded the 30 ms budget | [DOCUMENTED] source string |
| Cross-check frame count: `RecordingStats.getRecordedDurationNanos()` vs. decoded frame count of the finished file | The only reliable app-visible signal that the effect is dropping frames | [INFERRED] |

---

## 10. Should the overlay pass be the first thing shed under thermal pressure?

**No.** Three reasons, in order of force:

1. **You cannot remove it cheaply.** The GL node exists because `getEffect() != null` at pipeline-creation time (§2.6). Removing the effect requires rebinding the `SessionConfig`, which tears down and recreates the `VideoCapture` pipeline — i.e. **a gap in the recording** and (per §5.1) **a change in the recorded file's pixel geometry mid-loop**. Both violate "recording reliability is #1". `OverlayEffect` has no "bypass" switch; `clearOnDrawListener()` stops *updates* but leaves the last-drawn overlay frozen on screen forever (because the overlay texture is still blended every frame), which is worse than no HUD — a frozen "112 km/h" on evidence footage is actively harmful.
2. **Its marginal cost is small and, crucially, mostly fixed.** Per §2.5, the recurring GPU cost is one extra `samplerExternalOES` fetch + one MAD per output pixel inside a pass that already exists; the CPU cost is ~8 MB of writes per HUD update. The dominant heat sources in a dashcam are the camera/ISP, the video encoder, the always-on display and (in Roadguard) the map renderer — each of which is an order of magnitude larger. Confirming the ordering on the G04 is `NOT VERIFIED — needs on-device measurement` (test: `dumpsys thermalservice` / `PowerManager.getThermalHeadroom(0)` sampled over a 30-minute run in four configurations: overlay on/off × map on/off).
3. **There are strictly better levers**, and they are the ones the platform guidance points at ("*reduce the frame rate, lower fidelity*" — [DOCUMENTED] [Thermal API guide](https://developer.android.com/games/optimize/adpf/thermal)).

### Recommended thermal shed ladder for Roadguard (overlay is late, recording never)

Read thermal state with `PowerManager.addThermalStatusListener(Executor, OnThermalStatusChangedListener)` and `PowerManager.getThermalHeadroom(int forecastSeconds)`.

API gates [DOCUMENTED] ([Thermal API guide](https://developer.android.com/games/optimize/adpf/thermal), AOSP `PowerManager.java`):

| API | Introduced | Notes |
|---|---|---|
| `PowerManager.getCurrentThermalStatus()` | Android 11 / **API 30** | returns `THERMAL_STATUS_NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN` |
| `PowerManager.addThermalStatusListener(...)` | Android 11 / **API 30** (guide) | callback-driven; cheap |
| `PowerManager.getThermalHeadroom(int)` | Android 11 / **API 30** | *"If `getThermalHeadroom` returns NaN, make sure that you are not calling it more than once every 10 seconds."* |
| `PowerManager.getThermalHeadroomThresholds()` | Android 15 / **API 35** | gate with `Build.VERSION.SDK_INT >= 35`; use fixed thresholds below that |

All available at `minSdk 34`, except `getThermalHeadroomThresholds()` which needs an API-35 gate.

| Step | Trigger (indicative) | Action | Recording impact |
|---|---|---|---|
| 1 | `LIGHT` / headroom > 0.75 | Dim/blank the screen, stop the map from re-rendering (static tile snapshot), stop weather polling | none |
| 2 | `MODERATE` | HUD update 1 Hz → 0.2 Hz (keeps content correct-ish, cuts the `lockCanvas` cost 5×). Drop map to 1 fps or hide it | none |
| 3 | `MODERATE` sustained | Reduce bitrate (`Recorder.Builder.setTargetVideoEncodingBitRate`) | quality only |
| 4 | `SEVERE` | Reduce resolution at the **next segment boundary** (`Quality.FHD` → `Quality.HD`), which is also when a rebind is safe; if you must drop the overlay, do it *here*, at the same boundary, and record the fact in the sidecar | brief, boundary-aligned |
| 5 | `CRITICAL` | Frame rate 30 → 24/20; consider audio off | quality only |
| 6 | `EMERGENCY` / `SHUTDOWN` | Finalise the current segment cleanly, `fsync`, surface a prominent warning. **Never** silently stop | recording stops last |

**Bottom line for this question:** shed *screen, map, weather and HUD update frequency* before touching the video pipeline; if the overlay must go, remove it only at a segment boundary, and prefer "HUD updates less often" over "HUD disappears".

---

## 11. Primary recommendation, fallback, and runtime detection

### 11.1 Primary

```kotlin
// ---- setup (once, off the main thread for the HandlerThread creation) ----
private val glThread = HandlerThread("roadguard-overlay-gl",
        Process.THREAD_PRIORITY_DISPLAY).apply { start() }
private val glHandler = Handler(glThread.looper)

private val overlayEffect = OverlayEffect(
    /* targets    = */ CameraEffect.VIDEO_CAPTURE,   // == 2; recorded stream only
    /* queueDepth = */ 0,                            // no queue textures, no extra GL copy
    /* handler    = */ glHandler,
    /* errorListener = */ Consumer<Throwable> { t -> onOverlayUnrecoverable(t) }
)
// overlayEffect.setOnDrawListener { ... }  // see §5.3

// ---- bind ----
val sessionConfig = SessionConfig.Builder(preview, videoCapture)
    .addEffect(overlayEffect)
    .build()                     // no ViewPort: recorded stream must not be cropped
cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, sessionConfig)

// ---- teardown ----
// overlayEffect.close()  then  glThread.quitSafely()
```

Plus, unconditionally: the on-screen HUD as Compose/`View` content over `PreviewView` (`ScaleType.FILL_CENTER`), and the `.jsonl` telemetry sidecar per segment.

### 11.2 Documented fallback

1. **No burn-in, recording continues.** Rebind the same `SessionConfig` minus the effect. Recording quality/geometry reverts to "pixels in sensor orientation + MP4 rotation metadata" (§5.1) — still fully correct video, just no burned-in HUD.
2. **Sidecar remains authoritative.** The `.jsonl` still carries speed/GPS/time/weather per second, so evidence value is preserved.
3. **On-demand burn-in at export** with `androidx.media3:media3-transformer:1.11.0` + `androidx.media3.effect.OverlayEffect` / `TextOverlay` (§6.3), user-initiated, single clip, foreground.

### 11.3 Runtime detection — how to decide which path a device gets

Three independent mechanisms, because GL/driver failures on cheap SoCs are frequently *not* catchable exceptions:

**(a) Synchronous guards (cheap, catches programming errors):**
- Wrap `bindToLifecycle` in `try/catch (IllegalArgumentException | IllegalStateException)`. An illegal target bitmask or conflicting effects throw here.
- If `sessionType == SESSION_TYPE_HIGH_SPEED`, skip the effect and mark burn-in unavailable for that mode (§2.6 — it would be silently ignored).

**(b) The effect's own error listener (catches in-flight GL failures):**
```kotlin
private fun onOverlayUnrecoverable(t: Throwable) {
    Log.e(TAG, "OverlayEffect failed; disabling burn-in on this device", t)
    prefs.edit().putBoolean(KEY_OVERLAY_DISABLED, true).commit()   // commit, not apply
    mainHandler.post { rebindWithoutOverlay() }                    // keep recording alive
}
```
[DOCUMENTED] the `errorListener` receives *"the error thrown by this `CameraEffect`. For example, `ProcessingException`"* and is invoked on the supplied `Handler`.

**(c) A crash canary (catches native/driver death, ANRs, watchdog kills — the ones no `catch` sees):**
```
before bind:            prefs.edit().putInt(KEY_OVERLAY_TRIAL, trial + 1).commit()
after N seconds of a healthy recording (first VideoRecordEvent.Status with
recordedDurationNanos > 5e9 and no error):
                        prefs.edit().putInt(KEY_OVERLAY_TRIAL, 0).commit()
at app start:           if (KEY_OVERLAY_TRIAL >= 2) -> KEY_OVERLAY_DISABLED = true
```
Two failed trials ⇒ permanently disable burn-in on this device and log it in a user-visible diagnostics screen. This is the only technique that survives a GPU driver hard-crash, which is a realistic risk on a single-core Mali on a budget Unisoc part. [INFERRED] — standard defensive pattern, no source claims it.

**(d) Positive verification (does the overlay actually reach the file?)** After the first segment on a new device/OS build, run a one-shot self-test off the recording path: `MediaMetadataRetriever.getFrameAtTime(...)` on the finished MP4, sample a handful of pixels inside the known HUD rect, and confirm they are not equal to the same pixels sampled outside it. Store the result. This catches the nastiest failure class — effect "succeeds", HUD present in nothing.

**Do not** gate on SoC/GPU allow-lists or `Build.MODEL`. Gate on observed behaviour of *this* device.

---

## 12. API-level gates (consolidated)

| API / capability | Required level | Roadguard (`minSdk 34`) |
|---|---|---|
| `androidx.camera:camera-effects` `OverlayEffect`, `Frame` | no `@RequiresApi`; GLES 2.0; artifact stable at 1.6.1 | OK |
| `CameraEffect.PREVIEW/VIDEO_CAPTURE/IMAGE_CAPTURE` | CameraX 1.3.0-alpha05+ for `VIDEO_CAPTURE` as a target | OK |
| `androidx.camera.core.SessionConfig` + `Builder.addEffect` | CameraX 1.5+ (stable in 1.6.x) | OK |
| `EGLExt.eglPresentationTimeANDROID`, `EGL_RECORDABLE_ANDROID` | API 18 | OK (used internally) |
| `Surface.lockCanvas(Rect)` | API 1 | OK (used internally, `null` dirty rect) |
| `Surface.lockHardwareCanvas()` | API 23 | Available but **not** used by `camera-effects` (`b/186120366`) |
| `MediaMuxer` metadata track (`application/*`) | **API 26**, MP4 only | OK |
| `MediaCodec.createInputSurface()` | API 18 | OK (Option 3 only) |
| `androidx.media3:*` (transformer, effect) | `minSdk 23` | OK |
| `PowerManager.getCurrentThermalStatus` / `addThermalStatusListener` / `getThermalHeadroom(int)` | **API 30** | OK |
| `PowerManager.getThermalHeadroomThresholds()` | **API 35** | Gate with `SDK_INT >= 35` |
| Android 16 / API 36 changes affecting `camera-effects` | none found | `[UNVERIFIED]` — re-check `camera-effects` release notes before shipping against API 36 |

---

## 13. Anti-patterns to keep out of the codebase

1. `OverlayEffect(PREVIEW or VIDEO_CAPTURE, …)` — forces stream sharing, couples preview crop to recorded crop. [DOCUMENTED] 1.3.0-alpha05 release note.
2. `queueDepth > 0` — an extra full-frame GL copy per frame plus N full-resolution textures, for a feature we don't use. [DOCUMENTED] `GlRenderer.createBufferTextureIds`.
3. `Handler(Looper.getMainLooper())` as the effect handler (as in the official 1.4.0 blog sample) — puts GLES + a 30 ms blocking wait on the UI thread.
4. Calling `frame.overlayCanvas` on every frame — full-surface software repaint + blocking texture upload 30×/s.
5. Returning `false` from the draw listener — *"the frame will be dropped"*. [DOCUMENTED].
6. Using `frame.sensorToBufferTransform` to place the HUD — that matrix omits display rotation; it is for detection overlays.
7. Computing rotation from `CameraCharacteristics.SENSOR_ORIENTATION` / `Display.getRotation()` yourself — `frame.rotationDegrees` already is the answer and already includes CameraX's per-device compensation.
8. Setting a `ViewPort` to make preview and video match — crops the recording.
9. Running a `Transformer` burn-in job per 3-minute segment in the background.
10. Changing `videoCapture.targetRotation` mid-segment.
11. `String.format` / `StaticLayout` / `Bitmap.createBitmap` inside the HUD update.

---

## 14. Open questions / must-measure-on-device

Every item below is `NOT VERIFIED — needs on-device measurement`. The **baseline device is the Moto G04**; anything that passes there passes on the Edge 60 Fusion.

1. **Does `OverlayEffect` on `VIDEO_CAPTURE` sustain 1080p30 on the Mali-G57 MP1?**
   *Test:* 30-minute continuous 3-minute-segment recording, `Quality.FHD`, overlay at 1 Hz, map visible. For each segment compare `RecordingStats.getRecordedDurationNanos()` against the decoded frame count (`MediaExtractor` sample count) — expected ≈ duration × 30. Any deficit > 0.5% means the effect is dropping frames. Also grep logcat for `SurfaceProcessorImpl: Timed out waiting canvas post`.
2. **What is the *actual* recorded geometry and orientation metadata with the effect enabled?**
   *Test:* record 60 s in each of portrait / landscape-left / landscape-right / reverse-portrait, then dump `MediaFormat` `KEY_WIDTH`/`KEY_HEIGHT` and `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION`. Confirms §5.1's inference that pixels are pre-rotated and rotation metadata is 0. If it is *not* 0, the overlay counter-rotation in §5.3 is still correct (it keys off `frame.rotationDegrees`), but the file-geometry claims in this document must be corrected.
3. **Is the HUD upright in the recorded file in all four orientations, and is it inside `cropRect`?**
   *Test:* burn a HUD containing an unambiguous asymmetric marker (an arrow plus the literal text `TOP`), record in all four orientations, then view each file in Google Photos, VLC desktop, and a browser `<video>` tag. All three must show upright, fully-visible text.
4. **Cost of the full-surface clear at 1 Hz vs. clipped clear.**
   *Test:* `Trace`/Perfetto around the listener body; compare mean and p99 listener duration with (a) full `drawColor` clear, (b) clipped clear after priming. If (a) is < 3 ms p99, ship (a) and delete the clipped-clear path.
5. **How many buffers are in the overlay `Surface`'s BufferQueue?** (Determines whether the clipped-clear optimisation is safe, §8.2.)
   *Test:* clipped-clear mode with a fast-moving HUD element; look for ghost trails. Cross-check with `adb shell dumpsys SurfaceFlinger`/`meminfo` deltas when the effect is enabled.
6. **Does the effect measurably change thermal behaviour?**
   *Test:* four 30-minute runs (overlay on/off × map on/off) with `PowerManager.getThermalHeadroom(0)` sampled every 10 s (respecting the NaN rule) plus `dumpsys thermalservice`. Establishes whether the overlay belongs anywhere on the shed ladder in §10.
7. **Does the T606 camera HAL behave differently when the recording stream is a `SurfaceTexture` rather than a `MediaCodec` surface?** (CameraX explicitly warns it "*may treat the surface differently, potentially impacting video quality and stabilization*".)
   *Test:* record the same static scene with and without the effect; compare bitrate, visible noise/sharpness, and whether `CONTROL_VIDEO_STABILIZATION_MODE` is still honoured (`Camera2Interop` capture-result logging).
8. **Legible HUD text size.** What `textSize` (as a fraction of `cropRect` height) is readable when 1080p footage is reviewed on the G04's 1612×720 screen, and when zoomed 1:1 on a desktop? Pick the value from a side-by-side sample, don't guess.
9. **Two concurrent H.264 encoder instances at 1080p on the T606** — settles definitively whether *any* form of background transcode is even possible (§6.2). *Test:* configure two AVC encoders at 1920×1080@30 via `MediaCodec` and log `CodecException`/`MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()`.
10. **Mirroring path.** `frame.isMirroring` is expected `false` for the rear camera; verify, and if a front-camera mode is ever added, verify the `postScale` branch in §5.3 against a real front-camera recording.
11. **Mid-segment rotation change.** Rotate the device 30 s into a segment; confirm whether the HUD stays aligned with the video (i.e. whether `frame.rotationDegrees` and the node's baked rotation stay in sync, §5.4). If they diverge, latch the rotation used for the overlay at recording start instead of reading it per frame.
12. **API 36 (Android 16) regression check.** Re-run tests 1–3 on an API 36 device/emulator before shipping; nothing in the `camera-effects` release notes flags an API-36 issue, but that absence is `[UNVERIFIED]`, not proof.

---

### Sources actually fetched for this document

- CameraX releases & release notes — https://developer.android.com/jetpack/androidx/releases/camera
- camera-media3 releases — https://developer.android.com/jetpack/androidx/releases/camera-media3
- media3 releases — https://developer.android.com/jetpack/androidx/releases/media3
- CameraX video capture architecture — https://developer.android.com/media/camera/camerax/video-capture
- "What's new in CameraX 1.4.0…" (official blog) — https://android-developers.googleblog.com/2024/12/whats-new-in-camerax-140-and-jetpack-compose-support.html
- "Introducing CameraX 1.5…" (official blog) — https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html
- "Common media processing operations with Jetpack Media3 Transformer" (official blog, benchmarks) — https://android-developers.googleblog.com/2025/03/media-processing-performance-jetpack-media3-transformer.html
- Thermal API guide — https://developer.android.com/games/optimize/adpf/thermal
- androidx source (androidx-main, raw): `camera/camera-effects/{api/current.txt, build.gradle, src/main/java/androidx/camera/effects/{OverlayEffect.java, Frame.java, internal/{SurfaceProcessorImpl.java, Utils.java}, opengl/{GlRenderer.java, GlProgramOverlay.java, GlProgramCopy.java, GlContext.java}}}`; `camera/camera-core/src/main/java/androidx/camera/core/{CameraEffect.java, UseCaseGroup.java, SurfaceProcessor.java, SurfaceOutput.java, SessionConfig.kt, processing/SurfaceProcessorNode.java, processing/util/OutConfig.java}`; `camera/camera-video/src/main/java/androidx/camera/video/{VideoCapture.java, Recorder.java}`; `camera/media3/media3-effect/src/main/java/androidx/camera/media3/effect/{Media3Effect.kt, Media3SurfaceProcessor.kt}` — all under https://raw.githubusercontent.com/androidx/androidx/androidx-main/
- androidx-media source (release branch, raw): `libraries/effect/src/main/java/androidx/media3/effect/{OverlayEffect.java, TextOverlay.java, BitmapOverlay.java, TextureOverlay.java, StaticOverlaySettings.java}` — https://raw.githubusercontent.com/androidx/media/release/
- AOSP framework source (raw): `media/java/android/media/{MediaMuxer.java, MediaCodec.java}`, `core/java/android/view/Surface.java`, `core/java/android/os/PowerManager.java` — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/
- CameraX maintainer answers, `camerax-developers` Google Group — https://groups.google.com/a/android.com/g/camerax-developers/c/64eahzvdY4U and https://groups.google.com/a/android.com/g/camerax-developers/c/k3eVmhXejpk
- Motorola official specifications — moto g04: https://en-us.support.motorola.com/app/answers/detail/a_id/178144/~/moto-g04%C2%A0--sp%C3%A9cifications%C2%A0/ ; edge 60 fusion: https://en-us.support.motorola.com/app/answers/detail/a_id/184937/~/specifications---motorola-edge-60-fusion
