package io.github.tunlezah.roadguard.map

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
)
