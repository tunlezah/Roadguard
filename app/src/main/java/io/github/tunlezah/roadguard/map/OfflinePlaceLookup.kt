package io.github.tunlezah.roadguard.map

import android.util.Log
import io.github.tunlezah.roadguard.trip.PlaceNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Answers "what is this place called" without a network.
 *
 * Kept as an interface so trip naming can be tested with a table of names, and so the app can say
 * honestly when no answer is possible: with no map installed [isAvailable] is false and
 * [resolve] returns null, and the trip keeps its time-based title until a map arrives.
 */
interface PlaceLookup {
    val isAvailable: Boolean

    /** Names for a coordinate, or null when no map is installed or the lookup failed. */
    suspend fun resolve(latitude: Double, longitude: Double): PlaceNames?
}

/**
 * Names a coordinate from the installed offline map archives.
 *
 * The archives Roadguard downloads for the moving map carry a `places` layer -- suburbs, towns,
 * villages and cities, each with a kind and a population -- verified by decoding real tiles from
 * both the whole-of-Australia and the single-state archives. A lookup picks the most detailed
 * installed archive covering the point ([MapChooser]), reads the nine tiles around it at that
 * archive's deepest zoom for the fine name and at zoom [COARSE_ZOOM] for the city, decodes only
 * the places layer, and hands the candidates to [PlaceRanking].
 *
 * Nothing here contacts anything: the files are the ones the map renders from, opened read-only.
 */
class OfflinePlaceLookup(private val mapRepository: MapRepository) : PlaceLookup {

    private val mutex = Mutex()

    /** Open readers by archive identity, a few at most: one per installed map a drive touches. */
    private val readers = LinkedHashMap<String, PmtilesReader>()

    override val isAvailable: Boolean get() = mapRepository.isInstalled()

    override suspend fun resolve(latitude: Double, longitude: Double): PlaceNames? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val map = MapChooser.bestFor(mapRepository.installed.value, latitude, longitude) ?: return@withLock null
            val open = openReader(map.archive) ?: return@withLock null
            runCatching {
                val fine = placesAround(open, latitude, longitude, open.header.maxZoom)
                val coarse = placesAround(open, latitude, longitude, minOf(COARSE_ZOOM, open.header.maxZoom))
                PlaceNames(
                    place = PlaceRanking.finePlace(latitude, longitude, PlaceRanking.dedupe(fine))?.name,
                    area = PlaceRanking.coarsePlace(latitude, longitude, PlaceRanking.dedupe(coarse))?.name,
                )
            }.getOrElse { throwable ->
                Log.w(TAG, "place lookup failed", throwable)
                null
            }
        }
    }

    private fun placesAround(reader: PmtilesReader, latitude: Double, longitude: Double, zoom: Int): List<PlacePoint> {
        val centreX = PmtilesReader.tileX(longitude, zoom)
        val centreY = PmtilesReader.tileY(latitude, zoom)
        val n = 1 shl zoom
        val found = mutableListOf<PlacePoint>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val x = centreX + dx
                val y = centreY + dy
                if (x !in 0 until n || y !in 0 until n) continue
                val bytes = reader.tile(zoom, x, y) ?: continue
                found += VectorTileDecoder.places(bytes, zoom, x, y)
            }
        }
        return found
    }

    /**
     * The reader for [archive], opened on first use and kept.
     *
     * The key is path plus size plus modification time, so a reinstalled archive never gets a
     * reader pointing at the directory table of the previous file: the stale reader for that path
     * is closed and replaced. The cache is bounded, oldest out first.
     */
    private fun openReader(archive: File): PmtilesReader? {
        val key = "${archive.absolutePath}:${archive.length()}:${archive.lastModified()}"
        readers[key]?.let { return it }
        val stale = readers.keys.filter { it.startsWith("${archive.absolutePath}:") }
        for (old in stale) readers.remove(old)?.close()
        while (readers.size >= MAX_OPEN_READERS) {
            val eldest = readers.keys.first()
            readers.remove(eldest)?.close()
        }
        val reader = PmtilesReader.open(archive) ?: return null
        readers[key] = reader
        return reader
    }

    companion object {
        private const val TAG = "RoadguardPlaces"

        /**
         * Zoom for the city lookup. At zoom 8 a tile is about 120 km across and carries only places
         * with a low `min_zoom` -- cities and the larger towns -- which is exactly the set a coarse
         * label should choose from.
         */
        const val COARSE_ZOOM = 8

        /** Readers kept open at once. A drive crosses one border at most a handful of times. */
        const val MAX_OPEN_READERS = 3
    }
}
