package io.github.tunlezah.roadguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.settings.PreviewZoom
import io.github.tunlezah.roadguard.ui.about.AboutScreen
import io.github.tunlezah.roadguard.ui.diagnostics.DiagnosticsScreen
import io.github.tunlezah.roadguard.ui.firstrun.FirstRunScreen
import io.github.tunlezah.roadguard.ui.gallery.GalleryScreen
import io.github.tunlezah.roadguard.ui.gallery.PlayerScreen
import io.github.tunlezah.roadguard.ui.main.MainScreen
import io.github.tunlezah.roadguard.ui.main.MainViewModel
import io.github.tunlezah.roadguard.ui.settings.SettingsScreen
import io.github.tunlezah.roadguard.ui.storage.StorageScreen

/**
 * The whole app's navigation.
 *
 * A tiny explicit back stack rather than a navigation library. Roadguard has one primary screen and
 * a handful of leaves, no deep links, and its important state lives in a foreground service rather
 * than in a nav graph -- so a library here would add a dependency, a graph definition and a
 * serialisation story without answering a question the app actually has.
 *
 * First run is a *gate* rather than a destination: until setup is complete there is nowhere else to
 * go, which is what makes the permission and map-install flow reliable.
 */
@Composable
fun RoadguardApp(
    container: RoadguardContainer,
    onRequestCorePermissions: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.collectAsState()
    val backStack = remember { mutableStateListOf<RoadguardDestination>(RoadguardDestination.Main) }
    val current = backStack.last()

    fun push(destination: RoadguardDestination) {
        if (backStack.last() != destination) backStack.add(destination)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = backStack.size > 1) { pop() }

    if (!settings.setupComplete) {
        FirstRunScreen(
            onFinished = {
                onRequestCorePermissions()
            },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    AnimatedContent(
        targetState = current,
        transitionSpec = {
            // Deliberately a cross-fade rather than a slide: the driving screen should never appear
            // to move, and a fade is the cheapest transition on the baseline device's GPU.
            fadeIn(tween(120)) togetherWith fadeOut(tween(120))
        },
        label = "roadguard-navigation",
        modifier = modifier.fillMaxSize(),
    ) { destination ->
        when (destination) {
            RoadguardDestination.Main -> {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
                val state by viewModel.state.collectAsState()
                val surfaceRequest by viewModel.surfaceRequest.collectAsState()
                val rotation by container.recordingController.surfaceRotation.collectAsState()

                MainScreen(
                    state = state,
                    surfaceRequest = surfaceRequest,
                    previewRotationDegrees = io.github.tunlezah.roadguard.camera
                        .CameraOrientationTracker.degreesFor(rotation),
                    onProtect = viewModel::protectNow,
                    onToggleMap = { viewModel.setMapVisible(it) },
                    onToggleMicrophone = { enabled ->
                        if (enabled) onRequestMicrophonePermission() else viewModel.setMicrophoneEnabled(false)
                    },
                    onOpenSettings = { push(RoadguardDestination.Settings) },
                    onOpenStorage = { push(RoadguardDestination.Storage) },
                    onOpenGallery = { push(RoadguardDestination.Gallery) },
                    onOpenDiagnostics = { push(RoadguardDestination.Diagnostics) },
                    onCyclePreviewZoom = { viewModel.setPreviewZoom(nextPreviewZoom(state.settings.previewZoom)) },
                    onRecentreMap = viewModel::recentreMap,
                    onRetryMapInstall = viewModel::retryMapInstall,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onRequestPermissions = onRequestCorePermissions,
                )
            }

            RoadguardDestination.Settings -> SettingsScreen(
                onBack = ::pop,
                onOpenStorage = { push(RoadguardDestination.Storage) },
                onOpenGallery = { push(RoadguardDestination.Gallery) },
                onOpenDiagnostics = { push(RoadguardDestination.Diagnostics) },
                onOpenPrivacy = { push(RoadguardDestination.About) },
                onOpenAbout = { push(RoadguardDestination.About) },
            )

            RoadguardDestination.Storage -> StorageScreen(onBack = ::pop)

            RoadguardDestination.Diagnostics -> DiagnosticsScreen(onBack = ::pop)

            RoadguardDestination.Gallery -> GalleryScreen(
                onBack = ::pop,
                onOpenSegment = { push(RoadguardDestination.Player(it)) },
            )

            is RoadguardDestination.Player -> PlayerScreen(
                segmentId = destination.segmentId,
                onBack = ::pop,
            )

            RoadguardDestination.About -> AboutScreen(onBack = ::pop)

            // The remaining declared destinations are covered by the single scrolling settings
            // screen; they exist in the sealed set so a future split does not have to touch callers.
            else -> SettingsScreen(
                onBack = ::pop,
                onOpenStorage = { push(RoadguardDestination.Storage) },
                onOpenGallery = { push(RoadguardDestination.Gallery) },
                onOpenDiagnostics = { push(RoadguardDestination.Diagnostics) },
                onOpenPrivacy = { push(RoadguardDestination.About) },
                onOpenAbout = { push(RoadguardDestination.About) },
            )
        }
    }
}

/** Steps through the preview-zoom options, Auto first. */
internal fun nextPreviewZoom(current: PreviewZoom): PreviewZoom {
    val values = PreviewZoom.entries
    return values[(values.indexOf(current) + 1) % values.size]
}
