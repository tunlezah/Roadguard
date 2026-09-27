package io.github.tunlezah.roadguard.ui.gallery

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one rule the recordings list must never break: a clip on disk is always reachable.
 *
 * The list groups clips by trip. [GalleryViewModel.isLoose] decides which clips fall outside a
 * trip card and into the "not in a trip" card. It must return true not only for a clip with no
 * trip, but for a clip whose trip row has gone missing -- otherwise that clip is shown under no
 * card at all, and a recordings folder full of footage renders as an empty screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryViewModelTest {

    @Test
    fun `a clip with no trip is loose`() {
        assertThat(GalleryViewModel.isLoose(tripId = null, knownTripIds = setOf(1L, 2L))).isTrue()
    }

    @Test
    fun `a clip whose trip exists is grouped under that trip, not loose`() {
        assertThat(GalleryViewModel.isLoose(tripId = 2L, knownTripIds = setOf(1L, 2L))).isFalse()
    }

    @Test
    fun `a clip whose trip row is missing is still listed, never hidden`() {
        // The defect this pins: a clip pointing at a trip that no longer exists used to be dropped
        // from the list entirely, so a full recordings folder could show as empty.
        assertThat(GalleryViewModel.isLoose(tripId = 99L, knownTripIds = setOf(1L, 2L))).isTrue()
        assertThat(GalleryViewModel.isLoose(tripId = 1L, knownTripIds = emptySet())).isTrue()
    }

    // ── Route thumbnails ───────────────────────────────────────────────────────────────────

    private val entry = GalleryViewModel.SketchEntry(sizeBytes = 1_000L, computedAtElapsedMs = 10_000L, points = emptyList())

    @Test
    fun `an unchanged track is never read again`() {
        assertThat(GalleryViewModel.SketchEntry.isStale(entry, currentSizeBytes = 1_000L, nowElapsedMs = 10_000_000L)).isFalse()
    }

    @Test
    fun `a growing track is redrawn at most once a minute, not on every rebuild`() {
        val soon = 10_000L + GalleryViewModel.SKETCH_REFRESH_MS - 1
        val later = 10_000L + GalleryViewModel.SKETCH_REFRESH_MS

        assertThat(GalleryViewModel.SketchEntry.isStale(entry, currentSizeBytes = 2_000L, nowElapsedMs = soon)).isFalse()
        assertThat(GalleryViewModel.SketchEntry.isStale(entry, currentSizeBytes = 2_000L, nowElapsedMs = later)).isTrue()
    }
}
