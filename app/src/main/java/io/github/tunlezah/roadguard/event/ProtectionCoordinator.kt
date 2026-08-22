package io.github.tunlezah.roadguard.event

import android.util.Log
import io.github.tunlezah.roadguard.data.EventDao
import io.github.tunlezah.roadguard.data.EventEntity
import io.github.tunlezah.roadguard.data.EventKind
import io.github.tunlezah.roadguard.data.EventState
import io.github.tunlezah.roadguard.data.SegmentDao
import io.github.tunlezah.roadguard.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns a detected or requested event into protected footage.
 *
 * The order of operations is the whole point, and it is chosen so that a crash at any step
 * leaves footage protected rather than lost:
 *
 *  1. the event row is written **first**, so a crash immediately afterwards leaves a record that
 *     start-up reconciliation can finish;
 *  2. every segment already on disk that overlaps the protection window is marked -- sidecar
 *     file first, then index -- so the mark survives loss of the index;
 *  3. the event stays open, and every segment that finalises afterwards is offered to it until
 *     the post-event window is covered;
 *  4. only then is the event closed.
 *
 * Recording is never interrupted by any of this: protection is metadata plus a small sidecar
 * file, and no video file is ever moved or rewritten.
 */
class ProtectionCoordinator(
    private val segments: SegmentDao,
    private val events: EventDao,
    private val storage: StorageManager,
) {
    private val mutex = Mutex()
    private val open = mutableListOf<OpenEvent>()

    private val _lastProtection = MutableStateFlow<ProtectionResult?>(null)

    /** The most recent protection outcome, for the UI's confirmation message. */
    val lastProtection: StateFlow<ProtectionResult?> = _lastProtection.asStateFlow()

    /** Events still waiting for post-event footage. */
    val openEventCount: Int get() = open.size

    /**
     * Protects footage around [atEpochMs].
     *
     * @param inProgress timing of the segment currently being written, if any, so an event that
     *   lands microseconds before a rollover still claims the segment that is closing.
     */
    suspend fun protect(
        kind: EventKind,
        atEpochMs: Long,
        preSeconds: Int,
        postSeconds: Int,
        confidence: Float,
        detection: DetectedEvent?,
        inProgress: SegmentTiming?,
    ): ProtectionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val window = ProtectionPlanner.windowFor(atEpochMs, preSeconds, postSeconds)

            val eventId = events.insert(
                EventEntity(
                    detectedAtEpochMs = atEpochMs,
                    kind = kind.name,
                    confidence = confidence,
                    peakG = detection?.features?.peakG,
                    deltaSpeedKmh = detection?.context?.deltaSpeedKmh,
                    speedBeforeKmh = detection?.context?.speedBeforeKmh,
                    latitude = detection?.context?.latitude,
                    longitude = detection?.context?.longitude,
                    preEventSeconds = preSeconds,
                    postEventSeconds = postSeconds,
                    state = EventState.AwaitingPostRoll.name,
                    note = detection?.describe(),
                ),
            )

            val known = segments.overlapping(window.fromEpochMs, window.toEpochMs)
                .map { SegmentTiming(it.id, it.startedAtEpochMs, it.durationMs) }
            val candidates = known + listOfNotNull(inProgress?.takeIf { timing -> known.none { it.id == timing.id } })
            val decision = ProtectionPlanner.plan(window, candidates, atEpochMs)

            applyProtection(decision.segmentIds, "event $eventId", eventId, atEpochMs)

            if (decision.isComplete) {
                events.markProtected(eventId)
            } else {
                open += OpenEvent(eventId, window, decision.segmentIds.toMutableSet())
            }

            ProtectionResult(
                eventId = eventId,
                kind = kind,
                segmentsProtected = decision.segmentIds.size,
                awaitingPostRoll = !decision.isComplete,
                window = window,
            ).also {
                _lastProtection.value = it
                Log.i(TAG, "protected ${it.segmentsProtected} segment(s) for event $eventId")
            }
        }
    }

    /**
     * Offers a newly finalised segment to every open event.
     *
     * Called from the recorder's finalise path, so post-event footage is claimed the instant it
     * exists rather than on a timer that a process death could miss.
     */
    suspend fun onSegmentFinalised(timing: SegmentTiming, fileName: String) = withContext(Dispatchers.IO) {
        if (open.isEmpty()) return@withContext
        mutex.withLock {
            val completed = mutableListOf<OpenEvent>()
            for (event in open) {
                if (!timing.overlaps(event.window)) continue
                if (event.protectedIds.add(timing.id)) {
                    applyProtection(listOf(timing.id), "event ${event.eventId}", event.eventId, timing.startedAtEpochMs)
                }
                if (timing.endEpochMs >= event.window.toEpochMs) completed += event
            }
            completed.forEach { event ->
                events.markProtected(event.eventId)
                open.remove(event)
                Log.i(TAG, "event ${event.eventId} fully protected (${event.protectedIds.size} segments)")
            }
        }
    }

    /** Removes protection from one segment, at explicit user request. */
    suspend fun unprotect(segmentId: Long) = withContext(Dispatchers.IO) {
        val entity = segments.byId(segmentId) ?: return@withContext
        storage.removeProtectionSidecar(entity.fileName)
        segments.unprotect(segmentId)
    }

    private suspend fun applyProtection(ids: List<Long>, reason: String, eventId: Long?, atEpochMs: Long) {
        if (ids.isEmpty()) return
        // Sidecar first: it is the copy that survives the index being lost.
        ids.forEach { id ->
            segments.byId(id)?.let { entity ->
                storage.writeProtectionSidecar(entity.fileName, reason, eventId, atEpochMs)
            }
        }
        segments.protect(ids, reason, eventId)
    }

    private data class OpenEvent(
        val eventId: Long,
        val window: ProtectionWindow,
        val protectedIds: MutableSet<Long>,
    )

    companion object {
        private const val TAG = "RoadguardProtect"
    }
}

data class ProtectionResult(
    val eventId: Long,
    val kind: EventKind,
    val segmentsProtected: Int,
    val awaitingPostRoll: Boolean,
    val window: ProtectionWindow,
) {
    fun message(): String = when {
        segmentsProtected == 0 -> "Nothing to protect yet - recording has only just started"
        awaitingPostRoll -> "Protected $segmentsProtected clip${plural()} - still saving what happens next"
        else -> "Protected $segmentsProtected clip${plural()}"
    }

    private fun plural() = if (segmentsProtected == 1) "" else "s"
}
