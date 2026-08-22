package io.github.tunlezah.roadguard.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tunlezah.roadguard.camera.PreviewFitResult
import io.github.tunlezah.roadguard.ui.PaneArrangement
import io.github.tunlezah.roadguard.ui.RoadguardWindowInfo
import io.github.tunlezah.roadguard.ui.rememberRoadguardWindowInfo

/**
 * The driving screen: video and map as two equal primary panes.
 *
 * ### The layout rule
 *
 * Portrait puts the video on top and the map underneath; landscape puts the video on the left and
 * the map on the right. Each starts at half the available window and the split is derived from the
 * measured window size, never from a device model or a hard-coded aspect ratio, so it is right in
 * split-screen, on a foldable and on a tablet as well as on a phone.
 *
 * The divider can be dragged, within limits, because the specification requires both that the panes
 * be equal primary components *and* that the video never become an unusable postage stamp. Clamping
 * the split to [MIN_PANE_FRACTION] guarantees the second half of that even if the user drags hard.
 * The map can also be hidden outright, which gives the camera the whole window.
 *
 * ### Controls
 *
 * Deliberately sparse. This screen is used in a moving vehicle, so it shows state and offers three
 * actions -- protect, mute, and open the rest of the app -- and nothing else competes for attention.
 */
@Composable
fun MainScreen(
    state: MainUiState,
    surfaceRequest: androidx.camera.core.SurfaceRequest?,
    previewRotationDegrees: Int,
    onProtect: () -> Unit,
    onToggleMap: (Boolean) -> Unit,
    onToggleMicrophone: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onCyclePreviewZoom: () -> Unit,
    onRecentreMap: () -> Unit,
    onRetryMapInstall: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = rememberRoadguardWindowInfo()
    val snackbarHostState = remember { SnackbarHostState() }
    var splitFraction by rememberSaveable { mutableFloatStateOf(DEFAULT_SPLIT) }
    var previewFit by remember { mutableStateOf<PreviewFitResult?>(null) }

    LaunchedEffect(state.recording.lastProtectionMessage) {
        state.recording.lastProtectionMessage?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(state.recording.lastErrorMessage) {
        state.recording.lastErrorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            MainStatusBar(
                state = state,
                compact = window.isCompactHeight,
                onOpenStorage = onOpenStorage,
                onOpenDiagnostics = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val density = LocalDensity.current
                val totalPx = with(density) {
                    if (window.arrangement == PaneArrangement.VideoBesideMap) {
                        maxWidth.toPx()
                    } else {
                        maxHeight.toPx()
                    }
                }

                val videoPane: @Composable (Modifier) -> Unit = { paneModifier ->
                    VideoPane(
                        surfaceRequest = surfaceRequest,
                        previewZoom = state.settings.previewZoom,
                        rotationDegrees = previewRotationDegrees,
                        modifier = paneModifier,
                        onFitComputed = { previewFit = it },
                    ) {
                        VideoOverlayChrome(
                            state = state,
                            previewFit = previewFit,
                            compact = window.isCompactHeight,
                            onProtect = onProtect,
                            onToggleMicrophone = onToggleMicrophone,
                            onCyclePreviewZoom = onCyclePreviewZoom,
                            onToggleMap = onToggleMap,
                            onStartRecording = onStartRecording,
                            onStopRecording = onStopRecording,
                            onRequestPermissions = onRequestPermissions,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                val mapPane: @Composable (Modifier) -> Unit = { paneModifier ->
                    MapPane(
                        state = state,
                        onRecentre = onRecentreMap,
                        onRetryInstall = onRetryMapInstall,
                        onHide = { onToggleMap(false) },
                        modifier = paneModifier,
                    )
                }

                if (!state.showMap) {
                    videoPane(Modifier.fillMaxSize().padding(PANE_PADDING))
                } else if (window.arrangement == PaneArrangement.VideoBesideMap) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        videoPane(
                            Modifier
                                .fillMaxHeight()
                                .weight(splitFraction)
                                .padding(PANE_PADDING),
                        )
                        PaneDivider(
                            vertical = true,
                            onDrag = { delta ->
                                splitFraction = clampSplit(splitFraction + delta / totalPx)
                            },
                            onReset = { splitFraction = DEFAULT_SPLIT },
                        )
                        mapPane(
                            Modifier
                                .fillMaxHeight()
                                .weight(1f - splitFraction)
                                .padding(PANE_PADDING),
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        videoPane(
                            Modifier
                                .fillMaxWidth()
                                .weight(splitFraction)
                                .padding(PANE_PADDING),
                        )
                        PaneDivider(
                            vertical = false,
                            onDrag = { delta ->
                                splitFraction = clampSplit(splitFraction + delta / totalPx)
                            },
                            onReset = { splitFraction = DEFAULT_SPLIT },
                        )
                        mapPane(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f - splitFraction)
                                .padding(PANE_PADDING),
                        )
                    }
                }
            }

            MainControlBar(
                state = state,
                compact = window.isCompactHeight,
                onProtect = onProtect,
                onToggleMap = onToggleMap,
                onOpenSettings = onOpenSettings,
                onOpenGallery = onOpenGallery,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The draggable split handle.
 *
 * A real handle rather than an invisible hit area: in a vehicle a control the driver cannot see is
 * a control they will hunt for. It also has a double-tap-to-reset so an accidental drag is one
 * gesture to undo.
 */
@Composable
private fun PaneDivider(
    vertical: Boolean,
    onDrag: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionModifier = modifier
        .pointerInput(vertical) {
            detectDragGestures { _, dragAmount ->
                onDrag(if (vertical) dragAmount.x else dragAmount.y)
            }
        }
        .semantics {
            contentDescription = if (vertical) {
                "Drag to resize the video and map panes"
            } else {
                "Drag to resize the video and map panes"
            }
        }

    Box(
        modifier = if (vertical) {
            interactionModifier.fillMaxHeight().width(DIVIDER_TOUCH)
        } else {
            interactionModifier.fillMaxWidth().height(DIVIDER_TOUCH)
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (vertical) {
                        Modifier.width(4.dp).height(36.dp)
                    } else {
                        Modifier.height(4.dp).width(36.dp)
                    },
                )
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private fun clampSplit(value: Float): Float = value.coerceIn(MIN_PANE_FRACTION, 1f - MIN_PANE_FRACTION)

/** Default split: both panes get half the window, as the specification requires. */
const val DEFAULT_SPLIT = 0.5f

/**
 * Neither pane may shrink below this fraction of the window.
 *
 * 30% keeps the camera preview usable and the map legible however hard the divider is dragged.
 */
const val MIN_PANE_FRACTION = 0.30f

private val PANE_PADDING = 6.dp
private val DIVIDER_TOUCH = 24.dp
