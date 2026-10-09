package io.github.tunlezah.roadguard.parking

import io.github.tunlezah.roadguard.event.SensorSample
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A direction in device axes: for parking, always the way gravity points through the phone.
 *
 * A phone clipped into a cradle keeps gravity pointing the same way through it for as long as it
 * stays there, whatever the car does; a phone picked up, pocketed or laid on a seat does not. That
 * makes the direction a cheap, gyroscope-free answer to "is this still the phone in its mount?".
 */
data class Direction(val x: Float, val y: Float, val z: Float) {

    /** The angle between the two directions, in degrees. */
    fun degreesTo(other: Direction): Float {
        val cosine = (x * other.x + y * other.y + z * other.z).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine.toDouble())).toFloat()
    }

    companion object {
        /** The direction of (x, y, z), or null for a vector too short to have a trustworthy one. */
        fun of(x: Double, y: Double, z: Double): Direction? {
            val length = sqrt(x * x + y * y + z * z)
            if (length < MIN_LENGTH) return null
            return Direction((x / length).toFloat(), (y / length).toFloat(), (z / length).toFloat())
        }

        private const val MIN_LENGTH = 1e-6
    }
}

/**
 * One second of accelerometer, reduced to the two things parking decisions need.
 *
 * @param startMs the first sample's time, in milliseconds on the sensor clock (elapsed realtime).
 * @param endMs the last sample's time.
 * @param samples how many samples the window summarises.
 * @param vibration how much the phone was being shaken: the standard deviation of the linear
 *   acceleration, combined over the three axes, in m/s^2. Each axis's own mean is taken out first,
 *   so a fused linear-acceleration sensor that carries a small constant bias -- common on budget
 *   phones -- reads as still rather than as permanently moving.
 * @param gravity the mean gravity direction over the window, or null when no sample in it carried
 *   a usable gravity estimate.
 */
data class MotionWindow(
    val startMs: Long,
    val endMs: Long,
    val samples: Int,
    val vibration: Float,
    val gravity: Direction?,
)

/**
 * Cuts the accelerometer stream into one-second [MotionWindow]s.
 *
 * Windows are bounded by the samples' own timestamps, not by when a batch happened to be
 * delivered, so the sensor hub's batching changes nothing. A window is only completed when the
 * first sample of the next one arrives; a stream that stops simply stops producing windows, and
 * the consumers treat that silence as "not observed" rather than as "still".
 *
 * Pure and deterministic.
 */
class MotionMeter(windowMs: Long = WINDOW_MS) {

    private val windowNanos = windowMs * NANOS_PER_MS

    private var count = 0
    private var startNanos = 0L
    private var lastNanos = 0L
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var sumSquaresX = 0.0
    private var sumSquaresY = 0.0
    private var sumSquaresZ = 0.0
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 0.0

    /** Adds one sample; returns the window it completed, if it completed one. */
    fun accept(sample: SensorSample): MotionWindow? {
        val now = sample.elapsedRealtimeNanos
        // A clock that steps backwards means the sensor was restarted: close what there is.
        val completed = if (count > 0 && (now - startNanos >= windowNanos || now < lastNanos)) {
            summarise().also { clear() }
        } else {
            null
        }
        if (count == 0) startNanos = now
        add(sample)
        lastNanos = now
        return completed
    }

    /** Forgets the window in progress, for when the sensors are restarted. */
    fun reset() = clear()

    private fun add(sample: SensorSample) {
        count++
        sumX += sample.linearX
        sumY += sample.linearY
        sumZ += sample.linearZ
        sumSquaresX += sample.linearX.toDouble() * sample.linearX
        sumSquaresY += sample.linearY.toDouble() * sample.linearY
        sumSquaresZ += sample.linearZ.toDouble() * sample.linearZ
        // Averaged as unit vectors, so the direction is what is averaged and not the magnitude.
        // The sensor source reports zero gravity until its first gravity reading has arrived;
        // those samples have no direction to contribute.
        val x = sample.gravityX.toDouble()
        val y = sample.gravityY.toDouble()
        val z = sample.gravityZ.toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude >= MIN_GRAVITY_MPS2) {
            gravityX += x / magnitude
            gravityY += y / magnitude
            gravityZ += z / magnitude
        }
    }

    private fun summarise(): MotionWindow {
        val n = count.toDouble()
        fun variance(sum: Double, sumSquares: Double): Double {
            val mean = sum / n
            return (sumSquares / n - mean * mean).coerceAtLeast(0.0)
        }
        val vibration = sqrt(
            variance(sumX, sumSquaresX) + variance(sumY, sumSquaresY) + variance(sumZ, sumSquaresZ),
        )
        return MotionWindow(
            startMs = startNanos / NANOS_PER_MS,
            endMs = lastNanos / NANOS_PER_MS,
            samples = count,
            vibration = vibration.toFloat(),
            gravity = Direction.of(gravityX, gravityY, gravityZ),
        )
    }

    private fun clear() {
        count = 0
        sumX = 0.0
        sumY = 0.0
        sumZ = 0.0
        sumSquaresX = 0.0
        sumSquaresY = 0.0
        sumSquaresZ = 0.0
        gravityX = 0.0
        gravityY = 0.0
        gravityZ = 0.0
    }

    companion object {
        /** One second: long enough to average sensor noise, short enough to notice a car pulling away. */
        const val WINDOW_MS = 1_000L

        private const val NANOS_PER_MS = 1_000_000L

        /**
         * A gravity estimate weaker than this is the sensor source's zero placeholder, not a
         * reading. Real gravity is 9.8 m/s^2 and only ever looks smaller while being thrown.
         */
        private const val MIN_GRAVITY_MPS2 = 1.0
    }
}
