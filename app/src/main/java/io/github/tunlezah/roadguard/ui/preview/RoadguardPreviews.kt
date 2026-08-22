package io.github.tunlezah.roadguard.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.camera.PreviewFit
import io.github.tunlezah.roadguard.location.FixQuality
import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.recording.RecorderStatus
import io.github.tunlezah.roadguard.recording.RecordingBlocker
import io.github.tunlezah.roadguard.recording.RecordingUiState
import io.github.tunlezah.roadguard.settings.PreviewZoom
import io.github.tunlezah.roadguard.settings.SegmentLength
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.ThemeSetting
import io.github.tunlezah.roadguard.storage.StorageAssessment
import io.github.tunlezah.roadguard.storage.StorageState
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.ui.main.MainControlBar
import io.github.tunlezah.roadguard.ui.main.MainStatusBar
import io.github.tunlezah.roadguard.ui.main.MainUiState
import io.github.tunlezah.roadguard.ui.main.VideoOverlayChrome
import io.github.tunlezah.roadguard.ui.settings.SettingsChoiceRow
import io.github.tunlezah.roadguard.ui.settings.SettingsSection
import io.github.tunlezah.roadguard.ui.settings.SettingsSliderRow
import io.github.tunlezah.roadguard.ui.settings.SettingsSwitchRow
import io.github.tunlezah.roadguard.ui.settings.SettingsWarning
import io.github.tunlezah.roadguard.ui.theme.RoadguardTheme

/*
 * Design-time previews.
 *
 * Roadguard's screens are driven by view models that own a camera, a recorder and a database, so
 * they cannot be previewed directly. The pieces that carry the *visual* design -- the status strip,
 * the control bar, the on-video chrome and the settings row primitives -- are all stateless, and
 * these previews render them with representative state.
 *
 * That makes the layouts inspectable in Android Studio's preview pane without a device, which is
 * the only way to look at this UI until somebody installs it on a phone. Behaviour is covered
 * separately by the Compose UI tests in `src/test` (see docs/testing.md).
 */

private val recordingState = MainUiState(
    recording = RecordingUiState(
        status = RecorderStatus.Recording,
        segmentIndex = 12,
        sessionSegmentCount = 12,
        sessionDurationMs = 36 * 60 * 1_000L,
    ),
    location = LocationState(
        quality = FixQuality.Good,
        latitude = -37.8136,
        longitude = 144.9631,
        speedMetresPerSecond = 16.6667f,
        satellitesUsed = 11,
        satellitesVisible = 17,
        permissionGranted = true,
    ),
    storage = StorageAssessment(
        state = StorageState.Ok,
        reserveBytes = 1L shl 30,
        effectiveBudgetBytes = 5L shl 30,
        requestedBudgetBytes = 5L shl 30,
        budgetLimitedByDevice = false,
        loopUsedBytes = 3L shl 30,
        protectedBytes = 512L shl 20,
        mapBytes = 1_133_229_927L,
        freeBytes = 24L shl 30,
        volumeTotalBytes = 64L shl 30,
        bytesToFree = 0,
        measuredBytesPerSecond = 780_000.0,
        loopCoverageSeconds = 6_880,
        headroomSeconds = 2_752,
    ),
)

@Preview(name = "Status bar - recording", showBackground = true, widthDp = 412)
@Composable
private fun StatusBarRecordingPreview() = RoadguardTheme(ThemeSetting.Dark) {
    MainStatusBar(state = recordingState, compact = false, onOpenStorage = {}, onOpenDiagnostics = {})
}

@Preview(name = "Status bar - hot and no fix", showBackground = true, widthDp = 412)
@Composable
private fun StatusBarWarningPreview() = RoadguardTheme(ThemeSetting.Dark) {
    MainStatusBar(
        state = recordingState.copy(
            thermalLevel = ThermalLevel.High,
            location = LocationState(quality = FixQuality.Searching, permissionGranted = true),
            settings = Settings(microphoneEnabled = true),
        ),
        compact = false,
        onOpenStorage = {},
        onOpenDiagnostics = {},
    )
}

