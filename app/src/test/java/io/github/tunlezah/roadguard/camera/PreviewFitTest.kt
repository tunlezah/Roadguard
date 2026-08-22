package io.github.tunlezah.roadguard.camera

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.settings.PreviewZoom
import io.github.tunlezah.roadguard.ui.theme.DarkStatusColors
import io.github.tunlezah.roadguard.ui.theme.LightStatusColors
import io.github.tunlezah.roadguard.ui.theme.RoadguardDarkScheme
import io.github.tunlezah.roadguard.ui.theme.RoadguardLightScheme
import io.github.tunlezah.roadguard.ui.theme.RoadguardOledScheme
import io.github.tunlezah.roadguard.ui.theme.RoadguardStatusColors
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pins down the preview-fit geometry that decides how much of the camera image the driver
 * actually sees.
 *
 * Why this contract matters for a dashcam: the viewfinder is the only way a driver can
 * confirm that the bonnet, the lane markings and the vehicle in front are all inside the
 * frame *before* setting off. Auto is therefore allowed to magnify the preview to kill
 * letterbox bars, but only up to [PreviewFit.AUTO_MAX_FILL_ZOOM] -- past that it would be
 * hiding road that the recording is still capturing, which would make the preview lie about
 * the framing. These tests hand-compute the geometry independently of the implementation, so
 * a regression in the clamp, the road bias or the crop/letterbox accounting shows up as a
 * concrete number rather than as a subtly wrong picture.
 *
 * Everything here is pure arithmetic: no Android types, no clock, no randomness.
 */
class PreviewFitTest {

    // ── displayedSize ──────────────────────────────────────────────────────────────────

