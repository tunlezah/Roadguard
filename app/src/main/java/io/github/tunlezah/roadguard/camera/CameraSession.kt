package io.github.tunlezah.roadguard.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DynamicRange
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.LifecycleOwner
import io.github.tunlezah.roadguard.capability.RecordingProfile
import io.github.tunlezah.roadguard.overlay.VideoOverlayEffect
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Range as AndroidRange

/**
 * Owns the CameraX binding.
 *
 * ### Two use cases, no ViewPort
 *
 * Exactly `Preview` and `VideoCapture<Recorder>` are bound. Adding a third use case would, on
 * anything below camera hardware level `LEVEL_3`, make CameraX share and copy a stream, adding
 * a GPU pass on the device least able to afford one. And no `ViewPort` is ever set: a ViewPort
 * propagates a crop rectangle into `VideoCapture`, which crops the **recorded** stream. The
 * specification forbids cropping the recording to make the UI fit, so preview fitting is done
 * entirely as a view transform (see [PreviewFit]).
 *
 * ### Rotation
 *
 * `setTargetRotation` is applied to both use cases from the physical device orientation. With
 * an MP4 container CameraX records the rotation as the container's orientation hint and does
 * not rotate pixels, so the encoder keeps a constant frame size as the phone turns and no
 * reconfiguration is needed mid-drive.
 *
 * ### Codec
 *
 * CameraX's `Recorder` selects the video encoder itself from the device's own encoder profiles
 * and offers an app no way to override it. Roadguard therefore *detects and reports* the codec
 * rather than pretending to choose it -- see `docs/architecture.md`. Choosing it would mean
 * replacing `Recorder` with a hand-rolled Camera2 plus MediaCodec pipeline, which would trade
 * the reliability that is Roadguard's first priority for a codec preference.
 */
