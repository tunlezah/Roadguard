package io.github.tunlezah.roadguard.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

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
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        alpha = 190
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 110
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PROTECTED_COLOUR
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val bounds = Rect()
    private val scratch = RectF()

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

        val displaySize = displaySize(cropRect, rotationDegrees)
        val matrix = buildDisplayMatrix(cropRect, rotationDegrees, mirrored)

        canvas.save()
        canvas.concat(matrix)
        drawUpright(canvas, content, displaySize.first, displaySize.second)
        canvas.restore()
    }

    /** Lays the overlay out in upright display space. */
    private fun drawUpright(canvas: Canvas, content: OverlayContent, width: Int, height: Int) {
        val margin = height * MARGIN_FRACTION
        val bodySize = height * BODY_TEXT_FRACTION
        val speedSize = height * SPEED_TEXT_FRACTION

        // ── Bottom-left stack: date, time, coordinates ────────────────────────────────────
        val leftLines = buildList {
            val stamp = listOfNotNull(content.dateText, content.timeText).joinToString("  ")
            if (stamp.isNotEmpty()) add(stamp)
            content.coordinatesText?.let { add(it) }
            content.weatherText?.let { add(it) }
        }
        var baseline = height - margin
        leftLines.asReversed().forEach { line ->
            drawLabel(canvas, line, margin, baseline, bodySize, textPaint)
            baseline -= bodySize * LINE_SPACING
        }

        // ── Bottom-right: speed, the one value a driver reads at a glance ─────────────────
        content.speedText?.let { speed ->
            textPaint.textSize = speedSize
            textPaint.getTextBounds(speed, 0, speed.length, bounds)
            val x = width - margin - bounds.width()
            drawLabel(canvas, speed, x, height - margin, speedSize, textPaint)
        }

        // ── Top-right: protection notice ──────────────────────────────────────────────────
        content.protectedLabel?.let { label ->
            accentPaint.textSize = bodySize
            accentPaint.getTextBounds(label, 0, label.length, bounds)
            val x = width - margin - bounds.width()
            drawLabel(canvas, label, x, margin + bodySize, bodySize, accentPaint)
        }
    }

    /**
     * Draws one label with a scrim behind it and a dark offset copy underneath.
     *
     * A dashcam overlay has to stay readable over a bright sky and over dark asphalt in the
     * same frame, so it gets both: the scrim handles bright backgrounds, the offset copy
     * handles busy ones.
     */
    private fun drawLabel(canvas: Canvas, text: String, x: Float, baselineY: Float, size: Float, paint: Paint) {
        paint.textSize = size
        paint.getTextBounds(text, 0, text.length, bounds)
        val padding = size * SCRIM_PADDING_FRACTION
        scratch.set(
            x - padding,
            baselineY - bounds.height() - padding,
            x + bounds.width() + padding,
            baselineY + padding * 0.6f,
        )
        canvas.drawRoundRect(scratch, padding, padding, scrimPaint)

        shadowPaint.textSize = size
        val offset = size * SHADOW_OFFSET_FRACTION
        canvas.drawText(text, x + offset, baselineY + offset, shadowPaint)
        canvas.drawText(text, x, baselineY, paint)
    }

    companion object {
        /** Amber, matching the "protected" status colour in the app's palette. */
        const val PROTECTED_COLOUR = 0xFFFFC44D.toInt()

        const val MARGIN_FRACTION = 0.035f
        const val BODY_TEXT_FRACTION = 0.038f
        const val SPEED_TEXT_FRACTION = 0.070f
        const val LINE_SPACING = 1.35f
        const val SCRIM_PADDING_FRACTION = 0.28f
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
