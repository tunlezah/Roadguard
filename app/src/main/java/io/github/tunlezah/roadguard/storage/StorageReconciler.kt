package io.github.tunlezah.roadguard.storage

import android.util.Log
import io.github.tunlezah.roadguard.data.EventDao
import io.github.tunlezah.roadguard.data.EventState
import io.github.tunlezah.roadguard.data.SegmentDao
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.trip.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Brings the index and the filesystem back into agreement, once, at start-up.
 *
 * Roadguard assumes the last run ended badly, because sooner or later it did. Nine things can be
 * out of step, and each has a defined repair:
 *
 * | Situation | Cause | Repair |
 * |---|---|---|
 * | Row marked incomplete | killed mid-recording | inspect the file; index it if playable, quarantine it if not |
 * | Newest finished clip with no index | power lost before the file reached the disk | quarantine it |
 * | Row with no file | user deleted it, or the card was swapped | drop the row -- but only while other recordings are present |
 * | File with no row | crash between muxer finalise and index insert | inspect and adopt it |
 * | File with a protection sidecar but an unprotected row | crash between marking and indexing | re-apply protection |
 * | Event stuck awaiting post-roll | killed just after an impact | close it with whatever footage exists |
 * | Clip with no trip | recorded before trips existed, or adopted above | group by the gap rule and assign |
 * | Trip left recording | killed mid-drive | close it; its end is the last clip that finalised |
 * | Trip with no clips | its footage left the loop while the app was not running | drop the row and its track |
 * | GPX file with no trip | leftover of a dropped trip | delete it |
 *
 * The bias throughout is to **keep footage**. A file that cannot be verified is quarantined and
 * reported, never deleted: the segment that got truncated may be exactly the one the user needs.
 * Tracks are the one thing that is deleted, and only once the footage they described is gone.
 *
 * ### The index is never emptied on the strength of an empty folder
 *
 * Every repair compares the index with a directory listing. That listing is only meaningful when
 * the directory is the one the rows were written into and it is readable: a card still mounting
 * after boot, external storage not yet served right after the phone is unlocked, or a volume
 * switched in Settings all produce a listing with nothing in it, and acting on that used to drop
 * every row -- and with them the trips, the tracks and the protection marks -- for footage that
 * was sitting on the disk the whole time. So a folder that cannot be listed stops the pass, and a
 * folder holding no earlier recording keeps every row whose file is missing, shown as missing in
 * the list, until a start-up that can tell the difference.
 */
