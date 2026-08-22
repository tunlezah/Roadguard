package io.github.tunlezah.roadguard.overlay

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.OverlayEffect
import java.util.concurrent.atomic.AtomicReference

/**
 * Burns [OverlayContent] into the recorded video using CameraX's `OverlayEffect`.
 *
 * ### Why this mechanism
 *
 * Of the ways to get text into a recorded MP4 on a low-end phone, this is the only one that
 * adds no extra render pass: CameraX already inserts a single OpenGL pass whenever any effect
 * is present, and the overlay costs one additional texture fetch per output pixel inside that
 * same pass. Post-processing each finished segment with a transcoder would double the encoder
 * load and the storage churn; a hand-rolled Camera2 + MediaCodec + EGL pipeline would mean
 * reimplementing CameraX's device-quirk handling for no overlay benefit. See
 * `docs/research/overlay-embedding.md`.
 *
 * ### Targeting only the recording
 *
 * The effect targets `CameraEffect.VIDEO_CAPTURE` **only**. Targeting
 * `PREVIEW or VIDEO_CAPTURE` would make CameraX share one stream between the two use cases and
 * copy it, which costs more on the baseline device. The on-screen equivalent is drawn by
 * Compose over the viewfinder instead -- free, and it lets the UI label clearly which
 * indicators are burned into the file and which are screen-only.
 *
 * ### Cost control
 *
 * The renderer only runs when the content changes, which is at most once a second. Everything
 * else is a plain GPU blit of an unchanged overlay texture.
 *
 * ### Failure
 *
 * `OverlayEffect` reports errors to the listener passed to its constructor. Roadguard treats
 * any error as "give up on burn-in, keep recording": [onError] is invoked so the recorder can
 * rebind without the effect. Losing the timestamp overlay is a real loss; losing the recording
 * is a much bigger one.
 */
class VideoOverlayEffect(
    private val renderer: OverlayRenderer = OverlayRenderer(),
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {

    private val thread = HandlerThread("roadguard-overlay").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = AtomicReference(OverlayContent.EMPTY)
    private val drawn = AtomicReference<OverlayContent?>(null)

    /** The CameraX effect to add to the bound use cases. */
    val effect: OverlayEffect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE,
        QUEUE_DEPTH,
        handler,
    ) { throwable ->
        Log.w(TAG, "overlay effect failed; recording continues without burn-in", throwable)
        onError(throwable)
    }.also { overlay ->
        overlay.setOnDrawListener { frame ->
            val content = pending.get()
            // Only rasterise when something actually changed. Returning true with an untouched
            // canvas keeps the previous overlay texture, which is exactly what we want.
            if (drawn.get() != content) {
                runCatching {
                    renderer.draw(
                        canvas = frame.overlayCanvas,
                        content = content,
                        bufferWidth = frame.size.width,
                        bufferHeight = frame.size.height,
                        cropRect = frame.cropRect,
                        rotationDegrees = frame.rotationDegrees,
                        mirrored = frame.isMirroring,
                    )
                    drawn.set(content)
                }.onFailure { throwable ->
                    // A drawing failure must never take the recording with it.
                    Log.w(TAG, "overlay draw failed", throwable)
                    drawn.set(content)
                }
            }
            true
        }
    }

    /** Publishes new content; the next frame picks it up. Cheap, safe from any thread. */
    fun update(content: OverlayContent) {
        pending.set(content)
    }

    override fun close() {
        runCatching { effect.clearOnDrawListener() }
        runCatching { effect.close() }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "RoadguardOverlay"

        /**
         * Zero means "no frame buffering".
         *
         * A dashcam must never trade latency or memory for the ability to redraw past frames,
         * and a non-zero depth would hold full-resolution frames in a queue.
         */
        const val QUEUE_DEPTH = 0
    }
}
