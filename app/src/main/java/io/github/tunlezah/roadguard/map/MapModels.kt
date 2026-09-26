package io.github.tunlezah.roadguard.map

import io.github.tunlezah.roadguard.thermal.MapRenderBudget
import java.io.File

/**
 * An offline map package Roadguard can install.
 *
 * @param id stable identifier, used as the on-disk directory name.
 * @param sizeBytes the download size **as published by the source**, or null when the source does
 *   not state it. Roadguard shows "size unknown" rather than an estimate it made up.
 * @param sha256 published checksum, when the source publishes one. Usually absent -- the archives
 *   are rebuilt by their own workflow, so a pinned hash would eventually reject a good map --
 *   in which case [MapInstaller] verifies structurally instead, which also catches the failure a
 *   checksum cannot: a valid archive in the wrong schema. See [PmtilesArchive].
 * @param maxZoom the archive's deepest zoom level, or null when the catalogue does not state it.
 *   This is the honest measure of detail: 14 is street level, 12 shows the main-road network but
 *   not every suburban street. Roadguard shows it rather than inventing a "quality" label.
 * @param attribution the licence text that must be displayed while this data is in use.
 */
data class MapPackage(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
    val sha256: String?,
    val maxZoom: Int?,
    val attribution: String,
    val licence: String,
    val coversWholeCountry: Boolean,
) {
    /** True when this archive carries street-level geometry rather than just the main network. */
    val isStreetLevel: Boolean get() = (maxZoom ?: 0) >= STREET_LEVEL_ZOOM

    companion object {
        /** Zoom at which suburban street geometry is present in the Protomaps Basemap schema. */
        const val STREET_LEVEL_ZOOM = 14
    }
}

/**
 * A geographic bounding box, as a PMTiles archive states its own coverage in its header.
 *
 * A state extract's box reaches a little past the state line, so a point just over a border can
 * fall inside two boxes; [MapChooser] settles that by preferring the map the point lies deepest
 * inside.
 */
data class MapBounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {

    /** False for the all-zero box an archive writes when it states no coverage. */
    val isMeaningful: Boolean
        get() = minLon < maxLon && minLat < maxLat &&
            !(minLon == 0.0 && minLat == 0.0 && maxLon == 0.0 && maxLat == 0.0)

    /** True when the point lies inside, at least [insetDegrees] from every edge. */
    fun contains(latitude: Double, longitude: Double, insetDegrees: Double = 0.0): Boolean =
        latitude >= minLat + insetDegrees && latitude <= maxLat - insetDegrees &&
            longitude >= minLon + insetDegrees && longitude <= maxLon - insetDegrees

    /** How far inside the box the point is, in degrees to the nearest edge; negative when outside. */
    fun depth(latitude: Double, longitude: Double): Double =
        minOf(latitude - minLat, maxLat - latitude, longitude - minLon, maxLon - longitude)
}

/**
 * A package that is installed and readable, described by its own archive rather than the
 * catalogue: the zoom and coverage come from the PMTiles header.
 */
data class InstalledMap(
    val pack: MapPackage,
    val directory: File,
    val archive: File,
    val sizeBytes: Long,
    val installedAtEpochMs: Long,
    /** The archive's deepest zoom level, the honest measure of detail. */
    val maxZoom: Int,
    /** The archive's stated coverage, or null when it states none, which counts as covering everywhere. */
    val bounds: MapBounds?,
) {
    val id: String get() = pack.id

    fun covers(latitude: Double, longitude: Double, insetDegrees: Double = 0.0): Boolean =
        bounds?.contains(latitude, longitude, insetDegrees) ?: true
}

/** Where a map install has got to. */
sealed interface MapInstallState {
    data object NotInstalled : MapInstallState

    data class Downloading(
        val packageId: String,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long?,
    ) : MapInstallState {
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }

        val etaSeconds: Long?
            get() {
                val total = totalBytes ?: return null
                val rate = bytesPerSecond ?: return null
                if (rate <= 0) return null
                return ((total - bytesDownloaded).coerceAtLeast(0L)) / rate
            }
    }

    data class Paused(val packageId: String, val bytesDownloaded: Long, val totalBytes: Long?) : MapInstallState

    data class Verifying(val packageId: String) : MapInstallState

    data class Installed(
        val packageId: String,
        val sizeBytes: Long,
        val installedAtEpochMs: Long,
    ) : MapInstallState

    data class Failed(val packageId: String, val reason: MapFailureReason, val detail: String?) : MapInstallState
}

enum class MapFailureReason(val message: String) {
    NoNetwork("Map installation needs an internet connection the first time"),

    /**
     * The catalogue names a file the server does not have.
     *
     * Distinguished from a generic download failure because retrying the same region will not help:
     * either the asset was moved or the catalogue is pointing at the wrong URL. Choosing a different
     * region may well work.
     */
    NotPublished("That region's map file is not available at the moment"),
    InsufficientStorage("There is not enough free space to install the map"),
    DownloadFailed("The map download could not be completed"),
    VerificationFailed("The downloaded map data was incomplete or corrupt"),
    NotConfigured("No offline map package is configured for this build"),
    Cancelled("Map installation was cancelled"),
}

/**
 * How much work the map subsystem is allowed to do.
 *
 * Set by the thermal engine. The map is always subordinate to recording: under pressure it is
 * throttled and then torn down, and it never has a path that can stop or degrade the recorder.
 */
data class MapWorkBudget(
    val renderEnabled: Boolean = true,
    val positionUpdateIntervalMs: Long = 1_000L,
    val allowAnimation: Boolean = true,
) {
    companion object {
        /**
         * The map work allowed at [budget].
         *
         * `Reduced` keeps the map but moves it once per fix instead of animating: following the
         * vehicle with a camera animation re-renders the whole map continuously while moving,
         * which on a single-shader-core GPU is one of the largest draws in the app. `Frozen` and
         * `Disabled` both take the map off screen, which frees its GL context entirely.
         */
        fun forRenderBudget(budget: MapRenderBudget): MapWorkBudget = when (budget) {
            MapRenderBudget.Full -> MapWorkBudget()
            MapRenderBudget.Reduced -> MapWorkBudget(renderEnabled = true, positionUpdateIntervalMs = 2_000L, allowAnimation = false)
            MapRenderBudget.Frozen, MapRenderBudget.Disabled -> MapWorkBudget(renderEnabled = false, allowAnimation = false)
        }
    }
}
