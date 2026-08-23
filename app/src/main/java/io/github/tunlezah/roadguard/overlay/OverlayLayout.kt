package io.github.tunlezah.roadguard.overlay

import kotlin.math.max
import kotlin.math.min

/**
 * Works out where each piece of the burned-in overlay goes, and guarantees the pieces never
 * overlap.
 *
 * ### Why this is a separate, pure class
 *
 * The overlay used to be positioned inline while drawing, with text sized as a fraction of the
 * frame's **height**. That is fine in landscape and wrong in portrait: a 1080x1920 frame got
 * 73 px body text and 134 px speed text, so the date/time stamp ran from x=67 to x=912 while the
 * speed ran from x=468 to x=1013. They collided, and the speed's scrim was tall enough to cover
 * the coordinates line as well.
 *
 * Two changes fix it, and both belong in one place where they can be tested:
 *
 *  1. **Text is sized from the shorter frame dimension**, so a portrait frame gets the same text
 *    size as the landscape frame of the same resolution instead of a 78% larger one.
 *  2. **Placement is verified, not assumed.** Every block is measured, laid out, and then checked
 *    for intersection and for staying inside the frame. If the check fails the layout is retried
 *    with the blocks rearranged, and then at progressively smaller text, until it passes.
 *
 * Because this class is pure Kotlin -- no `android.graphics` -- the guarantee is exhaustively
 * unit tested across orientations, resolutions and content combinations rather than eyeballed on
 * one device. See `OverlayLayoutTest`.
 */
object OverlayLayout {

    /** Which block is which, so the renderer can pick a paint without matching on text. */
    enum class BlockId { Stamp, Coordinates, Weather, Speed, Protected }

    /** An axis-aligned rectangle in upright display space, y increasing downwards. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** True when the two rectangles share any area. Touching edges do not count. */
        fun overlaps(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom

        fun isInside(width: Float, height: Float): Boolean =
            left >= 0f && top >= 0f && right <= width && bottom <= height
    }

    /**
     * One drawable label.
     *
     * @param x left edge of the text itself (not of the scrim).
     * @param baselineY text baseline, in the same space.
     * @param scrim the rounded rectangle drawn behind the text; also the collision box.
     */
    data class Block(
        val id: BlockId,
        val text: String,
        val textSize: Float,
        val x: Float,
        val baselineY: Float,
        val scrim: Box,
    )

    /** How the bottom row is arranged. */
    enum class Arrangement {
        /** Left stack bottom-left, speed bottom-right, sharing the bottom row. */
        SideBySide,

        /** Speed bottom-right on its own row, left stack lifted above it. */
        Stacked,
    }

    data class Result(
        val blocks: List<Block>,
        val arrangement: Arrangement,
        val scale: Float,
        /** False when even the smallest layout could not be made to fit; see [layout]. */
        val fits: Boolean,
    ) {
        val isEmpty: Boolean get() = blocks.isEmpty()
    }

    /** Text measurement, injected so the layout can be tested without a `Paint`. */
    interface Metrics {
        fun width(text: String, textSize: Float): Float

        /** Baseline to top of the tallest glyph, positive. */
        fun ascent(textSize: Float): Float

        /** Baseline to bottom of the deepest descender, positive. */
        fun descent(textSize: Float): Float
    }

    /**
     * Lays the overlay out.
     *
     * The search order is deliberate: a full-size **rearrangement** is preferred over a shrunken
     * side-by-side one, because the speed reading is the thing a driver takes in at a glance and
     * making it smaller to keep it in a corner is the wrong trade.
     *
     * @return a layout whose blocks provably do not overlap and lie inside the frame. If even
     *   [MIN_SCALE] cannot achieve that -- a frame far narrower than anything a camera produces --
     *   the smallest stacked layout is returned with [Result.fits] false, so the caller can still
     *   draw something rather than nothing.
     */
    fun layout(
        frameWidth: Int,
        frameHeight: Int,
        content: OverlayContent,
        metrics: Metrics,
    ): Result {
        if (content.isEmpty || frameWidth <= 0 || frameHeight <= 0) {
            return Result(emptyList(), Arrangement.SideBySide, 1f, fits = true)
        }

        for (scale in SCALES) {
            for (arrangement in Arrangement.entries) {
                val candidate = build(frameWidth, frameHeight, content, metrics, scale, arrangement)
                if (isValid(candidate, frameWidth, frameHeight)) {
                    return Result(candidate, arrangement, scale, fits = true)
                }
            }
        }

        // Nothing fits. Draw the most compact arrangement anyway: an overlay that is slightly too
        // large is recoverable evidence, an absent one is not.
        val fallback = build(frameWidth, frameHeight, content, metrics, MIN_SCALE, Arrangement.Stacked)
        return Result(fallback, Arrangement.Stacked, MIN_SCALE, fits = false)
    }

    /** True when no two blocks intersect and every block is inside the frame. */
    fun isValid(blocks: List<Block>, frameWidth: Int, frameHeight: Int): Boolean {
        val width = frameWidth.toFloat()
        val height = frameHeight.toFloat()
        for (index in blocks.indices) {
            if (!blocks[index].scrim.isInside(width, height)) return false
            for (other in index + 1 until blocks.size) {
                if (blocks[index].scrim.overlaps(blocks[other].scrim)) return false
            }
        }
        return true
    }