class StorageReconciler(
    private val storage: StorageManager,
    private val segments: SegmentDao,
    private val events: EventDao,
    private val trips: TripRepository,
) {

    /**
     * @param sessionStartedAtEpochMs when this process started. Events detected at or after it
     *   belong to this run, not to the one being repaired, and are left to the recorder. Files are
     *   told apart exactly instead, by [StorageManager.isFromThisProcess].
     */
    suspend fun reconcile(sessionStartedAtEpochMs: Long = Long.MAX_VALUE): ReconcileReport = withContext(Dispatchers.IO) {
        // With the chosen volume unmounted, every row would compare against the fallback volume
        // and look deleted. Dropping thousands of index rows because a card was slow to mount
        // (or briefly ejected) is exactly the kind of loss this pass exists to prevent, so it
        // stands down instead.
        if (storage.requestedVolumeMissing) {
            return@withContext ReconcileReport.skipped("the chosen recording volume is not mounted; nothing was checked")
                .also { Log.w(TAG, "reconcile skipped: recording volume is not mounted") }
        }

        val layout = storage.layout
        layout.ensureDirectories()

        // A folder that cannot be listed -- a volume still coming up, a card mounted read-only
        // after a filesystem error -- would make every row look deleted. Nothing is judged.
        val onDisk = layout.recordings.listFiles { file -> file.isFile && file.name.endsWith(".mp4", ignoreCase = true) }
            ?: return@withContext ReconcileReport.skipped("the recordings folder could not be read; nothing was checked")
                .also { Log.w(TAG, "reconcile skipped: recordings folder could not be listed") }

        // Rows whose files are missing are dropped only while some earlier recording is present.
        // A folder with none is far more likely to be the wrong or not-yet-ready volume than a
        // user who deleted every clip by hand, and a stale row costs nothing but a "missing" line.
        val mayDropMissing = onDisk.any { !storage.isFromThisProcess(it.name) }

        var repairedIncomplete = 0
        var quarantined = 0
        var droppedRows = 0
        var keptMissing = 0
        var adoptedFiles = 0
        var reprotected = 0
        var closedEvents = 0
        val notes = mutableListOf<String>()

        // 1. Rows the last run never finished. A row this run created is simply a segment that is
        // still being recorded: its MP4 has no index yet, so it would look truncated.
        for (entity in segments.incomplete()) {
            if (storage.isFromThisProcess(entity.fileName)) continue
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

                // Kept rows are counted once, in step 2, which sees every row.
                Mp4Verdict.Missing -> if (mayDropMissing) {
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

        // 1b. The newest finished clips. The muxer writes a clip's index last and closes the file
        // without syncing it, so a phone that loses power within about half a minute of a clip
        // finishing can keep a row that says "complete" for a file whose end never reached the
        // disk. The recorder now syncs each clip as it finishes; this catches clips recorded
        // before it did, and a failure that lands inside that sync. Only the structure is read: an
        // index box present means the file is whole, and a passing metadata hiccup cannot send a
        // good clip to quarantine.
        for (entity in segments.recent(limit = RECHECK_NEWEST)) {
            if (!entity.isComplete || storage.isFromThisProcess(entity.fileName)) continue
            val file = storage.segmentFile(entity)
            if (!file.exists()) continue
            val boxes = runCatching { Mp4Inspector.topLevelBoxes(file) }.getOrNull() ?: continue
            if (boxes.any { it.type == "moov" }) continue
            val moved = storage.quarantine(file)
            segments.deleteById(entity.id)
            quarantined++
            notes += "${entity.fileName}: finished clip with no index, probably lost with the power" +
                if (moved != null) " (moved to quarantine)" else " (could not be moved)"
        }

        // 2. Rows whose files are gone. This run's newest row is indexed a moment before the
        // recorder creates its file, so it is skipped rather than mistaken for a deleted clip.
        for (entity in segments.recent(limit = MAX_ROWS_CHECKED)) {
            if (storage.isFromThisProcess(entity.fileName)) continue
            if (storage.segmentFile(entity).exists()) continue
            if (mayDropMissing) {
                segments.deleteById(entity.id)
                droppedRows++
            } else {
                keptMissing++
            }
        }
        if (keptMissing > 0) {
            notes += "$keptMissing indexed clip(s) were not found, and no earlier recording is on this volume; " +
                "kept in case the storage was not ready"
            Log.w(TAG, "$keptMissing indexed file(s) missing with an empty recordings folder; keeping their rows")
        }

        // 3. Files the index does not know about. The listing predates the steps above, which
        // may have moved some of what it names to quarantine.
        val known = segments.allFileNames().toHashSet()
        for (file in onDisk) {
            if (file.name in known) continue
            if (storage.isFromThisProcess(file.name)) continue
            if (!file.exists()) continue
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

        // 5. Events killed mid-protection. An event from this run is still collecting its post-roll.
        for (event in events.byState(EventState.AwaitingPostRoll.name)) {
            if (event.detectedAtEpochMs >= sessionStartedAtEpochMs) continue
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

        // 6-9. Trips: group what has none, close what was interrupted, drop what is empty, and
        // remove the tracks nothing refers to. Each step is guarded so a failure in trips can
        // never undo the footage repairs above.
        val tripsAssembled = runCatching { trips.assignUnassigned() }
            .onFailure { Log.w(TAG, "could not assign clips to trips", it) }.getOrDefault(0)
        val tripsClosed = runCatching { trips.closeInterrupted() }
            .onFailure { Log.w(TAG, "could not close interrupted trips", it) }.getOrDefault(0)
        val tripsPruned = runCatching { trips.pruneEmpty() }
            .onFailure { Log.w(TAG, "could not prune empty trips", it) }.getOrDefault(0)
        val tracksDeleted = runCatching { trips.deleteOrphanTracks() }
            .onFailure { Log.w(TAG, "could not delete orphan tracks", it) }.getOrDefault(0)

        ReconcileReport(
            repairedIncomplete = repairedIncomplete,
            quarantined = quarantined,
            droppedRows = droppedRows,
            adoptedFiles = adoptedFiles,
            reprotected = reprotected,
            closedEvents = closedEvents,
            notes = notes,
            tripsAssembled = tripsAssembled,
            tripsClosed = tripsClosed,
            tripsPruned = tripsPruned,
            tracksDeleted = tracksDeleted,
            keptMissing = keptMissing,
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

        /** How many of the newest finished clips are checked for a lost tail. A few box reads each. */
        const val RECHECK_NEWEST = 3
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
    val tripsAssembled: Int = 0,
    val tripsClosed: Int = 0,
    val tripsPruned: Int = 0,
    val tracksDeleted: Int = 0,
    /** Rows whose files were missing but were kept, because the folder held no earlier recording. */
    val keptMissing: Int = 0,
) {
    val changedAnything: Boolean
        get() = repairedIncomplete + quarantined + droppedRows + adoptedFiles + reprotected + closedEvents +
            tripsAssembled + tripsClosed + tripsPruned + tracksDeleted > 0

    fun summary(): String = if (!changedAnything) {
        notes.firstOrNull() ?: "Storage was consistent"
      } else {
        buildList {
            if (repairedIncomplete > 0) add("$repairedIncomplete recovered")
            if (adoptedFiles > 0) add("$adoptedFiles re-indexed")
            if (reprotected > 0) add("$reprotected re-protected")
            if (quarantined > 0) add("$quarantined quarantined")
            if (droppedRows > 0) add("$droppedRows stale entries removed")
            if (keptMissing > 0) add("$keptMissing missing file(s) kept in the index")
            if (closedEvents > 0) add("$closedEvents interrupted event(s) closed")
            if (tripsAssembled > 0) add("$tripsAssembled clip(s) grouped into trips")
            if (tripsClosed > 0) add("$tripsClosed interrupted trip(s) closed")
            if (tripsPruned > 0) add("$tripsPruned empty trip(s) removed")
            if (tracksDeleted > 0) add("$tracksDeleted orphan track(s) deleted")
        }.joinToString(", ")
    }

    companion object {
        /** A pass that checked nothing, and says why. */
        fun skipped(reason: String): ReconcileReport = ReconcileReport(
            repairedIncomplete = 0,
            quarantined = 0,
            droppedRows = 0,
            adoptedFiles = 0,
            reprotected = 0,
            closedEvents = 0,
            notes = listOf(reason),
        )
    }
}
