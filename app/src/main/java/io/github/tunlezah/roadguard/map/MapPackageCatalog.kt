package io.github.tunlezah.roadguard.map

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * The offline map packages this build can install.
 *
 * Loaded from `assets/map_packages.json` rather than hard-coded, for two reasons. First, the URL,
 * size and licence of an OpenStreetMap-derived extract are *facts about a third party* that change
 * independently of Roadguard's code, and burying them in Kotlin makes them look like implementation
 * detail rather than data with a licence attached. Second, it keeps the download and install code
 * honest: it cannot invent a source, and if the asset is missing the app reports
 * [MapFailureReason.NotConfigured] instead of silently doing nothing.
 *
 * The asset's schema, and the reasoning behind the chosen source, are documented in
 * `docs/offline-maps.md`.
 */
object MapPackageCatalog {

    const val ASSET_NAME = "map_packages.json"

    fun load(context: Context): List<MapPackage> = runCatching {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val packages = root.getJSONArray("packages")
        (0 until packages.length()).map { index ->
            val entry = packages.getJSONObject(index)
            MapPackage(
                id = entry.getString("id"),
                displayName = entry.getString("displayName"),
                description = entry.optString("description"),
                downloadUrl = entry.getString("downloadUrl"),
                sizeBytes = if (entry.has("sizeBytes") && !entry.isNull("sizeBytes")) {
                    entry.getLong("sizeBytes")
                } else {
                    null
                },
                sha256 = entry.optString("sha256").takeIf { it.isNotBlank() },
                maxZoom = if (entry.has("maxZoom") && !entry.isNull("maxZoom")) {
                    entry.getInt("maxZoom")
                } else {
                    null
                },
                attribution = entry.getString("attribution"),
                licence = entry.getString("licence"),
                coversWholeCountry = entry.optBoolean("coversWholeCountry", false),
            )
        }
    }.getOrElse { throwable ->
        Log.w(TAG, "no usable map catalogue in assets/$ASSET_NAME", throwable)
        emptyList()
    }

    /**
     * Picks the package to install by default.
     *
     * Whole-country coverage is preferred when one exists: the specification is explicit that the
     * user must not have to understand tiles or regions, and a driver crossing a state border must
     * not lose their map.
     */
    fun defaultFor(packages: List<MapPackage>): MapPackage? =
        packages.firstOrNull { it.coversWholeCountry } ?: packages.firstOrNull()

    /** The package with [id], or null. Used to restore the user's chosen region on start-up. */
    fun byId(packages: List<MapPackage>, id: String?): MapPackage? =
        id?.let { wanted -> packages.firstOrNull { it.id == wanted } }

    private const val TAG = "RoadguardMapCatalog"
}