@Preview(name = "Control bar - recording", showBackground = true, widthDp = 412)
@Composable
private fun ControlBarRecordingPreview() = RoadguardTheme(ThemeSetting.Dark) {
    MainControlBar(
        state = recordingState,
        compact = false,
        onProtect = {},
        onToggleMap = {},
        onOpenSettings = {},
        onOpenGallery = {},
        onStartRecording = {},
        onStopRecording = {},
    )
}

@Preview(name = "Control bar - idle", showBackground = true, widthDp = 412)
@Composable
private fun ControlBarIdlePreview() = RoadguardTheme(ThemeSetting.Dark) {
    MainControlBar(
        state = MainUiState(),
        compact = false,
        onProtect = {},
        onToggleMap = {},
        onOpenSettings = {},
        onOpenGallery = {},
        onStartRecording = {},
        onStopRecording = {},
    )
}

@Preview(name = "On-video chrome", showBackground = true, widthDp = 412, heightDp = 300)
@Composable
private fun VideoChromePreview() = RoadguardTheme(ThemeSetting.Dark) {
    // Black stands in for the camera image, which is what the chrome has to stay legible against.
    Column(Modifier.fillMaxWidth().height(300.dp).background(Color.Black)) {
        VideoOverlayChrome(
            state = recordingState,
            previewFit = PreviewFit.compute(1920, 1080, 1080, 1400, PreviewZoom.Auto),
            compact = false,
            onProtect = {},
            onToggleMicrophone = {},
            onCyclePreviewZoom = {},
            onToggleMap = {},
            onStartRecording = {},
            onStopRecording = {},
            onRequestPermissions = {},
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
    }
}

@Preview(name = "On-video chrome - blocked", showBackground = true, widthDp = 412, heightDp = 300)
@Composable
private fun VideoChromeBlockedPreview() = RoadguardTheme(ThemeSetting.Dark) {
    Column(Modifier.fillMaxWidth().height(300.dp).background(Color.Black)) {
        VideoOverlayChrome(
            state = MainUiState(
                recording = RecordingUiState(blockers = listOf(RecordingBlocker.CameraPermission)),
            ),
            previewFit = null,
            compact = false,
            onProtect = {},
            onToggleMicrophone = {},
            onCyclePreviewZoom = {},
            onToggleMap = {},
            onStartRecording = {},
            onStopRecording = {},
            onRequestPermissions = {},
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
    }
}

@Preview(name = "Settings rows - dark", showBackground = true, widthDp = 412)
@Composable
private fun SettingsRowsDarkPreview() = RoadguardTheme(ThemeSetting.Dark) { SettingsRowsSample() }

@Preview(name = "Settings rows - light", showBackground = true, widthDp = 412)
@Composable
private fun SettingsRowsLightPreview() = RoadguardTheme(ThemeSetting.Light) { SettingsRowsSample() }

@Preview(name = "Settings rows - OLED", showBackground = true, widthDp = 412)
@Composable
private fun SettingsRowsOledPreview() = RoadguardTheme(ThemeSetting.Oled) { SettingsRowsSample() }

@Composable
private fun SettingsRowsSample() {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        SettingsSection(
            title = "Overlays",
            subtitle = "These are burned into the video and cannot be removed afterwards",
        ) {
            SettingsSwitchRow(
                title = "Date and time",
                iconRes = R.drawable.ic_schedule,
                checked = true,
                onCheckedChange = {},
            )
            SettingsSwitchRow(
                title = "Coordinates",
                iconRes = R.drawable.ic_pin_drop,
                checked = false,
                onCheckedChange = {},
                subtitle = "Anyone you share a clip with will see where it was recorded",
            )
            SettingsChoiceRow(
                title = "Segment length",
                iconRes = R.drawable.ic_movie,
                currentLabel = SegmentLength.Minutes3.label,
                onClick = {},
            )
            SettingsSwitchRow(
                title = "Dual camera",
                iconRes = R.drawable.ic_flip_camera_android,
                checked = false,
                onCheckedChange = {},
                subtitle = "This device reports no concurrent camera pairs",
                enabled = false,
            )
            SettingsSliderRow(
                title = "Start-up delay",
                valueLabel = "3 seconds",
                value = 3f,
                range = 0f..30f,
                steps = 29,
                onValueChange = {},
            )
            SettingsWarning(text = "Recording zoom permanently narrows the recorded field of view")
        }
    }
}
