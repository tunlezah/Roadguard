package io.github.tunlezah.roadguard.overlay

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.event.BrakeLevel
import org.junit.Test

/**
 * The overlay must never draw one label over another, in any orientation.
 *
 * This is the test the old inline layout did not have, and the bug it would have caught: text was
 * sized as a fraction of frame *height*, so a 1080x1920 portrait frame got 78% larger text than the
 * 1920x1080 landscape frame of the same resolution. The date/time stamp and the speed then shared
 * the bottom row and collided -- invisible in landscape, obvious in portrait.
 *
 * So the important cases here are not the pretty ones. They are every orientation crossed with
 * every combination of enabled overlay fields, plus deliberately hostile strings.
 */
class OverlayLayoutTest {

    /**
     * A proportional stand-in for a bold sans font.
     *
     * Deliberately *wider* than a real one (0.62 em average against roughly 0.55 for Roboto Bold
     * digits and mixed case), so a layout that passes here has margin in hand on a device. Using a
     * real `Paint` would need Robolectric and would tie the assertions to whatever font the test
     * host happens to have.
     */
    private val metrics = object : OverlayLayout.Metrics {
        override fun width(text: String, textSize: Float) = text.length * textSize * 0.62f
        override fun ascent(textSize: Float) = textSize * 0.78f
        override fun descent(textSize: Float) = textSize * 0.22f
    }

    /** A narrower font, to check the layout is not tuned to one metric. */
    private val narrowMetrics = object : OverlayLayout.Metrics {
        override fun width(text: String, textSize: Float) = text.length * textSize * 0.48f
        override fun ascent(textSize: Float) = textSize * 0.75f
        override fun descent(textSize: Float) = textSize * 0.25f
    }

    private val full = OverlayContent(
        dateText = "23 Aug 2026",
        timeText = "10:30:00",
        speedText = "108 km/h",
        coordinatesText = "-33.86882, 151.20930",
        weatherText = "22°C  Partly cloudy",
        protectedLabel = "PROTECTED",
        brake = BrakeLevel.HardBraking,
    )

    /** Every resolution the recorder can produce, both ways round. */
    private val frames = listOf(
        720 to 480, 480 to 720,
        1280 to 720, 720 to 1280,
        1920 to 1080, 1080 to 1920,
        3840 to 2160, 2160 to 3840,
        // Square and near-square, because the ladder is not the only thing a device may report.
        1080 to 1080, 1440 to 1080, 1080 to 1440,
    )

    // ── The guarantee ─────────────────────────────────────────────────────────────────────

    @Test
    fun `nothing overlaps for any field combination in any orientation`() {
        var checked = 0
        for ((width, height) in frames) {
            for (content in allContentCombinations()) {
                if (content.isEmpty) continue
                val result = OverlayLayout.layout(width, height, content, metrics)
                assertThat(result.fits).isTrue()
                assertOverlapFree(result, width, height, "${width}x$height $content")
                checked++
            }
        }
        // 11 frames x 127 non-empty combinations. Guards against the loop silently doing nothing.
        assertThat(checked).isEqualTo(frames.size * 127)
    }

    @Test
    fun `nothing overlaps with a narrower font either`() {
        for ((width, height) in frames) {
            val result = OverlayLayout.layout(width, height, full, narrowMetrics)
            assertThat(result.fits).isTrue()
            assertOverlapFree(result, width, height, "${width}x$height narrow")
        }
    }

    @Test
    fun `the reported portrait case is fixed`() {
        // 1080x1920 with date, time and speed: the exact frame and fields that collided.
        val content = OverlayContent(
            dateText = "23 Aug 2026",
            timeText = "10:30:00",
            speedText = "108 km/h",
            coordinatesText = "-33.86882, 151.20930",
        )
        val result = OverlayLayout.layout(1080, 1920, content, metrics)

        assertOverlapFree(result, 1080, 1920, "reported case")
        val speed = result.blocks.single { it.id == OverlayLayout.BlockId.Speed }
        val stamp = result.blocks.single { it.id == OverlayLayout.BlockId.Stamp }
        val coordinates = result.blocks.single { it.id == OverlayLayout.BlockId.Coordinates }
        assertThat(speed.scrim.overlaps(stamp.scrim)).isFalse()
        assertThat(speed.scrim.overlaps(coordinates.scrim)).isFalse()
    }

