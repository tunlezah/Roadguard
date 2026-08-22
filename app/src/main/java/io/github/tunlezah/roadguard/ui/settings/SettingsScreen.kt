package io.github.tunlezah.roadguard.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.settings.CameraFacing
import io.github.tunlezah.roadguard.settings.EventSensitivity
import io.github.tunlezah.roadguard.settings.FrameRateSetting
import io.github.tunlezah.roadguard.settings.GpsStorageMode
import io.github.tunlezah.roadguard.settings.LoopBudget
import io.github.tunlezah.roadguard.settings.OrientationMode
import io.github.tunlezah.roadguard.settings.POST_EVENT_OPTIONS
import io.github.tunlezah.roadguard.settings.PRE_EVENT_OPTIONS
import io.github.tunlezah.roadguard.settings.PowerConnectedAction
import io.github.tunlezah.roadguard.settings.PowerDisconnectedAction
import io.github.tunlezah.roadguard.settings.PreviewZoom
import io.github.tunlezah.roadguard.settings.QualitySetting
import io.github.tunlezah.roadguard.settings.SegmentLength
import io.github.tunlezah.roadguard.settings.SpeedUnit
import io.github.tunlezah.roadguard.settings.ThemeSetting
import io.github.tunlezah.roadguard.settings.TriState
import io.github.tunlezah.roadguard.storage.StorageVolumeOption
import kotlin.math.roundToInt