    @Test
    fun `displayedSize swaps width and height only for quarter turns`() {
        val cases = listOf(
            0 to (1920 to 1080),
            90 to (1080 to 1920),
            180 to (1920 to 1080),
            270 to (1080 to 1920),
            360 to (1920 to 1080),
        )
        for ((rotation, expected) in cases) {
            assertWithMessage("rotation %s", rotation)
                .that(PreviewFit.displayedSize(1920, 1080, rotation))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `displayedSize normalises rotations outside zero to 360`() {
        val cases = listOf(
            // Negative rotations wrap forwards.
            -90 to (1080 to 1920),
            -180 to (1920 to 1080),
            -270 to (1080 to 1920),
            -360 to (1920 to 1080),
            -450 to (1080 to 1920),
            // Rotations beyond a full turn wrap back into range.
            450 to (1080 to 1920),
            540 to (1920 to 1080),
            630 to (1080 to 1920),
            720 to (1920 to 1080),
        )
        for ((rotation, expected) in cases) {
            assertWithMessage("rotation %s", rotation)
                .that(PreviewFit.displayedSize(1920, 1080, rotation))
                .isEqualTo(expected)
        }
    }

    // ── degenerate input ──────────────────────────────────────────────────────────────

    @Test
    fun `degenerate dimensions return a safe identity fit instead of crashing`() {
        val cases = listOf(
            "zero source width" to intArrayOf(0, 1080, 1080, 1200),
            "zero source height" to intArrayOf(1920, 0, 1080, 1200),
            "zero panel width" to intArrayOf(1920, 1080, 0, 1200),
            "zero panel height" to intArrayOf(1920, 1080, 1080, 0),
            "negative source width" to intArrayOf(-1920, 1080, 1080, 1200),
            "negative panel height" to intArrayOf(1920, 1080, 1080, -1200),
            "everything zero" to intArrayOf(0, 0, 0, 0),
        )
        for ((name, d) in cases) {
            val result = PreviewFit.compute(d[0], d[1], d[2], d[3], PreviewZoom.Auto)
            assertWithMessage("%s scale", name).that(result.scale).isEqualTo(1f)
            assertWithMessage("%s effectiveZoom", name).that(result.effectiveZoom).isEqualTo(1f)
            assertWithMessage("%s zoomToFill", name).that(result.zoomToFill).isEqualTo(1f)
            assertWithMessage("%s verticalBias", name).that(result.verticalBias).isEqualTo(0f)
            assertWithMessage("%s horizontalBias", name).that(result.horizontalBias).isEqualTo(0f)
            assertWithMessage("%s croppedFraction", name).that(result.croppedFraction).isEqualTo(0f)
            assertWithMessage("%s letterboxedFraction", name)
                .that(result.letterboxedFraction).isEqualTo(0f)
        }
    }

    @Test
    fun `degenerate fit still reports which mode was asked for`() {
        assertThat(PreviewFit.compute(0, 0, 0, 0, PreviewZoom.Auto).isAuto).isTrue()
        assertThat(PreviewFit.compute(0, 0, 0, 0, PreviewZoom.X1_5).isAuto).isFalse()
        // A manual factor is *not* smuggled through the degenerate path.
        assertThat(PreviewFit.compute(0, 0, 0, 0, PreviewZoom.X1_5).scale).isEqualTo(1f)
    }

    // ── the exact-aspect case ─────────────────────────────────────────────────────────

    @Test
    fun `a panel matching the camera aspect needs no zoom at all`() {
        // 1920x1080 is 16:9; so is a 960x540 panel.
        val result = PreviewFit.compute(1920, 1080, 960, 540, PreviewZoom.Auto)

        assertThat(result.zoomToFill).isWithin(TOL).of(1f)
        assertThat(result.scale).isWithin(TOL).of(1f)
        assertThat(result.effectiveZoom).isWithin(TOL).of(1f)
        assertThat(result.fillsPanel).isTrue()
        assertThat(result.croppedFraction).isWithin(TOL).of(0f)
        assertThat(result.letterboxedFraction).isWithin(TOL).of(0f)
        assertThat(result.verticalBias).isEqualTo(0f)
        assertThat(result.horizontalBias).isEqualTo(0f)
    }

    // ── the real dashcam case ─────────────────────────────────────────────────────────

    @Test
    fun `the half height portrait panel clamps Auto and accepts a letterbox`() {
        // Phone held portrait, video panel roughly half the window: 1080 x 1200.
        // Camera image displayed 16:9 landscape: 1920 x 1080.
        val sw = 1920
        val sh = 1080
        val pw = 1080
        val ph = 1200

        // Hand computation: the fit is width-limited at 1080/1920 = 0.5625, drawing a
        // 1080 x 607.5 image inside a 1200-tall panel. Filling would need 1200/1080.
        val fit = 1080f / 1920f
        val fill = 1200f / 1080f
        val handZoomToFill = fill / fit
        assertThat(handZoomToFill).isWithin(1e-4f).of(1.9753086f)

        val result = PreviewFit.compute(sw, sh, pw, ph, PreviewZoom.Auto)
        assertThat(result.zoomToFill).isWithin(1e-4f).of(handZoomToFill)

        // Nearly 2x would throw away half the road, so Auto stops at the fill limit.
        assertThat(result.scale).isWithin(TOL).of(PreviewFit.AUTO_MAX_FILL_ZOOM)
        assertThat(result.effectiveZoom).isWithin(TOL).of(PreviewFit.AUTO_MAX_FILL_ZOOM)
        assertThat(result.isAuto).isTrue()

        // 1080 * 1.35 = 1458 wide against a 1080 panel: 378/1458 = 25.9% hidden.
        assertThat(result.croppedFraction).isWithin(1e-4f).of(378f / 1458f)
        // 607.5 * 1.35 = 820.125 tall against a 1200 panel: 379.875/1200 = 31.7% empty.
        assertThat(result.letterboxedFraction).isWithin(1e-4f).of(379.875f / 1200f)
        assertThat(result.letterboxedFraction).isGreaterThan(0f)
        assertThat(result.fillsPanel).isFalse()

        // The crop is entirely horizontal here, so no road bias is spent.
        assertThat(result.verticalBias).isEqualTo(0f)
    }

    @Test
    fun `a mild aspect mismatch lets Auto fill the panel exactly`() {
        // A 1600x1000 panel is 1.6:1 against a 1.7778:1 image -> zoomToFill = 1.1111.
        val result = PreviewFit.compute(1920, 1080, 1600, 1000, PreviewZoom.Auto)

        val handZoomToFill = (1000f / 1080f) / (1600f / 1920f)
        assertThat(handZoomToFill).isWithin(1e-4f).of(1.1111111f)
        assertThat(handZoomToFill).isLessThan(PreviewFit.AUTO_MAX_FILL_ZOOM)

        assertThat(result.zoomToFill).isWithin(1e-4f).of(handZoomToFill)
        assertThat(result.scale).isWithin(1e-4f).of(handZoomToFill)
        assertThat(result.fillsPanel).isTrue()
        assertThat(result.letterboxedFraction).isWithin(1e-3f).of(0f)
        // 1600 * 1.1111 = 1777.8 wide in a 1600 panel: exactly a tenth hidden.
        assertThat(result.croppedFraction).isWithin(1e-3f).of(0.1f)
    }

    // ── invariants across a sweep ─────────────────────────────────────────────────────

    @Test
    fun `Auto never shrinks below the full frame fit and never exceeds the limit`() {
        for ((sw, sh) in SOURCES) {
            for ((pw, ph) in PANELS) {
                val label = "${sw}x$sh in ${pw}x$ph"
                val result = PreviewFit.compute(sw, sh, pw, ph, PreviewZoom.Auto)

                assertWithMessage("%s scale floor", label).that(result.scale).isAtLeast(1f)
                assertWithMessage("%s scale ceiling", label)
                    .that(result.scale).isAtMost(PreviewFit.AUTO_MAX_FILL_ZOOM)
                assertWithMessage("%s zoomToFill floor", label)
                    .that(result.zoomToFill).isAtLeast(1f - TOL)
                assertWithMessage("%s clamped fill", label)
                    .that(result.scale)
                    .isWithin(1e-4f)
                    .of(min(expectedZoomToFill(sw, sh, pw, ph), PreviewFit.AUTO_MAX_FILL_ZOOM))
            }
        }
    }

    @Test
    fun `manual zoom factors are honoured exactly and are never Auto`() {
        val manual = PreviewZoom.entries.filter { it.factor != null }
        // Guards against the enum silently losing a step.
        assertThat(manual.mapNotNull { it.factor })
            .containsExactly(1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)
            .inOrder()

        for (zoom in manual) {
            val factor = zoom.factor!!
            for ((sw, sh) in SOURCES) {
                for ((pw, ph) in PANELS) {
                    val label = "${zoom.name} for ${sw}x$sh in ${pw}x$ph"
                    val result = PreviewFit.compute(sw, sh, pw, ph, zoom)
                    assertWithMessage("%s scale", label).that(result.scale).isEqualTo(factor)
                    assertWithMessage("%s effectiveZoom", label)
                        .that(result.effectiveZoom).isEqualTo(factor)
                    assertWithMessage("%s floor", label).that(result.scale).isAtLeast(1f)
                    assertWithMessage("%s isAuto", label).that(result.isAuto).isFalse()
                    // Manual zoom is reported alongside, not instead of, the fill figure.
                    assertWithMessage("%s zoomToFill", label)
                        .that(result.zoomToFill)
                        .isWithin(1e-4f).of(expectedZoomToFill(sw, sh, pw, ph))
                }
            }
        }
    }

    @Test
    fun `horizontal bias is always zero because the vanishing point is central`() {
        for (zoom in PreviewZoom.entries) {
            for ((sw, sh) in SOURCES) {
                for ((pw, ph) in PANELS) {
                    assertWithMessage("%s for %sx%s in %sx%s", zoom.name, sw, sh, pw, ph)
                        .that(PreviewFit.compute(sw, sh, pw, ph, zoom).horizontalBias)
                        .isEqualTo(0f)
                }
            }
        }
    }

    @Test
    fun `a full frame fit only letterboxes and a fill or better only crops`() {
        for ((sw, sh) in SOURCES) {
            for ((pw, ph) in PANELS) {
                val label = "${sw}x$sh in ${pw}x$ph"

                // Zoom 1.0 is the whole frame: nothing can be hidden.
                val whole = PreviewFit.compute(sw, sh, pw, ph, PreviewZoom.X1_0)
                assertWithMessage("whole frame crops nothing, %s", label)
                    .that(whole.croppedFraction).isLessThan(1e-3f)
                assertWithMessage("whole frame is crop xor letterbox, %s", label)
                    .that(min(whole.croppedFraction, whole.letterboxedFraction))
                    .isLessThan(1e-3f)

                // At or above zoomToFill the panel is covered: nothing can be left empty.
                val fill = expectedZoomToFill(sw, sh, pw, ph)
                for (zoom in PreviewZoom.entries) {
                    val factor = zoom.factor ?: continue
                    if (factor < fill) continue
                    val covered = PreviewFit.compute(sw, sh, pw, ph, zoom)
                    assertWithMessage("%s covers %s", zoom.name, label)
                        .that(covered.letterboxedFraction).isLessThan(1e-3f)
                    assertWithMessage("%s fills %s", zoom.name, label)
                        .that(covered.fillsPanel).isTrue()
                    assertWithMessage("%s is crop xor letterbox in %s", zoom.name, label)
                        .that(min(covered.croppedFraction, covered.letterboxedFraction))
                        .isLessThan(1e-3f)
                }

                // Auto shows both a crop and a letterbox only when it deliberately held back.
                val auto = PreviewFit.compute(sw, sh, pw, ph, PreviewZoom.Auto)
                if (auto.scale >= fill - TOL) {
                    assertWithMessage("unclamped Auto is crop xor letterbox, %s", label)
                        .that(min(auto.croppedFraction, auto.letterboxedFraction))
                        .isLessThan(1e-3f)
                }
            }
        }
    }

    @Test
    fun `clamped Auto trades a crop on one axis for a letterbox on the other`() {
        // Documents the deliberate consequence of the 1.35x ceiling: the panel's long axis
        // keeps a bar rather than the image losing more road. Both figures are material at
        // once, which is only possible while Auto is holding back.
        val result = PreviewFit.compute(1920, 1080, 1080, 1200, PreviewZoom.Auto)
        assertThat(result.scale).isLessThan(result.zoomToFill)
        assertThat(result.croppedFraction).isGreaterThan(0.05f)
        assertThat(result.letterboxedFraction).isGreaterThan(0.05f)
    }

    // ── the road bias ─────────────────────────────────────────────────────────────────

    @Test
    fun `a panel wider than the image crops vertically and spends the road bias`() {
        // A 2400x800 panel (3:1) against a 16:9 image: the fit is height-limited, so zooming
        // pushes image off the top and bottom -- exactly where sky and bonnet live.
        val result = PreviewFit.compute(1920, 1080, 2400, 800, PreviewZoom.Auto)

        assertThat(result.zoomToFill).isWithin(1e-4f).of((2400f / 1920f) / (800f / 1080f))
        assertThat(result.zoomToFill).isGreaterThan(PreviewFit.AUTO_MAX_FILL_ZOOM)
        assertThat(result.scale).isWithin(TOL).of(PreviewFit.AUTO_MAX_FILL_ZOOM)

        // 800 * 1.35 = 1080 tall in an 800 panel: 280/1080 = 25.9% hidden vertically,
        // far above the 2% negligible threshold.
        assertThat(result.croppedFraction).isWithin(1e-3f).of(280f / 1080f)
        assertThat(result.verticalBias).isEqualTo(PreviewFit.ROAD_BIAS)
        assertThat(result.horizontalBias).isEqualTo(0f)
    }

    @Test
    fun `a purely horizontal crop never spends the road bias`() {
        val horizontalOnly = listOf(
            // Tall panels: all the overflow is width, so there is no hidden height to bias.
            Triple(PreviewZoom.Auto, 1080 to 1200, "auto, half-height portrait"),
            // The same panel at 1.5x draws 1620x911 -- still width-only overflow.
            Triple(PreviewZoom.X1_5, 1080 to 1200, "1.5x, half-height portrait"),
            Triple(PreviewZoom.Auto, 1080 to 2400, "auto, full portrait"),
            Triple(PreviewZoom.Auto, 800 to 2000, "auto, narrow column"),
        )
        for ((zoom, panel, label) in horizontalOnly) {
            val result = PreviewFit.compute(1920, 1080, panel.first, panel.second, zoom)
            assertWithMessage("%s crops horizontally", label)
                .that(result.croppedFraction).isGreaterThan(0.02f)
            assertWithMessage("%s spends no road bias", label)
                .that(result.verticalBias).isEqualTo(0f)
        }
    }

    @Test
    fun `a negligible vertical crop is centred rather than biased`() {
        // A 1010x1000 panel over a 1000x1000 image needs only 1.01x to fill, hiding
        // 10/1010 = 0.99% of the height -- under the 2% negligible threshold.
        val result = PreviewFit.compute(1000, 1000, 1010, 1000, PreviewZoom.Auto)

        assertThat(result.zoomToFill).isWithin(1e-4f).of(1.01f)
        assertThat(result.scale).isWithin(1e-4f).of(1.01f)
        assertThat(result.croppedFraction).isGreaterThan(0f)
        assertThat(result.croppedFraction).isLessThan(0.02f)
        assertThat(result.verticalBias).isEqualTo(0f)
        assertThat(result.fillsPanel).isTrue()
    }

    // ── describe() ────────────────────────────────────────────────────────────────────

    @Test
    fun `describe marks Auto and omits crop and letterbox when the fit is exact`() {
        val text = PreviewFit.compute(1920, 1080, 960, 540, PreviewZoom.Auto).describe()
        // Locale-tolerant: the decimal separator is whatever the JVM default gives.
        assertThat(text).matches("1[.,]00x \\(Auto\\)")
        assertThat(text).doesNotContain("crop")
        assertThat(text).doesNotContain("letterbox")
    }

    @Test
    fun `describe names the display crop when Auto has to hide part of the image`() {
        val text = PreviewFit.compute(1920, 1080, 1080, 1200, PreviewZoom.Auto).describe()
        assertThat(text).matches("1[.,]35x \\(Auto\\) - display crop 25%")
    }

    @Test
    fun `describe names the letterbox and drops the Auto marker for manual zoom`() {
        // 1080x607.5 in a 1080x1200 panel leaves 49.4% of the panel empty.
        val text = PreviewFit.compute(1920, 1080, 1080, 1200, PreviewZoom.X1_0).describe()
        assertThat(text).matches("1[.,]00x - letterboxed 49%")
        assertThat(text).doesNotContain("Auto")
    }

    @Test
    fun `describe always states a zoom factor and marks Auto only in Auto mode`() {
        for (zoom in PreviewZoom.entries) {
            for ((pw, ph) in PANELS) {
                val result = PreviewFit.compute(1920, 1080, pw, ph, zoom)
                val text = result.describe()
                val label = "${zoom.name} in ${pw}x$ph -> '$text'"
                assertWithMessage("%s states a factor", label)
                    .that(text).containsMatch("^[0-9]+[.,][0-9]{2}x")
                assertWithMessage("%s Auto marker", label)
                    .that(text.contains("(Auto)")).isEqualTo(zoom == PreviewZoom.Auto)
            }
        }
    }

    private companion object {
        const val TOL = 1e-5f

        /** Rotation-corrected camera image sizes Roadguard has to cope with. */
        val SOURCES = listOf(
            1920 to 1080, // 16:9 Full HD, the default recording size
            1280 to 720,  // 16:9 baseline tier
            3840 to 2160, // 16:9 4K
            1440 to 1080, // 4:3 sensor readout
            1080 to 1920, // portrait-rotated buffer
            1440 to 1440, // square: pathological but legal
        )

        /** Panel shapes the video pane takes across orientations and split screen. */
        val PANELS = listOf(
            1080 to 1200, // portrait, panel is roughly half the window
            1080 to 2400, // portrait, full-bleed
            2400 to 1080, // landscape, full-bleed
            960 to 540,   // exactly 16:9
            1000 to 1000, // square
            2400 to 800,  // ultra-wide strip
            800 to 2000,  // narrow column
        )

        fun expectedZoomToFill(sw: Int, sh: Int, pw: Int, ph: Int): Float {
            val byWidth = pw.toFloat() / sw
            val byHeight = ph.toFloat() / sh
            return max(byWidth, byHeight) / min(byWidth, byHeight)
        }
    }
}

/**
 * Pins down the theme's accessibility claim: every text-on-background pair in the shipped
 * palettes clears WCAG 2.1 AA (4.5:1) and every status indicator clears the 3:1 non-text
 * threshold, both against its own label colour and against the surface it is drawn on.
 *
 * Why this contract matters for a dashcam: the app is read at a glance, in a car, through a
 * windscreen, in direct sunlight or at night, by someone who must not be staring at it. A
 * "recording" dot that fades into the surface, or a warning label that is unreadable in the
 * light theme, is a safety and evidence problem rather than a cosmetic one -- the driver has
 * no other way to know whether the camera is running. `Color.kt` states outright that this
 * test fails the build if a pair regresses, so this is the enforcement point for that promise.
 *
 * The WCAG maths (sRGB linearisation plus (L1+0.05)/(L2+0.05)) is implemented here rather
 * than pulled in, so the thresholds stay visible and no dependency is added. Robolectric is
 * used only so `androidx.compose.ui.graphics.Color` and the real Material 3 schemes can be
 * referenced by name instead of being copied into the test and drifting from the palette.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoadguardColorContrastTest {

    @Test
    fun `body text pairs clear WCAG AA in the light dark and OLED schemes`() {
        val failures = mutableListOf<String>()
        for ((schemeName, scheme) in SCHEMES) {
            for ((pairName, pick) in BODY_TEXT_PAIRS) {
                val (foreground, background) = pick(scheme)
                val ratio = contrastRatio(foreground, background)
                if (ratio < AA_BODY_TEXT) {
                    failures += "%s %s = %.2f:1 (needs %.1f:1)"
                        .format(schemeName, pairName, ratio, AA_BODY_TEXT)
                }
            }
        }
        assertWithMessage("body-text pairs under WCAG AA").that(failures).isEmpty()
    }

    @Test
    fun `every scheme and body text pair is actually measured`() {
        // Guards the sweeps themselves: a scheme or a pair quietly dropped from the tables
        // would make the assertions above pass vacuously.
        assertThat(SCHEMES.map { it.first })
            .containsExactly("light", "dark", "oled")
        assertThat(BODY_TEXT_PAIRS.map { it.first }).containsExactly(
            "onPrimary/primary",
            "onSecondary/secondary",
            "onTertiary/tertiary",
            "onError/error",
            "onBackground/background",
            "onSurface/surface",
            "onSurfaceVariant/surfaceVariant",
            "onPrimaryContainer/primaryContainer",
            "onErrorContainer/errorContainer",
        )
        assertThat(STATUS_PAIRS.map { it.first }).containsExactly(
            "recording/onRecording",
            "protected/onProtected",
            "ok/onOk",
            "warning/onWarning",
            "critical/onCritical",
            "idle/onIdle",
        )
        assertThat(STATUS_SETS).hasSize(3)
    }

    @Test
    fun `status label colours clear the three to one non-text threshold`() {
        val failures = mutableListOf<String>()
        for ((setName, status, _) in STATUS_SETS) {
            for ((pairName, pick) in STATUS_PAIRS) {
                val (fill, label) = pick(status)
                val ratio = contrastRatio(fill, label)
                if (ratio < NON_TEXT) {
                    failures += "%s %s = %.2f:1 (needs %.1f:1)"
                        .format(setName, pairName, ratio, NON_TEXT)
                }
            }
        }
        assertWithMessage("status label pairs under 3:1").that(failures).isEmpty()
    }

    @Test
    fun `every status indicator is visible against the surface it is drawn on`() {
        // An indicator dot carries its meaning by colour alone, so it must be
        // distinguishable from the panel behind it as well as from its own label.
        val failures = mutableListOf<String>()
        for ((setName, status, surface) in STATUS_SETS) {
            for ((pairName, pick) in STATUS_PAIRS) {
                val (fill, _) = pick(status)
                val ratio = contrastRatio(fill, surface)
                if (ratio < NON_TEXT) {
                    failures += "%s %s vs surface = %.2f:1 (needs %.1f:1)"
                        .format(setName, pairName.substringBefore('/'), ratio, NON_TEXT)
                }
            }
        }
        assertWithMessage("status indicators under 3:1 against their surface")
            .that(failures).isEmpty()
    }

    @Test
    fun `the WCAG helper reproduces the reference ratios`() {
        // Without these the sweeps above could pass on a broken formula. Black on white is
        // the textbook 21:1; a colour against itself is 1:1; #767676 on white is the
        // canonical "only just passes AA" value.
        assertThat(contrastRatio(Color(0xFF000000), Color(0xFFFFFFFF))).isWithin(0.01).of(21.0)
        assertThat(contrastRatio(Color(0xFFFFFFFF), Color(0xFF000000))).isWithin(0.01).of(21.0)
        assertThat(contrastRatio(Color(0xFF33CEFB), Color(0xFF33CEFB))).isWithin(1e-9).of(1.0)
        assertThat(contrastRatio(Color(0xFF767676), Color(0xFFFFFFFF))).isWithin(0.02).of(4.54)
        assertThat(contrastRatio(Color(0xFF777777), Color(0xFFFFFFFF))).isLessThan(AA_BODY_TEXT)
    }

    private companion object {
        const val AA_BODY_TEXT = 4.5
        const val NON_TEXT = 3.0

        val SCHEMES: List<Pair<String, ColorScheme>> = listOf(
            "light" to RoadguardLightScheme,
            "dark" to RoadguardDarkScheme,
            "oled" to RoadguardOledScheme,
        )

        val BODY_TEXT_PAIRS: List<Pair<String, (ColorScheme) -> Pair<Color, Color>>> = listOf(
            "onPrimary/primary" to { s: ColorScheme -> s.onPrimary to s.primary },
            "onSecondary/secondary" to { s: ColorScheme -> s.onSecondary to s.secondary },
            "onTertiary/tertiary" to { s: ColorScheme -> s.onTertiary to s.tertiary },
            "onError/error" to { s: ColorScheme -> s.onError to s.error },
            "onBackground/background" to { s: ColorScheme -> s.onBackground to s.background },
            "onSurface/surface" to { s: ColorScheme -> s.onSurface to s.surface },
            "onSurfaceVariant/surfaceVariant" to
                { s: ColorScheme -> s.onSurfaceVariant to s.surfaceVariant },
            "onPrimaryContainer/primaryContainer" to
                { s: ColorScheme -> s.onPrimaryContainer to s.primaryContainer },
            "onErrorContainer/errorContainer" to
                { s: ColorScheme -> s.onErrorContainer to s.errorContainer },
        )

        /** Each status palette paired with the surface `RoadguardTheme` shows it against. */
        val STATUS_SETS: List<Triple<String, RoadguardStatusColors, Color>> = listOf(
            Triple("LightStatusColors", LightStatusColors, RoadguardLightScheme.surface),
            Triple("DarkStatusColors", DarkStatusColors, RoadguardDarkScheme.surface),
            Triple("DarkStatusColors on OLED", DarkStatusColors, RoadguardOledScheme.surface),
        )

        val STATUS_PAIRS: List<Pair<String, (RoadguardStatusColors) -> Pair<Color, Color>>> =
            listOf(
                "recording/onRecording" to
                    { c: RoadguardStatusColors -> c.recording to c.onRecording },
                "protected/onProtected" to
                    { c: RoadguardStatusColors -> c.protected to c.onProtected },
                "ok/onOk" to { c: RoadguardStatusColors -> c.ok to c.onOk },
                "warning/onWarning" to
                    { c: RoadguardStatusColors -> c.warning to c.onWarning },
                "critical/onCritical" to
                    { c: RoadguardStatusColors -> c.critical to c.onCritical },
                "idle/onIdle" to { c: RoadguardStatusColors -> c.idle to c.onIdle },
            )

        /** WCAG 2.1 relative luminance of an opaque sRGB colour. */
        fun relativeLuminance(color: Color): Double {
            fun linearise(channel: Float): Double {
                val c = channel.toDouble()
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linearise(color.red) +
                0.7152 * linearise(color.green) +
                0.0722 * linearise(color.blue)
        }

        /** WCAG 2.1 contrast ratio; always >= 1 regardless of argument order. */
        fun contrastRatio(a: Color, b: Color): Double {
            val la = relativeLuminance(a)
            val lb = relativeLuminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
    }
}
