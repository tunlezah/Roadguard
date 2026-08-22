package io.github.tunlezah.roadguard.camera

import io.github.tunlezah.roadguard.settings.PreviewZoom
import kotlin.math.max
import kotlin.math.min

/**
 * How the live camera image is fitted into its UI panel.
 *
 * ### Why this exists
 *
 * The video panel is roughly half the window, so its aspect ratio almost never matches the
 * camera's. Naively that produces thick letterbox bars. Roadguard solves it by *scaling the
 * displayed image*, never by cropping the recording: the specification is explicit that the
 * recorded stream must keep the best useful camera image regardless of how the UI is laid
 * out.
 *
 * ### Why it cannot affect the recording
 *
 * [PreviewFit] returns pure numbers. They are consumed only by the composable that draws
 * the viewfinder, as a `graphicsLayer` scale plus a clip. The recording path never reads
 * them. In particular Roadguard deliberately does **not** implement preview zoom with
 * either of the two mechanisms that *would* reach the encoder:
 *
 *  * `CameraControl.setZoomRatio()` changes the sensor crop and therefore changes the
 *    recorded frames -- it is wired to the separate, advanced "recording zoom" setting; and
 *  * a `ViewPort` on the bound `UseCaseGroup` propagates a crop rect into
 *    `VideoCapture.setViewPortCropRect(...)`, which crops the recorded stream, so Roadguard
 *    binds its use cases with no `ViewPort` at all.
 *
 * See `docs/research/camera-pipeline.md` §4 and `docs/architecture.md`.
 */
object PreviewFit {

    /**
     * The most the Auto algorithm will magnify in order to fill the panel.
     *
     * At 1.35x an exactly-filled panel has lost about 26% of one dimension. Beyond that,
     * Auto prefers to show a letterbox rather than throw away more of the road scene,
     * because the point of the preview is to let the driver confirm framing.
     */
    const val AUTO_MAX_FILL_ZOOM: Float = 1.35f

    /**
     * How far the visible window is pushed down the image when Auto has to crop
     * vertically, as a fraction of the hidden height.
     *
     * A windscreen-mounted phone spends the top of its frame on sky, so favouring the
     * lower part of the image keeps more road. This is a *preview* bias only.
     */
    const val ROAD_BIAS: Float = 0.30f

    /** Below this the crop is treated as nil and the image is simply centred. */
    private const val NEGLIGIBLE_CROP: Float = 0.02f

    /**
     * @param sourceWidth width of the camera image as it will be displayed (already
     *   rotation-corrected -- use [displayedSize]).
     * @param panelWidth width of the UI panel the image must sit in.
     * @param requested the user's preview-zoom setting.
     */
    fun compute(
        sourceWidth: Int,
        sourceHeight: Int,
        panelWidth: Int,
        panelHeight: Int,
        requested: PreviewZoom,
    ): PreviewFitResult {
        if (sourceWidth <= 0 || sourceHeight <= 0 || panelWidth <= 0 || panelHeight <= 0) {
            return PreviewFitResult(
                scale = 1f,
                verticalBias = 0f,
                horizontalBias = 0f,
                zoomToFill = 1f,
                isAuto = requested == PreviewZoom.Auto,
                effectiveZoom = 1f,
                croppedFraction = 0f,
                letterboxedFraction = 0f,
            )
        }

        val fitScale = min(
            panelWidth.toFloat() / sourceWidth,
            panelHeight.toFloat() / sourceHeight,
        )
        val fillScale = max(
            panelWidth.toFloat() / sourceWidth,
            panelHeight.toFloat() / sourceHeight,
        )
        // Zoom, relative to a letterboxed "fit", at which the panel is exactly filled.
        val zoomToFill = if (fitScale > 0f) fillScale / fitScale else 1f

        val zoom = when (requested) {
            PreviewZoom.Auto -> min(zoomToFill, AUTO_MAX_FILL_ZOOM)
            else -> requested.factor ?: 1f
        }.coerceAtLeast(1f)

        // Size of the image, in panel pixels, once the fit scale and the zoom are applied.
        val drawnWidth = sourceWidth * fitScale * zoom
        val drawnHeight = sourceHeight * fitScale * zoom

        val overflowX = (drawnWidth - panelWidth).coerceAtLeast(0f)
        val overflowY = (drawnHeight - panelHeight).coerceAtLeast(0f)
        val croppedFraction = max(
            if (drawnWidth > 0f) overflowX / drawnWidth else 0f,
            if (drawnHeight > 0f) overflowY / drawnHeight else 0f,
        )
        val letterboxedFraction = max(
            if (panelWidth > 0) (panelWidth - drawnWidth).coerceAtLeast(0f) / panelWidth else 0f,
            if (panelHeight > 0) (panelHeight - drawnHeight).coerceAtLeast(0f) / panelHeight else 0f,
        )

        // Only bias when there is a meaningful vertical crop to spend; horizontal crops stay
        // centred because the road vanishing point is horizontally central.
        val verticalBias = if (drawnHeight > 0f && overflowY / drawnHeight > NEGLIGIBLE_CROP) {
            ROAD_BIAS
        } else {
            0f
        }

        return PreviewFitResult(
            scale = zoom,
            verticalBias = verticalBias,
            horizontalBias = 0f,
            zoomToFill = zoomToFill,
            isAuto = requested == PreviewZoom.Auto,
            effectiveZoom = zoom,
            croppedFraction = croppedFraction,
            letterboxedFraction = letterboxedFraction,
        )
    }

    /**
     * The camera image's size as the user sees it: a 90 degree or 270 degree rotation
     * between buffer and display swaps width and height.
     */
    fun displayedSize(bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): Pair<Int, Int> =
        when (((rotationDegrees % 360) + 360) % 360) {
            90, 270 -> bufferHeight to bufferWidth
            else -> bufferWidth to bufferHeight
        }
}

/**
 * The outcome of [PreviewFit.compute].
 *
 * @property scale uniform magnification to apply on top of a letterboxed fit. 1.0 means
 *   "show the whole frame"; larger values crop the displayed image.
 * @property verticalBias where the visible window sits within the hidden height, 0 = centre,
 *   1 = flush with the bottom of the image.
 * @property zoomToFill the zoom that would exactly fill the panel; useful for telling the
 *   user how much Auto held back.
 * @property croppedFraction largest fraction of a dimension hidden by the crop.
 * @property letterboxedFraction largest fraction of the panel left empty.
 */
data class PreviewFitResult(
    val scale: Float,
    val verticalBias: Float,
    val horizontalBias: Float,
    val zoomToFill: Float,
    val isAuto: Boolean,
    val effectiveZoom: Float,
    val croppedFraction: Float,
    val letterboxedFraction: Float,
) {
    /** True when the panel is fully covered by camera image. */
    val fillsPanel: Boolean get() = letterboxedFraction <= 0.001f

    /** Short human-readable summary for the preview-zoom control. */
    fun describe(): String = buildString {
        append(String.format("%.2fx", effectiveZoom).removeSuffix("0").removeSuffix("."))
        if (isAuto) append(" (Auto)")
        when {
            croppedFraction > 0.005f -> append(" - display crop ${(croppedFraction * 100).toInt()}%")
            letterboxedFraction > 0.005f -> append(" - letterboxed ${(letterboxedFraction * 100).toInt()}%")
        }
    }
}
