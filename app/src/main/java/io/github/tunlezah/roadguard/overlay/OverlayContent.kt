package io.github.tunlezah.roadguard.overlay

import io.github.tunlezah.roadguard.event.BrakeLevel

/**
 * The text Roadguard burns into the recorded video.
 *
 * Deliberately a plain immutable value: it is produced once a second from the location, clock
 * and weather state, and rendered by [OverlayRenderer]. Anything absent is null and simply
 * omitted -- the overlay never shows a placeholder like "0 km/h" or "--,--" that could be
 * mistaken for a measurement.
 */
data class OverlayContent(
    val dateText: String? = null,
    val timeText: String? = null,
    val speedText: String? = null,
    val coordinatesText: String? = null,
    val weatherText: String? = null,
    /** Shown while an event's footage is being protected. */
    val protectedLabel: String? = null,
    /** Lights the brake indicator LED while the vehicle is braking. Never protects anything. */
    val brake: BrakeLevel? = null,
) {
    val isEmpty: Boolean
        get() = dateText == null && timeText == null && speedText == null &&
            coordinatesText == null && weatherText == null && protectedLabel == null &&
            brake == null

    companion object {
        val EMPTY = OverlayContent()
    }
}
