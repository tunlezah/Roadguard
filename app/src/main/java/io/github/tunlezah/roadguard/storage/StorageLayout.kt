package io.github.tunlezah.roadguard.storage

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * Where Roadguard keeps its files, and what they are called.
 *
 * Everything lives under the app's own external files directory
 * (`Android/data/<package>/files/...`). That choice is deliberate:
 *
 *  * it needs **no storage permission**, on any supported API level;
 *  * it is writable from a background foreground-service with no SAF round trips, so the
 *    recorder's write path is a plain `File` and as fast as the volume allows;
 *  * it can be placed on a **removable volume** simply by picking a different entry from
 *    `Context.getExternalFilesDirs()`, which is how the Moto G04's microSD slot is used; and
 *  * it keeps thousands of dashcam segments out of the user's gallery, while remaining
 *    reachable over USB/MTP and shareable through the app's `FileProvider`.
 *
 * The trade-off is that uninstalling the app deletes the footage. That is called out in the
 * UI and in `docs/storage.md`.
 *
 * ### Segments are never moved
 *
 * Protected footage stays in [StorageBucket.Recordings] and is marked by a sidecar file
 * (see [protectionSidecar]) as well as by the Room index. Nothing renames or moves a video
 * file after it is written, so there is no window in which a half-completed move can lose
 * the very footage an event was trying to protect. The sidecar means protection also
 * survives loss of the index.
 */
class StorageLayout(private val context: Context, val root: File) {

    val recordings: File get() = dir(StorageBucket.Recordings)
    val quarantine: File get() = dir(StorageBucket.Quarantine)
    val tracks: File get() = dir(StorageBucket.Tracks)
    val diagnostics: File get() = dir(StorageBucket.Diagnostics)
    val maps: File get() = dir(StorageBucket.Maps)

    fun dir(bucket: StorageBucket): File = File(root, bucket.dirName).apply { mkdirs() }

    fun file(bucket: StorageBucket, name: String): File = File(dir(bucket), name)

    /**
     * The sidecar that marks a segment as protected.
     *
     * Written *after* the video file is closed and *before* the index is updated, so a crash
     * can leave a protected file with no index row (recoverable: the reconciler re-adds it)
     * but never an index row claiming protection for a file that was not marked.
     */
    fun protectionSidecar(videoFileName: String): File =
        File(recordings, "$videoFileName$PROTECTION_SUFFIX")

    fun ensureDirectories() {
        StorageBucket.entries.forEach { dir(it) }
        // Nothing under here should ever be indexed by the media scanner or offered to a
        // gallery app; a .nomedia keeps thousands of loop segments out of the user's photos.
        File(root, ".nomedia").takeIf { !it.exists() }?.createNewFile()
    }

    /** True when [root] sits on a removable volume rather than built-in storage. */
    val isRemovable: Boolean
        get() = runCatching { Environment.isExternalStorageRemovable(root) }.getOrDefault(false)

    companion object {
        const val PROTECTION_SUFFIX = ".protected.json"
        private const val VIDEO_EXTENSION = "mp4"

        /**
         * Storage volumes Roadguard can record to, primary first.
         *
         * `getExternalFilesDirs` returns one entry per volume; entries can be null while a
         * card is being mounted, so nulls are filtered rather than indexed into.
         */
        fun availableVolumes(context: Context): List<File> =
            context.getExternalFilesDirs(null).filterNotNull()

        fun forVolume(context: Context, volumeId: String?): StorageLayout {
            val volumes = availableVolumes(context)
            val chosen = volumeId
                ?.let { id -> volumes.firstOrNull { volumeIdOf(it) == id } }
                ?: volumes.firstOrNull()
                ?: context.filesDir
            return StorageLayout(context, chosen)
        }

        /** A stable identifier for a volume, safe to persist in settings. */
        fun volumeIdOf(volumeRoot: File): String = volumeRoot.absolutePath

        /**
         * Segment file name: `RG_20260822-143015_000042.mp4`.
         *
         * Sorted lexicographically the names are also sorted chronologically, which makes
         * the on-disk listing usable even without the index, and the zero-padded sequence
         * number disambiguates two segments that start inside the same second.
         */
        fun segmentFileName(startedAtEpochMs: Long, sequence: Long, timeZoneOffsetFormatter: (Long) -> String): String =
            "RG_${timeZoneOffsetFormatter(startedAtEpochMs)}_${sequence.toString().padStart(6, '0')}.$VIDEO_EXTENSION"

        /** GPX track file name for a driving session. */
        fun trackFileName(startedAtEpochMs: Long, timestamp: (Long) -> String): String =
            "RG_${timestamp(startedAtEpochMs)}.gpx"

        fun diagnosticsFileName(atEpochMs: Long, timestamp: (Long) -> String): String =
            String.format(Locale.US, "roadguard-diagnostics-%s.txt", timestamp(atEpochMs))
    }
}

/** Top-level directories inside the Roadguard storage root. */
enum class StorageBucket(val dirName: String) {
    /** Every video segment, loop and protected alike. */
    Recordings("recordings"),

    /** Segments that could not be validated on startup, kept for inspection, never played. */
    Quarantine("quarantine"),

    /** GPX tracks, when the user asks for them. */
    Tracks("tracks"),

    /** Exported diagnostics reports. */
    Diagnostics("diagnostics"),

    /** Offline map data. Accounted separately so the user can see what maps cost. */
    Maps("maps"),
}