class CameraSession(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var overlay: VideoOverlayEffect? = null

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)

    /**
     * The latest preview surface request, for the viewfinder composable to fulfil.
     *
     * Exposed as state rather than pushed into a view because the camera is bound to the
     * recording service, not to an Activity: the UI may come and go many times during a drive
     * and must be able to pick up the current request whenever it appears.
     */
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    private val _cameraError = MutableStateFlow<CameraState.StateError?>(null)
    val cameraError: StateFlow<CameraState.StateError?> = _cameraError.asStateFlow()

    val isBound: Boolean get() = camera != null

    /** The recorder in use, or null when unbound. Used to read live bitrate/frame-rate state. */
    val recorder: Recorder? get() = videoCapture?.output

    val boundCamera: Camera? get() = camera

    suspend fun initialise(): Result<ProcessCameraProvider> = runCatching {
        provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
    }

    suspend fun availableCameraInfos(): List<CameraInfo> =
        initialise().getOrNull()?.availableCameraInfos ?: emptyList()

    suspend fun concurrentCameraInfos(): List<List<CameraInfo>> =
        initialise().getOrNull()?.availableConcurrentCameraInfos ?: emptyList()

    /**
     * Binds (or rebinds) the camera.
     *
     * Rebinding stops any in-progress recording, so callers must only do it at a segment
     * boundary. That constraint is what lets the thermal engine change profile without ever
     * cutting a recording short.
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        profile: RecordingProfile,
        surfaceRotation: Int,
        overlayEffect: VideoOverlayEffect?,
    ): Result<BoundSession> = runCatching {
        val cameraProvider = requireNotNull(provider) { "initialise() must succeed before bind()" }
        cameraProvider.unbindAll()

        val newPreview = Preview.Builder()
            .setTargetRotation(surfaceRotation)
            .build()
            .also { it.setSurfaceProvider { request -> onSurfaceRequest(request) } }

        val recorderBuilder = Recorder.Builder()
            .setQualitySelector(qualitySelectorFor(profile))
            // Ask the recorder to reserve headroom so it aborts cleanly rather than filling the
            // volume; Roadguard's own loop budget keeps far more free than this floor.
            .setRequiredFreeStorageBytes(RECORDER_FREE_STORAGE_FLOOR_BYTES)
        if (profile.targetBitrateBps > 0) {
            recorderBuilder.setTargetVideoEncodingBitRate(profile.targetBitrateBps)
        }
        val newRecorder = recorderBuilder.build()

        val newVideoCapture = VideoCapture.Builder(newRecorder)
            .setTargetRotation(surfaceRotation)
            // A dashcam recording must never be mirrored, including on the front camera: a
            // mirrored number plate is useless as evidence.
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .setDynamicRange(DynamicRange.SDR)
            .setTargetFrameRate(AndroidRange(profile.frameRate, profile.frameRate))
            .setVideoStabilizationEnabled(profile.stabilisation)
            .build()

        val groupBuilder = UseCaseGroup.Builder()
            .addUseCase(newPreview)
            .addUseCase(newVideoCapture)
        // Deliberately no setViewPort(...): see the class documentation.
        if (profile.burnInOverlays && overlayEffect != null) {
            groupBuilder.addEffect(overlayEffect.effect)
        }

        val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, groupBuilder.build())

        preview = newPreview
        videoCapture = newVideoCapture
        camera = boundCamera
        overlay = overlayEffect
        _cameraError.value = null

        boundCamera.cameraInfo.addCameraStateListener(MAIN_EXECUTOR) { state ->
            _cameraError.value = state.error
            state.error?.let { Log.w(TAG, "camera state error ${it.code}", it.cause) }
        }

        BoundSession(
            camera = boundCamera,
            recorder = newRecorder,
            videoCapture = newVideoCapture,
            preview = newPreview,
        )
    }.onFailure { throwable ->
        Log.e(TAG, "camera bind failed", throwable)
        unbind()
    }

    /** Applies a new target rotation without rebinding. Safe mid-recording. */
    fun updateRotation(surfaceRotation: Int) {
        preview?.targetRotation = surfaceRotation
        videoCapture?.targetRotation = surfaceRotation
    }

    /**
     * Detaches or reattaches the preview surface.
     *
     * Used for screen-off and for thermal relief: removing the surface stops preview frames
     * being produced and composited without unbinding the use case, so the recording is
     * untouched. Rebinding to drop the Preview use case entirely would stop the recording, and
     * is never worth it.
     */
    fun setPreviewEnabled(enabled: Boolean) {
        val current = preview ?: return
        if (enabled) {
            current.setSurfaceProvider { request -> onSurfaceRequest(request) }
        } else {
            current.setSurfaceProvider(null)
            _surfaceRequest.value?.willNotProvideSurface()
            _surfaceRequest.value = null
        }
    }

    /**
     * Sets the camera's zoom ratio.
     *
     * This changes the sensor crop and therefore the **recorded** frames. It is wired only to
     * the advanced "recording zoom" setting, never to preview zoom.
     */
    fun setRecordingZoom(ratio: Float) {
        val control = camera?.cameraControl ?: return
        val state = camera?.cameraInfo?.zoomState?.value
        val clamped = state?.let { ratio.coerceIn(it.minZoomRatio, it.maxZoomRatio) } ?: ratio
        control.setZoomRatio(clamped)
    }

    fun unbind() {
        runCatching { provider?.unbindAll() }
        _surfaceRequest.value?.willNotProvideSurface()
        _surfaceRequest.value = null
        camera = null
        preview = null
        videoCapture = null
        overlay = null
    }

    private fun onSurfaceRequest(request: SurfaceRequest) {
        // A superseded request must be released or CameraX waits on it forever.
        _surfaceRequest.getAndUpdate { previous ->
            if (previous !== request) previous?.willNotProvideSurface()
            request
        }
        request.addRequestCancellationListener(MAIN_EXECUTOR) {
            _surfaceRequest.compareAndSet(request, null)
        }
    }

    companion object {
        private const val TAG = "RoadguardCamera"

        private val MAIN_EXECUTOR = java.util.concurrent.Executor { command ->
            android.os.Handler(android.os.Looper.getMainLooper()).post(command)
        }

        /**
         * Free-space floor handed to `Recorder`.
         *
         * CameraX aborts a recording with `ERROR_INSUFFICIENT_STORAGE` below its own 50 MiB
         * default; Roadguard raises that so the recorder gives up *before* the filesystem is
         * in a state that damages other apps. Roadguard's own reserve is far larger again --
         * this is only the last line of defence.
         */
        const val RECORDER_FREE_STORAGE_FLOOR_BYTES = 200L * 1024 * 1024

        /**
         * Builds the quality selector.
         *
         * The fallback strategy matters more than the first choice: on a device that cannot do
         * the requested quality, `lowerQualityOrHigherThan` keeps recording at the next best
         * option instead of failing to bind. A dashcam that records at 720p is infinitely
         * better than one that refuses to start at 1080p.
         */
        fun qualitySelectorFor(profile: RecordingProfile): QualitySelector {
            val preferred = qualityFor(profile.cameraXQuality)
            val ladder = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
            val ordered = ladder.dropWhile { it != preferred }.ifEmpty { listOf(Quality.HD, Quality.SD) }
            return QualitySelector.fromOrderedList(
                ordered,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
            )
        }

        fun qualityFor(name: String): Quality = when (name) {
            "UHD" -> Quality.UHD
            "FHD" -> Quality.FHD
            "HD" -> Quality.HD
            "SD" -> Quality.SD
            else -> Quality.HD
        }

        fun nameFor(quality: Quality?): String = when (quality) {
            Quality.UHD -> "UHD"
            Quality.FHD -> "FHD"
            Quality.HD -> "HD"
            Quality.SD -> "SD"
            else -> "unknown"
        }
    }
}

/** The objects produced by a successful bind. */
data class BoundSession(
    val camera: Camera,
    val recorder: Recorder,
    val videoCapture: VideoCapture<Recorder>,
    val preview: Preview,
)

private inline fun <T> MutableStateFlow<T>.getAndUpdate(transform: (T) -> T): T {
    while (true) {
        val current = value
        val next = transform(current)
        if (compareAndSet(current, next)) return current
    }
}
