package io.github.tunlezah.roadguard.recording

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.recording.SegmentPlanner.MIN_SEGMENT_MS
import org.junit.Test

/** Roadguard's default loop segment length. */
private const val TARGET_MS = 180_000L

/**
 * Pins down [SegmentPlanner]: when the recorder is allowed to close the current file.
 *
 * A rollover is the only safe moment to change resolution, frame rate or codec, so every
 * subsystem that wants a change (thermal, storage, error recovery) has to ask through here.
 * Two opposite failure modes matter for a dashcam and both are decided in this one function:
 * rolling over too eagerly shreds the loop into fragments too short to show what happened,
 * while refusing to roll over lets a recorder error or a full disk stop recording entirely.
 * These tests fix the target-duration boundary, the [MIN_SEGMENT_MS] floor that protects
 * footage from thermal churn, and the priority order between competing reasons.
 */
class SegmentPlannerTest {

    private fun decide(
        elapsedMs: Long,
        targetSegmentMs: Long = TARGET_MS,
        reconfigurationPending: Boolean = false,
        storageCleanupRequired: Boolean = false,
        recorderErrorPending: Boolean = false,
    ): RolloverDecision = SegmentPlanner.decide(
        elapsedMs = elapsedMs,
        targetSegmentMs = targetSegmentMs,
        reconfigurationPending = reconfigurationPending,
        storageCleanupRequired = storageCleanupRequired,
        recorderErrorPending = recorderErrorPending,
    )

    // ---------------------------------------------------------------- the plain loop

    @Test
    fun `a quiet segment does not roll over before its target duration`() {
        val elapsedCases = listOf(0L, 1L, MIN_SEGMENT_MS - 1, MIN_SEGMENT_MS, TARGET_MS - 1)

        for (elapsed in elapsedCases) {
            val decision = decide(elapsed)

            assertWithMessage("elapsed=$elapsed with nothing pending").that(decision.shouldRoll).isFalse()
            assertWithMessage("elapsed=$elapsed with nothing pending").that(decision.reason).isNull()
        }
    }

    @Test
    fun `a segment rolls over exactly at its target duration`() {
        assertThat(decide(TARGET_MS - 1)).isEqualTo(RolloverDecision(false, null))
        assertThat(decide(TARGET_MS)).isEqualTo(RolloverDecision(true, RolloverReason.SegmentComplete))
    }

    @Test
    fun `a segment that has overrun its target still rolls over as segment complete`() {
        // Overrun happens whenever a rollover check is late (a stalled camera thread, a doze).
        val decision = decide(TARGET_MS * 3)

        assertThat(decision.shouldRoll).isTrue()
        assertThat(decision.reason).isEqualTo(RolloverReason.SegmentComplete)
    }

    @Test
    fun `a target shorter than the minimum segment still rolls over on time`() {
        // The user may pick a 10 s loop; MIN_SEGMENT_MS must not extend the target.
        val target = 10_000L

        assertThat(decide(target - 1, targetSegmentMs = target).shouldRoll).isFalse()
        assertThat(decide(target, targetSegmentMs = target))
            .isEqualTo(RolloverDecision(true, RolloverReason.SegmentComplete))
    }

    // ---------------------------------------------------------------- recorder error

    @Test
    fun `a recorder error rolls over immediately even at zero elapsed`() {
        val decision = decide(elapsedMs = 0, recorderErrorPending = true)

        assertThat(decision.shouldRoll).isTrue()
        assertThat(decision.reason).isEqualTo(RolloverReason.RecorderError)
    }

    @Test
    fun `a recorder error ignores the minimum segment floor at every elapsed time`() {
        val elapsedCases = listOf(0L, 1L, MIN_SEGMENT_MS - 1, MIN_SEGMENT_MS, TARGET_MS, TARGET_MS + 1)

        for (elapsed in elapsedCases) {
            val decision = decide(elapsed, recorderErrorPending = true)

            assertWithMessage("elapsed=$elapsed with a recorder error").that(decision.reason)
                .isEqualTo(RolloverReason.RecorderError)
        }
    }

    // ---------------------------------------------------------------- reconfiguration

    @Test
    fun `a pending reconfiguration waits for the minimum segment length`() {
        val cases = listOf(
            0L to null,
            1L to null,
            MIN_SEGMENT_MS - 1 to null,
            MIN_SEGMENT_MS to RolloverReason.Reconfiguration,
            MIN_SEGMENT_MS + 1 to RolloverReason.Reconfiguration,
            TARGET_MS - 1 to RolloverReason.Reconfiguration,
        )

        for ((elapsed, expected) in cases) {
            val decision = decide(elapsed, reconfigurationPending = true)

            assertWithMessage("elapsed=$elapsed with a reconfiguration pending")
                .that(decision.reason).isEqualTo(expected)
            assertWithMessage("elapsed=$elapsed with a reconfiguration pending")
                .that(decision.shouldRoll).isEqualTo(expected != null)
        }
    }

