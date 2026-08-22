package io.github.tunlezah.roadguard.ui

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pane-arrangement rule.
 *
 * Pure, so it needs no Compose runtime: the specification's layout requirement -- video above map
 * in portrait, video beside map in landscape -- reduces to one comparison of the *actual window*
 * dimensions, and this is the test that keeps it that simple. Anything cleverer (aspect-ratio
 * thresholds, device classes) would be a rule that could disagree with what a user can plainly see.
 */
class RoadguardWindowInfoTest {

    @Test
    fun `a tall phone window puts video above the map`() {
        assertThat(RoadguardWindowInfo.arrangementFor(412.dp, 892.dp))
            .isEqualTo(PaneArrangement.VideoAboveMap)
    }

    @Test
    fun `a wide phone window puts video beside the map`() {
        assertThat(RoadguardWindowInfo.arrangementFor(892.dp, 412.dp))
            .isEqualTo(PaneArrangement.VideoBesideMap)
    }

    @Test
    fun `an exactly square window keeps the portrait arrangement`() {
        // Ties go to portrait deliberately: it is the orientation a cradled phone is usually in,
        // and a tie must resolve one way rather than flapping.
        assertThat(RoadguardWindowInfo.arrangementFor(600.dp, 600.dp))
            .isEqualTo(PaneArrangement.VideoAboveMap)
    }

    @Test
    fun `a one-dp difference is enough to switch arrangement`() {
        assertThat(RoadguardWindowInfo.arrangementFor(601.dp, 600.dp))
            .isEqualTo(PaneArrangement.VideoBesideMap)
        assertThat(RoadguardWindowInfo.arrangementFor(600.dp, 601.dp))
            .isEqualTo(PaneArrangement.VideoAboveMap)
    }

    @Test
    fun `a small freeform window still gets a definite arrangement`() {
        // Multi-window and desktop-windowing cases must not fall through to a default: the rule
        // reads the window, not the display, so a 300x200 dp window is landscape like any other.
        assertThat(RoadguardWindowInfo.arrangementFor(300.dp, 200.dp))
            .isEqualTo(PaneArrangement.VideoBesideMap)
    }

    @Test
    fun `a foldable inner screen is landscape when it is wider than tall`() {
        assertThat(RoadguardWindowInfo.arrangementFor(1024.dp, 800.dp))
            .isEqualTo(PaneArrangement.VideoBesideMap)
    }

    @Test
    fun `isLandscape agrees with the arrangement`() {
        val portrait = info(412.dp, 892.dp)
        val landscape = info(892.dp, 412.dp)
        assertThat(portrait.isLandscape).isFalse()
        assertThat(landscape.isLandscape).isTrue()
    }

    private fun info(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) =
        RoadguardWindowInfo(
            widthDp = width,
            heightDp = height,
            arrangement = RoadguardWindowInfo.arrangementFor(width, height),
            sizeClass = androidx.window.core.layout.WindowSizeClass.compute(
                width.value,
                height.value,
            ),
        )
}
