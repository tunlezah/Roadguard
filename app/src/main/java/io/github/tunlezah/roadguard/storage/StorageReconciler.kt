package io.github.tunlezah.roadguard.storage

import android.util.Log
import io.github.tunlezah.roadguard.data.EventDao
import io.github.tunlezah.roadguard.data.EventState
import io.github.tunlezah.roadguard.data.SegmentDao
import io.github.tunlezah.roadguard.data.SegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Brings the index and the filesystem back into agreement, once, at start-up.
 *
 * Roadguard assumes the last run ended badly, because sooner or later it did. Five things can be
 * out of step, and each has a defined repair:
 *
 * | Situation | Cause | Repair |
 * |---|---|---|
 * | Row marked incomplete | killed mid-recording | inspect the file; index it if playable, quarantine it if not |
 * | Row with no file | user deleted it, or the card was swapped | drop the row |
 * | File with no row | crash between muxer finalise and index insert | inspect and adopt it |
 * | File with a protection sidecar but an unprotected row | crash between marking and indexing | re-apply protection |
 * | Event stuck awaiting post-roll | killed just after an impact | close it with whatever footage exists |
 *
 * The bias throughout is to **keep footage**. A file that cannot be verified is quarantined and
 * reported, never deleted: the segment that got truncated may be exactly the one the user needs.
 */
