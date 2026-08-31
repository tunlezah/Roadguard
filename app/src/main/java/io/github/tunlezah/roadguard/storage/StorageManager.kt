package io.github.tunlezah.roadguard.storage

import android.content.Context
import android.os.StatFs
import android.util.Log
import io.github.tunlezah.roadguard.data.SegmentDao
import io.github.tunlezah.roadguard.data.SegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
) {
    private val _assessment = MutableStateFlow<StorageAssessment?>(null)
    val assessment: StateFlow<StorageAssessment?> = _assessment.asStateFlow()

    @Volatile
    var layout: StorageLayout = StorageLayout.forVolume(context, null)
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

    fun useVolume(volumeId: String?) {
        requestedVolumeMissing = volumeId != null &&
            StorageLayout.availableVolumes(context).none { StorageLayout.volumeIdOf(it) == volumeId }
        layout = StorageLayout.forVolume(context, volumeId)
        layout.ensureDirectories()
        _layoutGeneration.value += 1
    }

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
        for (id in plan.segmentIds) {
            val entity = segments.byId(id) ?: continue
            // Belt and braces: never delete something now marked protected, even though the
            // query excluded it, because protection can be applied between query and delete.
            if (entity.isProtected) continue
            val file = layout.file(StorageBucket.entries.first { it.dirName == entity.bucket }, entity.fileName)
            val existed = file.exists()
            if (!existed || file.delete()) {
                segments.deleteById(id)
                if (existed) {
                    deletedFiles++
                    freedBytes += entity.sizeBytes
                }
            } else {
                Log.w(TAG, "could not delete ${entity.fileName}; leaving it indexed")
            }
        }
        CleanupOutcome(deletedFiles, freedBytes)
    }

    /** Creates the file for the next segment. Never overwrites an existing file. */
    fun createSegmentFile(startedAtEpochMs: Long, sequence: Long): File {
        layout.ensureDirectories()
        var candidate = StorageLayout.segmentFileName(startedAtEpochMs, sequence, ::fileTimestamp)
        var attempt = 0
        while (File(layout.recordings, candidate).exists() && attempt < 100) {
            attempt++
            candidate = StorageLayout.segmentFileName(startedAtEpochMs, sequence + attempt, ::fileTimestamp)
        }
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
        val target = File(layout.quarantine, file.name)
        layout.quarantine.mkdirs()
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