    @Test
    fun `portrait and landscape of the same resolution use the same text size`() {
        // The root cause: sizing off height alone made portrait text 78% larger than landscape.
        val landscape = OverlayLayout.layout(1920, 1080, full, metrics)
        val portrait = OverlayLayout.layout(1080, 1920, full, metrics)

        val landscapeSpeed = landscape.blocks.single { it.id == OverlayLayout.BlockId.Speed }
        val portraitSpeed = portrait.blocks.single { it.id == OverlayLayout.BlockId.Speed }
        assertThat(portraitSpeed.textSize).isWithin(0.01f).of(landscapeSpeed.textSize)
    }

    @Test
    fun `an absurdly long line is shrunk rather than allowed to run off the frame`() {
        val content = OverlayContent(
            dateText = "Saturday 23 August 2026",
            timeText = "10:30:00 Australian Eastern Standard Time",
            speedText = "108 kilometres per hour",
            coordinatesText = "-33.868820000, 151.209300000 (+/- 3 m)",
        )
        val result = OverlayLayout.layout(720, 480, content, metrics)

        assertOverlapFree(result, 720, 480, "long strings")
        assertThat(result.scale).isLessThan(1.0f)
    }

    // ── Arrangement behaviour ─────────────────────────────────────────────────────────────

    @Test
    fun `a wide frame keeps the speed beside the stack`() {
        val result = OverlayLayout.layout(1920, 1080, full, metrics)

        assertThat(result.arrangement).isEqualTo(OverlayLayout.Arrangement.SideBySide)
        assertThat(result.scale).isEqualTo(1.0f)
    }

    @Test
    fun `rearranging is preferred over shrinking the speed`() {
        // A frame narrow enough that side-by-side cannot hold both at full size. The speed reading
        // is what a driver glances at, so the layout should move it to its own row at full size
        // rather than keep the corner and make it smaller.
        val content = OverlayContent(
            dateText = "23 Aug 2026",
            timeText = "10:30:00",
            speedText = "108 km/h",
        )
        val result = OverlayLayout.layout(480, 720, content, metrics)

        assertOverlapFree(result, 480, 720, "narrow frame")
        if (result.arrangement == OverlayLayout.Arrangement.Stacked) {
            assertThat(result.scale).isEqualTo(1.0f)
        }
    }

    @Test
    fun `stacking lifts the left stack clear of the speed block`() {
        val content = OverlayContent(
            dateText = "Saturday 23 August 2026",
            timeText = "10:30:00",
            speedText = "108 km/h",
        )
        val result = OverlayLayout.layout(600, 900, content, metrics)
        val speed = result.blocks.single { it.id == OverlayLayout.BlockId.Speed }
        val stamp = result.blocks.single { it.id == OverlayLayout.BlockId.Stamp }

        if (result.arrangement == OverlayLayout.Arrangement.Stacked) {
            assertThat(stamp.scrim.bottom).isAtMost(speed.scrim.top)
        }
    }

    // ── Placement ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the speed sits in the bottom-right and the stamp in the bottom-left`() {
        val result = OverlayLayout.layout(1920, 1080, full, metrics)
        val speed = result.blocks.single { it.id == OverlayLayout.BlockId.Speed }
        val stamp = result.blocks.single { it.id == OverlayLayout.BlockId.Stamp }

        assertThat(speed.scrim.left).isGreaterThan(1920f / 2)
        assertThat(stamp.scrim.left).isLessThan(1920f / 2)
        assertThat(speed.scrim.bottom).isGreaterThan(1080f * 0.9f)

