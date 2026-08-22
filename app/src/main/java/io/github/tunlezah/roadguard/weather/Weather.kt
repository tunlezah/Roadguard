package io.github.tunlezah.roadguard.weather

/**
 * A cached weather observation.
 *
 * Weather is entirely optional in Roadguard, is off by default, and can never gate or delay
 * recording. See `docs/research/weather-australia.md` for the source evaluation and
 * `docs/privacy.md` for exactly what a weather lookup does and does not send.
 */
data class WeatherSnapshot(
    val temperatureC: Float?,
    val conditionText: String?,
    val precipitationMmPerHour: Float?,
    val windSpeedKmh: Float?,
    val observedAtEpochMs: Long,
    val fetchedAtEpochMs: Long,
    /** Human-readable attribution the UI is obliged to display. */
    val attribution: String,
) {
    /** Compact form for the burned-in overlay: short, or nothing at all. */
    fun overlayText(): String? {
        val parts = buildList {
            temperatureC?.let { add("${it.toInt()} C") }
            conditionText?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
    }

    fun isStale(nowEpochMs: Long, maximumAgeMs: Long): Boolean =
        nowEpochMs - fetchedAtEpochMs > maximumAgeMs
}

/** Why weather is unavailable, so the UI can explain rather than just show nothing. */
enum class WeatherUnavailableReason(val message: String) {
    Disabled("Weather is turned off"),
    NoLocation("Waiting for a GPS fix"),
    Offline("No internet connection"),
    NotSupported("No suitable free weather source is available"),
    Failed("Weather could not be retrieved"),
}

sealed interface WeatherState {
    data object Loading : WeatherState
    data class Available(val snapshot: WeatherSnapshot) : WeatherState
    data class Unavailable(val reason: WeatherUnavailableReason) : WeatherState
}
