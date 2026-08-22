package io.github.tunlezah.roadguard.ui

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * How the two primary panes are arranged.
 *
 * The specification is explicit: portrait puts video on top and the map underneath; landscape puts
 * video on the left and the map on the right.
 */
enum class PaneArrangement {
    /** Portrait: video above, map below. */
    VideoAboveMap,

    /** Landscape: video left, map right. */
    VideoBesideMap,
}

/**
 * What Roadguard knows about the window it is drawing into.
 *
 * Derived from the *actual window size*, not from the device model or a hard-coded aspect ratio:
 * the app has to be right on a tall 20:9 phone, on a squarer one, in a freeform window and on a
 * foldable's inner screen. `currentWindowDpSize()` reports the window rather than the display, so
 * multi-window and desktop-windowing cases fall out for free.
 */
data class RoadguardWindowInfo(
    val widthDp: Dp,
    val heightDp: Dp,
    val arrangement: PaneArrangement,
    val sizeClass: WindowSizeClass,
) {
    val isLandscape: Boolean get() = arrangement == PaneArrangement.VideoBesideMap

    /**
     * True on a short window where full-height controls would crowd the panes -- a phone in
     * landscape, mostly. Used to drop to a compact control strip rather than a full bar.
     */
    val isCompactHeight: Boolean
        get() = !sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)

    /** True when there is genuinely room for a wider layout, e.g. a tablet or unfolded device. */
    val isExpandedWidth: Boolean
        get() = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    companion object {
        /**
         * The arrangement rule, as a pure function so it can be unit tested.
         *
         * A window wider than it is tall gets the side-by-side layout. Deliberately simple:
         * anything cleverer (aspect-ratio thresholds, device classes) would be a rule that could
         * disagree with what the user can plainly see on screen.
         */
        fun arrangementFor(widthDp: Dp, heightDp: Dp): PaneArrangement =
            if (widthDp > heightDp) PaneArrangement.VideoBesideMap else PaneArrangement.VideoAboveMap
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberRoadguardWindowInfo(): RoadguardWindowInfo {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val size = currentWindowDpSize()
    return remember(size, adaptiveInfo) {
        RoadguardWindowInfo(
            widthDp = size.width,
            heightDp = size.height,
            arrangement = RoadguardWindowInfo.arrangementFor(size.width, size.height),
            sizeClass = adaptiveInfo.windowSizeClass,
        )
    }
}

/** Corner inset used consistently for the floating controls over both panes. */
val PaneInset: Dp = 12.dp
