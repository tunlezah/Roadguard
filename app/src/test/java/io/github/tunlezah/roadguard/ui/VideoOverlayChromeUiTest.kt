package io.github.tunlezah.roadguard.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.camera.PreviewFit
import io.github.tunlezah.roadguard.camera.PreviewFitResult
import io.github.tunlezah.roadguard.recording.RecorderStatus
import io.github.tunlezah.roadguard.recording.RecordingBlocker
import io.github.tunlezah.roadguard.recording.RecordingUiState
import io.github.tunlezah.roadguard.settings.PreviewZoom
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.ui.main.MainUiState
import io.github.tunlezah.roadguard.ui.main.VideoOverlayChrome
import io.github.tunlezah.roadguard.ui.theme.RoadguardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chrome drawn over the camera image.
 *
 * This is where the specification's hardest UI requirement lives: preview zoom must be visibly,
 * unambiguously **display-only**, so a user can never wonder whether what they are looking at is
 * what is being written to the file. The caption and the zoom control's content description are
 * that guarantee, and these tests hold them in place.
 *
 * The other job here is blockers. A dashcam that has silently stopped is worse than one that never
 * started, so every blocking condition must reach the screen with a specific message, and an
 * actionable one must offer the action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class VideoOverlayChromeUiTest {

    @get:Rule val compose = createComposeRule()

    // ── The display-only guarantee ─────────────────────────────────────────────────────

    @Test
    fun `the caption states in words that the recording is not cropped`() {
        setChrome(previewFit = croppingFit())

        compose.onNodeWithText("display only, recording is not cropped", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the caption is present even when the preview is not cropping at all`() {
        // The claim must be permanent, not something that appears only when it happens to crop.
        setChrome(previewFit = letterboxedFit())

        compose.onNodeWithText("display only, recording is not cropped", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the caption names the crop percentage so the user can see what is hidden`() {
        val fit = croppingFit()
        setChrome(previewFit = fit)

        assertThat(fit.describe()).contains("display crop")
        compose.onNodeWithText(fit.describe(), substring = true).assertIsDisplayed()
    }

    @Test
    fun `the zoom control's accessibility description also says display only`() {
        setChrome(previewFit = croppingFit())

        compose.onNodeWithContentDescription("Display only", substring = true).assertExists()
    }

    @Test
    fun `auto zoom is labelled as auto`() {
        val fit = PreviewFit.compute(
            sourceWidth = 1920,
            sourceHeight = 1080,
            panelWidth = 1080,
            panelHeight = 700,
            requested = PreviewZoom.Auto,
        )
        setChrome(previewFit = fit)

        assertThat(fit.isAuto).isTrue()
        compose.onNodeWithText("(Auto)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `cycling the zoom reports the click`() {
        var cycles = 0
        compose.setContent {
            RoadguardTheme {
                VideoOverlayChrome(
                    state = MainUiState(),
                    previewFit = croppingFit(),
                    compact = false,
                    onProtect = {},
                    onToggleMicrophone = {},
                    onCyclePreviewZoom = { cycles++ },
                    onToggleMap = {},
                    onStartRecording = {},
                    onStopRecording = {},
                    onRequestPermissions = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithContentDescription("Display only", substring = true).performClick()
        assertThat(cycles).isEqualTo(1)
    }

    @Test
    fun `the caption is dropped in a compact window but the zoom control keeps its description`() {
        // A landscape phone has no room for the caption strip; the accessibility text still says it.
        setChrome(previewFit = croppingFit(), compact = true)

        compose.onNodeWithText("display only, recording is not cropped", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Display only", substring = true).assertExists()
    }

    // ── Blockers ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an actionable blocker shows its message and offers the fix`() {
        setChrome(
            state = MainUiState(
                recording = RecordingUiState(
                    status = RecorderStatus.Idle,
                    blockers = listOf(RecordingBlocker.CameraPermission),
                ),
            ),
        )

        compose.onNodeWithText(RecordingBlocker.CameraPermission.message).assertIsDisplayed()
        compose.onNodeWithText("Fix this").assertIsDisplayed()
    }

    @Test
    fun `a non-actionable blocker shows its message without a misleading button`() {
        setChrome(
            state = MainUiState(
                recording = RecordingUiState(
                    status = RecorderStatus.Idle,
                    blockers = listOf(RecordingBlocker.NoCamera),
                ),
            ),
        )

        compose.onNodeWithText(RecordingBlocker.NoCamera.message).assertIsDisplayed()
        compose.onNodeWithText("Fix this").assertDoesNotExist()
    }

    @Test
    fun `an actionable blocker is shown ahead of an informational one`() {
        setChrome(
            state = MainUiState(
                recording = RecordingUiState(
                    status = RecorderStatus.Idle,
                    blockers = listOf(RecordingBlocker.NoCamera, RecordingBlocker.StorageFull),
                ),
            ),
        )

        compose.onNodeWithText(RecordingBlocker.StorageFull.message).assertIsDisplayed()
        compose.onNodeWithText(RecordingBlocker.NoCamera.message).assertDoesNotExist()
    }

    @Test
    fun `the fix button reports the click`() {
        var requests = 0
        compose.setContent {
            RoadguardTheme {
                VideoOverlayChrome(
                    state = MainUiState(
                        recording = RecordingUiState(
                            blockers = listOf(RecordingBlocker.NotificationPermission),
                        ),
                    ),
                    previewFit = null,
                    compact = false,
                    onProtect = {},
                    onToggleMicrophone = {},
                    onCyclePreviewZoom = {},
                    onToggleMap = {},
                    onStartRecording = {},
                    onStopRecording = {},
                    onRequestPermissions = { requests++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithText("Fix this").performClick()
        assertThat(requests).isEqualTo(1)
    }

    @Test
    fun `no blocker means no centre overlay`() {
        setChrome(state = MainUiState(recording = RecordingUiState(status = RecorderStatus.Recording)))

        compose.onNodeWithText("Fix this").assertDoesNotExist()
    }

    // ── Microphone and map toggles ─────────────────────────────────────────────────────

    @Test
    fun `the microphone toggle offers to turn the microphone on when it is off`() {
        setChrome(state = MainUiState(settings = Settings(microphoneEnabled = false)))

        compose.onNodeWithContentDescription("Turn microphone recording on").assertExists()
        compose.onNodeWithContentDescription("Turn microphone recording off").assertDoesNotExist()
    }

    @Test
    fun `the microphone toggle offers to turn it off when it is on`() {
        setChrome(state = MainUiState(settings = Settings(microphoneEnabled = true)))

        compose.onNodeWithContentDescription("Turn microphone recording off").assertExists()
    }

    @Test
    fun `toggling the microphone requests the opposite of the current value`() {
        var requested: Boolean? = null
        compose.setContent {
            RoadguardTheme {
                VideoOverlayChrome(
                    state = MainUiState(settings = Settings(microphoneEnabled = false)),
                    previewFit = null,
                    compact = false,
                    onProtect = {},
                    onToggleMicrophone = { requested = it },
                    onCyclePreviewZoom = {},
                    onToggleMap = {},
                    onStartRecording = {},
                    onStopRecording = {},
                    onRequestPermissions = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithContentDescription("Turn microphone recording on").performClick()
        assertThat(requested).isTrue()
    }

    @Test
    fun `the map toggle describes the action, not the state`() {
        setChrome(state = MainUiState(settings = Settings(mapVisible = true)))

        compose.onNodeWithContentDescription("Hide the map").assertExists()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────

    private fun croppingFit(): PreviewFitResult = PreviewFit.compute(
        sourceWidth = 1920,
        sourceHeight = 1080,
        panelWidth = 1080,
        panelHeight = 1400,
        requested = PreviewZoom.X2_0,
    )

    private fun letterboxedFit(): PreviewFitResult = PreviewFit.compute(
        sourceWidth = 1920,
        sourceHeight = 1080,
        panelWidth = 1080,
        panelHeight = 1400,
        requested = PreviewZoom.X1_0,
    )

    private fun setChrome(
        state: MainUiState = MainUiState(),
        previewFit: PreviewFitResult? = null,
        compact: Boolean = false,
    ) {
        compose.setContent {
            RoadguardTheme {
                VideoOverlayChrome(
                    state = state,
                    previewFit = previewFit,
                    compact = compact,
                    onProtect = {},
                    onToggleMicrophone = {},
                    onCyclePreviewZoom = {},
                    onToggleMap = {},
                    onStartRecording = {},
                    onStopRecording = {},
                    onRequestPermissions = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
