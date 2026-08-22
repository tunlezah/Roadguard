package io.github.tunlezah.roadguard.overlay

import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.weather.WeatherSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the burned-in overlay text from the app's live state.
 *
 * Kept separate from the renderer and from the recorder so that "what the overlay says" is a pure
 * function of settings, location, weather and the clock, and can be unit tested. It is called at
 * most once a second; [OverlayContent] equality is what stops the renderer rasterising a frame
 * whose text has not changed.
 *
 * Nothing is invented. If speed is unknown the field is omitted rather than shown as zero, and
 * coordinates are formatted to a precision the current fix actually supports.
 */
class OverlayComposer(
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()),
    private val timeFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()),
) {

    fun compose(
        settings: Settings,
        location: LocationState,
        weather: WeatherSnapshot?,
        nowEpochMs: Long,
        protectedLabel: String? = null,
    ): OverlayContent {
        val showCoordinates = settings.overlayCoordinates && settings.gpsStorage.overlay
        val showSpeed = settings.overlaySpeed && settings.gpsStorage.overlay
        return OverlayContent(
            dateText = if (settings.overlayDateTime) dateFormat.format(Date(nowEpochMs)) else null,
            timeText = if (settings.overlayDateTime) timeFormat.format(Date(nowEpochMs)) else null,
            speedText = if (showSpeed) {
                location.speedIn(settings.speedUnit)?.let { "$it ${settings.speedUnit.suffix}" }
            } else {
                null
            },
            coordinatesText = if (showCoordinates) location.formatCoordinates() else null,
            weatherText = if (settings.overlayWeather && settings.weatherEnabled) {
                weather?.overlayText()
            } else {
                null
            },
            protectedLabel = protectedLabel,
        )
    }
}
