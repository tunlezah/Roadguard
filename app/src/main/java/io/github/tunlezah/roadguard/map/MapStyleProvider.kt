package io.github.tunlezah.roadguard.map

import android.content.Context
import android.util.Log
import io.github.tunlezah.roadguard.ui.theme.ThemeMode
import java.io.File

/**
 * Loads the offline MapLibre style and points it at the installed tile archive.
 *
 * ### Why this is a few lines and not a rendering engine
 *
 * MapLibre Native's shipped renderer registers file sources for `asset://`, `file://`, `mbtiles://`
 * and `pmtiles://` (all four are compiled into the `libmaplibre.so` that
 * `org.maplibre.gl:android-sdk-opengl` bundles, which contains a complete PMTiles v3 reader). So an
 * archive sitting in the app's own storage *is* a style source: no tile server, no per-tile HTTP, and
 * -- because local URLs are resolved ahead of the cache database in MapLibre's source waterfall --
 * no second copy of a 900 MB file in an ambient cache.
 *
 * The style itself is generated at build time into `assets/map/` with its glyphs and sprites also
 * under `asset://`, so loading it performs no network I/O at all. The only thing that cannot be
 * known until runtime is the archive's absolute path, which is why the style ships with a
 * [PMTILES_PLACEHOLDER] token for this class to substitute.
 *
 * ### The URL form
 *
 * `pmtiles://file:///absolute/path/archive.pmtiles` -- the PMTiles source requires a *fully
 * qualified* inner URL, which it then re-resolves through the ordinary file sources. Because
 * `absolutePath` already begins with `/`, `"pmtiles://file://" + absolutePath` produces the three
 * slashes that form needs.
 */
class MapStyleProvider(private val context: Context) {

    /**
     * Builds the style for an installed package, or null when the install is unusable.
     *
     * @param mode which palette to use. Roadguard switches to the night style rather than dimming
     *   the day one: a bright map at night reflected in a windscreen is a real safety problem.
     */
    fun styleFor(directory: File, pack: MapPackage, mode: ThemeMode): MapStyleSpec? {
        val archive = findArchive(directory) ?: run {
            Log.w(TAG, "no tile archive under ${directory.absolutePath}")
            return null
        }
        val assetName = if (mode == ThemeMode.Light) STYLE_DAY else STYLE_NIGHT
        val template = runCatching {
            context.assets.open(assetName).bufferedReader().use { it.readText() }
        }.getOrElse { throwable ->
            Log.e(TAG, "bundled style $assetName is missing", throwable)
            return null
        }

        val uri = archiveUri(archive)
        return MapStyleSpec(
            json = template.replace(PMTILES_PLACEHOLDER, uri),
            archiveUri = uri,
            archive = archive,
            attribution = pack.attribution,
            describedAs = "$assetName over ${archive.name}",
        )
    }

    /**
     * The URI for a local archive.
     *
     * `pmtiles://` and `mbtiles://` both take a fully qualified inner URL, so the scheme is chosen
     * from the file's own extension rather than assumed.
     */
    fun archiveUri(archive: File): String {
        val scheme = if (archive.extension.equals("mbtiles", ignoreCase = true)) "mbtiles" else "pmtiles"
        return "$scheme://file://${archive.absolutePath}"
    }

    fun findArchive(directory: File): File? {
        if (!directory.isDirectory) return null
        return directory.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension.equals("pmtiles", true) || it.extension.equals("mbtiles", true) }
            // Largest wins: a package could carry a small companion archive, and the basemap is
            // always the big one.
            .maxByOrNull { it.length() }
    }

    companion object {
        private const val TAG = "RoadguardMapStyle"

        const val PMTILES_PLACEHOLDER = "__PMTILES_URI__"
        const val STYLE_DAY = "map/style-day.json"
        const val STYLE_NIGHT = "map/style-night.json"

        /** The GeoJSON source in the bundled styles that carries the vehicle marker. */
        const val VEHICLE_SOURCE_ID = "vehicle"

        /**
         * Every `source-layer` the bundled styles draw from the tile archive.
         *
         * Held as a constant so [MapInstaller] can reject an archive in the wrong schema without
         * parsing the style, and kept honest by `MapStyleAssetTest`, which reads the shipped style
         * JSON and fails the build if the two ever diverge.
         */
        val REQUIRED_SOURCE_LAYERS: Set<String> = setOf(
            "boundaries", "buildings", "earth", "landcover", "landuse", "places", "roads", "water",
        )
    }
}

/**
 * A ready-to-load style.
 *
 * @property json the style document with the archive URI substituted in. Handed to MapLibre as a
 *   JSON string rather than a URI, which is safe here because every URL inside it is absolute.
 */
data class MapStyleSpec(
    val json: String,
    val archiveUri: String,
    val archive: File,
    val attribution: String,
    val describedAs: String,
)
