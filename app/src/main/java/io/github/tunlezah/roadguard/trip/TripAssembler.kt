package io.github.tunlezah.roadguard.trip

/**
 * Decides which clips belong to the same drive.
 *
 * The rule is the one a person would apply looking at the timestamps: clips that follow each other
 * with at most a short gap are one trip, and a jump in the recording times means the car stopped
 * and a new trip began. A rollover gap is milliseconds, a crash-and-relaunch is seconds, and a
 * fuel stop with recording off is many minutes, so [MAX_GAP_MS] sits comfortably between them.
 *
 * The same rule is used live -- a recording that starts within the gap of the previous trip's end
 * continues that trip -- and at start-up, when clips recorded before trips existed are grouped. Pure,
 * so both paths are held to the same tested behaviour.
 */
object TripAssembler {

    /** Longest silence between clips that still counts as the same drive. */
    const val MAX_GAP_MS = 2 * 60 * 1_000L

    /** The three timing facts about a clip that grouping needs. */
    data class Clip(val id: Long, val startedAtEpochMs: Long, val durationMs: Long) {
        val endedAtEpochMs: Long get() = startedAtEpochMs + durationMs.coerceAtLeast(0L)
    }

    /** One group of clips, in chronological order. */
    data class Group(val clips: List<Clip>) {
        val startedAtEpochMs: Long get() = clips.first().startedAtEpochMs
        val endedAtEpochMs: Long get() = clips.maxOf { it.endedAtEpochMs }
        val ids: List<Long> get() = clips.map { it.id }
    }

    /**
     * True when a clip starting at [nextStartEpochMs] belongs to a trip that ended at
     * [previousEndEpochMs].
     *
     * A start *before* the previous end is tolerated too: the end of a clip is measured by the
     * recorder and its start by the wall clock, and the two can disagree by a little, or the clock
     * can be set back. What breaks a trip is a long silence, not a small overlap.
     */
    fun continues(previousEndEpochMs: Long, nextStartEpochMs: Long, maxGapMs: Long = MAX_GAP_MS): Boolean =
        nextStartEpochMs - previousEndEpochMs <= maxGapMs

    /** Groups [clips] into trips. Input order does not matter; the output is chronological. */
    fun group(clips: Collection<Clip>, maxGapMs: Long = MAX_GAP_MS): List<Group> {
        val ordered = clips.sortedBy { it.startedAtEpochMs }
        val groups = mutableListOf<Group>()
        var current = mutableListOf<Clip>()
        var currentEnd = Long.MIN_VALUE
        for (clip in ordered) {
            if (current.isNotEmpty() && !continues(currentEnd, clip.startedAtEpochMs, maxGapMs)) {
                groups += Group(current)
                current = mutableListOf()
            }
            current += clip
            currentEnd = maxOf(currentEnd, clip.endedAtEpochMs)
        }
        if (current.isNotEmpty()) groups += Group(current)
        return groups
    }
}
