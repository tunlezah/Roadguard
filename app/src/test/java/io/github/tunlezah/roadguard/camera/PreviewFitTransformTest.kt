package io.github.tunlezah.roadguard.camera

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.settings.PreviewZoom
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The preview fit, exercised the way the viewfinder exercises it.
 *
 * These are the tests the old implementation could not have: it derived the displayed source size
 * from the *device* rotation, which is not the rotation the viewfinder applies, so there was no
 * point at which the real source size was available to assert against. [PreviewFitTransform] is
 * handed that size by the framework, so the whole thing is now a pure function of two sizes.
 *
 * The landscape regression is pinned twice over: once on the scale, and once on the alignment,
 * because the visible symptom -- an image pushed off the top with a black band at the bottom --
 * came from the *alignment* applying a vertical bias to an axis that had no overflow.
 */
class PreviewFitTransformTest {

    private fun transform(zoom: PreviewZoom = PreviewZoom.Auto) =
        PreviewFitTransform().apply { this.zoom = zoom }

    /** What the viewfinder does: scale, then align, then read where the image landed. */
    private fun place(
        source: Size,
        panel: Size,
        zoom: PreviewZoom = PreviewZoom.Auto,
    ): Placement {
        val subject = transform(zoom)
        val factor = subject.computeScaleFactor(source, panel)
        val scaled = IntSize(
            (source.width * factor.scaleX).roundToInt(),
            (source.height * factor.scaleY).roundToInt(),
        )
        val space = IntSize(panel.width.roundToInt(), panel.height.roundToInt())
        val offset = subject.align(scaled, space, LayoutDirection.Ltr)
        return Placement(factor, scaled, space, offset, requireNotNull(subject.latest))
    }

    private data class Placement(
        val factor: ScaleFactor,
        val scaled: IntSize,
        val space: IntSize,
        val offset: IntOffset,
        val fit: PreviewFitResult,
    ) {
        val top: Int get() = offset.y
        val bottom: Int get() = offset.y + scaled.height
        val left: Int get() = offset.x
        val right: Int get() = offset.x + scaled.width

        /** Empty panel above the image. Negative means the image is clipped off the top. */
        val gapTop: Int get() = top

        /** Empty panel below the image. Negative means the image is clipped off the bottom. */
        val gapBottom: Int get() = space.height - bottom
    }

    // ── The reported landscape regression ─────────────────────────────────────────────────

    @Test
    fun `landscape source in a landscape panel is not pushed off the top`() {
        // The exact geometry from the bug report: a 1920x1080 image displayed in the left-hand pane
        // of a landscape window. The old code left the image 12px off the top with an 88px black
        // band along the bottom.
        val placement = place(Size(1920f, 1080f), Size(1150f, 950f))

        // Before the fix: gapTop = -12 (clipped) and gapBottom = 88 (black band). The property is
        // that neither edge is clipped and the leftover space is shared evenly -- within the one
        // pixel an odd amount of slack cannot split.
        assertWithMessage("image clipped off the top")
            .that(placement.gapTop).isAtLeast(0)
        assertWithMessage("image clipped off the bottom")
            .that(placement.gapBottom).isAtLeast(0)
        assertWithMessage("lopsided: ${placement.gapTop} above, ${placement.gapBottom} below")
            .that(Math.abs(placement.gapTop - placement.gapBottom)).isAtMost(1)
    }

    @Test
    fun `landscape gets no vertical bias, because there is no vertical overflow`() {
        val placement = place(Size(1920f, 1080f), Size(1150f, 950f))

        // Auto fills the width and letterboxes vertically here, so a downward bias would be
        // spending overflow that does not exist -- which is precisely the old bug.
        assertThat(placement.fit.verticalBias).isEqualTo(0f)
        assertThat(placement.scaled.height).isAtMost(placement.space.height)
    }

    @Test
    fun `the image is centred vertically when it is shorter than the panel`() {
        val placement = place(Size(1920f, 1080f), Size(1150f, 950f))

        assertThat(Math.abs(placement.gapTop - placement.gapBottom)).isAtMost(1)
    }

    @Test
    fun `the image is centred horizontally in every case`() {
        for ((source, panel) in ORIENTATIONS) {
            val placement = place(source, panel)
            val slack = placement.space.width - placement.scaled.width
            // Integer pixels: an odd amount of slack cannot split exactly in half.
            assertWithMessage("$source in $panel")
                .that(Math.abs(placement.left - slack / 2)).isAtMost(1)
        }
    }

    // ── Portrait must not regress ─────────────────────────────────────────────────────────

    @Test
    fun `portrait still fills the panel and applies the road bias`() {
        // A portrait image in the top pane of a portrait window: the image is taller than the
        // panel once Auto magnifies, so the road bias has real overflow to spend.
        val placement = place(Size(1080f, 1920f), Size(1080f, 1000f))

        assertThat(placement.fit.verticalBias).isGreaterThan(0f)
        assertThat(placement.scaled.height).isGreaterThan(placement.space.height)
        // Biased downwards means the visible window sits lower in the image, so the image itself
        // is shifted up relative to centre.
        val centred = (placement.space.height - placement.scaled.height) / 2
        assertThat(placement.top).isLessThan(centred)
    }