    @Test
    fun `a reconfiguration cannot shred a young segment into fragments`() {
        // The thermal engine asking for a lower profile 5 s in must not cut a 5 s file.
        assertThat(decide(5_000, reconfigurationPending = true))
            .isEqualTo(RolloverDecision(false, null))
        assertThat(MIN_SEGMENT_MS).isEqualTo(20_000L)
    }

    // ---------------------------------------------------------------- storage cleanup

    @Test
    fun `a storage cleanup waits for the minimum segment length`() {
        val cases = listOf(
            0L to null,
            1L to null,
            MIN_SEGMENT_MS - 1 to null,
            MIN_SEGMENT_MS to RolloverReason.StorageCleanup,
            MIN_SEGMENT_MS + 1 to RolloverReason.StorageCleanup,
            TARGET_MS - 1 to RolloverReason.StorageCleanup,
        )

        for ((elapsed, expected) in cases) {
            val decision = decide(elapsed, storageCleanupRequired = true)

            assertWithMessage("elapsed=$elapsed with a storage cleanup required")
                .that(decision.reason).isEqualTo(expected)
            assertWithMessage("elapsed=$elapsed with a storage cleanup required")
                .that(decision.shouldRoll).isEqualTo(expected != null)
        }
    }

    // ---------------------------------------------------------------- priority order

    @Test
    fun `a recorder error outranks a completed segment`() {
        val decision = decide(TARGET_MS, recorderErrorPending = true)

        assertThat(decision.reason).isEqualTo(RolloverReason.RecorderError)
    }

    @Test
    fun `a recorder error outranks a pending reconfiguration`() {
        val decision = decide(MIN_SEGMENT_MS, reconfigurationPending = true, recorderErrorPending = true)

        assertThat(decision.reason).isEqualTo(RolloverReason.RecorderError)
    }

    @Test
    fun `a recorder error outranks a storage cleanup`() {
        val decision = decide(MIN_SEGMENT_MS, storageCleanupRequired = true, recorderErrorPending = true)

        assertThat(decision.reason).isEqualTo(RolloverReason.RecorderError)
    }

    @Test
    fun `a completed segment outranks a pending reconfiguration`() {
        val decision = decide(TARGET_MS, reconfigurationPending = true)

        assertThat(decision.reason).isEqualTo(RolloverReason.SegmentComplete)
    }

    @Test
    fun `a completed segment outranks a storage cleanup`() {
        val decision = decide(TARGET_MS, storageCleanupRequired = true)

        assertThat(decision.reason).isEqualTo(RolloverReason.SegmentComplete)
    }

    @Test
    fun `a pending reconfiguration outranks a storage cleanup`() {
        val decision = decide(
            elapsedMs = MIN_SEGMENT_MS,
            reconfigurationPending = true,
            storageCleanupRequired = true,
        )

        assertThat(decision.reason).isEqualTo(RolloverReason.Reconfiguration)
    }

    @Test
    fun `every reason loses to the one above it when all four conditions hold`() {
        // Peel the reasons off one at a time from the highest priority down.
        assertThat(decide(TARGET_MS, reconfigurationPending = true, storageCleanupRequired = true, recorderErrorPending = true).reason)
            .isEqualTo(RolloverReason.RecorderError)
        assertThat(decide(TARGET_MS, reconfigurationPending = true, storageCleanupRequired = true).reason)
            .isEqualTo(RolloverReason.SegmentComplete)
        assertThat(decide(TARGET_MS - 1, reconfigurationPending = true, storageCleanupRequired = true).reason)
            .isEqualTo(RolloverReason.Reconfiguration)
        assertThat(decide(TARGET_MS - 1, storageCleanupRequired = true).reason)
            .isEqualTo(RolloverReason.StorageCleanup)
        assertThat(decide(TARGET_MS - 1).reason).isNull()
    }

    // ---------------------------------------------------------------- invariants

    @Test
    fun `shouldRoll false always comes with a null reason and vice versa`() {
        val elapsedCases = listOf(0L, 1L, MIN_SEGMENT_MS - 1, MIN_SEGMENT_MS, TARGET_MS - 1, TARGET_MS, TARGET_MS + 1)
        val flags = listOf(false, true)
        var falseCases = 0
        var trueCases = 0

        for (elapsed in elapsedCases) {
            for (reconfiguration in flags) {
                for (cleanup in flags) {
                    for (error in flags) {
                        val decision = decide(elapsed, TARGET_MS, reconfiguration, cleanup, error)
                        val label = "elapsed=$elapsed reconfig=$reconfiguration cleanup=$cleanup error=$error"

                        assertWithMessage(label).that(decision.reason != null).isEqualTo(decision.shouldRoll)
                        if (decision.shouldRoll) trueCases++ else falseCases++
                    }
                }
            }
        }

        // Guard against the matrix degenerating into an all-roll or never-roll sweep.
        assertThat(falseCases).isGreaterThan(0)
        assertThat(trueCases).isGreaterThan(0)
    }

    @Test
    fun `every rollover reason carries a human readable label`() {
        for (reason in RolloverReason.entries) {
            assertWithMessage("label for $reason").that(reason.label).isNotEmpty()
        }
        assertThat(RolloverReason.entries.map { it.label }.toSet())
            .hasSize(RolloverReason.entries.size)
    }
}
