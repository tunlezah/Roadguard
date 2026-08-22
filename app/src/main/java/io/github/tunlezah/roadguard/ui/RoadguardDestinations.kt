package io.github.tunlezah.roadguard.ui

/**
 * Roadguard's screens.
 *
 * A sealed set of destinations with a tiny hand-rolled back stack instead of Navigation-Compose:
 * the app has one primary screen plus a handful of leaves, none of them deep-linked, and the
 * recorder's state lives in the service rather than in a nav graph. Adding a navigation library
 * for this shape would be a dependency without a job.
 */
sealed interface RoadguardDestination {
    data object Main : RoadguardDestination
    data object FirstRun : RoadguardDestination
    data object Settings : RoadguardDestination
    data object RecordingSettings : RoadguardDestination
    data object OverlaySettings : RoadguardDestination
    data object EventSettings : RoadguardDestination
    data object PowerSettings : RoadguardDestination
    data object MapSettings : RoadguardDestination
    data object PrivacySettings : RoadguardDestination
    data object Storage : RoadguardDestination
    data object Diagnostics : RoadguardDestination
    data object Gallery : RoadguardDestination
    data object About : RoadguardDestination
    data class Player(val segmentId: Long) : RoadguardDestination
}
