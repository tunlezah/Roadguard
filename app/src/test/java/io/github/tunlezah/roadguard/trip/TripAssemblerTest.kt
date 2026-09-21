package io.github.tunlezah.roadguard.trip

import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.trip.TripAssembler.Clip
import org.junit.Test

/**
 * The gap rule that turns clips into trips.
 *
 * It is stated once and tested here because two code paths depend on it agreeing with itself: the
 * recorder continuing a trip after a stop-and-restart, and the start-up reconciler grouping clips
 * recorded before trips existed. Pure JVM.
 */
class TripAssemblerTest {

    private val minute = 60_000L
    private val segment = 3 * minute

    private fun clip(id: Long, startMinutes: Long, durationMs: Long = segment) =
        Clip(id, startMinutes * minute, durationMs)

    // ── Grouping ──────────────────────────────────────────────────────────────────────

    @Test
    fun `back-to-back clips are one trip`() {
        val groups = TripAssembler.group(listOf(clip(1, 0), clip(2, 3), clip(3, 6)))

        assertThat(groups).hasSize(1)
        assertThat(groups.single().ids).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(groups.single().startedAtEpochMs).isEqualTo(0L)
        assertThat(groups.single().endedAtEpochMs).isEqualTo(9 * minute)
    }

    @Test
    fun `a jump in the recording times starts a new trip`() {
        // Clip 2 ends at 6 min; clip 3 starts at 20 min: a fourteen-minute silence.
        val groups = TripAssembler.group(listOf(clip(1, 0), clip(2, 3), clip(3, 20), clip(4, 23)))

        assertThat(groups.map { it.ids }).containsExactly(listOf(1L, 2L), listOf(3L, 4L)).inOrder()
    }

    @Test
    fun `a gap of exactly the limit still continues the trip, one millisecond more does not`() {
        val end = segment
        val within = TripAssembler.group(listOf(clip(1, 0), Clip(2, end + TripAssembler.MAX_GAP_MS, segment)))
        val beyond = TripAssembler.group(listOf(clip(1, 0), Clip(2, end + TripAssembler.MAX_GAP_MS + 1, segment)))

        assertThat(within).hasSize(1)
        assertThat(beyond).hasSize(2)
    }

    @Test
    fun `a crash and relaunch a few seconds later is the same drive`() {
        // The relaunch's first clip starts 8 s after the killed clip's end.
        val killedEnd = segment
        val groups = TripAssembler.group(listOf(clip(1, 0), Clip(2, killedEnd + 8_000, segment)))

        assertThat(groups).hasSize(1)
    }

    @Test
    fun `input order does not matter`() {
        val shuffled = listOf(clip(3, 6), clip(1, 0), clip(2, 3))

        assertThat(TripAssembler.group(shuffled).single().ids).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `a clip that starts slightly before the previous one ended still continues the trip`() {
        // A wall clock set back by a few seconds mid-drive must not split the trip.
        val groups = TripAssembler.group(listOf(clip(1, 0), Clip(2, segment - 5_000, segment)))

        assertThat(groups).hasSize(1)
    }

    @Test
    fun `an unfinished clip counts as ending where it started`() {
        // durationMs is 0 until a clip finalises; a following clip 1 min later is the same trip.
        val groups = TripAssembler.group(listOf(clip(1, 0, durationMs = 0), clip(2, 1)))

        assertThat(groups).hasSize(1)
    }

    @Test
    fun `no clips means no trips`() {
        assertThat(TripAssembler.group(emptyList())).isEmpty()
    }

    // ── Live continuation ─────────────────────────────────────────────────────────────

    @Test
    fun `continues agrees with grouping`() {
        assertThat(TripAssembler.continues(previousEndEpochMs = 100_000L, nextStartEpochMs = 100_000L + TripAssembler.MAX_GAP_MS)).isTrue()
        assertThat(TripAssembler.continues(previousEndEpochMs = 100_000L, nextStartEpochMs = 100_000L + TripAssembler.MAX_GAP_MS + 1)).isFalse()
        assertThat(TripAssembler.continues(previousEndEpochMs = 100_000L, nextStartEpochMs = 90_000L)).isTrue()
    }

    @Test
    fun `the gap limit sits between a rollover and a fuel stop`() {
        // A rollover is milliseconds; a fuel stop with recording off is many minutes.
        assertThat(TripAssembler.MAX_GAP_MS).isEqualTo(2 * minute)
    }
}
