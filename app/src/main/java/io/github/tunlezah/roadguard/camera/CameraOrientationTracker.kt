package io.github.tunlezah.roadguard.camera

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.UseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the *physical* orientation of the phone and turns it into a CameraX target rotation.
 *
 * ### Why physical, not window, orientation
 *
 * Roadguard behaves like a normal Android camera app: the phone's orientation decides the
 * recording's orientation. Portrait phone gives a portrait video; landscape phone gives a
 * landscape video. Nothing here computes a "dashcam angle", inspects the camera sensor
 * mounting, or second-guesses the user's mounting choice.
 *
 * The *window* rotation is the wrong input for that, because a user with auto-rotate locked
 * would mount the phone in landscape and get a portrait recording. So the device's own
 * orientation sensor is used, exactly as `androidx.camera.view.RotationProvider` does, and
 * `UseCase.snapToSurfaceRotation` converts the reported degrees into a `Surface.ROTATION_*`
 * constant using CameraX's own quadrant rules rather than arithmetic of our own.
 *
 * ### Hysteresis
 *
 * A phone in a windscreen cradle vibrates, and a vehicle leans on cornering. Without
 * hysteresis the reported rotation would flap near the 45 degree quadrant boundaries and, since
 * `Recorder` latches rotation at the start of each segment, produce a run of segments with
 * alternating orientation. A change is therefore only published once it has held for
 * [SETTLE_MS].
 */
class CameraOrientationTracker(context: Context, private val settleMs: Long = SETTLE_MS) {

    private val _surfaceRotation = MutableStateFlow(Surface.ROTATION_0)

    /** The current target rotation, as a `Surface.ROTATION_*` constant. */
    val surfaceRotation: StateFlow<Int> = _surfaceRotation.asStateFlow()

    private var pendingRotation: Int? = null
    private var pendingSinceMs: Long = 0L
    private var listening = false

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            // Face-up on a desk reports unstable values; CameraX's own snap helper is the
            // documented way to map degrees to a surface rotation, so use it rather than
            // reimplementing the quadrant boundaries.
            onDegrees(orientation, android.os.SystemClock.elapsedRealtime())
        }
    }

    /** Testable core: feed degrees and a clock, get the settled rotation. */
    fun onDegrees(orientationDegrees: Int, nowMs: Long): Int {
        val snapped = UseCase.snapToSurfaceRotation(orientationDegrees)
        if (snapped == _surfaceRotation.value) {
            pendingRotation = null
            return _surfaceRotation.value
        }
        if (snapped != pendingRotation) {
            pendingRotation = snapped
            pendingSinceMs = nowMs
            return _surfaceRotation.value
        }
        if (nowMs - pendingSinceMs >= settleMs) {
            _surfaceRotation.value = snapped
            pendingRotation = null
        }
        return _surfaceRotation.value
    }

    fun start() {
        if (listening) return
        if (listener.canDetectOrientation()) {
            listener.enable()
            listening = true
        }
    }

    fun stop() {
        if (!listening) return
        listener.disable()
        listening = false
        pendingRotation = null
    }

    /** True when the device has a usable orientation sensor. */
    fun canDetectOrientation(): Boolean = listener.canDetectOrientation()

    companion object {
        /**
         * How long a new orientation must hold before Roadguard acts on it.
         *
         * Long enough to ride out cornering and cradle vibration, short enough that a
         * deliberate re-mount is picked up before the next segment starts.
         */
        const val SETTLE_MS = 700L

        /** Human-readable name for a `Surface.ROTATION_*` value, for diagnostics. */
        fun describe(surfaceRotation: Int): String = when (surfaceRotation) {
            Surface.ROTATION_0 -> "0 degrees (portrait)"
            Surface.ROTATION_90 -> "90 degrees (landscape)"
            Surface.ROTATION_180 -> "180 degrees (reverse portrait)"
            Surface.ROTATION_270 -> "270 degrees (reverse landscape)"
            else -> "unknown ($surfaceRotation)"
        }

        /** The rotation, in degrees, that a player must apply to display the video upright. */
        fun degreesFor(surfaceRotation: Int): Int = when (surfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