class StorageReconciler(
    private val storage: StorageManager,
    private val segments: SegmentDao,
    private val events: EventDao,
) {

    suspend fun reconcile(): ReconcileReport = withContext(Dispatchers.IO) {
        val layout = storage.layout
        layout.ensureDirectories()

        var repairedIncomplete = 0
        var quarantined = 0
        var droppedRows = 0
        var adoptedFiles = 0
        var reprotected = 0
        var closedEvents = 0
        val notes = mutableListOf<String>()

        // 1. Rows the last run never finished.
        for (entity in segments.incomplete()) {
            val file = storage.segmentFile(entity)
            when (val verdict = Mp4Inspector.inspect(file)) {
                is Mp4Verdict.Playable -> {
                    segments.update(
                        entity.copy(
                            isComplete = true,
                            durationMs = verdict.metadata.durationMs,
                            sizeBytes = file.length(),
                            widthPx = verdict.metadata.width.takeIf { it > 0 } ?: entity.widthPx,
                            heightPx = verdict.metadata.height.takeIf { it > 0 } ?: entity.heightPx,
                            rotationDegrees = verdict.metadata.rotationDegrees,
                        ),
                    )
                    repairedIncomplete++
                }

                Mp4Verdict.Missing -> {
                    segments.deleteById(entity.id)
                    droppedRows++
                }

                else -> {
                    val moved = storage.quarantine(file)
                    segments.deleteById(entity.id)
                    quarantined++
                    notes += "${entity.fileName}: ${verdict.summary}" +
                        if (moved != null) " (moved to quarantine)" else " (could not be moved)"
                }
            }
        }

        // 2. Rows whose files are gone.
        for (entity in segments.recent(limit = MAX_ROWS_CHECKED)) {
            if (!storage.segmentFile(entity).exists()) {
                segments.deleteById(entity.id)
                droppedRows++
            }
        }

        // 3. Files the index does not know about.
        val known = segments.allFileNames().toHashSet()
        val onDisk = layout.recordings.listFiles { file -> file.isFile && file.name.endsWith(".mp4") }
            ?: emptyArray()
        for (file in onDisk) {
            if (file.name in known) continue
            when (val verdict = Mp4Inspector.inspect(file)) {
                is Mp4Verdict.Playable -> {
                    val adopted = adopt(file, verdict)
                    if (adopted != null) adoptedFiles++
                }

                else -> {
                    val moved = storage.quarantine(file)
                    quarantined++
                    notes += "${file.name}: unindexed and ${verdict.summary}" +
                        if (moved != null) " (moved to quarantine)" else ""
                }
            }
        }

        // 4. Protection sidecars that outlived their index row's protected flag.
        for (entity in segments.recent(limit = MAX_ROWS_CHECKED)) {
            if (!entity.isProtected && storage.hasProtectionSidecar(entity.fileName)) {
                segments.protect(listOf(entity.id), reason = "recovered from protection marker", eventId = entity.eventId)
                reprotected++
            }
        }

        // 5. Events killed mid-protection.
        for (event in events.byState(EventState.AwaitingPostRoll.name)) {
            val overlapping = segments.overlapping(
                fromEpochMs = event.detectedAtEpochMs - event.preEventSeconds * 1_000L,
                toEpochMs = event.detectedAtEpochMs + event.postEventSeconds * 1_000L,
            )
            if (overlapping.isNotEmpty()) {
                segments.protect(overlapping.map { it.id }, reason = "event ${event.id}", eventId = event.id)
                overlapping.forEach {
                    storage.writeProtectionSidecar(it.fileName, "event ${event.id}", event.id, event.detectedAtEpochMs)
                }
            }
            // The post-roll can no longer be recorded, so the event is closed as incomplete
            // rather than left waiting for footage that will never arrive.
            events.update(event.copy(state = EventState.Incomplete.name))
            closedEvents++
            notes += "event ${event.id} was interrupted; protected ${overlapping.size} segment(s) that survived"
        }

        ReconcileReport(
            repairedIncomplete = repairedIncomplete,
            quarantined = quarantined,
            droppedRows = droppedRows,
            adoptedFiles = adoptedFiles,
            reprotected = reprotected,
            closedEvents = closedEvents,
            notes = notes,
        ).also { Log.i(TAG, "reconcile: $it") }
    }

    /**
     * Indexes a file found on disk.
     *
     * Fields the file cannot tell us (codec profile label, camera facing) are recorded as
     * "recovered" rather than guessed, so diagnostics never present a reconstruction as a
     * measurement.
     */
    private suspend fun adopt(file: File, verdict: Mp4Verdict.Playable): Long? = runCatching {
        segments.insert(
            SegmentEntity(
                fileName = file.name,
                bucket = StorageBucket.Recordings.dirName,
                startedAtEpochMs = file.lastModified() - verdict.metadata.durationMs,
                durationMs = verdict.metadata.durationMs,
                sizeBytes = file.length(),
                widthPx = verdict.metadata.width,
                heightPx = verdict.metadata.height,
                rotationDegrees = verdict.metadata.rotationDegrees,
                codec = verdict.metadata.mimeType ?: "recovered",
                bitrateBps = verdict.metadata.bitrateBps,
                frameRate = verdict.metadata.captureFrameRate?.toInt() ?: 0,
                hasAudio = verdict.metadata.hasAudio,
                cameraFacing = "recovered",
                profileLabel = "recovered",
                isProtected = storage.hasProtectionSidecar(file.name),
                protectionReason = if (storage.hasProtectionSidecar(file.name)) "recovered marker" else null,
                isComplete = true,
            ),
        )
    }.getOrElse {
        Log.w(TAG, "could not adopt ${file.name}", it)
        null
    }

    companion object {
        private const val TAG = "RoadguardReconcile"

        /**
         * Upper bound on rows examined per pass.
         *
         * A full reconcile of tens of thousands of rows would delay the start of recording,
         * which is the one thing start-up must not do. Older rows are checked lazily by loop
         * deletion, which already tolerates a missing file.
         */
        const val MAX_ROWS_CHECKED = 2_000
    }
}

data class ReconcileReport(
    val repairedIncomplete: Int,
    val quarantined: Int,
    val droppedRows: Int,
    val adoptedFiles: Int,
    val reprotected: Int,
    val closedEvents: Int,
    val notes: List<String>,
) {
    val changedAnything: Boolean
        get() = repairedIncomplete + quarantined + droppedRows + adoptedFiles + reprotected + closedEvents > 0

    fun summary(): String = if (!changedAnything) {
        "Storage was consistent"
      } else {
        buildList {
            if (repairedIncomplete > 0) add("$repairedIncomplete recovered")
            if (adoptedFiles > 0) add("$adoptedFiles re-indexed")
            if (reprotected > 0) add("$reprotected re-protected")
            if (quarantined > 0) add("$quarantined quarantined")
            if (droppedRows > 0) add("$droppedRows stale entries removed")
            if (closedEvents > 0) add("$closedEvents interrupted event(s) closed")
        }.joinToString(", ")
    }
}
