package io.github.tunlezah.roadguard.event

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/** A fixed, arbitrary epoch. Nothing here may read a real clock. */
private const val T0 = 1_700_000_000_000L

/** Roadguard's default loop segment length. */
private const val SEGMENT_MS = 180_000L

private const val DEFAULT_PRE_S = 30
private const val DEFAULT_POST_S = 60

/**
 * Builds a gap-free chain of finished segments starting at [T0]: ids 1..[count],
 * segment n covering `[T0 + (n-1)*len, T0 + n*len)`.
 */
private fun chain(count: Int, len: Long = SEGMENT_MS): List<SegmentTiming> =
    (1..count).map { n ->
        SegmentTiming(
            id = n.toLong(),
            startedAtEpochMs = T0 + (n - 1) * len,
            durationMs = len,
        )
    }

/** Epoch of the rollover between chained segment [n] and segment [n] + 1. */
private fun boundary(n: Int, len: Long = SEGMENT_MS): Long = T0 + n * len

/**
 * Pins down [ProtectionPlanner]: which footage an impact claims, and when protection may be
 * declared finished.
 *
 * For a dashcam this is the difference between keeping and losing the only evidence of a crash.
 * A collision does not politely wait for a segment boundary -- it lands milliseconds either side
 * of one, so the closing *and* the opening file must both be claimed, and the claim must stay
 * open until the post-roll footage actually exists. The interesting content of this class is
 * therefore entirely in the boundaries: exact-touch overlap, rollover straddling, and the
 * in-progress segment whose tail has not been recorded yet.
 */
class ProtectionPlannerTest {

    // ---------------------------------------------------------------- windowFor arithmetic

    @Test
    fun `windowFor spans pre and post seconds around the event for the 30 to 60 second default`() {
        val window = ProtectionPlanner.windowFor(T0, DEFAULT_PRE_S, DEFAULT_POST_S)

        assertThat(window.fromEpochMs).isEqualTo(T0 - 30_000L)
        assertThat(window.toEpochMs).isEqualTo(T0 + 60_000L)
        assertThat(window.durationMs).isEqualTo(90_000L)
    }

    @Test
    fun `windowFor with zero pre and post collapses to the instant of the event`() {
        val window = ProtectionPlanner.windowFor(T0, preSeconds = 0, postSeconds = 0)

        assertThat(window.fromEpochMs).isEqualTo(T0)
        assertThat(window.toEpochMs).isEqualTo(T0)
        assertThat(window.durationMs).isEqualTo(0L)
    }

    @Test
    fun `windowFor keeps one side when only the other is zero`() {
        val preOnly = ProtectionPlanner.windowFor(T0, preSeconds = 45, postSeconds = 0)
        assertThat(preOnly.fromEpochMs).isEqualTo(T0 - 45_000L)
        assertThat(preOnly.toEpochMs).isEqualTo(T0)

        val postOnly = ProtectionPlanner.windowFor(T0, preSeconds = 0, postSeconds = 300)
        assertThat(postOnly.fromEpochMs).isEqualTo(T0)
        assertThat(postOnly.toEpochMs).isEqualTo(T0 + 300_000L)
    }

