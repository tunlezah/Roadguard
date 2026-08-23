package io.github.tunlezah.roadguard.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.withMatrix

/**
 * Draws the burned-in overlay onto a camera frame's overlay canvas.
 *
 * ### The orientation problem, and the fix
 *
 * `OverlayEffect`'s canvas is in **camera buffer** coordinates and is transformed by exactly
 * the same matrix as the camera image -- including the rotation implied by the phone's
 * orientation. Text drawn naively therefore comes out sideways whenever the frame carries a
 * 90 or 270 degree rotation, which for a dashcam is most of the time.
 *
 * [buildDisplayMatrix] fixes it with one documented rule: build the matrix that maps *display*
 * space (the frame as a viewer will finally see it, origin top-left, y down) into buffer space
 * by inverting the frame's own mirroring and rotation, then lay the overlay out in that upright
 * display space. No sensor angles, no per-device special cases -- just the inverse of the
 * transform CameraX says it is going to apply.
 *
 * ### Where each label goes
 *
 * Not decided here. [OverlayLayout] measures every block and returns positions that provably do
 * not overlap in either orientation; this class only rasterises them. That split exists because
 * the overlap bug it fixes was invisible in landscape and obvious in portrait, which is exactly
 * the kind of thing a unit test should catch and a human should not have to.
 *
 * ### Cost
 *
 * The renderer is called at most once a second, and only when the content actually changed. All
 * `Paint` objects are allocated once. The clear-and-redraw covers the whole buffer because the
 * overlay surface is persistent: leaving stale pixels behind would leave a ghost of the
 * previous second's speed on screen.
 */
class OverlayRenderer {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        alpha = TEXT_ALPHA
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        alpha = SHADOW_ALPHA
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = SCRIM_ALPHA
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PROTECTED_COLOUR
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        alpha = TEXT_ALPHA
    }
    private val scratch = RectF()

    /** Measures with the same paint that will draw, so layout and rendering cannot disagree. */
    private val metrics = object : OverlayLayout.Metrics {
        private val measuring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        override fun width(text: String, textSize: Float): Float {
            measuring.textSize = textSize
            return measuring.measureText(text)
        }

        override fun ascent(textSize: Float): Float {
            measuring.textSize = textSize
            return -measuring.fontMetrics.ascent
        }

        override fun descent(textSize: Float): Float {
            measuring.textSize = textSize
            return measuring.fontMetrics.descent
        }
    }

    /**
     * @param canvas the frame's overlay canvas, in buffer coordinates.
     * @param bufferWidth/[bufferHeight] the overlay canvas size (`Frame.getSize()`).
     * @param cropRect the part of the buffer that survives into the output (`Frame.getCropRect()`).
     * @param rotationDegrees `Frame.getRotationDegrees()`.
     * @param mirrored `Frame.isMirroring()`.
     */
    fun draw(
        canvas: Canvas,
        content: OverlayContent,
        bufferWidth: Int,
        bufferHeight: Int,
        cropRect: Rect,
        rotationDegrees: Int,
        mirrored: Boolean,
    ) {
        // The overlay surface persists between frames, so always start from nothing.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (content.isEmpty) return
        if (cropRect.isEmpty || bufferWidth <= 0 || bufferHeight <= 0) return

        val (displayWidth, displayHeight) = displaySize(cropRect, rotationDegrees)
        val layout = OverlayLayout.layout(displayWidth, displayHeight, content, metrics)
        if (layout.isEmpty) return

        val matrix = buildDisplayMatrix(cropRect, rotationDegrees, mirrored)

        // withMatrix restores the canvas even if drawing throws, which matters here: this runs on
        // the GL thread and a leaked transform would corrupt every later frame.
        canvas.withMatrix(matrix) {
            layout.blocks.forEach { block -> drawBlock(this, block) }
        }
    }

    /**
     * Draws one label: a scrim, a dark offset copy, then the text.
     *
     * A dashcam overlay has to stay readable over a bright sky and over dark asphalt in the same
     * frame, so it gets both: the scrim handles bright backgrounds, the offset copy handles busy
     * ones. Both are deliberately translucent -- see [SCRIM_ALPHA].
     */
    private fun drawBlock(canvas: Canvas, block: OverlayLayout.Block) {
        val paint = if (block.id == OverlayLayout.BlockId.Protected) accentPaint else textPaint
        val radius = block.textSize * OverlayLayout.SCRIM_PADDING_FRACTION

        scratch.set(block.scrim.left, block.scrim.top, block.scrim.right, block.scrim.bottom)
        canvas.drawRoundRect(scratch, radius, radius, scrimPaint)

        paint.textSize = block.textSize
        shadowPaint.textSize = block.textSize
        val offset = block.textSize * SHADOW_OFFSET_FRACTION
        canvas.drawText(block.text, block.x + offset, block.baselineY + offset, shadowPaint)
        canvas.drawText(block.text, block.x, block.baselineY, paint)
    }

    companion object {
        /** Amber, matching the "protected" status colour in the app's palette. */
        const val PROTECTED_COLOUR = 0xFFFFC44D.toInt()

        /**
         * Alpha values, all deliberately short of opaque.
         *
         * The overlay is evidence *about* the footage, not a replacement for the part of the
         * footage it sits on, so it is drawn light enough to see the road through. These are the
         * lowest values that still survived the two cases a dashcam overlay has to survive -- white
         * text over a bright sky, and over sunlit concrete -- with the scrim carrying the contrast
         * rather than the text being made heavier.
         */
        const val SCRIM_ALPHA = 64
        const val TEXT_ALPHA = 224
        const val SHADOW_ALPHA = 150

        const val SHADOW_OFFSET_FRACTION = 0.055f

        /** Size of the frame as a viewer will see it, after rotation. */
        fun displaySize(cropRect: Rect, rotationDegrees: Int): Pair<Int, Int> =
            when (normalise(rotationDegrees)) {
                90, 270 -> cropRect.height() to cropRect.width()
                else -> cropRect.width() to cropRect.height()
            }

        /**
         * Matrix mapping upright display space into camera buffer space.
         *
         * Built as the inverse of what CameraX will apply: undo the mirroring, undo the
         * rotation, then translate into the crop rectangle's position in the buffer.
         */
        fun buildDisplayMatrix(cropRect: Rect, rotationDegrees: Int, mirrored: Boolean): Matrix {
            val rotation = normalise(rotationDegrees)
            val (displayWidth, displayHeight) = displaySize(cropRect, rotation)
            val matrix = Matrix()

            if (mirrored) {
                // Mirror within display space first, so text is readable after CameraX mirrors
                // the image back.
                matrix.postScale(-1f, 1f, displayWidth / 2f, displayHeight / 2f)
            }

            matrix.postRotate(-rotation.toFloat())

            // Rotating about the origin can push the rectangle negative; shift it back so the
            // display rectangle lands exactly on the crop rectangle.
            val rotated = RectF(0f, 0f, displayWidth.toFloat(), displayHeight.toFloat())
            Matrix().apply {
                if (mirrored) postScale(-1f, 1f, displayWidth / 2f, displayHeight / 2f)
                postRotate(-rotation.toFloat())
            }.mapRect(rotated)
            matrix.postTranslate(-rotated.left, -rotated.top)
            matrix.postTranslate(cropRect.left.toFloat(), cropRect.top.toFloat())
            return matrix
        }

        private fun normalise(degrees: Int): Int = ((degrees % 360) + 360) % 360
    }
}