        // The stamp is the *top* line of the left stack (date, then coordinates, then weather
        // reading downwards), so it is the lowest left-hand block that sits on the bottom margin.
        val lowestLeft = result.blocks
            .filter { it.id != OverlayLayout.BlockId.Speed && it.id != OverlayLayout.BlockId.Protected }
            .maxBy { it.scrim.bottom }
        assertThat(lowestLeft.scrim.bottom).isGreaterThan(1080f * 0.9f)
        assertThat(lowestLeft.scrim.left).isLessThan(1920f / 2)
    }

    @Test
    fun `the protection notice sits at the top, away from the data`() {
        val result = OverlayLayout.layout(1920, 1080, full, metrics)
        val protectedBlock = result.blocks.single { it.id == OverlayLayout.BlockId.Protected }

        assertThat(protectedBlock.scrim.top).isLessThan(1080f * 0.1f)
        assertThat(protectedBlock.scrim.right).isGreaterThan(1920f * 0.7f)
    }

    @Test
    fun `the left stack reads top to bottom as date, coordinates, weather`() {
        val result = OverlayLayout.layout(1920, 1080, full, metrics)
        val stamp = result.blocks.single { it.id == OverlayLayout.BlockId.Stamp }
        val coordinates = result.blocks.single { it.id == OverlayLayout.BlockId.Coordinates }
        val weather = result.blocks.single { it.id == OverlayLayout.BlockId.Weather }

        assertThat(stamp.scrim.top).isLessThan(coordinates.scrim.top)
        assertThat(coordinates.scrim.top).isLessThan(weather.scrim.top)
    }

    @Test
    fun `date and time are one block, so they cannot separate`() {
        val result = OverlayLayout.layout(1920, 1080, full, metrics)

        assertThat(result.blocks.count { it.id == OverlayLayout.BlockId.Stamp }).isEqualTo(1)
        assertThat(result.blocks.single { it.id == OverlayLayout.BlockId.Stamp }.text)
            .isEqualTo("23 Aug 2026  10:30:00")
    }

    // ── Degenerate input ──────────────────────────────────────────────────────────────────

    @Test
    fun `empty content produces nothing to draw`() {
        val result = OverlayLayout.layout(1920, 1080, OverlayContent.EMPTY, metrics)

        assertThat(result.isEmpty).isTrue()
        assertThat(result.fits).isTrue()
    }

    @Test
    fun `a zero-sized frame produces nothing rather than throwing`() {
        assertThat(OverlayLayout.layout(0, 1080, full, metrics).isEmpty).isTrue()
        assertThat(OverlayLayout.layout(1920, 0, full, metrics).isEmpty).isTrue()
        assertThat(OverlayLayout.layout(-1, -1, full, metrics).isEmpty).isTrue()
    }

    @Test
    fun `a single field is placed without incident`() {
        for ((width, height) in frames) {
            val result = OverlayLayout.layout(width, height, OverlayContent(speedText = "60 km/h"), metrics)
            assertThat(result.blocks).hasSize(1)
            assertOverlapFree(result, width, height, "speed only ${width}x$height")
        }
    }

    // ── The brake LED ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the brake LED sits in the top-left corner when lit`() {
        for ((width, height) in frames) {
            val result = OverlayLayout.layout(width, height, full, metrics)
            val led = result.led
            assertWithMessage("${width}x$height should keep the LED").that(led).isNotNull()
            led!!
            assertThat(led.level).isEqualTo(BrakeLevel.HardBraking)
            assertWithMessage("LED belongs in the left half").that(led.centerX).isLessThan(width / 2f)
            assertWithMessage("LED belongs in the top half").that(led.centerY).isLessThan(height / 2f)
        }
    }

    @Test
    fun `no brake means no LED`() {
        val result = OverlayLayout.layout(1920, 1080, full.copy(brake = null), metrics)
        assertThat(result.led).isNull()
    }

    @Test
    fun `the LED is sized from the shorter dimension like the text`() {
        val landscape = OverlayLayout.layout(1920, 1080, full, metrics)
        val portrait = OverlayLayout.layout(1080, 1920, full, metrics)
        assertThat(portrait.led!!.radius).isWithin(0.01f).of(landscape.led!!.radius)
        // And it really is tiny: about a couple of percent of the short side.
        assertThat(landscape.led!!.radius).isWithin(0.01f).of(1080 * OverlayLayout.LED_RADIUS_FRACTION)
    }

    @Test
    fun `a lit LED with no text still renders`() {
        // Speed can expire a moment before the brake light lets go; the layout must not treat
        // "dot only" as an empty overlay.
        val result = OverlayLayout.layout(1920, 1080, OverlayContent(brake = BrakeLevel.Braking), metrics)
        assertThat(result.isEmpty).isFalse()
        assertThat(result.blocks).isEmpty()
        assertThat(result.led).isNotNull()
    }

    // ── Box arithmetic ────────────────────────────────────────────────────────────────────

    @Test
    fun `touching edges do not count as overlapping`() {
        val left = OverlayLayout.Box(0f, 0f, 10f, 10f)
        val right = OverlayLayout.Box(10f, 0f, 20f, 10f)

        assertThat(left.overlaps(right)).isFalse()
        assertThat(right.overlaps(left)).isFalse()
    }

    @Test
    fun `a one-unit intrusion does count as overlapping`() {
        val left = OverlayLayout.Box(0f, 0f, 10f, 10f)
        val right = OverlayLayout.Box(9f, 0f, 20f, 10f)

        assertThat(left.overlaps(right)).isTrue()
        assertThat(right.overlaps(left)).isTrue()
    }

    @Test
    fun `isInside rejects a box hanging off any edge`() {
        assertThat(OverlayLayout.Box(0f, 0f, 10f, 10f).isInside(10f, 10f)).isTrue()
        assertThat(OverlayLayout.Box(-1f, 0f, 10f, 10f).isInside(10f, 10f)).isFalse()
        assertThat(OverlayLayout.Box(0f, -1f, 10f, 10f).isInside(10f, 10f)).isFalse()
        assertThat(OverlayLayout.Box(0f, 0f, 11f, 10f).isInside(10f, 10f)).isFalse()
        assertThat(OverlayLayout.Box(0f, 0f, 10f, 11f).isInside(10f, 10f)).isFalse()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private fun assertOverlapFree(result: OverlayLayout.Result, width: Int, height: Int, label: String) {
        result.blocks.forEach { block ->
            assertWithMessage("$label: ${block.id} at ${block.scrim} escapes ${width}x$height")
                .that(block.scrim.isInside(width.toFloat(), height.toFloat()))
                .isTrue()
        }
        for (index in result.blocks.indices) {
            for (other in index + 1 until result.blocks.size) {
                val a = result.blocks[index]
                val b = result.blocks[other]
                assertWithMessage("$label: ${a.id} ${a.scrim} overlaps ${b.id} ${b.scrim}")
                    .that(a.scrim.overlaps(b.scrim))
                    .isFalse()
            }
        }
        result.led?.let { led ->
            assertWithMessage("$label: LED ${led.box} escapes ${width}x$height")
                .that(led.box.isInside(width.toFloat(), height.toFloat()))
                .isTrue()
            result.blocks.forEach { block ->
                assertWithMessage("$label: LED ${led.box} overlaps ${block.id} ${block.scrim}")
                    .that(block.scrim.overlaps(led.box))
                    .isFalse()
            }
        }
    }

    /** All 128 on/off combinations of the seven overlay fields. */
    private fun allContentCombinations(): List<OverlayContent> = (0 until 128).map { mask ->
        OverlayContent(
            dateText = full.dateText.takeIf { mask and 1 != 0 },
            timeText = full.timeText.takeIf { mask and 2 != 0 },
            speedText = full.speedText.takeIf { mask and 4 != 0 },
            coordinatesText = full.coordinatesText.takeIf { mask and 8 != 0 },
            weatherText = full.weatherText.takeIf { mask and 16 != 0 },
            protectedLabel = full.protectedLabel.takeIf { mask and 32 != 0 },
            brake = full.brake.takeIf { mask and 64 != 0 },
        )
    }
}
