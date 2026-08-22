package io.github.tunlezah.roadguard.location

import io.github.tunlezah.roadguard.settings.SpeedUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** GNSS fix quality, as shown by the GPS status indicator. */
enum class FixQuality(val label: String) {
    /** No fix and no satellites in view yet. */
    NoSignal("No GPS"),

    /** Satellites visible, still acquiring. */
    Searching("Acquiring"),

    /** A fix, but too imprecise to trust for speed or position display. */
    Poor("Weak GPS"),

    /** A usable fix. */
    Good("GPS"),

    /** A tight fix. */
    Excellent("GPS"),
    ;

    val hasFix: Boolean get() = this == Poor || this == Good || this == Excellent
    val isTrustworthy: Boolean get() = this == Good || this == Excellent
}

/**
 * Roadguard's view of where the vehicle is and how fast it is going.
 *
 * @param speedMetresPerSecond filtered speed, or null when no trustworthy speed is available.
 *   Null is used rather than 0 so the UI can say "--" instead of claiming the vehicle is
 *   stopped.
 * @param accuracyMetres horizontal accuracy of [latitude]/[longitude].
 * @param ageMillis how stale the fix is. A fix older than
 *   [LocationEngine.STALE_FIX_MS] is shown as stale rather than silently presented as current.
 * @param satellitesUsed satellites used in the last fix; [satellitesVisible] is the total in
 *   view, which is what tells a user "it is working, just not yet".
 */
data class LocationState(
    val quality: FixQuality = FixQuality.NoSignal,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMetres: Double? = null,
    val speedMetresPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val accuracyMetres: Float? = null,
    val fixEpochMs: Long? = null,
    val ageMillis: Long? = null,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val isMock: Boolean = false,
    val permissionGranted: Boolean = false,
    val providerEnabled: Boolean = true,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null

    /** Speed in the user's chosen unit, rounded the way a speedometer rounds. */
    fun speedIn(unit: SpeedUnit): Int? =
        speedMetresPerSecond?.let { (it * unit.fromMetresPerSecond).roundToInt() }

    /**
     * Coordinates formatted without false precision.
     *
     * Five decimal places is about 1 m at Australian latitudes; showing more would imply an
     * accuracy consumer GNSS does not have. When accuracy is poor the string is deliberately
     * coarser still.
     */
    fun formatCoordinates(): String? {
        val latitude = latitude ?: return null
        val longitude = longitude ?: return null
        val decimals = when {
            accuracyMetres == null -> 4
            accuracyMetres <= 10f -> 5
            accuracyMetres <= 50f -> 4
            else -> 3
        }
        val latitudeHemisphere = if (latitude >= 0) "N" else "S"
        val longitudeHemisphere = if (longitude >= 0) "E" else "W"
        return "%.${decimals}f${latitudeHemisphere} %.${decimals}f${longitudeHemisphere}"
            .format(abs(latitude), abs(longitude))
    }
}
