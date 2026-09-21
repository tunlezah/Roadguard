package io.github.tunlezah.roadguard.ui.gallery

import java.util.Locale

/** Formatting shared by the recordings list and the player. Plain functions, so they are testable. */
internal object GalleryFormat {

    /** `3m 0s` for a clip, which is short enough that seconds matter. */
    fun clipDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    /** `29 min` or `3 h 17 min` for a trip, where seconds are noise. */
    fun tripDuration(millis: Long): String {
        val totalMinutes = (millis / 60_000).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours h $minutes min"
            hours > 0 -> "$hours h"
            totalMinutes == 0L -> "under a minute"
            else -> "$minutes min"
        }
    }

    fun size(bytes: Long): String {
        val megabytes = bytes.toDouble() / (1024.0 * 1024)
        return if (megabytes >= 1024) {
            String.format(Locale.getDefault(), "%.2f GB", megabytes / 1024)
        } else {
            String.format(Locale.getDefault(), "%.0f MB", megabytes)
        }
    }

    /**
     * `11 km`, or `0.8 km` under ten kilometres; null under 100 m, where the figure would be
     * GNSS wander rather than a drive.
     */
    fun distance(metres: Long): String? {
        if (metres < 100) return null
        val km = metres / 1000.0
        return if (km < 10) String.format(Locale.getDefault(), "%.1f km", km) else "${km.toInt()} km"
    }

    fun clipCount(count: Int): String = if (count == 1) "1 clip" else "$count clips"
}