    @Test
    fun `ProtectionWindow rejects a backwards window`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectionWindow(fromEpochMs = T0, toEpochMs = T0 - 1)
        }
        assertThat(error).hasMessageThat().contains("must not run backwards")

        // Zero-length is legal: it is what a 0 s / 0 s configuration produces.
        assertThat(ProtectionWindow(T0, T0).durationMs).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- simple selection

    @Test
    fun `a window entirely inside one segment selects only that segment`() {
        val segments = chain(count = 3)
        // 30 s / 60 s around the middle of segment 2: both edges stay inside it.
        val event = boundary(1) + SEGMENT_MS / 2
        val window = ProtectionPlanner.windowFor(event, DEFAULT_PRE_S, DEFAULT_POST_S)

        val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = boundary(3))

        assertThat(decision.segmentIds).containsExactly(2L)
        assertThat(decision.window).isEqualTo(window)
        assertThat(decision.coveredToEpochMs).isEqualTo(boundary(2))
        assertThat(decision.isComplete).isTrue()
    }

    @Test
    fun `distant segments are never claimed`() {
        val segments = chain(count = 6)
        val event = boundary(2) + SEGMENT_MS / 2
        val window = ProtectionPlanner.windowFor(event, DEFAULT_PRE_S, DEFAULT_POST_S)

        val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = boundary(6))

        assertThat(decision.segmentIds).containsExactly(3L)
    }

    // ---------------------------------------------------------------- headline: rollover

    @Test
    fun `an event either side of a rollover claims both the closing and the opening segment`() {
        val segments = chain(count = 3)
        val rollover = boundary(1) // start of segment 2 == end of segment 1
        val cases = listOf(
            "1 ms before the rollover" to rollover - 1,
            "exactly on the rollover" to rollover,
            "1 ms after the rollover" to rollover + 1,
        )

        for ((label, event) in cases) {
            val window = ProtectionPlanner.windowFor(event, DEFAULT_PRE_S, DEFAULT_POST_S)

            // Sanity: each placement really does straddle the rollover, so both files
            // genuinely hold footage the event needs.
            assertWithMessage("pre-roll must reach back past the rollover ($label)")
                .that(window.fromEpochMs).isLessThan(rollover)
            assertWithMessage("post-roll must reach forward past the rollover ($label)")
                .that(window.toEpochMs).isGreaterThan(rollover)

            val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = boundary(3))

            assertWithMessage("segments claimed for an event $label")
                .that(decision.segmentIds).containsExactly(1L, 2L).inOrder()
            assertWithMessage("protection must be closable for an event $label")
                .that(decision.isComplete).isTrue()
        }
    }

    @Test
    fun `an event on a rollover claims the whole run of short segments the window straddles`() {
        // 10 s segments: a 30 s / 60 s window is far wider than one file, and an event exactly
        // on a rollover then needs the whole run of files it straddles.
        val len = 10_000L
        val segments = chain(count = 20, len = len)
        val event = boundary(9, len) // start of segment 10
        val window = ProtectionPlanner.windowFor(event, DEFAULT_PRE_S, DEFAULT_POST_S)

        val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = boundary(20, len))

        // Window is [T0+60_000, T0+150_000): segments 7..15 by start time.
        assertThat(decision.segmentIds)
            .containsExactly(7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L).inOrder()
        assertThat(decision.isComplete).isTrue()
    }

    // ---------------------------------------------------------------- exact-touch overlap

    @Test
    fun `a segment ending exactly at the window start is not claimed but one ending 1 ms later is`() {
        val window = ProtectionWindow(fromEpochMs = T0, toEpochMs = T0 + 90_000)

        val touching = SegmentTiming(id = 1, startedAtEpochMs = T0 - SEGMENT_MS, durationMs = SEGMENT_MS)
        assertThat(touching.endEpochMs).isEqualTo(window.fromEpochMs)
        assertThat(touching.overlaps(window)).isFalse()
        assertThat(ProtectionPlanner.plan(window, listOf(touching), T0 + 90_000).segmentIds).isEmpty()

        val overlappingByOneMs = touching.copy(durationMs = SEGMENT_MS + 1)
        assertThat(overlappingByOneMs.overlaps(window)).isTrue()
        assertThat(ProtectionPlanner.plan(window, listOf(overlappingByOneMs), T0 + 90_000).segmentIds)
            .containsExactly(1L)
    }

    @Test
    fun `a segment starting exactly at the window end is not claimed but one starting 1 ms earlier is`() {
        val window = ProtectionWindow(fromEpochMs = T0, toEpochMs = T0 + 90_000)

        val touching = SegmentTiming(id = 2, startedAtEpochMs = window.toEpochMs, durationMs = SEGMENT_MS)
        assertThat(touching.overlaps(window)).isFalse()
        assertThat(ProtectionPlanner.plan(window, listOf(touching), T0 + 90_000).segmentIds).isEmpty()

        val overlappingByOneMs = touching.copy(startedAtEpochMs = window.toEpochMs - 1)
        assertThat(overlappingByOneMs.overlaps(window)).isTrue()
        assertThat(ProtectionPlanner.plan(window, listOf(overlappingByOneMs), T0 + 90_000).segmentIds)
            .containsExactly(2L)
    }

    @Test
    fun `an in-progress segment starting exactly at the window end is not claimed`() {
        val window = ProtectionWindow(fromEpochMs = T0, toEpochMs = T0 + 90_000)
        val live = SegmentTiming(
            id = 3,
            startedAtEpochMs = window.toEpochMs,
            durationMs = 0,
            isInProgress = true,
        )

        assertThat(live.overlaps(window)).isFalse()
        assertThat(ProtectionPlanner.plan(window, listOf(live), window.toEpochMs).segmentIds).isEmpty()
    }

    @Test
    fun `a zero-length window claims the segment that strictly contains the instant`() {
        val window = ProtectionPlanner.windowFor(boundary(1) + 1_000, preSeconds = 0, postSeconds = 0)

        val decision = ProtectionPlanner.plan(window, chain(count = 3), nowEpochMs = boundary(3))

        assertThat(decision.segmentIds).containsExactly(2L)
        assertThat(decision.coveredToEpochMs).isEqualTo(boundary(2))
        assertThat(decision.isComplete).isTrue()
    }

    @Test
    fun `a zero-length window exactly on a rollover claims nothing`() {
        // Strict inequalities on both sides: the closing segment ends at the instant and the
        // opening one starts at it, so neither overlaps. See the report note -- a 0 s / 0 s
        // configuration plus an impact on the exact rollover millisecond protects no footage.
        val window = ProtectionPlanner.windowFor(boundary(1), preSeconds = 0, postSeconds = 0)

        val decision = ProtectionPlanner.plan(window, chain(count = 3), nowEpochMs = boundary(3))

        assertThat(decision.segmentIds).isEmpty()
        assertThat(decision.coveredToEpochMs).isNull()
        assertThat(decision.isComplete).isFalse()
    }

    // ---------------------------------------------------------------- in-progress segment

    @Test
    fun `an in-progress segment is claimed once it starts before the window ends`() {
        val event = boundary(1) - 1 // impact 1 ms before the rollover
        val window = ProtectionPlanner.windowFor(event, DEFAULT_PRE_S, DEFAULT_POST_S)
        val now = boundary(1) + 20_000 // 20 s into the live segment
        val segments = listOf(
            chain(count = 1).single(),
            SegmentTiming(id = 2, startedAtEpochMs = boundary(1), durationMs = 20_000, isInProgress = true),
        )

        val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = now)

        assertThat(decision.segmentIds).containsExactly(1L, 2L).inOrder()
        // The live segment counts only up to now, not to its (unknown) end.
        assertThat(decision.coveredToEpochMs).isEqualTo(now)
        assertThat(decision.isComplete).isFalse()
    }

    @Test
    fun `protection stays open while the post-roll is still being recorded and closes once the segment finishes`() {
        val event = boundary(1) - 1
        val window = ProtectionPlanner.windowFor(event, DEFAULT_PRE_S, DEFAULT_POST_S)
        val finished = chain(count = 1).single()

        // Still recording, 20 s in: the post-roll tail does not exist yet.
        val live = SegmentTiming(id = 2, startedAtEpochMs = boundary(1), durationMs = 20_000, isInProgress = true)
        val openDecision = ProtectionPlanner.plan(
            window,
            listOf(finished, live),
            nowEpochMs = boundary(1) + 20_000,
        )
        assertThat(openDecision.isComplete).isFalse()
        assertThat(openDecision.coveredToEpochMs).isLessThan(window.toEpochMs)

        // Same segment, now finalised at its full length: recorded footage passes the window end.
        val finalised = live.copy(durationMs = SEGMENT_MS, isInProgress = false)
        val closedDecision = ProtectionPlanner.plan(
            window,
            listOf(finished, finalised),
            nowEpochMs = boundary(2),
        )
        assertThat(closedDecision.segmentIds).containsExactly(1L, 2L).inOrder()
        assertThat(closedDecision.coveredToEpochMs).isEqualTo(boundary(2))
        assertThat(closedDecision.isComplete).isTrue()
    }

    @Test
    fun `a finished segment ending exactly at the window end closes protection`() {
        val window = ProtectionWindow(fromEpochMs = T0, toEpochMs = T0 + 90_000)
        val exact = SegmentTiming(id = 1, startedAtEpochMs = T0, durationMs = 90_000)

        val decision = ProtectionPlanner.plan(window, listOf(exact), nowEpochMs = T0 + 90_000)

        assertThat(decision.coveredToEpochMs).isEqualTo(window.toEpochMs)
        assertThat(decision.isComplete).isTrue()
    }

    @Test
    fun `a finished segment ending 1 ms short of the window end leaves protection open`() {
        val window = ProtectionWindow(fromEpochMs = T0, toEpochMs = T0 + 90_000)
        val short = SegmentTiming(id = 1, startedAtEpochMs = T0, durationMs = 90_000 - 1)

        val decision = ProtectionPlanner.plan(window, listOf(short), nowEpochMs = T0 + 90_000)

        assertThat(decision.segmentIds).containsExactly(1L)
        assertThat(decision.coveredToEpochMs).isEqualTo(window.toEpochMs - 1)
        assertThat(decision.isComplete).isFalse()
    }

    @Test
    fun `an in-progress segment closes protection once now itself reaches the window end`() {
        // Documents the actual rule: coverage for a live segment is "now", so once the clock
        // passes the window end the decision is complete even though the file is still open --
        // safe only because that segment id has already been claimed above.
        val window = ProtectionWindow(fromEpochMs = T0, toEpochMs = T0 + 90_000)
        val live = SegmentTiming(id = 7, startedAtEpochMs = T0, durationMs = 1_000, isInProgress = true)

        val decision = ProtectionPlanner.plan(window, listOf(live), nowEpochMs = window.toEpochMs)

        assertThat(decision.segmentIds).containsExactly(7L)
        assertThat(decision.coveredToEpochMs).isEqualTo(window.toEpochMs)
        assertThat(decision.isComplete).isTrue()
    }

    @Test
    fun `a finished segment past the window end wins over an earlier live segment for coverage`() {
        val window = ProtectionWindow(fromEpochMs = T0, toEpochMs = T0 + 90_000)
        val segments = listOf(
            SegmentTiming(id = 1, startedAtEpochMs = T0, durationMs = 200_000),
            SegmentTiming(id = 2, startedAtEpochMs = T0 + 10_000, durationMs = 5_000, isInProgress = true),
        )

        val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = T0 + 15_000)

        assertThat(decision.coveredToEpochMs).isEqualTo(T0 + 200_000)
        assertThat(decision.isComplete).isTrue()
    }

    // ---------------------------------------------------------------- no coverage at all

    @Test
    fun `no overlapping segment means incomplete protection and no coverage`() {
        val window = ProtectionPlanner.windowFor(T0, DEFAULT_PRE_S, DEFAULT_POST_S)
        // Both candidates sit entirely outside the window.
        val segments = listOf(
            SegmentTiming(id = 1, startedAtEpochMs = T0 - 10 * SEGMENT_MS, durationMs = SEGMENT_MS),
            SegmentTiming(id = 2, startedAtEpochMs = T0 + 10 * SEGMENT_MS, durationMs = SEGMENT_MS),
        )

        val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = T0 + 60_000)

        assertThat(decision.segmentIds).isEmpty()
        assertThat(decision.coveredToEpochMs).isNull()
        assertThat(decision.isComplete).isFalse()
        assertThat(decision.window).isEqualTo(window)
    }

    @Test
    fun `an empty segment list means incomplete protection and no coverage`() {
        val window = ProtectionPlanner.windowFor(T0, DEFAULT_PRE_S, DEFAULT_POST_S)

        val decision = ProtectionPlanner.plan(window, emptyList(), nowEpochMs = T0 + 60_000)

        assertThat(decision.segmentIds).isEmpty()
        assertThat(decision.coveredToEpochMs).isNull()
        assertThat(decision.isComplete).isFalse()
    }

    // ---------------------------------------------------------------- long windows, ordering

    @Test
    fun `a long pre-event window claims every segment it reaches back through`() {
        val segments = chain(count = 5)
        val event = boundary(4) + 30_000 // 30 s into segment 5
        val window = ProtectionPlanner.windowFor(event, preSeconds = 480, postSeconds = DEFAULT_POST_S)

        val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = event + 60_000)

        // Window is [T0+270_000, T0+810_000]: segment 1 ends at T0+180_000 and is excluded.
        assertThat(decision.segmentIds).containsExactly(2L, 3L, 4L, 5L).inOrder()
        assertThat(decision.coveredToEpochMs).isEqualTo(boundary(5))
        assertThat(decision.isComplete).isTrue()
    }

    @Test
    fun `a long post-event window is incomplete until enough segments exist`() {
        // 120 s post-roll during segment 3, but only segments 1..3 have been recorded.
        val recorded = chain(count = 3)
        val event = boundary(2) + 170_000 // 10 s before segment 3 closes
        val window = ProtectionPlanner.windowFor(event, DEFAULT_PRE_S, postSeconds = 120)

        val partial = ProtectionPlanner.plan(window, recorded, nowEpochMs = boundary(3))
        assertThat(partial.segmentIds).containsExactly(3L)
        assertThat(partial.coveredToEpochMs).isEqualTo(boundary(3))
        assertThat(partial.isComplete).isFalse()

        // Segment 4 finalises and the window is finally covered.
        val complete = ProtectionPlanner.plan(window, chain(count = 4), nowEpochMs = boundary(4))
        assertThat(complete.segmentIds).containsExactly(3L, 4L).inOrder()
        assertThat(complete.coveredToEpochMs).isEqualTo(boundary(4))
        assertThat(complete.isComplete).isTrue()
    }

    @Test
    fun `claimed segment ids come back sorted by start time regardless of input order`() {
        val ordered = chain(count = 5)
        val shuffled = listOf(ordered[3], ordered[0], ordered[4], ordered[2], ordered[1])
        val event = boundary(4) + 30_000
        val window = ProtectionPlanner.windowFor(event, preSeconds = 480, postSeconds = DEFAULT_POST_S)

        val fromShuffled = ProtectionPlanner.plan(window, shuffled, nowEpochMs = event + 60_000)
        val fromOrdered = ProtectionPlanner.plan(window, ordered, nowEpochMs = event + 60_000)

        assertThat(fromShuffled.segmentIds).containsExactly(2L, 3L, 4L, 5L).inOrder()
        assertThat(fromShuffled).isEqualTo(fromOrdered)
    }

    @Test
    fun `ids are ordered by start time even when the ids themselves are not monotonic`() {
        // Segment ids are database row ids; a restored or re-imported row need not be in order.
        val segments = listOf(
            SegmentTiming(id = 99, startedAtEpochMs = T0, durationMs = SEGMENT_MS),
            SegmentTiming(id = 4, startedAtEpochMs = T0 + SEGMENT_MS, durationMs = SEGMENT_MS),
            SegmentTiming(id = 57, startedAtEpochMs = T0 + 2 * SEGMENT_MS, durationMs = SEGMENT_MS),
        )
        val window = ProtectionWindow(fromEpochMs = T0 + 1, toEpochMs = T0 + 3 * SEGMENT_MS - 1)

        val decision = ProtectionPlanner.plan(window, segments, nowEpochMs = T0 + 3 * SEGMENT_MS)

        assertThat(decision.segmentIds).containsExactly(99L, 4L, 57L).inOrder()
    }
}
