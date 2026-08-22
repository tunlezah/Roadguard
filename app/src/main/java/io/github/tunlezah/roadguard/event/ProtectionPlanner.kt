package io.github.tunlezah.roadguard.event

/**
 * Works out exactly which footage an event must protect.
 *
 * The hard part of pre/post-event protection is not the arithmetic, it is the boundaries:
 *
 *  * an impact can land microseconds before a segment rolls over, so **both** the closing and
 *    the opening segment must be protected;
 *  * the post-event footage usually does not exist yet when the event fires, so protection has
 *    to stay open and claim segments as they finalise; and
 *  * the process can die between the event and the end of the post-roll, so the state has to
 *    be reconstructible from the database alone.
 *
 * This class is pure so all of that can be tested exhaustively, including the boundary cases,
 * without a camera. [ProtectionCoordinator] applies its decisions.
 */
object ProtectionPlanner {

    /** The wall-clock window an event protects. */
    fun windowFor(eventAtEpochMs: Long, preSeconds: Int, postSeconds: Int): ProtectionWindow =
        ProtectionWindow(
            fromEpochMs = eventAtEpochMs - preSeconds * 1_000L,
            toEpochMs = eventAtEpochMs + postSeconds * 1_000L,
        )

    /**
     * Selects the segments that overlap [window].
     *
     * Overlap, not containment: a 3-minute segment that merely clips the first second of the
     * pre-roll still holds footage the event needs.
     *
     * @param segments candidate segments; only their timing matters here.
     * @param nowEpochMs current time, used to decide whether the window is still open.
     */
    fun plan(
        window: ProtectionWindow,
        segments: List<SegmentTiming>,
        nowEpochMs: Long,
    ): ProtectionDecision {
        val overlapping = segments
            .filter { it.overlaps(window) }
            .sortedBy { it.startedAtEpochMs }

        // The window is satisfied once recorded footage reaches its end. An in-progress
        // segment counts only up to "now", because the rest has not been recorded yet.
        val covered = overlapping.maxOfOrNull { timing ->
            if (timing.isInProgress) nowEpochMs else timing.endEpochMs
        } ?: Long.MIN_VALUE

        return ProtectionDecision(
            segmentIds = overlapping.map { it.id },
            window = window,
            isComplete = covered >= window.toEpochMs,
            coveredToEpochMs = if (covered == Long.MIN_VALUE) null else covered,
        )
    }
}

data class ProtectionWindow(val fromEpochMs: Long, val toEpochMs: Long) {
    init {
        require(toEpochMs >= fromEpochMs) { "protection window must not run backwards" }
    }

    val durationMs: Long get() = toEpochMs - fromEpochMs
}

/** The timing facts about a segment that protection planning needs. */
data class SegmentTiming(
    val id: Long,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    /** True for the segment currently being written, whose duration is still growing. */
    val isInProgress: Boolean = false,
) {
    val endEpochMs: Long get() = startedAtEpochMs + durationMs

    fun overlaps(window: ProtectionWindow): Boolean {
        // An in-progress segment has no meaningful end yet, so treat it as extending forever.
        val end = if (isInProgress) Long.MAX_VALUE else endEpochMs
        return startedAtEpochMs < window.toEpochMs && end > window.fromEpochMs
    }
}

/**
 * @property isComplete true when every second of [window] is held by a finished segment, so
 *   the event can be closed. While false the coordinator must keep claiming new segments.
 * @property coveredToEpochMs how far protection currently reaches, for diagnostics.
 */
data class ProtectionDecision(
    val segmentIds: List<Long>,
    val window: ProtectionWindow,
    val isComplete: Boolean,
    val coveredToEpochMs: Long?,
)
