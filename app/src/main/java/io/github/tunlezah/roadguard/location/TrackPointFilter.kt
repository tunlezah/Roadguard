package io.github.tunlezah.roadguard.location

import io.github.tunlezah.roadguard.map.PlaceRanking

/**
 * Decides which fixes become GPX track points, and how far the vehicle has travelled.
 *
 * A receiver reports once a second whether or not the car is moving. Writing every one of those
 * fixes while parked at a set of lights would fill the track with a cloud of near-identical points
 * and, worse, accumulate GNSS wander as distance. So a fix is written when the vehicle has moved at
 * least [minDistanceMetres] since the last written point, or when [heartbeatMs] has passed with
 * nothing written (so a long stop still shows in the track), and distance is only ever added for
 * a point written because of movement.
 *
 * Fixes with a horizontal accuracy worse than [maxAccuracyMetres] are ignored entirely: a point
 * that could be a block away is not evidence of where the car went.
 *
 * Pure and deterministic: every timestamp is passed in.
 */
class TrackPointFilter(
    private val minDistanceMetres: Double = MIN_DISTANCE_M,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val maxAccuracyMetres: Float = MAX_ACCURACY_M,
) {
    /** Whether to write the fix, and how much it adds to the trip distance. */
    data class Decision(val write: Boolean, val distanceMetres: Double) {
        companion object {
            val SKIP = Decision(write = false, distanceMetres = 0.0)
        }
    }

    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastWrittenAtEpochMs: Long? = null

    fun consider(latitude: Double, longitude: Double, accuracyMetres: Float?, atEpochMs: Long): Decision {
        if (accuracyMetres != null && accuracyMetres > maxAccuracyMetres) return Decision.SKIP
        val previousLatitude = lastLatitude
        val previousLongitude = lastLongitude
        val previousAt = lastWrittenAtEpochMs
        if (previousLatitude == null || previousLongitude == null || previousAt == null) {
            remember(latitude, longitude, atEpochMs)
            return Decision(write = true, distanceMetres = 0.0)
        }
        val movedMetres = PlaceRanking.distanceKm(previousLatitude, previousLongitude, latitude, longitude) * 1_000.0
        if (movedMetres >= minDistanceMetres) {
            remember(latitude, longitude, atEpochMs)
            return Decision(write = true, distanceMetres = movedMetres)
        }
        if (atEpochMs - previousAt >= heartbeatMs) {
            // A stationary heartbeat: the point is worth having, the wander is not worth counting.
            remember(previousLatitude, previousLongitude, atEpochMs)
            return Decision(write = true, distanceMetres = 0.0)
        }
        return Decision.SKIP
    }

    fun reset() {
        lastLatitude = null
        lastLongitude = null
        lastWrittenAtEpochMs = null
    }

    private fun remember(latitude: Double, longitude: Double, atEpochMs: Long) {
        lastLatitude = latitude
        lastLongitude = longitude
        lastWrittenAtEpochMs = atEpochMs
    }

    companion object {
        /** Below this the car has not meaningfully moved; consumer GNSS wanders by a few metres parked. */
        const val MIN_DISTANCE_M = 5.0

        /** A point is written this often even when stationary, so a stop is visible in the track. */
        const val HEARTBEAT_MS = 30_000L

        /** Matches [FixQuality.Poor]'s ceiling: anything worse is "still acquiring", not a position. */
        const val MAX_ACCURACY_M = 75f
    }
}
