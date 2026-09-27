package io.github.tunlezah.roadguard.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Which installed map is shown, and named from, when several are installed at once.
 *
 * The bounds are the real ones a state extract carries: boxes, not state outlines, which overlap
 * a little at the borders. The contracts pinned here are that the most detailed map covering the
 * vehicle wins, that the deeper of two overlapping boxes is the state the vehicle is in, that a
 * map showing is not swapped out for a fix that merely brushes another map's edge, and that a map
 * with no data under the vehicle is dropped at once.
 */
class MapChooserTest {

    private fun pack(id: String, wholeCountry: Boolean = false, zoom: Int) = MapPackage(
        id = id,
        displayName = id,
        description = "",
        downloadUrl = "https://example.invalid/$id.pmtiles",
        sizeBytes = 1L,
        sha256 = null,
        maxZoom = zoom,
        attribution = "© OpenStreetMap contributors",
        licence = "ODbL",
        coversWholeCountry = wholeCountry,
    )

    private fun installed(id: String, zoom: Int, bounds: MapBounds?, wholeCountry: Boolean = false) = InstalledMap(
        pack = pack(id, wholeCountry, zoom),
        directory = File(id),
        archive = File(id, "$id.pmtiles"),
        sizeBytes = 1L,
        installedAtEpochMs = 0L,
        maxZoom = zoom,
        bounds = bounds,
    )

    // Bounding boxes close to the published extracts'.
    private val australia = installed("au-all", 12, MapBounds(112.9, -43.7, 153.7, -10.0), wholeCountry = true)
    private val nsw = installed("au-nsw-act", 14, MapBounds(140.9, -37.6, 153.7, -28.1))
    private val victoria = installed("au-vic", 14, MapBounds(140.9, -39.2, 150.0, -33.9))

    private val canberra = -35.28 to 149.13
    private val melbourne = -37.81 to 144.96
    private val perth = -31.95 to 115.86

    @Test
    fun `nothing installed means no map`() {
        assertThat(MapChooser.choose(emptyList(), canberra.first, canberra.second, current = null)).isNull()
    }

    @Test
    fun `with no position the whole-country map is shown, whatever else is installed`() {
        assertThat(MapChooser.choose(listOf(nsw, australia), null, null, current = null)).isEqualTo(australia)
    }

    @Test
    fun `with no position the map already showing is kept`() {
        assertThat(MapChooser.choose(listOf(nsw, australia), null, null, current = nsw)).isEqualTo(nsw)
    }

    @Test
    fun `the most detailed map covering the vehicle wins`() {
        assertThat(MapChooser.choose(listOf(australia, nsw), canberra.first, canberra.second, current = null)).isEqualTo(nsw)
    }

    @Test
    fun `outside every detailed map the whole-country map is shown`() {
        assertThat(MapChooser.choose(listOf(nsw, australia), melbourne.first, melbourne.second, current = null)).isEqualTo(australia)
        assertThat(MapChooser.choose(listOf(nsw, victoria, australia), perth.first, perth.second, current = null)).isEqualTo(australia)
    }

    @Test
    fun `of two states whose boxes overlap, the one the vehicle is deeper inside is chosen`() {
        // Canberra lies inside Victoria's box too, but only just.
        assertThat(MapChooser.choose(listOf(victoria, nsw), canberra.first, canberra.second, current = null)).isEqualTo(nsw)
        assertThat(MapChooser.choose(listOf(nsw, victoria), melbourne.first, melbourne.second, current = null)).isEqualTo(victoria)
    }

    @Test
    fun `a map showing is not swapped for one the vehicle has only just entered`() {
        val justInsideNsw = -37.58 to 147.0 // 0.02° inside the southern edge of the NSW box
        val chosen = MapChooser.choose(listOf(australia, nsw), justInsideNsw.first, justInsideNsw.second, current = australia)
        assertThat(chosen).isEqualTo(australia)

        val wellInsideNsw = -37.0 to 147.0
        val switched = MapChooser.choose(listOf(australia, nsw), wellInsideNsw.first, wellInsideNsw.second, current = australia)
        assertThat(switched).isEqualTo(nsw)
    }

    @Test
    fun `leaving a map's coverage switches away at once`() {
        val chosen = MapChooser.choose(listOf(australia, nsw), melbourne.first, melbourne.second, current = nsw)
        assertThat(chosen).isEqualTo(australia)
    }

    @Test
    fun `a map that was removed while showing is replaced`() {
        val chosen = MapChooser.choose(listOf(australia), canberra.first, canberra.second, current = nsw)
        assertThat(chosen).isEqualTo(australia)
    }

    @Test
    fun `a stale description of the map showing still counts as that map`() {
        val reinstalled = nsw.copy(sizeBytes = 2L, installedAtEpochMs = 5L)
        val chosen = MapChooser.choose(listOf(australia, reinstalled), canberra.first, canberra.second, current = nsw)
        assertThat(chosen).isEqualTo(reinstalled)
    }

    @Test
    fun `a map that states no coverage counts as covering everywhere, ranked by its detail`() {
        val unknown = installed("unknown", 13, bounds = null)
        assertThat(MapChooser.choose(listOf(australia, unknown), perth.first, perth.second, current = null)).isEqualTo(unknown)
        assertThat(MapChooser.choose(listOf(nsw, unknown), canberra.first, canberra.second, current = null)).isEqualTo(nsw)
    }

    @Test
    fun `the only map installed is shown even where it has no data`() {
        assertThat(MapChooser.choose(listOf(nsw), perth.first, perth.second, current = null)).isEqualTo(nsw)
    }

    @Test
    fun `place lookups take the most detailed covering map with no memory`() {
        assertThat(MapChooser.bestFor(listOf(australia, nsw), canberra.first, canberra.second)).isEqualTo(nsw)
        assertThat(MapChooser.bestFor(listOf(australia, nsw), melbourne.first, melbourne.second)).isEqualTo(australia)
    }

    // ── Bounds ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `an all-zero box means the archive states no coverage`() {
        assertThat(MapBounds(0.0, 0.0, 0.0, 0.0).isMeaningful).isFalse()
        assertThat(MapBounds(150.0, -30.0, 140.0, -20.0).isMeaningful).isFalse()
        assertThat(nsw.bounds!!.isMeaningful).isTrue()
    }

    @Test
    fun `containment honours the inset and depth measures the nearest edge`() {
        val box = MapBounds(140.0, -40.0, 150.0, -30.0)
        assertThat(box.contains(-35.0, 145.0)).isTrue()
        assertThat(box.contains(-39.99, 145.0)).isTrue()
        assertThat(box.contains(-39.99, 145.0, insetDegrees = 0.05)).isFalse()
        assertThat(box.contains(-41.0, 145.0)).isFalse()
        assertThat(box.depth(-35.0, 141.0)).isWithin(1e-9).of(1.0)
        assertThat(box.depth(-41.0, 145.0)).isLessThan(0.0)
    }
}
