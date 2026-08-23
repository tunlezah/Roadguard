package io.github.tunlezah.roadguard.camera

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.tunlezah.roadguard.settings.PreviewZoom
import kotlin.math.roundToInt

/**
 * Applies Roadguard's preview-fit policy through the viewfinder's own scale and alignment hooks.
 *
 * ### Why this exists, and what it replaced
 *
 * The preview used to compute its own scale from `PreviewFit.displayedSize(bufferSize, deviceRotation)`
 * and apply it as a `graphicsLayer` scale over a `ContentScale.Fit` viewfinder. That was wrong,
 * because **the device's rotation is not the rotation the viewfinder applies**. CameraX combines the
 * target rotation with the camera's sensor mounting, so on a typical phone a portrait device
 * (`ROTATION_0`) displays a 1920x1080 buffer as a *portrait* 1080x1920 image, and a landscape device
 * (`ROTATION_270`) displays it as landscape. The old code had those exactly the wrong way round.
 *
 * In portrait the two errors happened to cancel at the zoom cap, so it looked fine. In landscape it
 * did not: the layout believed the crop was vertical when it was horizontal, so it applied the road
 * bias to an axis with no overflow -- pushing the image 12 px off the top of the panel and leaving an
 * 88 px black band along the bottom, on top of over-cropping about a quarter of the frame's width.
 *
 * ### The fix: ask, do not derive
 *
 * `androidx.camera.viewfinder` hands a `ContentScale` the **already rotation-corrected** source size.
 * The chain, verified against the shipped `viewfinder-core` bytecode, is
 * `rotatedViewportFor(transformationInfo, size)` -> `setTransform(...)` ->
 * `contentScale.computeScaleFactor(rotatedSource, viewfinder)` -> `alignment.align(...)`.
 *
 * So this class needs no rotation parameter at all, and there is no arithmetic left to get backwards.
 * That is the whole point: the previous bug was not a wrong constant, it was deriving something the
 * framework already knew.
 *
 * ### Why one object implements both interfaces
 *
 * `align` needs the bias that `computeScaleFactor` worked out. The viewfinder calls them in that
 * order for the same frame, so holding the result in a field is both correct and free -- and avoids
 * routing a layout-time value back through Compose state, which would risk a recomposition loop.
 *
 * Preview zoom remains display-only. This class returns numbers to the viewfinder and reports them
 * for the on-screen caption; it never touches `CameraControl`, and Roadguard binds its use cases
 * without a `ViewPort`, which is the other mechanism that could reach the encoder.
 */
class PreviewFitTransform : ContentScale, Alignment {

    /**
     * The zoom the user asked for.
     *
     * The composable creates a fresh instance whenever this changes, rather than mutating it: a
     * `ContentScale` is `@Stable`, so an in-place change could leave the viewfinder holding a
     * cached transform until something else forced a relayout.
     */
    var zoom: PreviewZoom = PreviewZoom.Auto

    /** Called whenever the fit is recomputed, for the on-screen "display only" caption. */
    var onFit: ((PreviewFitResult) -> Unit)? = null

    /** The most recent result, held so [align] can use the bias [computeScaleFactor] derived. */
    var latest: PreviewFitResult? = null
        private set

    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val fit = PreviewFit.compute(
            sourceWidth = srcSize.width,
            sourceHeight = srcSize.height,
            panelWidth = dstSize.width,
            panelHeight = dstSize.height,
            requested = zoom,
        )
        if (fit != latest) {
            latest = fit
            onFit?.invoke(fit)
        }
        // Absolute, like ContentScale.Fit: the letterboxed fit times the zoom over it.
        val total = fit.totalScale
        return ScaleFactor(total, total)
    }

    /**
     * Centres the image, then spends any *vertical* overflow downwards.
     *
     * A windscreen-mounted phone wastes the top of its frame on sky, so when Auto has cropped
     * vertically the visible window is pushed down the hidden height. When there is no vertical
     * overflow -- which is the normal landscape case -- the bias is zero and this is a plain centre.
     * Getting that distinction wrong is what produced the black band along the bottom in landscape.
     */
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset {
        val centreX = (space.width - size.width) / 2f
        val centreY = (space.height - size.height) / 2f
        val hiddenHeight = (size.height - space.height).coerceAtLeast(0)
        val bias = latest?.verticalBias ?: 0f
        return IntOffset(
            x = centreX.roundToInt(),
            y = (centreY - hiddenHeight / 2f * bias).roundToInt(),
        )
    }
}
