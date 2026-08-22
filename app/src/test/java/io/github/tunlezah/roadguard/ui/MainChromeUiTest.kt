package io.github.tunlezah.roadguard.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.location.FixQuality
import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.recording.RecorderStatus
import io.github.tunlezah.roadguard.recording.RecordingUiState
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.SpeedUnit
import io.github.tunlezah.roadguard.storage.StorageAssessment
import io.github.tunlezah.roadguard.storage.StorageState
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.ui.main.MainControlBar
import io.github.tunlezah.roadguard.ui.main.MainStatusBar
import io.github.tunlezah.roadguard.ui.main.MainUiState
import io.github.tunlezah.roadguard.ui.theme.RoadguardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The driving screen's chrome, exercised on the JVM under Robolectric.
 *
 * These are real Compose UI tests -- they compose the production composables, read the semantics
 * tree and perform clicks -- but they live in `src/test`, so they run on every CI push rather than
 * only when somebody boots an emulator. What they cannot check is pixels, real font metrics and
 * gesture timing; `docs/testing.md` is explicit about that boundary.
 *
 * Two things are asserted throughout, because they are the two that matter while driving:
 *
 *  * **every control carries a content description** -- an icon alone is not a message, and a
 *    status chip deliberately collapses to a single accessibility node
 *    (`clearAndSetSemantics`), so the description *is* the contract; and
 *  * **state maps to the right control** -- a stop button that says "start" is a lost recording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class MainChromeUiTest {

    @get:Rule val compose = createComposeRule()

    // ── Control bar ────────────────────────────────────────────────────────────────────

    @Test
    fun `idle state offers start and not stop`() {
        setControlBar(state(RecorderStatus.Idle))

        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop recording").assertDoesNotExist()
    }

    @Test
    fun `recording state offers stop and not start`() {
        setControlBar(state(RecorderStatus.Recording))

        compose.onNodeWithContentDescription("Stop recording").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start recording").assertDoesNotExist()
    }

    @Test
    fun `rolling over still shows stop, because it is still recording`() {
        // A segment boundary must not make the UI look like recording stopped.
        setControlBar(state(RecorderStatus.RollingOver))

        compose.onNodeWithContentDescription("Stop recording").assertIsDisplayed()
    }

    @Test
    fun `stopping shows start, because the recording is ending`() {
        setControlBar(state(RecorderStatus.Stopping))

        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed()
    }

    @Test
    fun `protect is disabled when not recording`() {
        setControlBar(state(RecorderStatus.Idle))

        compose.onNodeWithText("Protect recording").assertIsNotEnabled()
    }

    @Test
    fun `protect is enabled while recording`() {
        setControlBar(state(RecorderStatus.Recording))

        compose.onNodeWithText("Protect recording").assertIsEnabled()
    }

    @Test
    fun `protect is enabled across a segment boundary`() {
        // The one moment a driver is most likely to hit Protect is just after an impact, which may
        // land during a rollover. It must not be disabled then.
        setControlBar(state(RecorderStatus.RollingOver))

        compose.onNodeWithText("Protect recording").assertIsEnabled()
    }

    @Test
    fun `protect reports the click`() {
        var protects = 0
        compose.setContent {
            RoadguardTheme {
                MainControlBar(
                    state = state(RecorderStatus.Recording),
                    compact = false,
                    onProtect = { protects++ },
                    onToggleMap = {},
                    onOpenSettings = {},
                    onOpenGallery = {},
                    onStartRecording = {},
                    onStopRecording = {},
                )
            }
        }

        compose.onNodeWithText("Protect recording").performClick()
        assertThat(protects).isEqualTo(1)
    }

    @Test
    fun `start fires only the start callback`() {
        var starts = 0
        var stops = 0
        compose.setContent {
            RoadguardTheme {
                MainControlBar(
                    state = state(RecorderStatus.Idle),
                    compact = false,
                    onProtect = {},
                    onToggleMap = {},
                    onOpenSettings = {},
                    onOpenGallery = {},
                    onStartRecording = { starts++ },
                    onStopRecording = { stops++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Start recording").performClick()
        assertThat(starts).isEqualTo(1)
        assertThat(stops).isEqualTo(0)
    }

    @Test
    fun `stop fires only the stop callback`() {
        var starts = 0
        var stops = 0
        compose.setContent {
            RoadguardTheme {
                MainControlBar(
                    state = state(RecorderStatus.Recording),
                    compact = false,
                    onProtect = {},
                    onToggleMap = {},
                    onOpenSettings = {},
                    onOpenGallery = {},
                    onStartRecording = { starts++ },
                    onStopRecording = { stops++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Stop recording").performClick()
        assertThat(stops).isEqualTo(1)
        assertThat(starts).isEqualTo(0)
    }

    @Test
    fun `the compact bar shortens the protect label but keeps every control`() {
        setControlBar(state(RecorderStatus.Recording), compact = true)

        compose.onNodeWithText("Protect").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop recording").assertIsDisplayed()
        compose.onNodeWithContentDescription("Recordings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `gallery and settings are reachable and described`() {
        setControlBar(MainUiState())

        compose.onNodeWithContentDescription("Recordings").assertHasClickAction()
        compose.onNodeWithContentDescription("Settings").assertHasClickAction()
    }

    // ── Status bar ─────────────────────────────────────────────────────────────────────

    @Test
    fun `speed is announced in full and rendered in the chosen unit`() {
        setStatusBar(
            MainUiState(
                settings = Settings(speedUnit = SpeedUnit.KilometresPerHour),
                location = LocationState(quality = FixQuality.Good, speedMetresPerSecond = 16.6667f),
            ),
        )

        compose.onNodeWithContentDescription("Speed 60 km/h").assertExists()
        // The chip collapses to one accessibility node, so the visible text lives in the
        // unmerged tree. Both matter: the description is what TalkBack reads, the text is what
        // the driver sees.
        compose.onNodeWithText("60 km/h", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `speed shows placeholder dashes rather than zero when there is no fix`() {
        // Showing "0 km/h" with no fix would be a lie a driver could act on.
        setStatusBar(MainUiState(location = LocationState(quality = FixQuality.NoSignal)))

        compose.onNodeWithContentDescription("Speed unavailable").assertExists()
        compose.onNodeWithText("-- km/h", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `mph is honoured`() {
        setStatusBar(
            MainUiState(
                settings = Settings(speedUnit = SpeedUnit.MilesPerHour),
                location = LocationState(quality = FixQuality.Good, speedMetresPerSecond = 26.8224f),
            ),
        )

        compose.onNodeWithContentDescription("Speed 60 mph").assertExists()
        compose.onNodeWithText("60 mph", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the gps chip announces satellite counts`() {
        setStatusBar(
            MainUiState(
                location = LocationState(
                    quality = FixQuality.Good,
                    satellitesUsed = 9,
                    satellitesVisible = 14,
                ),
            ),
        )

        compose.onNodeWithContentDescription(
            "GPS status: ${FixQuality.Good.label}, 9 of 14 satellites",
        ).assertExists()
    }

    @Test
    fun `location chips disappear entirely when location is off`() {
        setStatusBar(MainUiState(settings = Settings(locationEnabled = false)))

        compose.onNodeWithContentDescription("Speed unavailable").assertDoesNotExist()
    }

    @Test
    fun `there is no thermal chip at normal`() {
        setStatusBar(MainUiState(thermalLevel = ThermalLevel.Normal))

        compose.onNodeWithContentDescription("Device temperature: Normal").assertDoesNotExist()
    }

    @Test
    fun `a hot device gets a thermal chip that leads to diagnostics`() {
        var opened = 0
        compose.setContent {
            RoadguardTheme {
                MainStatusBar(
                    state = MainUiState(thermalLevel = ThermalLevel.High),
                    compact = false,
                    onOpenStorage = {},
                    onOpenDiagnostics = { opened++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Device temperature: High").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `a critical device also gets a chip`() {
        setStatusBar(MainUiState(thermalLevel = ThermalLevel.Critical))

        compose.onNodeWithContentDescription("Device temperature: Critical").assertExists()
    }

    @Test
    fun `the storage chip is tappable and reports its state`() {
        var opened = 0
        compose.setContent {
            RoadguardTheme {
                MainStatusBar(
                    state = MainUiState(storage = assessment(StorageState.Warning)),
                    compact = false,
                    onOpenStorage = { opened++ },
                    onOpenDiagnostics = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Loop storage used, tap for details").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `the microphone chip is absent by default`() {
        // The microphone is off by default, and its chip must not imply otherwise.
        setStatusBar(MainUiState(settings = Settings()))

        compose.onNodeWithContentDescription("Microphone recording is on").assertDoesNotExist()
    }

    @Test
    fun `the microphone chip appears when the microphone is enabled`() {
        setStatusBar(MainUiState(settings = Settings(microphoneEnabled = true)))

        compose.onNodeWithContentDescription("Microphone recording is on").assertExists()
    }

    @Test
    fun `the recording status chip reports the recorder state`() {
        setStatusBar(MainUiState(recording = RecordingUiState(status = RecorderStatus.Idle)))

        compose.onNodeWithContentDescription(RecorderStatus.Idle.label).assertExists()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────

    private fun state(status: RecorderStatus) =
        MainUiState(recording = RecordingUiState(status = status))

    private fun setControlBar(state: MainUiState, compact: Boolean = false) {
        compose.setContent {
            RoadguardTheme {
                MainControlBar(
                    state = state,
                    compact = compact,
                    onProtect = {},
                    onToggleMap = {},
                    onOpenSettings = {},
                    onOpenGallery = {},
                    onStartRecording = {},
                    onStopRecording = {},
                )
            }
        }
    }

    private fun setStatusBar(state: MainUiState) {
        compose.setContent {
            RoadguardTheme {
                MainStatusBar(
                    state = state,
                    compact = false,
                    onOpenStorage = {},
                    onOpenDiagnostics = {},
                )
            }
        }
    }

    private fun assessment(state: StorageState) = StorageAssessment(
        state = state,
        reserveBytes = 1L shl 30,
        effectiveBudgetBytes = 5L shl 30,
        requestedBudgetBytes = 5L shl 30,
        budgetLimitedByDevice = false,
        loopUsedBytes = 2L shl 30,
        protectedBytes = 0,
        mapBytes = 0,
        freeBytes = 20L shl 30,
        volumeTotalBytes = 64L shl 30,
        bytesToFree = 0,
        measuredBytesPerSecond = 0.0,
        loopCoverageSeconds = null,
        headroomSeconds = null,
    )
}
