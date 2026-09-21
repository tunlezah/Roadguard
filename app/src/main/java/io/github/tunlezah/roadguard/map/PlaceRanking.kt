package io.github.tunlezah.roadguard.map

import io.github.tunlezah.roadguard.trip.PlaceNames
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A named point from the map archive's `places` layer.
 *
 * `kind` and `kindDetail` follow the Protomaps Basemap schema as found in the shipped archives:
 * suburbs are `neighbourhood` (detail `suburb` or `neighbourhood`), and towns, villages and cities
 * are `locality` with the detail saying which.
 */
data class PlacePoint(
    val name: String,
    val kind: String,
    val kindDetail: String?,
    val population: Long,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Chooses the names for a coordinate from the places found around it.
 *
 * Two answers are produced, and [io.github.tunlezah.roadguard.trip.TripNaming] decides which one a
 * trip title uses:
 *
 *  * the **fine** name is the nearest suburb within [SUBURB_RADIUS_KM], else the nearest town,
 *    village or hamlet within [TOWN_RADIUS_KM] -- "town if in the country", as the brief put it;
 *  * the **coarse** name is a city within [CITY_RADIUS_KM], chosen by distance discounted by
 *    population so that Canberra wins over Queanbeyan from a northern suburb even though both are
 *    a similar distance away, while somebody actually in Queanbeyan still gets Queanbeyan.
 *
 * The radii were chosen against real archive data (Harrison, Braddon, Gundaroo, Marulan, Goulburn,
 * Surry Hills) and are named constants so a drive that disagrees with them can be fixed in one
 * place. Pure, so the choices are unit tested without a tile in sight.
 */
object PlaceRanking {

    const val SUBURB_RADIUS_KM = 3.0
    const val TOWN_RADIUS_KM = 8.0
    const val CITY_RADIUS_KM = 20.0

    private const val KIND_NEIGHBOURHOOD = "neighbourhood"
    private const val KIND_LOCALITY = "locality"
    private const val DETAIL_CITY = "city"

    /** Duplicate features (the same place in two neighbouring tiles) collapse within this distance. */
    private const val DUPLICATE_RADIUS_KM = 0.25

    fun namesFor(latitude: Double, longitude: Double, candidates: Collection<PlacePoint>): PlaceNames {
        val places = dedupe(candidates)
        return PlaceNames(
            place = finePlace(latitude, longitude, places)?.name,
            area = coarsePlace(latitude, longitude, places)?.name,
        )
    }

    fun finePlace(latitude: Double, longitude: Double, candidates: Collection<PlacePoint>): PlacePoint? {
        val suburb = candidates
            .filter { it.kind == KIND_NEIGHBOURHOOD }
            .map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { (_, distance) -> distance <= SUBURB_RADIUS_KM }
            .minByOrNull { (_, distance) -> distance }
        if (suburb != null) return suburb.first
        return candidates
            .filter { it.kind == KIND_LOCALITY }
            .map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { (_, distance) -> distance <= TOWN_RADIUS_KM }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    fun coarsePlace(latitude: Double, longitude: Double, candidates: Collection<PlacePoint>): PlacePoint? =
        candidates
            .filter { it.kind == KIND_LOCALITY && it.kindDetail == DETAIL_CITY }
            .map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { (_, distance) -> distance <= CITY_RADIUS_KM }
            .minByOrNull { (place, distance) -> distance / ln(place.population.coerceAtLeast(0L) + 100.0) }
            ?.first

    /** Collapses the same place appearing in several tiles' buffers into one point. */
    fun dedupe(candidates: Collection<PlacePoint>): List<PlacePoint> {
        val kept = mutableListOf<PlacePoint>()
        for (candidate in candidates) {
            val duplicate = kept.any { existing ->
                existing.name == candidate.name &&
                    existing.kind == candidate.kind &&
                    distanceKm(existing.latitude, existing.longitude, candidate.latitude, candidate.longitude) <= DUPLICATE_RADIUS_KM
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    /** Great-circle distance, which is all the precision a suburb boundary deserves. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dPhi = p2 - p1
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(p1) * cos(p2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_KM = 6371.0
}
