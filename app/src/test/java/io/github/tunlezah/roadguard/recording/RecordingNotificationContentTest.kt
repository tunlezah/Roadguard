package io.github.tunlezah.roadguard.recording

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.capability.DeviceTier
import io.github.tunlezah.roadguard.capability.RecordingProfile
import io.github.tunlezah.roadguard.capability.Resolution
import io.github.tunlezah.roadguard.capability.VideoMimeTypes
import io.github.tunlezah.roadguard.recording.RecordingNotifications.Actions
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import org.junit.Test

/**
 * What the ongoing recording notification says, in every recorder state.
 *
 * The notification is the driver's only proof that recording is running when the screen is off,
 * so it must tell the truth -- above all that recording has *stopped*, or is reconnecting -- and
 * offer the right controls. It is also posted only when its [RecordingNotifications.Content]
 * changes, which is what stopped it being rebuilt for every encoded frame; the last test pins
 * that progress alone changes nothing.
 */
class RecordingNotificationContentTest {

    private fun content(state: RecordingUiState, storage: String? = null) =
        RecordingNotifications.contentFor(state, storage)

    private fun profile() = RecordingProfile(
        cameraXQuality = "FHD",
        resolution = Resolution(1920, 1080),
        frameRate = 30,
        codecMimeType = VideoMimeTypes.H264,
        targetBitrateBps = 0,
        stabilisation = false,
        nightAssist = false,
        hdr = false,
        dualCamera = false,
        burnInOverlays = true,
        tier = DeviceTier.Capable,
        isAuto = true,
        rationale = listOf("fixture"),
    )

    @Test
    fun `every state has its own title, except that a rollover reads as recording`() {
        val titles = RecorderStatus.entries
            .filter { it != RecorderStatus.RollingOver }
            .map { content(RecordingUiState(status = it)).title }
        assertThat(titles).containsNoDuplicates()
        // A segment boundary lasts milliseconds and must not look like recording stopped.
        assertThat(content(RecordingUiState(status = RecorderStatus.RollingOver)))
            .isEqualTo(content(RecordingUiState(status = RecorderStatus.Recording)))
    }

    @Test
    fun `reconnecting says so, with a warning icon and the controls still there`() {
        val content = content(RecordingUiState(status = RecorderStatus.Recovering))
        assertThat(content.title).contains("reconnecting")
        assertThat(content.iconRes).isEqualTo(R.drawable.ic_warning)
        assertThat(content.actions).isEqualTo(Actions.ProtectAndStop)
        assertThat(content.ongoing).isTrue()
    }

    @Test
    fun `a stopped recorder says it has stopped and offers to record again`() {
        val content = content(RecordingUiState(status = RecorderStatus.Failed, lastErrorMessage = "Battery is at 3%."))
        assertThat(content.title).contains("stopped")
        assertThat(content.text).contains("Battery is at 3%.")
        assertThat(content.iconRes).isEqualTo(R.drawable.ic_error)
        assertThat(content.actions).isEqualTo(Actions.Record)
        assertThat(content.ongoing).isFalse()
    }

    @Test
    fun `the actions follow the state`() {
        val expected = mapOf(
            RecorderStatus.Idle to Actions.Record,
            RecorderStatus.Starting to Actions.StopOnly,
            RecorderStatus.Recording to Actions.ProtectAndStop,
            RecorderStatus.RollingOver to Actions.ProtectAndStop,
            RecorderStatus.Recovering to Actions.ProtectAndStop,
            RecorderStatus.Stopping to Actions.None,
            RecorderStatus.Failed to Actions.Record,
        )
        assertThat(expected.keys).containsExactlyElementsIn(RecorderStatus.entries)
        for ((status, actions) in expected) {
            assertWithMessage(status.name).that(content(RecordingUiState(status = status)).actions).isEqualTo(actions)
        }
    }

    @Test
    fun `the notification can only be swiped away when no session is running`() {
        for (status in RecorderStatus.entries) {
            val state = RecordingUiState(status = status)
            assertWithMessage(status.name).that(content(state).ongoing).isEqualTo(state.isSessionActive)
        }
    }

    @Test
    fun `the detail line carries profile, storage, heat, battery-safe mode and the problem`() {
        val state = RecordingUiState(
            status = RecorderStatus.Recording,
            profile = profile(),
            thermalLevel = ThermalLevel.High,
            batterySafe = true,
            blockers = listOf(RecordingBlocker.CameraInUse),
            lastErrorMessage = "Something else",
        )
        val text = content(state, storage = "1024 MB of 4096 MB").text
        assertThat(text).contains(profile().label)
        assertThat(text).contains("1024 MB of 4096 MB")
        assertThat(text).contains("Temperature: ${ThermalLevel.High.label}")
        assertThat(text).contains("Battery-safe mode")
        assertThat(text).contains(RecordingBlocker.CameraInUse.message)
        assertThat(text).contains("Something else")
    }

    @Test
    fun `a message that is both the blocker and the error is shown once`() {
        val blocker = RecordingBlocker.CameraInUse
        val state = RecordingUiState(
            status = RecorderStatus.Recovering,
            blockers = listOf(blocker),
            lastErrorMessage = blocker.message,
        )
        val text = content(state).text
        assertThat(text.split(blocker.message).size - 1).isEqualTo(1)
    }

    @Test
    fun `normal temperature is not mentioned`() {
        val text = content(RecordingUiState(status = RecorderStatus.Recording, thermalLevel = ThermalLevel.Normal)).text
        assertThat(text).doesNotContain("Temperature")
    }

    @Test
    fun `progress alone does not change the notification`() {
        val before = RecordingUiState(status = RecorderStatus.Recording, profile = profile())
        val after = before.copy(
            segmentBytes = 123_456_789,
            segmentIndex = 42,
            sessionDurationMs = 3_600_000,
            sessionSegmentCount = 12,
            unfinalizedSegments = 1,
            audioMuted = true,
        )
        assertThat(content(after, "1 MB of 2 MB")).isEqualTo(content(before, "1 MB of 2 MB"))
    }
}
