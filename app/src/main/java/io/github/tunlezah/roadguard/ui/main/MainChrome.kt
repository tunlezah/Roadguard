package io.github.tunlezah.roadguard.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.camera.PreviewFitResult
import io.github.tunlezah.roadguard.recording.RecorderStatus
import io.github.tunlezah.roadguard.storage.StorageState
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.ui.components.RecordingDot
import io.github.tunlezah.roadguard.ui.components.StatusChip
import io.github.tunlezah.roadguard.ui.components.gpsIconFor
import io.github.tunlezah.roadguard.ui.components.thermalIconFor
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors

/**
 * The thin status strip above the two panes.
 *
 * Everything here is glanceable and nothing here is a control the driver needs to hunt for: state
 * first, and the two chips that lead somewhere (storage, diagnostics) are only tappable, never
 * required.
 */
@Composable
fun MainStatusBar(
    state: MainUiState,
    compact: Boolean,
    onOpenStorage: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalRoadguardStatusColors.current
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 8.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecordingStatusChip(state)

        val speed = state.location.speedIn(state.settings.speedUnit)
        if (state.settings.locationEnabled) {
            StatusChip(
                text = speed?.let { "$it ${state.settings.speedUnit.suffix}" } ?: "-- ${state.settings.speedUnit.suffix}",
                iconRes = R.drawable.ic_speed,
                contentDescription = speed?.let { "Speed $it ${state.settings.speedUnit.suffix}" }
                    ?: "Speed unavailable",
            )
            StatusChip(
                text = state.location.quality.label,
                iconRes = gpsIconFor(state.location.quality),
                contentDescription = "GPS status: ${state.location.quality.label}, " +
                    "${state.location.satellitesUsed} of ${state.location.satellitesVisible} satellites",
                contentColour = if (state.location.quality.isTrustworthy) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    status.warning
                },
            )
        }

        state.storage?.let { assessment ->
            StatusChip(
                text = "${assessment.loopUsedBytes / (1024 * 1024 * 1024)}/" +
                    "${assessment.effectiveBudgetBytes / (1024 * 1024 * 1024)} GB",
                iconRes = R.drawable.ic_storage,
                contentDescription = "Loop storage used, tap for details",
                contentColour = when (assessment.state) {
                    StorageState.Ok -> MaterialTheme.colorScheme.onSurface
                    StorageState.Warning -> status.warning
                    StorageState.Critical -> status.critical
                },
                modifier = Modifier.clickableChip(onOpenStorage),
            )
        }

        if (state.thermalLevel != ThermalLevel.Normal) {
            StatusChip(
                text = state.thermalLevel.label,
                iconRes = thermalIconFor(state.thermalLevel),
                contentDescription = "Device temperature: ${state.thermalLevel.label}",
                contentColour = if (state.thermalLevel == ThermalLevel.Critical) status.critical else status.warning,
                modifier = Modifier.clickableChip(onOpenDiagnostics),
            )
        }

        if (state.settings.microphoneEnabled) {
            StatusChip(
                text = "Mic",
                iconRes = R.drawable.ic_mic,
                contentDescription = "Microphone recording is on",
            )
        }
    }
}

@Composable
private fun RecordingStatusChip(state: MainUiState) {
    val status = LocalRoadguardStatusColors.current
    val recording = state.recording
    when (recording.status) {
        RecorderStatus.Recording, RecorderStatus.RollingOver -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RecordingDot(colour = status.recording, contentDescription = "Recording")
            Text(
                text = recording.segmentStartedAtEpochMs?.let { "REC" } ?: "REC",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = status.recording,
            )
        }

        RecorderStatus.Starting -> StatusChip(
            text = recording.startupCountdownSeconds?.let { "Starting in $it s" } ?: "Starting",
            iconRes = R.drawable.ic_schedule,
            contentDescription = "Recording is starting",
        )

        RecorderStatus.Stopping -> StatusChip(
            text = "Stopping",
            iconRes = R.drawable.ic_schedule,
            contentDescription = "Recording is stopping",
        )

        RecorderStatus.Failed -> StatusChip(
            text = "Stopped",
            iconRes = R.drawable.ic_error,
            contentDescription = recording.lastErrorMessage ?: "Recording stopped because of an error",
            contentColour = status.critical,
        )

        RecorderStatus.Idle -> StatusChip(
            text = "Not recording",
            iconRes = R.drawable.ic_videocam_off,
            contentDescription = "Not recording",
            contentColour = status.idle,
        )
    }
}

