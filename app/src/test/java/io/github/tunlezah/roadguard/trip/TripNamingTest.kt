package io.github.tunlezah.roadguard.trip

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The title rule, run against the place names the offline lookup was verified to return for real
 * coordinates: Harrison and Braddon are suburbs of Canberra, Surry Hills of Sydney, Gundaroo is a
 * village with no city within reach, Goulburn is a city in its own right.
 */
class TripNamingTest {

    private val harrison = PlaceNames(place = "Harrison", area = "Canberra")
    private val braddon = PlaceNames(place = "Braddon", area = "Canberra")
    private val surryHills = PlaceNames(place = "Surry Hills", area = "Sydney")
    private val gundaroo = PlaceNames(place = "Gundaroo", area = null)
    private val goulburn = PlaceNames(place = "Goulburn", area = "Goulburn")
    private val fallback = "Trip · 08:12 – 08:41"

    @Test
    fun `a run across one city names the suburbs and puts the city underneath`() {
        val label = TripNaming.label(harrison, braddon, fallback)

        assertThat(label.title).isEqualTo("Harrison → Braddon")
        assertThat(label.subtitle).isEqualTo("Canberra")
    }

    @Test
    fun `a run between two cities names the cities and puts the suburbs underneath`() {
        val label = TripNaming.label(harrison, surryHills, fallback)

        assertThat(label.title).isEqualTo("Canberra → Sydney")
        assertThat(label.subtitle).isEqualTo("Harrison → Surry Hills")
    }

    @Test
    fun `in the country the towns are the whole label`() {
        val label = TripNaming.label(gundaroo, goulburn, fallback)

        assertThat(label.title).isEqualTo("Gundaroo → Goulburn")
        assertThat(label.subtitle).isNull()
    }

    @Test
    fun `a city to a village names the city first and the suburb underneath`() {
        val label = TripNaming.label(harrison, gundaroo, fallback)

        assertThat(label.title).isEqualTo("Canberra → Gundaroo")
        assertThat(label.subtitle).isEqualTo("Harrison → Gundaroo")
    }

    @Test
    fun `ending where it started reads as a loop`() {
        val label = TripNaming.label(harrison, harrison, fallback)

        assertThat(label.title).isEqualTo("Around Harrison")
        assertThat(label.subtitle).isEqualTo("Canberra")
    }

    @Test
    fun `no names at all falls back to the time-based title`() {
        assertThat(TripNaming.label(null, null, fallback)).isEqualTo(TripLabel(fallback, null))
        assertThat(TripNaming.label(PlaceNames(null, null), PlaceNames(null, null), fallback))
            .isEqualTo(TripLabel(fallback, null))
    }

    @Test
    fun `one unknown end is said to be unknown rather than guessed`() {
        val label = TripNaming.label(null, braddon, fallback)

        assertThat(label.title).isEqualTo("Unknown → Braddon")
        assertThat(label.subtitle).isEqualTo("Canberra")
    }

    @Test
    fun `a place with only a coarse name uses it at both levels`() {
        // Open country near a city but with no suburb point: the city is the finest name there is.
        val outskirts = PlaceNames(place = null, area = "Goulburn")
        val label = TripNaming.label(outskirts, gundaroo, fallback)

        assertThat(label.title).isEqualTo("Goulburn → Gundaroo")
        assertThat(label.subtitle).isNull()
    }

    @Test
    fun `the arrow is a real arrow`() {
        // The product owner asked for it; and a hyphen would read as a range, not a direction.
        assertThat(TripNaming.ARROW).isEqualTo(" → ")
    }
}