    @Test
    fun `portrait and landscape agree on scale for the same source and panel`() {
        // Nothing about the transform depends on device orientation any more, so identical inputs
        // must give identical output regardless of how the phone is held.
        val a = place(Size(1920f, 1080f), Size(1150f, 950f))
        val b = place(Size(1920f, 1080f), Size(1150f, 950f))

        assertThat(a.factor.scaleX).isEqualTo(b.factor.scaleX)
    }

    // ── Scale correctness ─────────────────────────────────────────────────────────────────

    @Test
    fun `the returned scale is absolute, like ContentScale Fit`() {
        // A 2:1 source in a 1:1 panel with no zoom fits to the width.
        val subject = transform(PreviewZoom.X1_0)
        val factor = subject.computeScaleFactor(Size(1000f, 500f), Size(500f, 500f))

        assertThat(factor.scaleX).isWithin(0.001f).of(0.5f)
        assertThat(factor.scaleY).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `the scale stays uniform, so the image is never distorted`() {
        for ((source, panel) in ORIENTATIONS) {
            for (zoom in PreviewZoom.entries) {
                val subject = transform(zoom)
                val factor = subject.computeScaleFactor(source, panel)
                assertWithMessage("$source in $panel at $zoom")
                    .that(factor.scaleX).isEqualTo(factor.scaleY)
            }
        }
    }

    @Test
    fun `a manual zoom multiplies the fit rather than replacing it`() {
        val fitOnly = transform(PreviewZoom.X1_0)
            .computeScaleFactor(Size(1920f, 1080f), Size(1150f, 950f)).scaleX
        val doubled = transform(PreviewZoom.X2_0)
            .computeScaleFactor(Size(1920f, 1080f), Size(1150f, 950f)).scaleX

        assertThat(doubled).isWithin(0.001f).of(fitOnly * 2f)
    }

    @Test
    fun `auto never magnifies past the documented ceiling`() {
        for ((source, panel) in ORIENTATIONS) {
            val subject = transform(PreviewZoom.Auto)
            subject.computeScaleFactor(source, panel)
            assertWithMessage("$source in $panel")
                .that(subject.latest!!.scale)
                .isAtMost(PreviewFit.AUTO_MAX_FILL_ZOOM)
        }
    }

    @Test
    fun `the image never leaves the panel in the axis it does not overflow`() {
        for ((source, panel) in ORIENTATIONS) {
            val placement = place(source, panel)
            // Whichever axis has slack must be centred, never pushed past an edge.
            if (placement.scaled.height <= placement.space.height) {
                assertWithMessage("$source in $panel: top").that(placement.top).isAtLeast(0)
                assertWithMessage("$source in $panel: bottom")
                    .that(placement.bottom).isAtMost(placement.space.height)
            }
            if (placement.scaled.width <= placement.space.width) {
                assertWithMessage("$source in $panel: left").that(placement.left).isAtLeast(0)
                assertWithMessage("$source in $panel: right")
                    .that(placement.right).isAtMost(placement.space.width)
            }
        }
    }

    // ── Reporting ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the fit is reported once per change, not once per call`() {
        var reports = 0
        val subject = transform().apply { onFit = { reports++ } }

        subject.computeScaleFactor(Size(1920f, 1080f), Size(1150f, 950f))
        subject.computeScaleFactor(Size(1920f, 1080f), Size(1150f, 950f))
        subject.computeScaleFactor(Size(1920f, 1080f), Size(1150f, 950f))
        assertThat(reports).isEqualTo(1)

        subject.computeScaleFactor(Size(1920f, 1080f), Size(900f, 950f))
        assertThat(reports).isEqualTo(2)
    }

    @Test
    fun `align before any scale computation is a plain centre`() {
        // Defensive: the viewfinder always scales first, but a centre is the right answer if the
        // order ever changed, rather than a crash or an arbitrary offset.
        val subject = transform()
        val offset = subject.align(IntSize(100, 100), IntSize(300, 300), LayoutDirection.Ltr)

        assertThat(offset).isEqualTo(IntOffset(100, 100))
    }

    @Test
    fun `a degenerate source does not throw`() {
        val subject = transform()

        assertThat(subject.computeScaleFactor(Size(0f, 0f), Size(100f, 100f)).scaleX).isEqualTo(1f)
        assertThat(subject.computeScaleFactor(Size(100f, 100f), Size(0f, 0f)).scaleX).isEqualTo(1f)
    }

    private companion object {
        /** Source/panel pairs covering both orientations of each, plus square. */
        val ORIENTATIONS = listOf(
            Size(1920f, 1080f) to Size(1150f, 950f),   // landscape image, landscape pane
            Size(1920f, 1080f) to Size(1080f, 1000f),  // landscape image, portrait pane
            Size(1080f, 1920f) to Size(1080f, 1000f),  // portrait image, portrait pane
            Size(1080f, 1920f) to Size(1150f, 950f),   // portrait image, landscape pane
            Size(1440f, 1080f) to Size(1000f, 1000f),  // 4:3 image, square pane
            Size(1080f, 1080f) to Size(1150f, 950f),   // square image
        )
    }
}
