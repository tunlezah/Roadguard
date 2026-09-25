package io.github.tunlezah.roadguard.storage

import android.content.Context
import android.os.StatFs
import android.util.Log
import io.github.tunlezah.roadguard.data.SegmentDao
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.data.TripDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the recording directory: how much space is used, what may be deleted, and what to do
 * when things have gone wrong.
 *
 * The policy lives in [StorageBudget], which is pure and tested; this class is the thin
 * Android-facing part that measures the volume, asks the index for its sums, and carries out
 * the plan.
 */
class StorageManager(
    private val context: Context,
    private val segments: SegmentDao,
    private val trips: TripDao,
    /**
     * Where to start. Production leaves this null and uses the primary volume until the persisted
     * choice is applied by [useVolume]; tests hand in a folder of their own.
     */
    initialLayout: StorageLayout? = null,
) {
    private val _assessment = MutableStateFlow<StorageAssessment?>(null)
    val assessment: StateFlow<StorageAssessment?> = _assessment.asStateFlow()

    /**
     * The trip being recorded right now, or null.
     *
     * Set by [io.github.tunlezah.roadguard.trip.TripRepository] when a recording opens a trip. It
     * lives here because this is where trips are pruned: a trip that has just been opened has no
     * clips for a moment, and that moment must not look like an empty trip to be deleted.
     */
    @Volatile
    var activeTripId: Long? = null

    @Volatile
    var layout: StorageLayout = initialLayout ?: StorageLayout.forVolume(context, null)
        private set

    private val _layoutGeneration = MutableStateFlow(0)

    /**
     * Bumped whenever [useVolume] changes where files resolve to, so anything that has already
     * turned index rows into paths (the gallery, above all) knows to resolve them again. Without
     * it, a list built before the persisted volume was applied keeps reporting every file as
     * missing until something else happens to rebuild it.
     */
    val layoutGeneration: StateFlow<Int> = _layoutGeneration.asStateFlow()

    /**
     * True when the volume the user chose was not mounted at the last [useVolume], and [layout]
     * is the primary-volume fallback. The reconciler must not judge rows against the fallback:
     * every file on the chosen card would look deleted.
     */
    @Volatile
    var requestedVolumeMissing: Boolean = false
        private set

    /**
     * Names of the segment and track files this process has created.
     *
     * Start-up reconciliation repairs what the *previous* run left behind. It must never judge a
     * file this run is still writing: an MP4 has no index until it is finalised, so an in-progress
     * segment looks exactly like a truncated one and would be quarantined out from under the
     * recorder, and a new track looks like an orphan and would be deleted. The recorder normally
     * waits for reconciliation before it writes anything; this set is what keeps the two apart if
     * that wait ever times out.
     */
    private val createdThisProcess: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** True when [fileName] was created by this process rather than inherited from an earlier run. */
    fun isFromThisProcess(fileName: String): Boolean = fileName in createdThisProcess

    fun useVolume(volumeId: String?) {
        val available = StorageLayout.availableVolumes(context)
        // A chosen card that is not mounted; or, rarer and seen right after boot, no external
        // volume listed at all, in which case the layout falls back to private internal storage.
        // Either way nothing recorded is where the index says it is: the reconciler must not
        // judge rows against this layout, and the recorder must not write into it.
        requestedVolumeMissing = if (volumeId != null) {
            available.none { StorageLayout.volumeIdOf(it) == volumeId }
        } else {
            available.isEmpty()
        }
        layout = StorageLayout.forVolume(context, volumeId)
        layout.ensureDirectories()
        _layoutGeneration.value += 1
    }

    /**
     * Forces [file]'s contents onto the storage medium.
     *
     * The muxer closes a finished clip without syncing it, so its last seconds -- and the index at
     * its very end, without which no player can open it -- can sit in the kernel's write cache for
     * up to half a minute. A phone that loses power in that window is left with a row saying the
     * clip is complete and a file that is not. Called once per clip, off the main thread, before
     * its row is marked complete. Best effort: a volume that refuses is logged, not fatal.
     */
    fun flushToDisk(file: File): Boolean = runCatching {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
        true
    }.onFailure { Log.w(TAG, "could not flush ${file.name} to disk", it) }.getOrDefault(false)

    /** Volumes the user may choose between, with their sizes, for the storage screen. */
    fun volumeOptions(): List<StorageVolumeOption> =
        StorageLayout.availableVolumes(context).mapIndexed { index, root ->
            val stats = statsFor(root)
            StorageVolumeOption(
                id = StorageLayout.volumeIdOf(root),
                label = if (index == 0) "Internal storage" else "Removable storage",
                isRemovable = runCatching { android.os.Environment.isExternalStorageRemovable(root) }
                    .getOrDefault(index > 0),
                totalBytes = stats.first,
                freeBytes = stats.second,
                isSelected = StorageLayout.volumeIdOf(root) == StorageLayout.volumeIdOf(layout.root),
            )
        }

    /** Recomputes the assessment. Cheap enough to call once per segment, not per frame. */
    suspend fun refresh(requestedBudgetBytes: Long): StorageAssessment = withContext(Dispatchers.IO) {
        val (total, free) = statsFor(layout.root)
        val loopBytes = segments.loopBytes()
        val protectedBytes = segments.protectedBytes()
        val mapBytes = directorySize(layout.maps)
        val rate = segments.measuredBytesPerSecond()
        StorageBudget.evaluate(
            requestedBudgetBytes = requestedBudgetBytes,
            loopUsedBytes = loopBytes,
            protectedBytes = protectedBytes,
            mapBytes = mapBytes,
            freeBytes = free,
            volumeTotalBytes = total,
            measuredBytesPerSecond = rate,
        ).also { _assessment.value = it }
    }

    /**
     * Frees space by deleting the oldest unprotected segments.
     *
     * Protected segments are excluded by the SQL query itself, not filtered afterwards, so
     * there is no code path in which protected footage can reach the deleter. Every deletion
     * also removes the row, and a file that has already vanished is treated as success.
     */
    suspend fun runCleanup(assessment: StorageAssessment): CleanupOutcome = withContext(Dispatchers.IO) {
        if (!assessment.needsCleanup) return@withContext CleanupOutcome(0, 0L)

        val candidates = segments.oldestUnprotected(limit = CLEANUP_BATCH)
            .map { CleanupCandidate(it.id, it.sizeBytes, it.startedAtEpochMs) }
        val plan = StorageBudget.planCleanup(candidates, assessment.bytesToFree)

        var deletedFiles = 0
        var freedBytes = 0L
        val touchedTrips = LinkedHashSet<Long>()
        for (id in plan.segmentIds) {
            val entity = segments.byId(id) ?: continue
            // Belt and braces: never delete something now marked protected, even though the
            // query excluded it, because protection can be applied between query and delete.
            if (entity.isProtected) continue
            // The sidecar is the copy of the protection mark that survives the index being wrong.
            // If it exists the clip is protected, whatever the row says: restore the row and keep
            // the file. Without this, a row that lost its flag -- a crash between the two writes,
            // or anything else that ever wrote back a stale row -- would let the loop delete
            // footage the user or an impact had protected.
            if (hasProtectionSidecar(entity.fileName)) {
                runCatching {
                    segments.protect(listOf(entity.id), reason = "recovered from protection marker", eventId = entity.eventId)
                }.onFailure { Log.w(TAG, "could not restore protection for ${entity.fileName}", it) }
                continue
            }
            val file = layout.file(StorageBucket.entries.first { it.dirName == entity.bucket }, entity.fileName)
            val existed = file.exists()
            if (!existed || file.delete()) {
                segments.deleteById(id)
                entity.tripId?.let(touchedTrips::add)
                if (existed) {
                    deletedFiles++
                    freedBytes += entity.sizeBytes
                }
            } else {
                Log.w(TAG, "could not delete ${entity.fileName}; leaving it indexed")
            }
        }
        // A trip whose last clip has just left the loop takes its track with it.
        pruneEmptyTrips(touchedTrips)
        CleanupOutcome(deletedFiles, freedBytes)
    }

    /**
     * Deletes every trip in [tripIds] that has no clips left, together with its GPX track.
     *
     * The rule is the product owner's: a track lives exactly as long as the footage it belongs
     * to. The trip being recorded is never a candidate, however many clips it has.
     *
     * @return how many trips were removed.
     */
    suspend fun pruneEmptyTrips(tripIds: Collection<Long>): Int = withContext(Dispatchers.IO) {
        var pruned = 0
        for (tripId in tripIds.toSet()) {
            if (tripId == activeTripId) continue
            if (segments.countForTrip(tripId) > 0) continue
            val trip = trips.byId(tripId) ?: continue
            trip.trackFileName?.let { deleteTrack(it) }
            trips.deleteById(tripId)
            pruned++
        }
        pruned
    }

    /** Creates the file for a trip's GPX track. Never overwrites an existing file. */
    fun createTrackFile(startedAtEpochMs: Long): File {
        layout.ensureDirectories()
        var candidate = StorageLayout.trackFileName(startedAtEpochMs, ::fileTimestamp)
        var attempt = 0
        while (File(layout.tracks, candidate).exists() && attempt < 100) {
            attempt++
            candidate = StorageLayout.trackFileName(startedAtEpochMs + attempt * 1_000L, ::fileTimestamp)
        }
        createdThisProcess += candidate
        return File(layout.tracks, candidate)
    }

    fun trackFile(fileName: String): File = layout.file(StorageBucket.Tracks, fileName)

    fun deleteTrack(fileName: String): Boolean {
        val file = trackFile(fileName)
        return !file.exists() || file.delete()
    }

    /** Every GPX file in the tracks directory, for start-up reconciliation. */
    fun trackFiles(): List<File> =
        layout.tracks.listFiles { file -> file.isFile && file.name.endsWith(".gpx", ignoreCase = true) }
            ?.toList()
            .orEmpty()

    /**
     * Chooses the file for the next segment. Never overwrites an existing file.
     *
     * Does disk I/O (directory creation and existence checks), so callers keep it off the main
     * thread.
     */
    fun createSegmentFile(startedAtEpochMs: Long, sequence: Long): File {
        layout.ensureDirectories()
        var candidate = StorageLayout.segmentFileName(startedAtEpochMs, sequence, ::fileTimestamp)
        var attempt = 0
        while (File(layout.recordings, candidate).exists() && attempt < 100) {
            attempt++
            candidate = StorageLayout.segmentFileName(startedAtEpochMs, sequence + attempt, ::fileTimestamp)
        }
        createdThisProcess += candidate
        return File(layout.recordings, candidate)
    }

    fun segmentFile(entity: SegmentEntity): File =
        layout.file(StorageBucket.entries.first { it.dirName == entity.bucket }, entity.fileName)

    /**
     * Writes the protection sidecar.
     *
     * Written before the index is updated so that a crash can leave a marked file with no index
     * row -- which the reconciler repairs -- rather than an index row for an unmarked file,
     * which loop deletion could later undo.
     */
    fun writeProtectionSidecar(fileName: String, reason: String, eventId: Long?, atEpochMs: Long) {
        runCatching {
            layout.protectionSidecar(fileName).writeText(
                buildString {
                    appendLine("{")
                    appendLine("""  "file": ${quote(fileName)},""")
                    appendLine("""  "reason": ${quote(reason)},""")
                    appendLine("""  "eventId": ${eventId ?: "null"},""")
                    appendLine("""  "protectedAtEpochMs": $atEpochMs""")
                    appendLine("}")
                },
            )
        }.onFailure { Log.w(TAG, "could not write protection sidecar for $fileName", it) }
    }

    fun removeProtectionSidecar(fileName: String) {
        runCatching { layout.protectionSidecar(fileName).delete() }
    }

    fun hasProtectionSidecar(fileName: String): Boolean = layout.protectionSidecar(fileName).exists()

    fun quarantine(file: File): File? {
        layout.quarantine.mkdirs()
        // rename(2) silently replaces an existing target, which would destroy a file quarantined
        // earlier under the same name. Quarantine exists to keep footage, so find a free name.
        var target = File(layout.quarantine, file.name)
        var attempt = 0
        while (target.exists() && attempt < 100) {
            attempt++
            target = File(layout.quarantine, "${file.nameWithoutExtension}.$attempt.${file.extension}")
        }
        return if (file.renameTo(target)) target else null
    }

    fun mapBytes(): Long = directorySize(layout.maps)

    fun freeBytes(): Long = statsFor(layout.root).second

    private fun statsFor(root: File): Pair<Long, Long> = runCatching {
        val stats = StatFs(root.absolutePath)
        stats.blockCountLong * stats.blockSizeLong to stats.availableBlocksLong * stats.blockSizeLong
    }.getOrDefault(0L to 0L)

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val TAG = "RoadguardStorage"

        /** How many candidates a single cleanup pass considers. */
        const val CLEANUP_BATCH = 200

        private val FILE_TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

        fun fileTimestamp(epochMs: Long): String = synchronized(FILE_TIMESTAMP) {
            FILE_TIMESTAMP.format(Date(epochMs))
        }
    }
}

data class CleanupOutcome(val filesDeleted: Int, val bytesFreed: Long)

data class StorageVolumeOption(
    val id: String,
    val label: String,
    val isRemovable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
    val isSelected: Boolean,
)
