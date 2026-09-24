package io.github.tunlezah.roadguard.recording

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.settings.SegmentLength
import org.junit.Test

/**
 * Pins down [RecoveryPolicy] and [RecordingFailure]: how Roadguard gets a failed recording going
 * again, and what each kind of failure asks of it.
 *
 * The contract that matters most is the one the recorder used to break: **recovery never gives
 * up while the session lives.** It used to stop for good after five failures -- about thirty
 * seconds -- so a video call holding the camera, or a memory card re-seating itself, ended
 * recording for the rest of the drive. The rest keep that from becoming a battery problem: the
 * wake lock is bounded, and the driver is told once recovery is more than a blip.
 */
class RecoveryPolicyTest {

    // ── Schedule ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the first attempts follow the fast schedule`() {
        for ((index, expected) in RecoveryPolicy.FAST_DELAYS_MS.withIndex()) {
            assertWithMessage("attempt ${index + 1}").that(RecoveryPolicy.delayFor(index + 1)).isEqualTo(expected)
        }
    }

    @Test
    fun `the first retry comes within a couple of seconds`() {
        // Most failures -- a camera reopening after another app, an encoder restart -- clear in
        // about a second. Waiting longer than that first time is footage lost for nothing.
        assertThat(RecoveryPolicy.delayFor(1)).isAtMost(2_000L)
    }

    @Test
    fun `recovery never gives up`() {
        for (attempt in listOf(6, 7, 50, 1_000, 100_000, Int.MAX_VALUE)) {
            assertWithMessage("attempt $attempt").that(RecoveryPolicy.delayFor(attempt)).isEqualTo(RecoveryPolicy.SLOW_DELAY_MS)
        }
    }

    @Test
    fun `the slow cadence is still frequent enough to matter on a drive`() {
        assertThat(RecoveryPolicy.SLOW_DELAY_MS).isAtMost(60_000L)
    }

    @Test
    fun `delays never shrink as attempts accumulate`() {
        var previous = 0L
        for (attempt in 1..20) {
            val delay = RecoveryPolicy.delayFor(attempt)
            assertWithMessage("attempt $attempt").that(delay).isAtLeast(previous)
            previous = delay
        }
    }

    @Test
    fun `an out-of-range attempt number is treated as the first`() {
        assertThat(RecoveryPolicy.delayFor(0)).isEqualTo(RecoveryPolicy.FAST_DELAYS_MS.first())
        assertThat(RecoveryPolicy.delayFor(-3)).isEqualTo(RecoveryPolicy.FAST_DELAYS_MS.first())
    }

    // ── Forced rebinds ──────────────────────────────────────────────────────────────────

    @Test
    fun `every third attempt rebuilds the camera session`() {
        val forced = (1..12).filter { RecoveryPolicy.forcesRebind(it) }
        assertThat(forced).containsExactly(3, 6, 9, 12).inOrder()
        assertThat(RecoveryPolicy.forcesRebind(0)).isFalse()
    }

    @Test
    fun `a camera that never reopens is rebuilt within the fast phase`() {
        // CameraLost waits for CameraX to reopen the camera by itself. If it never does, a forced
        // rebind must come while retries are still quick, not minutes later.
        val firstForced = (1..RecoveryPolicy.FAST_DELAYS_MS.size).firstOrNull { RecoveryPolicy.forcesRebind(it) }
        assertThat(firstForced).isNotNull()
    }

    // ── Battery and alerting ────────────────────────────────────────────────────────────

    @Test
    fun `recovery holds the wake lock only within its budget`() {
        assertThat(RecoveryPolicy.holdsWakeLock(0)).isTrue()
        assertThat(RecoveryPolicy.holdsWakeLock(RecoveryPolicy.WAKE_LOCK_BUDGET_MS - 1)).isTrue()
        assertThat(RecoveryPolicy.holdsWakeLock(RecoveryPolicy.WAKE_LOCK_BUDGET_MS)).isFalse()
        assertThat(RecoveryPolicy.holdsWakeLock(24L * 60 * 60 * 1000)).isFalse()
    }

    @Test
    fun `the wake-lock budget outlasts every fast retry`() {
        // Going quiet before the quick attempts are spent would throw away the ones most likely
        // to succeed.
        assertThat(RecoveryPolicy.WAKE_LOCK_BUDGET_MS).isGreaterThan(RecoveryPolicy.FAST_DELAYS_MS.sum())
    }

    @Test
    fun `the driver is alerted past a blip, and before recovery goes quiet`() {
        assertThat(RecoveryPolicy.isOverdue(RecoveryPolicy.ALERT_AFTER_MS - 1)).isFalse()
        assertThat(RecoveryPolicy.isOverdue(RecoveryPolicy.ALERT_AFTER_MS)).isTrue()
        assertThat(RecoveryPolicy.ALERT_AFTER_MS).isLessThan(RecoveryPolicy.WAKE_LOCK_BUDGET_MS)
    }

    @Test
    fun `a recovered recording proves itself within its first segment`() {
        // The episode ends when a restarted recording has run cleanly for HEALTHY_AFTER_MS. That
        // has to happen inside one segment, or a healthy recording would never end the episode.
        val shortestSegmentMs = SegmentLength.entries.minOf { it.seconds } * 1_000L
        assertThat(RecoveryPolicy.HEALTHY_AFTER_MS).isLessThan(shortestSegmentMs)
    }

    // ── Failures ────────────────────────────────────────────────────────────────────────

    @Test
    fun `losing the camera waits for it rather than rebinding over CameraX's own reopen`() {
        assertThat(RecordingFailure.CameraLost.needsRebind).isFalse()
    }

    @Test
    fun `a suspect camera session is always rebuilt`() {
        val suspect = listOf(
            RecordingFailure.Stalled,
            RecordingFailure.EncoderFailed,
            RecordingFailure.StartRejected,
            RecordingFailure.CameraUnavailable,
            RecordingFailure.BindFailed,
        )
        for (failure in suspect) {
            assertWithMessage(failure.name).that(failure.needsRebind).isTrue()
        }
    }

    @Test
    fun `storage failures wait on storage, not the camera, and say which problem it is`() {
        assertThat(RecordingFailure.StorageFull.isStorage).isTrue()
        assertThat(RecordingFailure.StorageUnavailable.isStorage).isTrue()
        assertThat(RecordingFailure.StorageFull.needsRebind).isFalse()
        assertThat(RecordingFailure.StorageUnavailable.needsRebind).isFalse()
        assertThat(RecordingFailure.StorageFull.blocker).isEqualTo(RecordingBlocker.StorageFull)
        assertThat(RecordingFailure.StorageUnavailable.blocker).isEqualTo(RecordingBlocker.StorageUnavailable)
        for (failure in RecordingFailure.entries.filterNot { it.isStorage }) {
            assertWithMessage(failure.name).that(failure.blocker).isNull()
        }
    }

    @Test
    fun `every failure tells the driver what is happening`() {
        for (failure in RecordingFailure.entries) {
            assertWithMessage(failure.name).that(failure.message).isNotEmpty()
            assertWithMessage(failure.name).that(failure.message.endsWith(".")).isTrue()
        }
    }
}