    private fun build(
        frameWidth: Int,
        frameHeight: Int,
        content: OverlayContent,
        metrics: Metrics,
        scale: Float,
        arrangement: Arrangement,
    ): List<Block> {
        // The shorter dimension. This is the whole reason portrait and landscape now agree: a
        // fraction of the height alone makes portrait text enormous relative to the width it has
        // to fit into.
        val base = min(frameWidth, frameHeight).toFloat()
        val margin = base * MARGIN_FRACTION
        val bodySize = base * BODY_TEXT_FRACTION * scale
        val speedSize = base * SPEED_TEXT_FRACTION * scale
        val gap = base * GAP_FRACTION

        val blocks = mutableListOf<Block>()

        val leftLines = buildList {
            val stamp = listOfNotNull(content.dateText, content.timeText).joinToString("  ")
            if (stamp.isNotEmpty()) add(BlockId.Stamp to stamp)
            content.coordinatesText?.let { add(BlockId.Coordinates to it) }
            content.weatherText?.let { add(BlockId.Weather to it) }
        }

        // ── Speed: bottom-right, the anchor everything else works around ──────────────────
        var speedTop = frameHeight.toFloat()
        content.speedText?.let { speed ->
            val block = rightAligned(
                id = BlockId.Speed,
                text = speed,
                textSize = speedSize,
                bottom = frameHeight - margin,
                frameWidth = frameWidth,
                margin = margin,
                metrics = metrics,
            )
            blocks += block
            speedTop = block.scrim.top
        }

        // ── Left stack: date/time, coordinates, weather, growing upwards ──────────────────
        val leftBottom = when (arrangement) {
            Arrangement.SideBySide -> frameHeight - margin
            // Lift the stack clear of the speed block entirely.
            Arrangement.Stacked -> min(frameHeight - margin, speedTop - gap)
        }
        var bottom = leftBottom
        leftLines.asReversed().forEach { (id, text) ->
            val block = leftAligned(
                id = id,
                text = text,
                textSize = bodySize,
                bottom = bottom,
                margin = margin,
                metrics = metrics,
            )
            blocks += block
            bottom = block.scrim.top - gap * LINE_GAP_FRACTION
        }

        // ── Protection notice: top-right, out of the way of everything else ───────────────
        content.protectedLabel?.let { label ->
            val padding = bodySize * SCRIM_PADDING_FRACTION
            val ascent = metrics.ascent(bodySize)
            val descent = metrics.descent(bodySize)
            val top = margin
            val baseline = top + padding + ascent
            val textWidth = metrics.width(label, bodySize)
            val x = max(margin, frameWidth - margin - textWidth)
            blocks += Block(
                id = BlockId.Protected,
                text = label,
                textSize = bodySize,
                x = x,
                baselineY = baseline,
                scrim = Box(
                    left = x - padding,
                    top = top,
                    right = x + textWidth + padding,
                    bottom = baseline + descent + padding,
                ),
            )
        }

        return blocks
    }

    private fun leftAligned(
        id: BlockId,
        text: String,
        textSize: Float,
        bottom: Float,
        margin: Float,
        metrics: Metrics,
    ): Block {
        val padding = textSize * SCRIM_PADDING_FRACTION
        val ascent = metrics.ascent(textSize)
        val descent = metrics.descent(textSize)
        val baseline = bottom - padding - descent
        return Block(
            id = id,
            text = text,
            textSize = textSize,
            x = margin,
            baselineY = baseline,
            scrim = Box(
                left = margin - padding,
                top = baseline - ascent - padding,
                right = margin + metrics.width(text, textSize) + padding,
                bottom = bottom,
            ),
        )
    }

    private fun rightAligned(
        id: BlockId,
        text: String,
        textSize: Float,
        bottom: Float,
        frameWidth: Int,
        margin: Float,
        metrics: Metrics,
    ): Block {
        val padding = textSize * SCRIM_PADDING_FRACTION
        val ascent = metrics.ascent(textSize)
        val descent = metrics.descent(textSize)
        val baseline = bottom - padding - descent
        val textWidth = metrics.width(text, textSize)
        val x = max(margin, frameWidth - margin - textWidth)
        return Block(
            id = id,
            text = text,
            textSize = textSize,
            x = x,
            baselineY = baseline,
            scrim = Box(
                left = x - padding,
                top = baseline - ascent - padding,
                right = x + textWidth + padding,
                bottom = bottom,
            ),
        )
    }

    // ── Tuning ────────────────────────────────────────────────────────────────────────────

    /** Inset from the frame edge, as a fraction of the shorter dimension. */
    const val MARGIN_FRACTION = 0.030f

    /** Body text (date, time, coordinates, weather) as a fraction of the shorter dimension. */
    const val BODY_TEXT_FRACTION = 0.038f

    /** Speed text: larger, because it is the one value read at a glance. */
    const val SPEED_TEXT_FRACTION = 0.070f

    /** Clear space kept between blocks, as a fraction of the shorter dimension. */
    const val GAP_FRACTION = 0.020f

    /** Gap between stacked left-hand lines, as a fraction of [GAP_FRACTION]. */
    const val LINE_GAP_FRACTION = 0.45f

    /** Scrim padding around text, as a fraction of that text's size. */
    const val SCRIM_PADDING_FRACTION = 0.24f

    /**
     * Text scales tried in order, largest first.
     *
     * Coarse on purpose: each step is a visible change, and a finer search would spend more
     * arithmetic to land on a size nobody could distinguish.
     */
    val SCALES: List<Float> = listOf(1.0f, 0.92f, 0.84f, 0.76f, 0.68f, 0.60f, MIN_SCALE)

    const val MIN_SCALE = 0.52f
}