/**
 * Every Roadguard setting, on one scrolling screen.
 *
 * ### One screen, not a tree
 *
 * A settings tree looks tidier and is worse to use: a driver setting the app up in a car park wants
 * to see what exists and change three things, not navigate. Sections give the structure a tree would
 * have given, without the navigation.
 *
 * ### Nothing pretends to work
 *
 * Several settings depend on what the device can actually do -- dual camera on concurrent-camera
 * support, stabilisation on the camera reporting it, a resolution on the camera listing it, weather
 * on a usable source existing. Each of those is shown **disabled with the reason**, never hidden and
 * never silently ignored. The reasons come from [SettingsUiState], which computes them from probed
 * capability.
 *
 * ### "Why Auto chose this"
 *
 * The active profile's rationale is rendered verbatim under Recording. It is the most useful thing
 * on the screen: it is how a user finds out that Auto picked 720p because their camera does not
 * report 1080p, rather than assuming the app is broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Recording ─────────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Recording",
                subtitle = "Auto picks the highest quality this device can sustain for hours, " +
                    "which is not the same as the highest it can technically manage.",
            ) {
                SettingsChoiceRow(
                    title = "Quality",
                    subtitle = state.supportedQualitySummary,
                    iconRes = R.drawable.ic_hd,
                    currentLabel = settings.quality.label,
                    onClick = { dialog = SettingsDialog.Quality },
                )
                state.quality(settings.quality).reason?.let { SettingsWarning(it) }

                SettingsChoiceRow(
                    title = "Frame rate",
                    iconRes = R.drawable.ic_speed,
                    currentLabel = settings.frameRate.label,
                    onClick = { dialog = SettingsDialog.FrameRate },
                )
                state.frameRate(settings.frameRate).reason?.let { SettingsWarning(it) }

                SettingsChoiceRow(
                    title = "Segment length",
                    subtitle = "Shorter segments lose less footage if the phone loses power mid-clip.",
                    iconRes = R.drawable.ic_movie,
                    currentLabel = settings.segmentLength.label,
                    onClick = { dialog = SettingsDialog.SegmentLength },
                )
                SettingsChoiceRow(
                    title = "Camera",
                    iconRes = R.drawable.ic_cameraswitch,
                    currentLabel = settings.cameraFacing.label,
                    onClick = { dialog = SettingsDialog.CameraFacing },
                )
                SettingsSwitchRow(
                    title = "Record both cameras",
                    subtitle = "Front and rear at the same time.",
                    iconRes = R.drawable.ic_flip_camera_android,
                    checked = settings.dualCameraEnabled,
                    enabled = state.dualCamera.available,
                    onCheckedChange = { enabled -> viewModel.update { it.copy(dualCameraEnabled = enabled) } },
                )
                state.dualCamera.reason?.let { SettingsNote(it) }

                SettingsChoiceRow(
                    title = "Video stabilisation",
                    iconRes = R.drawable.ic_vibration,
                    currentLabel = settings.videoStabilisation.label,
                    onClick = { dialog = SettingsDialog.Stabilisation },
                    enabled = state.stabilisation.available,
                )
                state.stabilisation.reason?.let { SettingsNote(it) }

                SettingsChoiceRow(
                    title = "Low-light assist",
                    iconRes = R.drawable.ic_nights_stay,
                    currentLabel = settings.nightAssist.label,
                    onClick = { dialog = SettingsDialog.NightAssist },
                    enabled = state.nightAssist.available,
                )
                state.nightAssist.reason?.let { SettingsNote(it) }

                SettingsSwitchRow(
                    title = "Record audio",
                    subtitle = "Off by default. Roadguard only asks for microphone permission if " +
                        "you turn this on, and conversations in the cabin are recorded when it is on.",
                    iconRes = if (settings.microphoneEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off,
                    checked = settings.microphoneEnabled,
                    onCheckedChange = { enabled -> viewModel.update { it.copy(microphoneEnabled = enabled) } },
                )

                SettingsSliderRow(
                    title = "Recording zoom",
                    valueLabel = "%.2fx".format(settings.recordingZoom),
                    value = settings.recordingZoom,
                    range = 1f..4f,
                    steps = 11,
                    onValueChange = { value ->
                        viewModel.update { it.copy(recordingZoom = (value * 100).roundToInt() / 100f) }
                    },
                )
                if (settings.recordingZoom > 1f) {
                    SettingsWarning(
                        "Recording zoom narrows the recorded field of view permanently. 1.00x is " +
                            "recommended for a dashcam: a wider frame catches more of what happened.",
                    )
                }

                state.profile?.let { profile ->
                    SettingsInfoRow(
                        title = if (state.profileIsPredicted) "Profile Auto would choose" else "Active profile",
                        value = profile.label,
                        iconRes = R.drawable.ic_high_quality,
                    )
                    profile.rationale.forEach { reason -> SettingsNote(reason) }
                }
            }

            // ── Preview and display ───────────────────────────────────────────────────────────
            SettingsSection(
                title = "Preview and display",
                subtitle = "These change what you see, not what is recorded.",
            ) {
                SettingsChoiceRow(
                    title = "Preview zoom",
                    subtitle = "Display only. The recorded video is never cropped to fit the screen.",
                    iconRes = R.drawable.ic_zoom_out_map,
                    currentLabel = settings.previewZoom.label,
                    onClick = { dialog = SettingsDialog.PreviewZoom },
                )
                SettingsSwitchRow(
                    title = "Show the map",
                    iconRes = R.drawable.ic_map,
                    checked = settings.mapVisible,
                    onCheckedChange = { visible -> viewModel.update { it.copy(mapVisible = visible) } },
                )
                SettingsChoiceRow(
                    title = "Theme",
                    iconRes = R.drawable.ic_palette,
                    currentLabel = settings.theme.label,
                    onClick = { dialog = SettingsDialog.Theme },
                )
                SettingsSwitchRow(
                    title = "Use wallpaper colours",
                    subtitle = "Recording red and protected amber stay fixed either way.",
                    iconRes = R.drawable.ic_contrast,
                    checked = settings.useDynamicColour,
                    onCheckedChange = { on -> viewModel.update { it.copy(useDynamicColour = on) } },
                )
                SettingsChoiceRow(
                    title = "Screen orientation",
                    subtitle = "Follow device matches however the phone is mounted, even with " +
                        "rotation lock on. The recording always follows the phone.",
                    iconRes = R.drawable.ic_screen_lock_portrait,
                    currentLabel = settings.orientationMode.label,
                    onClick = { dialog = SettingsDialog.Orientation },
                )
                SettingsSwitchRow(
                    title = "Keep the screen on",
                    iconRes = R.drawable.ic_brightness_high,
                    checked = settings.keepScreenOn,
                    onCheckedChange = { on -> viewModel.update { it.copy(keepScreenOn = on) } },
                )
                SettingsSwitchRow(
                    title = "Stop drawing the preview when hidden",
                    subtitle = "Saves power and heat while recording continues in the background.",
                    iconRes = R.drawable.ic_visibility_off,
                    checked = settings.screenOffDimming,
                    onCheckedChange = { on -> viewModel.update { it.copy(screenOffDimming = on) } },
                )
            }

            // ── Overlays ──────────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Video overlays",
                subtitle = "These are burned into the recorded file. The indicators around the " +
                    "camera preview are on-screen only and are never in the video.",
            ) {
                SettingsSwitchRow(
                    title = "Date and time",
                    iconRes = R.drawable.ic_schedule,
                    checked = settings.overlayDateTime,
                    onCheckedChange = { on -> viewModel.update { it.copy(overlayDateTime = on) } },
                )
                SettingsSwitchRow(
                    title = "Speed",
                    iconRes = R.drawable.ic_speed,
                    checked = settings.overlaySpeed,
                    enabled = settings.locationEnabled && settings.gpsStorage.overlay,
                    onCheckedChange = { on -> viewModel.update { it.copy(overlaySpeed = on) } },
                )
                SettingsSwitchRow(
                    title = "Coordinates",
                    subtitle = "Writes your location into the video itself. Off by default.",
                    iconRes = R.drawable.ic_pin_drop,
                    checked = settings.overlayCoordinates,
                    enabled = settings.locationEnabled && settings.gpsStorage.overlay,
                    onCheckedChange = { on -> viewModel.update { it.copy(overlayCoordinates = on) } },
                )
                SettingsSwitchRow(
                    title = "Weather",
                    iconRes = R.drawable.ic_cloud,
                    checked = settings.overlayWeather,
                    enabled = settings.weatherEnabled && state.weather.available,
                    onCheckedChange = { on -> viewModel.update { it.copy(overlayWeather = on) } },
                )
                if (!settings.locationEnabled) {
                    SettingsNote("Speed and coordinates need location, which is turned off below.")
                }
                SettingsNote(
                    "Burning overlays in costs one extra GPU pass. Roadguard drops it automatically " +
                        "if the device gets too hot, and keeps recording.",
                )
            }

            // ── Startup ───────────────────────────────────────────────────────────────────────
            SettingsSection(title = "Startup") {
                SettingsSwitchRow(
                    title = "Start recording automatically",
                    iconRes = R.drawable.ic_fiber_manual_record,
                    checked = settings.autoStartRecording,
                    onCheckedChange = { on -> viewModel.update { it.copy(autoStartRecording = on) } },
                )
                SettingsSliderRow(
                    title = "Start-up delay",
                    valueLabel = "${settings.startupDelaySeconds} s",
                    value = settings.startupDelaySeconds.toFloat(),
                    range = 0f..30f,
                    steps = 29,
                    onValueChange = { value ->
                        viewModel.update { it.copy(startupDelaySeconds = value.roundToInt()) }
                    },
                )
                SettingsNote(
                    "A short delay lets the camera's exposure settle and gives you a moment to seat " +
                        "the phone before the first clip starts.",
                )
            }

            // ── Events ────────────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Incident detection",
                subtitle = "Saves footage from around a detected impact so the loop cannot delete it.",
            ) {
                SettingsSwitchRow(
                    title = "Detect incidents",
                    iconRes = R.drawable.ic_sensors,
                    checked = settings.eventDetectionEnabled,
                    onCheckedChange = { on -> viewModel.update { it.copy(eventDetectionEnabled = on) } },
                )
                SettingsChoiceRow(
                    title = "Sensitivity",
                    iconRes = R.drawable.ic_tune,
                    currentLabel = settings.eventSensitivity.label,
                    enabled = settings.eventDetectionEnabled,
                    onClick = { dialog = SettingsDialog.EventSensitivity },
                )
                SettingsChoiceRow(
                    title = "Keep before an incident",
                    iconRes = R.drawable.ic_restore,
                    currentLabel = "${settings.preEventSeconds} s",
                    enabled = settings.eventDetectionEnabled,
                    onClick = { dialog = SettingsDialog.PreEvent },
                )
                SettingsChoiceRow(
                    title = "Keep after an incident",
                    iconRes = R.drawable.ic_history,
                    currentLabel = "${settings.postEventSeconds} s",
                    enabled = settings.eventDetectionEnabled,
                    onClick = { dialog = SettingsDialog.PostEvent },
                )
                SettingsWarning(
                    "Roadguard is not a certified crash detector. It watches the accelerometer for " +
                        "an impact signature and can miss real collisions or react to a bad pothole. " +
                        "Use Protect recording whenever something matters.",
                )
                state.capabilities?.sensors?.let { sensors ->
                    if (!sensors.hasGyroscope) {
                        SettingsNote(
                            "This device has no gyroscope, so detection runs on the accelerometer " +
                                "alone and is a little more cautious.",
                        )
                    }
                }
            }

            // ── Location ──────────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Location",
                subtitle = "GPS works with no SIM and no mobile data. Nothing is ever uploaded.",
            ) {
                SettingsSwitchRow(
                    title = "Use location",
                    iconRes = R.drawable.ic_my_location,
                    checked = settings.locationEnabled,
                    onCheckedChange = { on -> viewModel.update { it.copy(locationEnabled = on) } },
                )
                SettingsChoiceRow(
                    title = "Speed units",
                    iconRes = R.drawable.ic_speed,
                    currentLabel = settings.speedUnit.label,
                    onClick = { dialog = SettingsDialog.SpeedUnit },
                )
                SettingsChoiceRow(
                    title = "Store location data",
                    iconRes = R.drawable.ic_privacy_tip,
                    currentLabel = settings.gpsStorage.label,
                    onClick = { dialog = SettingsDialog.GpsStorage },
                )
            }

            // ── Power ─────────────────────────────────────────────────────────────────────────
            SettingsSection(
                title = "Power",
                subtitle = "In a vehicle, power appearing and disappearing is usually the ignition.",
            ) {
                SettingsChoiceRow(
                    title = "When power is connected",
                    iconRes = R.drawable.ic_bolt,
                    currentLabel = settings.onPowerConnected.label,
                    onClick = { dialog = SettingsDialog.PowerConnected },
                )
                SettingsChoiceRow(
                    title = "When power is disconnected",
                    iconRes = R.drawable.ic_power_off,
                    currentLabel = settings.onPowerDisconnected.label,
                    onClick = { dialog = SettingsDialog.PowerDisconnected },
                )
                if (settings.onPowerDisconnected == PowerDisconnectedAction.StopAfterDelay) {
                    SettingsSliderRow(
                        title = "Stop after",
                        valueLabel = "${settings.powerDisconnectStopDelaySeconds / 60} min",
                        value = (settings.powerDisconnectStopDelaySeconds / 60).toFloat(),
                        range = 1f..30f,
                        steps = 28,
                        onValueChange = { value ->
                            viewModel.update {
                                it.copy(powerDisconnectStopDelaySeconds = value.roundToInt() * 60)
                            }
                        },
                    )
                }
                SettingsSliderRow(
                    title = "Battery-safe below",
                    valueLabel = "${settings.batterySafeThresholdPercent}%",
                    value = settings.batterySafeThresholdPercent.toFloat(),
                    range = 0f..50f,
                    steps = 9,
                    onValueChange = { value ->
                        viewModel.update { it.copy(batterySafeThresholdPercent = value.roundToInt()) }
                    },
                )
                SettingsNote(
                    "Below 3% Roadguard stops recording regardless, so the last clip is closed " +
                        "cleanly instead of being cut off by the phone powering down.",
                )
            }

            // ── Weather ───────────────────────────────────────────────────────────────────────
            SettingsSection(title = "Weather") {
                SettingsSwitchRow(
                    title = "Show weather",
                    subtitle = if (state.weatherSupported) {
                        "Source: ${state.weatherSourceName}. Cached, and never required for recording."
                    } else {
                        null
                    },
                    iconRes = R.drawable.ic_cloud,
                    checked = settings.weatherEnabled,
                    enabled = state.weather.available,
                    onCheckedChange = { on -> viewModel.update { it.copy(weatherEnabled = on) } },
                )
                state.weather.reason?.let { SettingsNote(it) }
            }

            // ── Map ───────────────────────────────────────────────────────────────────────────
            SettingsSection(title = "Map") {
                SettingsSwitchRow(
                    title = "Follow the vehicle",
                    iconRes = R.drawable.ic_near_me,
                    checked = settings.mapFollowsVehicle,
                    onCheckedChange = { on -> viewModel.update { it.copy(mapFollowsVehicle = on) } },
                )
                SettingsSwitchRow(
                    title = "Keep north up",
                    subtitle = "Otherwise the map rotates to your direction of travel.",
                    iconRes = R.drawable.ic_north,
                    checked = settings.mapNorthUp,
                    onCheckedChange = { on -> viewModel.update { it.copy(mapNorthUp = on) } },
                )
                SettingsSwitchRow(
                    title = "Install and update the map automatically",
                    iconRes = R.drawable.ic_download_for_offline,
                    checked = settings.mapAutoDownload,
                    onCheckedChange = { on -> viewModel.update { it.copy(mapAutoDownload = on) } },
                )
            }

            // ── Storage ───────────────────────────────────────────────────────────────────────
            SettingsSection(title = "Storage") {
                SettingsChoiceRow(
                    title = "Loop size",
                    subtitle = "How much footage Roadguard keeps before it starts deleting the oldest.",
                    iconRes = R.drawable.ic_storage,
                    currentLabel = formatBudget(settings.loopBudgetBytes),
                    onClick = { dialog = SettingsDialog.LoopBudget },
                )
                if (state.storageVolumes.size > 1) {
                    SettingsChoiceRow(
                        title = "Record to",
                        iconRes = R.drawable.ic_sd_card,
                        currentLabel = state.selectedVolume?.label ?: "Internal storage",
                        onClick = { dialog = SettingsDialog.StorageVolume },
                    )
                }
                SettingsNavigationRow(
                    title = "Storage and protected footage",
                    iconRes = R.drawable.ic_folder,
                    onClick = onOpenStorage,
                )
            }

            // ── Elsewhere ─────────────────────────────────────────────────────────────────────
            HorizontalDivider(modifier = Modifier.padding(top = 20.dp))
            SettingsSection(title = "More") {
                SettingsNavigationRow(
                    title = "Recordings",
                    iconRes = R.drawable.ic_video_library,
                    onClick = onOpenGallery,
                )
                SettingsNavigationRow(
                    title = "Diagnostics",
                    subtitle = "What Roadguard probed on this device, and what it measured.",
                    iconRes = R.drawable.ic_bug_report,
                    onClick = onOpenDiagnostics,
                )
                SettingsNavigationRow(
                    title = "Privacy",
                    iconRes = R.drawable.ic_privacy_tip,
                    onClick = onOpenPrivacy,
                )
                SettingsNavigationRow(
                    title = "About Roadguard",
                    iconRes = R.drawable.ic_help_outline,
                    onClick = onOpenAbout,
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────────────────────
    when (dialog) {
        SettingsDialog.Quality -> SettingsChoiceDialog(
            title = "Quality",
            options = QualitySetting.entries,
            currentValue = settings.quality,
            labelFor = { it.label },
            descriptionFor = { state.quality(it).reason },
            enabledFor = { state.quality(it).available },
            onPick = { value ->
                viewModel.update { it.copy(quality = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.FrameRate -> SettingsChoiceDialog(
            title = "Frame rate",
            options = FrameRateSetting.entries,
            currentValue = settings.frameRate,
            labelFor = { it.label },
            descriptionFor = { state.frameRate(it).reason },
            enabledFor = { state.frameRate(it).available },
            onPick = { value ->
                viewModel.update { it.copy(frameRate = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.SegmentLength -> SettingsChoiceDialog(
            title = "Segment length",
            options = SegmentLength.entries,
            currentValue = settings.segmentLength,
            labelFor = { it.label },
            onPick = { value ->
                viewModel.update { it.copy(segmentLength = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.CameraFacing -> SettingsChoiceDialog(
            title = "Camera",
            options = CameraFacing.entries,
            currentValue = settings.cameraFacing,
            labelFor = { it.label },
            descriptionFor = { facing ->
                if (facing == CameraFacing.Front) {
                    "A front camera points at the cabin, not the road."
                } else {
                    null
                }
            },
            onPick = { value ->
                viewModel.update { it.copy(cameraFacing = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.Stabilisation -> SettingsChoiceDialog(
            title = "Video stabilisation",
            options = TriState.entries,
            currentValue = settings.videoStabilisation,
            labelFor = { it.label },
            descriptionFor = { option ->
                if (option == TriState.Auto) {
                    "Off unless the device is fast enough for it. Stabilisation crops the frame " +
                        "and costs power, and a cradled phone barely needs it."
                } else {
                    null
                }
            },
            onPick = { value ->
                viewModel.update { it.copy(videoStabilisation = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.NightAssist -> SettingsChoiceDialog(
            title = "Low-light assist",
            options = TriState.entries,
            currentValue = settings.nightAssist,
            labelFor = { it.label },
            onPick = { value ->
                viewModel.update { it.copy(nightAssist = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.PreviewZoom -> SettingsChoiceDialog(
            title = "Preview zoom",
            options = PreviewZoom.entries,
            currentValue = settings.previewZoom,
            labelFor = { it.label },
            descriptionFor = { option ->
                if (option == PreviewZoom.Auto) {
                    "Fills the panel where it can without throwing away too much of the road."
                } else {
                    null
                }
            },
            onPick = { value ->
                viewModel.update { it.copy(previewZoom = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.Theme -> SettingsChoiceDialog(
            title = "Theme",
            options = ThemeSetting.entries,
            currentValue = settings.theme,
            labelFor = { it.label },
            descriptionFor = { option ->
                if (option == ThemeSetting.Oled) "True black, for OLED screens at night." else null
            },
            onPick = { value ->
                viewModel.update { it.copy(theme = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.Orientation -> SettingsChoiceDialog(
            title = "Screen orientation",
            options = OrientationMode.entries,
            currentValue = settings.orientationMode,
            labelFor = { it.label },
            descriptionFor = { option ->
                when (option) {
                    OrientationMode.FollowDevice ->
                        "Matches how the phone is mounted, even with rotation lock on."

                    OrientationMode.FollowSystem -> "Respects Android's rotation lock."
                    else -> "The recording still follows the phone, not the screen."
                }
            },
            onPick = { value ->
                viewModel.update { it.copy(orientationMode = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.EventSensitivity -> SettingsChoiceDialog(
            title = "Sensitivity",
            options = EventSensitivity.entries,
            currentValue = settings.eventSensitivity,
            labelFor = { it.label },
            onPick = { value ->
                viewModel.update { it.copy(eventSensitivity = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.PreEvent -> SettingsChoiceDialog(
            title = "Keep before an incident",
            options = PRE_EVENT_OPTIONS,
            currentValue = settings.preEventSeconds,
            labelFor = { "$it seconds" },
            onPick = { value ->
                viewModel.update { it.copy(preEventSeconds = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.PostEvent -> SettingsChoiceDialog(
            title = "Keep after an incident",
            options = POST_EVENT_OPTIONS,
            currentValue = settings.postEventSeconds,
            labelFor = { "$it seconds" },
            onPick = { value ->
                viewModel.update { it.copy(postEventSeconds = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.SpeedUnit -> SettingsChoiceDialog(
            title = "Speed units",
            options = SpeedUnit.entries,
            currentValue = settings.speedUnit,
            labelFor = { it.label },
            onPick = { value ->
                viewModel.update { it.copy(speedUnit = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.GpsStorage -> SettingsChoiceDialog(
            title = "Store location data",
            options = GpsStorageMode.entries,
            currentValue = settings.gpsStorage,
            labelFor = { it.label },
            descriptionFor = { mode ->
                buildList {
                    if (mode.overlay) add("shown on screen and in the video")
                    if (mode.metadata) add("written into the video file")
                    if (mode.track) add("saved as a GPX track")
                    if (isEmpty()) add("nothing is stored")
                }.joinToString(", ")
            },
            onPick = { value ->
                viewModel.update { it.copy(gpsStorage = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.PowerConnected -> SettingsChoiceDialog(
            title = "When power is connected",
            options = PowerConnectedAction.entries,
            currentValue = settings.onPowerConnected,
            labelFor = { it.label },
            onPick = { value ->
                viewModel.update { it.copy(onPowerConnected = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.PowerDisconnected -> SettingsChoiceDialog(
            title = "When power is disconnected",
            options = PowerDisconnectedAction.entries,
            currentValue = settings.onPowerDisconnected,
            labelFor = { it.label },
            onPick = { value ->
                viewModel.update { it.copy(onPowerDisconnected = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.LoopBudget -> SettingsChoiceDialog(
            title = "Loop size",
            options = LoopBudget.presets,
            currentValue = settings.loopBudgetBytes,
            labelFor = { formatBudget(it) },
            onPick = { value ->
                viewModel.update { it.copy(loopBudgetBytes = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.StorageVolume -> SettingsChoiceDialog(
            title = "Record to",
            options = state.storageVolumes,
            currentValue = state.selectedVolume ?: state.storageVolumes.firstOrNull(),
            labelFor = { option -> option?.label ?: "Internal storage" },
            descriptionFor = { option ->
                option?.let { "${it.freeBytes / (1024 * 1024 * 1024)} GB free of ${it.totalBytes / (1024 * 1024 * 1024)} GB" }
            },
            onPick = { value ->
                viewModel.update { it.copy(storageVolumeId = value?.id) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }
}

/** Which choice dialog is open. */
private enum class SettingsDialog {
    Quality,
    FrameRate,
    SegmentLength,
    CameraFacing,
    Stabilisation,
    NightAssist,
    PreviewZoom,
    Theme,
    Orientation,
    EventSensitivity,
    PreEvent,
    PostEvent,
    SpeedUnit,
    GpsStorage,
    PowerConnected,
    PowerDisconnected,
    LoopBudget,
    StorageVolume,
}

private fun formatBudget(bytes: Long): String {
    val gigabytes = bytes.toDouble() / (1024.0 * 1024 * 1024)
    return if (gigabytes >= 1) "%.0f GB".format(gigabytes) else "${bytes / (1024 * 1024)} MB"
}

private fun StorageVolumeOption?.orDefault(): String = this?.label ?: "Internal storage"
