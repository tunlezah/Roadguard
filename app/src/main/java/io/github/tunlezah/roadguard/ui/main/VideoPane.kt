package io.github.tunlezah.roadguard.ui.main

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.camera.PreviewFit
import io.github.tunlezah.roadguard.camera.PreviewFitResult
import io.github.tunlezah.roadguard.settings.PreviewZoom
import io.github.tunlezah.roadguard.ui.theme.PaneCorner
import kotlin.math.max

/**
 * The camera pane.
 *
 * ### Preview zoom is display-only, provably
 *
 * The camera image is drawn by `CameraXViewfinder` with `ContentScale.Fit`, and the zoom is a
 * `graphicsLayer` scale on the composable inside a `clipToBounds`. There is no path from this file
 * to the encoder: it never touches `CameraControl`, and Roadguard binds its use cases without a
 * `ViewPort`, which is the other mechanism that would propagate a crop into the recorded stream.
 * The screen states this, so a user can see the recording is untouched.
 *
 * ### Letterbox rather than over-crop
 *
 * Auto stops magnifying at [PreviewFit.AUTO_MAX_FILL_ZOOM]: a little letterboxing beats discarding
 * a quarter of the road scene to fill a panel. [onFitComputed] reports which happened so the caller
 * can label it.
 */
@Composable
fun VideoPane(
    surfaceRequest: SurfaceRequest?,
    previewZoom: PreviewZoom,
    rotationDegrees: Int,
    modifier: Modifier = Modifier,
    onFitComputed: (PreviewFitResult) -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .background(Color.Black, PaneCorner)
            .clipToBounds(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val panelWidthPx = with(density) { maxWidth.roundToPx() }
            val panelHeightPx = with(density) { maxHeight.roundToPx() }

            if (surfaceRequest == null) {
                CameraPlaceholder(Modifier.fillMaxSize())
            } else {
                val resolution = surfaceRequest.resolution
                val displayed = remember(resolution, rotationDegrees) {
                    PreviewFit.displayedSize(resolution.width, resolution.height, rotationDegrees)
                }
                val fit = remember(displayed, panelWidthPx, panelHeightPx, previewZoom) {
                    PreviewFit.compute(
                        sourceWidth = displayed.first,
                        sourceHeight = displayed.second,
                        panelWidth = panelWidthPx,
                        panelHeight = panelHeightPx,
                        requested = previewZoom,
                    )
                }
                SideEffect { onFitComputed(fit) }

                // Size the image after ContentScale.Fit and the zoom, so the road bias can be
                // expressed as a translation of exactly half the hidden height.
                val fitScale = fitScaleFor(displayed.first, displayed.second, panelWidthPx, panelHeightPx)
                val drawnHeightPx = displayed.second * fitScale * fit.scale
                val hiddenHeightPx = max(0f, drawnHeightPx - panelHeightPx)
                val translationY = -hiddenHeightPx / 2f * fit.verticalBias

                CameraXViewfinder(
                    surfaceRequest = surfaceRequest,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = fit.scale,
                            scaleY = fit.scale,
                            translationY = translationY,
                        ),
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Fit,
                )
            }
        }
        overlay()
    }
}

/** The "fit" scale `ContentScale.Fit` applies, needed to size the road-bias translation. */
internal fun fitScaleFor(sourceWidth: Int, sourceHeight: Int, panelWidth: Int, panelHeight: Int): Float {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1f
    return minOf(
        panelWidth.toFloat() / sourceWidth,
        panelHeight.toFloat() / sourceHeight,
    )
}

@Composable
private fun CameraPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_videocam_off),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "Camera preview is off",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Recording, if it is running, is unaffected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