/**
 * The controls drawn over the camera image.
 *
 * These are *screen-only* indicators. Anything burned into the video is drawn by the recorder's
 * overlay effect, and the caption at the bottom of the pane says which is which so a user is never
 * left guessing whether what they see is in the file.
 */
@Composable
fun VideoOverlayChrome(
    state: MainUiState,
    previewFit: PreviewFitResult?,
    compact: Boolean,
    onProtect: () -> Unit,
    onToggleMicrophone: (Boolean) -> Unit,
    onCyclePreviewZoom: () -> Unit,
    onToggleMap: (Boolean) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalRoadguardStatusColors.current
    Box(modifier = modifier.padding(10.dp)) {
        // Top-right: display-only controls, kept small and out of the road view.
        Column(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OverlayIconButton(
                iconRes = R.drawable.ic_zoom_out_map,
                contentDescription = "Preview zoom: ${previewFit?.describe() ?: "auto"}. Display only.",
                onClick = onCyclePreviewZoom,
            )
            OverlayIconButton(
                iconRes = if (state.settings.mapVisible) R.drawable.ic_fullscreen else R.drawable.ic_map,
                contentDescription = if (state.settings.mapVisible) "Hide the map" else "Show the map",
                onClick = { onToggleMap(!state.settings.mapVisible) },
            )
            OverlayIconButton(
                iconRes = if (state.settings.microphoneEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off,
                contentDescription = if (state.settings.microphoneEnabled) {
                    "Turn microphone recording off"
                } else {
                    "Turn microphone recording on"
                },
                onClick = { onToggleMicrophone(!state.settings.microphoneEnabled) },
            )
        }

        // Bottom-left: the preview-zoom explanation, so display-only is never ambiguous.
        previewFit?.let { fit ->
            if (!compact) {
                Text(
                    text = "${fit.describe()} - display only, recording is not cropped",
                    style = MaterialTheme.typography.labelSmall,
                    color = status.overlayText,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(status.overlayScrim, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }

        // Centre: only ever a blocker the user can act on.
        state.recording.primaryBlocker?.let { blocker ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(status.overlayScrim, MaterialTheme.shapes.medium)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = blocker.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = status.overlayText,
                )
                if (blocker.actionable) {
                    FilledTonalButton(onClick = onRequestPermissions) { Text("Fix this") }
                }
            }
        }
    }
}

/**
 * The control bar under the panes.
 *
 * Three actions and no more: record/stop, protect, and the way into the rest of the app. Protect is
 * the prominent one because it is the only control a driver has a real reason to reach for.
 */
@Composable
fun MainControlBar(
    state: MainUiState,
    compact: Boolean,
    onProtect: () -> Unit,
    onToggleMap: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalRoadguardStatusColors.current
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.recording.isRecording) {
            FilledIconButton(
                onClick = onStopRecording,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop recording")
            }
        } else {
            FilledIconButton(
                onClick = onStartRecording,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = status.recording,
                    contentColor = status.onRecording,
                ),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_fiber_manual_record), contentDescription = "Start recording")
            }
        }

        FilledTonalButton(
            onClick = onProtect,
            enabled = state.recording.canProtect,
            modifier = Modifier.weight(1f),
        ) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (compact) "Protect" else "Protect recording")
        }

        IconButton(onClick = onOpenGallery, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_video_library), contentDescription = "Recordings")
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_tune),
                contentDescription = "Settings",
            )
        }
    }
}

@Composable
private fun OverlayIconButton(iconRes: Int, contentDescription: String, onClick: () -> Unit) {
    val status = LocalRoadguardStatusColors.current
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = status.overlayScrim,
            contentColor = status.overlayText,
        ),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Makes a status chip tappable. */
private fun Modifier.clickableChip(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
