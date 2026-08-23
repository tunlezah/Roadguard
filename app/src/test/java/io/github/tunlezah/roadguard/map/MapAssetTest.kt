package io.github.tunlezah.roadguard.map

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the two shipped map assets against the failure mode that is invisible until someone
 * drives somewhere: a style and an archive that load cleanly and draw nothing.
 *
 * These read the real files out of `app/src/main/assets/`, not fixtures, so a hand-edit of a style
 * or a catalogue entry that breaks the contract fails the build rather than the map.
 *
 * Robolectric is needed only for `org.json`: it lives in `android.jar`, so in a plain JVM test the
 * stub silently returns null instead of parsing anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapAssetTest {

    private val assets = File("src/main/assets")

    private fun styleJson(name: String): JSONObject {
        val file = File(assets, "map/$name")
        assertThat(file.exists()).isTrue()
        return JSONObject(file.readText())
    }

    private fun sourceLayers(style: JSONObject): Set<String> {
        val layers = style.getJSONArray("layers")
        return (0 until layers.length())
            .mapNotNull { layers.getJSONObject(it).optString("source-layer").takeIf(String::isNotBlank) }
            .toSet()
    }

    // ── The style/installer contract ──────────────────────────────────────────────────

    @Test
    fun `both styles reference exactly the source layers the installer requires`() {
        // This is the whole point of the test file. MapInstaller rejects an archive that lacks any
        // of MapStyleProvider.REQUIRED_SOURCE_LAYERS; if the style starts drawing a layer that is
        // not in that set, a wrong-schema archive would install cleanly and render blank.
        for (name in listOf("style-day.json", "style-night.json")) {
            assertThat(sourceLayers(styleJson(name)))
                .isEqualTo(MapStyleProvider.REQUIRED_SOURCE_LAYERS)
        }
    }

    @Test
    fun `both styles declare the schema the catalogue ships archives for`() {
        for (name in listOf("style-day.json", "style-night.json")) {
            assertThat(styleJson(name).getJSONObject("metadata").getString("roadguard:schema"))
                .isEqualTo("Protomaps Basemap")
        }
    }

    // ── Offline-only guarantees ───────────────────────────────────────────────────────

    @Test
    fun `styles carry the runtime placeholder rather than a baked path`() {
        for (name in listOf("style-day.json", "style-night.json")) {
            val text = File(assets, "map/$name").readText()
            assertThat(text).contains(MapStyleProvider.PMTILES_PLACEHOLDER)
        }
    }

    @Test
    fun `no style reaches the network for tiles, glyphs or sprites`() {
        // An "offline" map that fetches its glyphs the first time it draws a label is not offline.
        for (name in listOf("style-day.json", "style-night.json")) {
            val style = styleJson(name)
            assertThat(style.getString("glyphs")).startsWith("asset://")
            val sprites = style.getJSONArray("sprite")
            for (index in 0 until sprites.length()) {
                assertThat(sprites.getJSONObject(index).getString("url")).startsWith("asset://")
            }
            assertThat(File(assets, "map/$name").readText()).doesNotContain("https://api.")
        }
    }

    @Test
    fun `the layer budget stays within what a single-shader-core GPU can draw`() {
        for (name in listOf("style-day.json", "style-night.json")) {
            assertThat(styleJson(name).getJSONArray("layers").length()).isAtMost(20)
        }
    }

    @Test
    fun `the vehicle marker source is the one the map pane looks for`() {
        for (name in listOf("style-day.json", "style-night.json")) {
            assertThat(styleJson(name).getJSONObject("sources").has(MapStyleProvider.VEHICLE_SOURCE_ID))
                .isTrue()
        }
    }

    @Test
    fun `day and night differ only in colour, never in structure`() {
        // Two palettes of one style. If they ever diverge structurally, one of them is untested.
        val day = styleJson("style-day.json")
        val night = styleJson("style-night.json")
        val dayIds = (0 until day.getJSONArray("layers").length())
            .map { day.getJSONArray("layers").getJSONObject(it).getString("id") }
        val nightIds = (0 until night.getJSONArray("layers").length())
            .map { night.getJSONArray("layers").getJSONObject(it).getString("id") }
        assertThat(nightIds).isEqualTo(dayIds)
    }

    // ── The catalogue ─────────────────────────────────────────────────────────────────

    private fun catalogue(): JSONObject = JSONObject(File(assets, "map_packages.json").readText())

    private fun packages(): List<JSONObject> {
        val array = catalogue().getJSONArray("packages")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `the catalogue offers whole-country cover and every state`() {
        val ids = packages().map { it.getString("id") }
        assertThat(ids).containsExactly(
            "au-all", "au-nsw-act", "au-vic", "au-qld", "au-wa", "au-sa", "au-tas", "au-nt",
        )
    }

    @Test
    fun `exactly one package covers the whole country, and it is the default`() {
        val whole = packages().filter { it.optBoolean("coversWholeCountry") }
        assertThat(whole).hasSize(1)
        // defaultFor() prefers whole-country cover, so this is the package a user who makes no
        // choice at all ends up with: a map that does not blank out at a state border.
        assertThat(whole.single().getString("id")).isEqualTo("au-all")
    }

    @Test
    fun `every package states a real size, a plausible zoom and an ODbL attribution`() {
        for (entry in packages()) {
            val id = entry.getString("id")
            assertThat(entry.getLong("sizeBytes")).isGreaterThan(10L * 1024 * 1024)
            assertThat(entry.getInt("maxZoom")).isAtLeast(PmtilesArchive.MIN_USEFUL_MAX_ZOOM)
            assertThat(entry.getInt("maxZoom")).isAtMost(16)
            assertThat(entry.getString("attribution")).contains("OpenStreetMap")
            assertThat(entry.getString("licence")).contains("ODbL")
            assertThat(entry.getString("downloadUrl")).startsWith("https://")
            assertThat(entry.getString("downloadUrl")).endsWith(".pmtiles")
            assertThat(id).isNotEmpty()
        }
    }

    @Test
    fun `state packages carry street-level detail and the whole-country one does not claim to`() {
        for (entry in packages()) {
            val streetLevel = entry.getInt("maxZoom") >= MapPackage.STREET_LEVEL_ZOOM
            assertThat(streetLevel).isEqualTo(!entry.optBoolean("coversWholeCountry"))
        }
    }

    @Test
    fun `no package pins a checksum, because the assets are rebuilt in place`() {
        // Documented decision, not an oversight: see the catalogue's own comment and
        // docs/offline-maps.md. Verification is structural instead.
        for (entry in packages()) {
            assertThat(entry.getString("sha256")).isEmpty()
        }
    }
}
