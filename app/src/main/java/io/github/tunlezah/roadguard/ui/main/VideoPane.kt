package io.github.tunlezah.roadguard.ui.main

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.camera.PreviewFitTransform
import io.github.tunlezah.roadguard.camera.PreviewFitResult
import io.github.tunlezah.roadguard.settings.PreviewZoom
import io.github.tunlezah.roadguard.ui.theme.PaneCorner

/**
 * The camera pane.
 *
 * ### Preview zoom is display-only, provably
 *
 * The camera image is drawn by `CameraXViewfinder`, and the zoom is expressed through the
 * viewfinder's own `ContentScale`/`Alignment` hooks (see [PreviewFitTransform]). There is no path
 * from this file to the encoder: it never touches `CameraControl`, and Roadguard binds its use cases
 * without a `ViewPort`, which is the other mechanism that would propagate a crop into the recorded
 * stream. The screen states this, so a user can see the recording is untouched.
 *
 * ### Why the fit is not computed here
 *
 * It used to be, from the buffer size and the *device* rotation, and applied as a `graphicsLayer`
 * scale. That was wrong in landscape, because the device rotation is not the rotation the viewfinder
 * applies -- see [PreviewFitTransform] for the full account. The viewfinder already knows the
 * rotation-corrected source size and hands it to a `ContentScale`, so asking is both simpler and
 * correct where deriving was neither.
 *
 * ### Letterbox rather than over-crop
 *
 * Auto stops magnifying at `PreviewFit.AUTO_MAX_FILL_ZOOM`: a little letterboxing beats discarding a
 * quarter of the road scene to fill a panel. [onFitComputed] reports which happened so the caller
 * can label it.
 */
@Composable
fun VideoPane(
    surfaceRequest: SurfaceRequest?,
    previewZoom: PreviewZoom,
    modifier: Modifier = Modifier,
    onFitComputed: (PreviewFitResult) -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            // Clip to the pane's own rounded shape, not to its bounding rectangle. With
            // clipToBounds the magnified image filled the square corners that the rounded
            // background leaves empty, so in landscape a sliver of video appeared outside the
            // pane outline, next to the map.
            .clip(PaneCorner)
            .background(Color.Black),
    ) {
        if (surfaceRequest == null) {
            CameraPlaceholder(Modifier.fillMaxSize())
        } else {
            var fit by remember { mutableStateOf<PreviewFitResult?>(null) }
            // Keyed on the zoom so the instance's *identity* changes when the user cycles it.
            // ContentScale is @Stable, so mutating the zoom in place could leave the viewfinder
            // holding a cached transform and the change would not appear until something else
            // forced a relayout. Recreating costs nothing: the viewfinder always calls
            // computeScaleFactor before align, so the bias is never read before it is derived.
            val transform = remember(previewZoom) {
                PreviewFitTransform().apply { zoom = previewZoom }
            }
            transform.onFit = { fit = it }

            // Reported from an effect rather than from inside layout, so the caller's callback
            // never runs during measurement.
            LaunchedEffect(fit) { fit?.let(onFitComputed) }

            CameraXViewfinder(
                surfaceRequest = surfaceRequest,
                modifier = Modifier.fillMaxSize(),
                alignment = transform,
                contentScale = transform,
            )
        }
        overlay()
    }
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
