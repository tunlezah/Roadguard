package io.github.tunlezah.roadguard.location

import kotlin.math.abs

/**
 * Turns raw GNSS speed reports into a value that is safe to burn into a video.
 *
 * Raw `Location.getSpeed()` is Doppler-derived on modern chipsets and is usually good, but it
 * still misbehaves in exactly the situations a dashcam meets: under bridges and in tunnels the
 * fix degrades and speed jumps; when stationary it wanders by a few km/h; and after a gap the
 * next fix can arrive with a wildly different value.
 *
 * The filter therefore:
 *
 *  * **rejects** fixes whose accuracy or speed accuracy is worse than a threshold, rather than
 *    averaging bad data into good;
 *  * **clamps to zero** below a floor, because a parked car reading "3 km/h" looks broken and,
 *    burned into evidence footage, is actively misleading;
 *  * **rate-limits** change to a physically plausible acceleration, so one bad fix cannot make
 *    the overlay read 180 km/h; and
 *  * **expires**, returning null once the last good fix is older than [holdMs], so the overlay
 *    shows "--" instead of a stale speed.
 *
 * Pure and deterministic: every timestamp is passed in.
 */
class SpeedFilter(
    private val maxAccuracyMetres: Float = MAX_ACCURACY_M,
    private val maxSpeedAccuracyMps: Float = MAX_SPEED_ACCURACY_MPS,
    private val zeroFloorMps: Float = ZERO_FLOOR_MPS,
    private val maxAccelerationMps2: Float = MAX_ACCELERATION_MPS2,
    private val holdMs: Long = HOLD_MS,
) {
    private var lastSpeedMps: Float? = null
    private var lastAtElapsedMs: Long? = null

    /**
     * @param rawSpeedMps `Location.getSpeed()`, or null when the fix carries no speed.
     * @param speedAccuracyMps `getSpeedAccuracyMetersPerSecond()`, or null when unreported.
     * @return the speed to display, or null when nothing trustworthy is available.
     */
    fun accept(
        rawSpeedMps: Float?,
        speedAccuracyMps: Float?,
        horizontalAccuracyMetres: Float?,
        atElapsedMs: Long,
    ): Float? {
        expireIfStale(atElapsedMs)

        if (rawSpeedMps == null || rawSpeedMps.isNaN() || rawSpeedMps < 0f) return lastSpeedMps
        if (horizontalAccuracyMetres != null && horizontalAccuracyMetres > maxAccuracyMetres) {
            return lastSpeedMps
        }
        if (speedAccuracyMps != null && speedAccuracyMps > maxSpeedAccuracyMps) return lastSpeedMps

        val floored = if (rawSpeedMps < zeroFloorMps) 0f else rawSpeedMps

        val previous = lastSpeedMps
        val previousAt = lastAtElapsedMs
        val accepted = if (previous == null || previousAt == null) {
            floored
        } else {
            val dtSeconds = ((atElapsedMs - previousAt).coerceAtLeast(1L)).toFloat() / 1000f
            val maxDelta = maxAccelerationMps2 * dtSeconds
            val delta = floored - previous
            if (abs(delta) > maxDelta) previous + maxDelta * (if (delta > 0) 1f else -1f) else floored
        }

        lastSpeedMps = accepted
        lastAtElapsedMs = atElapsedMs
        return accepted
    }

    /** Drops the held value once it is older than [holdMs]. */
    fun expireIfStale(atElapsedMs: Long) {
        val previousAt = lastAtElapsedMs ?: return
        if (atElapsedMs - previousAt > holdMs) {
            lastSpeedMps = null
            lastAtElapsedMs = null
        }
    }

    fun current(atElapsedMs: Long): Float? {
        expireIfStale(atElapsedMs)
        return lastSpeedMps
    }

    fun reset() {
        lastSpeedMps = null
        lastAtElapsedMs = null
    }

    companion object {
        /** Fixes worse than this are ignored for speed purposes. */
        const val MAX_ACCURACY_M = 50f

        /** Speed-accuracy ceiling, about 7 km/h. */
        const val MAX_SPEED_ACCURACY_MPS = 2.0f

        /** Below about 2.5 km/h the vehicle is treated as stopped. */
        const val ZERO_FLOOR_MPS = 0.7f

        /**
         * Plausible vehicle acceleration ceiling, about 0.8 g. Generous enough for hard
         * braking and sports-car acceleration, tight enough to reject a single bad fix.
         */
        const val MAX_ACCELERATION_MPS2 = 8f

        /** How long a good speed is held once fixes stop arriving. */
        const val HOLD_MS = 5_000L
    }
}
