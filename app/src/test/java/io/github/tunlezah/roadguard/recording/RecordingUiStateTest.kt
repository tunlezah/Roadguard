package io.github.tunlezah.roadguard.recording

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The derived properties of [RecordingUiState] that the service, the notification and the main
 * screen all act on.
 *
 * Each is a small table, but each guards something real: [RecordingUiState.holdsWakeLock] decides
 * whether the last clip of a drive gets its index written before the phone sleeps, and whether a
 * long outage becomes a long wake lock; [RecordingUiState.isSessionActive] decides whether Stop is
 * on offer while Roadguard is reconnecting.
 */
class RecordingUiStateTest {

    private fun state(status: RecorderStatus, unfinalized: Int = 0, recoveryIdle: Boolean = false) =
        RecordingUiState(status = status, unfinalizedSegments = unfinalized, recoveryIdle = recoveryIdle)

    @Test
    fun `the wake lock is held from the start of a session until it has stopped`() {
        val expected = mapOf(
            RecorderStatus.Idle to false,
            RecorderStatus.Starting to true,
            RecorderStatus.Recording to true,
            RecorderStatus.RollingOver to true,
            RecorderStatus.Recovering to true,
            RecorderStatus.Stopping to true,
            RecorderStatus.Failed to false,
        )
        assertThat(expected.keys).containsExactlyElementsIn(RecorderStatus.entries)
        for ((status, holds) in expected) {
            assertWithMessage(status.name).that(state(status).holdsWakeLock).isEqualTo(holds)
        }
    }

    @Test
    fun `a file still being closed keeps the wake lock after the session has ended`() {
        // An MP4 without its closing index is unplayable. A low-battery stop with the screen off
        // is exactly when the CPU would otherwise sleep between "stopped" and "file closed".
        for (status in RecorderStatus.entries) {
            assertWithMessage(status.name).that(state(status, unfinalized = 1).holdsWakeLock).isTrue()
        }
    }

    @Test
    fun `recovery that has gone quiet lets the CPU sleep`() {
        assertThat(state(RecorderStatus.Recovering, recoveryIdle = true).holdsWakeLock).isFalse()
        // ...unless a file is still waiting to be closed.
        assertThat(state(RecorderStatus.Recovering, unfinalized = 1, recoveryIdle = true).holdsWakeLock).isTrue()
    }

    @Test
    fun `only frames actually being written count as recording`() {
        val recording = RecorderStatus.entries.filter { state(it).isRecording }
        assertThat(recording).containsExactly(RecorderStatus.Recording, RecorderStatus.RollingOver)
    }

    @Test
    fun `a session is active while starting, recording and reconnecting`() {
        val active = RecorderStatus.entries.filter { state(it).isSessionActive }
        assertThat(active).containsExactly(
            RecorderStatus.Starting,
            RecorderStatus.Recording,
            RecorderStatus.RollingOver,
            RecorderStatus.Recovering,
        )
    }

    @Test
    fun `protect is offered while reconnecting, because a camera failure may be the impact`() {
        val protectable = RecorderStatus.entries.filter { state(it).canProtect }
        assertThat(protectable).containsExactly(
            RecorderStatus.Recording,
            RecorderStatus.RollingOver,
            RecorderStatus.Recovering,
        )
    }

    @Test
    fun `the first blocker shown is one the driver can act on`() {
        val state = RecordingUiState(blockers = listOf(RecordingBlocker.CameraFatal, RecordingBlocker.StorageFull))
        assertThat(state.primaryBlocker).isEqualTo(RecordingBlocker.StorageFull)
    }
}
