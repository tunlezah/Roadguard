package io.github.tunlezah.roadguard.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The ranking rules, fed the places the real archives were verified to hold around Canberra and
 * the Southern Tablelands. Coordinates are the ones decoded from the tiles.
 */
class PlaceRankingTest {

    private fun suburb(name: String, lat: Double, lon: Double) = PlacePoint(name, "neighbourhood", "suburb", 0, lat, lon)
    private fun city(name: String, population: Long, lat: Double, lon: Double) = PlacePoint(name, "locality", "city", population, lat, lon)
    private fun town(name: String, population: Long, lat: Double, lon: Double) = PlacePoint(name, "locality", "town", population, lat, lon)
    private fun village(name: String, population: Long, lat: Double, lon: Double) = PlacePoint(name, "locality", "village", population, lat, lon)

    private val harrison = suburb("Harrison", -35.1991, 149.1561)
    private val franklin = suburb("Franklin", -35.1994, 149.1433)
    private val braddon = suburb("Braddon", -35.2708, 149.1357)
    private val turner = suburb("Turner", -35.2689, 149.1246)
    private val canberra = city("Canberra", 466_566, -35.2965, 149.1013)
    private val queanbeyan = city("Queanbeyan", 37_511, -35.3517, 149.2338)
    private val westonCreek = town("Weston Creek", 22_700, -35.3427, 149.0477)
    private val goulburn = city("Goulburn", 23_963, -34.7524, 149.7198)
    private val gundaroo = village("Gundaroo", 503, -35.0253, 149.2673)
    private val marulan = town("Marulan", 819, -34.7112, 150.0064)

    // ── Fine names ────────────────────────────────────────────────────────────────────

    @Test
    fun `in a suburb the nearest suburb wins`() {
        val fine = PlaceRanking.finePlace(-35.1989, 149.1547, listOf(franklin, harrison, canberra))

        assertThat(fine?.name).isEqualTo("Harrison")
    }

    @Test
    fun `a town is used where there is no suburb within reach`() {
        // Gundaroo village, 0.4 km away; Canberra's suburbs are 20 km south and out of range.
        val fine = PlaceRanking.finePlace(-35.0289, 149.2673, listOf(gundaroo, harrison, canberra))

        assertThat(fine?.name).isEqualTo("Gundaroo")
    }

    @Test
    fun `open country with nothing within eight kilometres has no fine name`() {
        val fine = PlaceRanking.finePlace(-34.90, 149.90, listOf(marulan, goulburn, gundaroo))

        assertThat(fine).isNull()
    }

    @Test
    fun `a suburb just beyond three kilometres loses to a town within eight`() {
        val fine = PlaceRanking.finePlace(-34.7112, 150.0064 + 0.04, listOf(marulan, suburb("Far Estate", -34.7112, 150.0064 - 0.02)))

        assertThat(fine?.name).isEqualTo("Marulan")
    }

    // ── Coarse names ──────────────────────────────────────────────────────────────────

    @Test
    fun `from an inner suburb the city is the nearest city`() {
        val coarse = PlaceRanking.coarsePlace(braddon.latitude, braddon.longitude, listOf(canberra, queanbeyan, westonCreek))

        assertThat(coarse?.name).isEqualTo("Canberra")
    }

    @Test
    fun `from a northern suburb the big city beats a small city at a similar distance`() {
        // Harrison is ~11 km from Canberra's point and ~19 km from Queanbeyan's; population settles
        // it in Canberra's favour even when the distances are closer than that.
        val coarse = PlaceRanking.coarsePlace(harrison.latitude, harrison.longitude, listOf(canberra, queanbeyan))

        assertThat(coarse?.name).isEqualTo("Canberra")
    }

    @Test
    fun `someone actually in the smaller city still gets the smaller city`() {
        val coarse = PlaceRanking.coarsePlace(-35.3520, 149.2330, listOf(canberra, queanbeyan))

        assertThat(coarse?.name).isEqualTo("Queanbeyan")
    }

    @Test
    fun `towns are never a coarse name, however large`() {
        val coarse = PlaceRanking.coarsePlace(westonCreek.latitude, westonCreek.longitude, listOf(westonCreek))

        assertThat(coarse).isNull()
    }

    @Test
    fun `a city more than twenty kilometres away is out of reach`() {
        // Gundaroo is ~30 km north of Canberra's point: the village stands on its own.
        val coarse = PlaceRanking.coarsePlace(gundaroo.latitude, gundaroo.longitude, listOf(canberra, queanbeyan, goulburn))

        assertThat(coarse).isNull()
    }

    @Test
    fun `a regional city names itself`() {
        assertThat(PlaceRanking.coarsePlace(-34.7515, 149.7181, listOf(goulburn, canberra))?.name).isEqualTo("Goulburn")
    }

    // ── namesFor, dedupe and distance ─────────────────────────────────────────────────

    @Test
    fun `namesFor combines both levels`() {
        val names = PlaceRanking.namesFor(braddon.latitude, braddon.longitude, listOf(braddon, turner, canberra, queanbeyan))

        assertThat(names.place).isEqualTo("Braddon")
        assertThat(names.area).isEqualTo("Canberra")
    }

    @Test
    fun `the same place from two tiles collapses to one`() {
        val twice = listOf(braddon, braddon.copy(latitude = braddon.latitude + 0.0002), turner)

        assertThat(PlaceRanking.dedupe(twice).map { it.name }).containsExactly("Braddon", "Turner")
    }

    @Test
    fun `two different places sharing a name are both kept`() {
        // "Carrick" appears twice near Marulan, as a locality and as a hamlet 4 km apart.
        val carricks = listOf(
            PlacePoint("Carrick", "locality", "locality", 1000, -34.6895, 149.9276),
            PlacePoint("Carrick", "locality", "hamlet", 200, -34.6983, 149.8844),
        )

        assertThat(PlaceRanking.dedupe(carricks)).hasSize(2)
    }

    @Test
    fun `distance is great-circle and roughly right`() {
        // Braddon to Canberra's city point: about 4 km.
        val km = PlaceRanking.distanceKm(braddon.latitude, braddon.longitude, canberra.latitude, canberra.longitude)

        assertThat(km).isGreaterThan(3.5)
        assertThat(km).isLessThan(4.5)
        assertThat(PlaceRanking.distanceKm(-35.0, 149.0, -35.0, 149.0)).isEqualTo(0.0)
    }
}
