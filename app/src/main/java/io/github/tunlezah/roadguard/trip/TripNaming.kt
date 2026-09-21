package io.github.tunlezah.roadguard.trip

/**
 * The two names a place resolves to.
 *
 * @param place the fine name: the suburb, or the town or village where there is no suburb.
 * @param area the coarse name: the city the place sits in, when one is within reach. Null in the
 *   country, where the town itself is the broadest sensible label.
 */
data class PlaceNames(val place: String?, val area: String?) {
    val isEmpty: Boolean get() = place == null && area == null

    /** The finest name available, falling back to the coarse one. */
    val finest: String? get() = place ?: area

    /** The broadest name available, falling back to the fine one. */
    val broadest: String? get() = area ?: place
}

/** What the gallery prints for a trip: a title and, when it adds something, a second line. */
data class TripLabel(val title: String, val subtitle: String?)

/**
 * Turns the two ends of a trip into a title.
 *
 * The rule the product owner asked for: **suburb names by default, city names when the trip moves
 * between two different cities.** So a run across town reads "Harrison → Braddon" with "Canberra"
 * underneath, and a run down the highway reads "Canberra → Sydney" with "Harrison → Surry Hills"
 * underneath. A trip in the country, where there is no city within reach of either end, reads
 * "Gundaroo → Goulburn" and nothing more, because the towns are already the broadest honest label.
 *
 * Pure, so every branch is unit tested against the real place names the lookup was verified on.
 */
object TripNaming {

    /** Between the two ends. A real arrow rather than a hyphen: it reads as direction, not a range. */
    const val ARROW = " → "

    /** Shown in place of an end whose location was never fixed. */
    const val UNKNOWN = "Unknown"

    fun label(start: PlaceNames?, end: PlaceNames?, fallbackTitle: String): TripLabel {
        val from = start?.takeIf { !it.isEmpty }
        val to = end?.takeIf { !it.isEmpty }
        if (from == null && to == null) return TripLabel(fallbackTitle, null)

        val fromFine = from?.finest ?: UNKNOWN
        val toFine = to?.finest ?: UNKNOWN
        val fromBroad = from?.broadest
        val toBroad = to?.broadest

        // Two different cities: the cities are the title and the suburbs the detail.
        if (fromBroad != null && toBroad != null && fromBroad != toBroad) {
            val detail = join(fromFine, toFine)
            return TripLabel(
                title = join(fromBroad, toBroad),
                subtitle = detail.takeIf { it != join(fromBroad, toBroad) },
            )
        }

        // The same city, or a city on one side only: suburbs are the title, the city the detail.
        val sharedArea = listOfNotNull(from?.area, to?.area).distinct().singleOrNull()
        val title = if (from != null && to != null && fromFine == toFine) "Around $fromFine" else join(fromFine, toFine)
        return TripLabel(
            title = title,
            subtitle = sharedArea?.takeIf { it != fromFine && it != toFine },
        )
    }

    private fun join(from: String, to: String): String = from + ARROW + to
}
