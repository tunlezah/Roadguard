package io.github.tunlezah.roadguard.map

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * The tile reader and the places decoder, against an archive built by hand.
 *
 * A real archive is hundreds of megabytes; every structure that matters here -- the header, a
 * root directory with a run-length entry and a leaf pointer, a leaf directory, gzipped tiles and
 * a vector tile with a `places` layer -- fits in a few kilobytes when written from the spec. The
 * encoders below are the spec read forwards, the reader is it read backwards, and the two meeting
 * in the middle is the test. Pure JVM: no Robolectric, `android.util.Log` returns defaults.
 */
class PmtilesReaderTest {

    @get:Rule val folder = TemporaryFolder()

    // ── Tile ids and coordinates ──────────────────────────────────────────────────────

    @Test
    fun `tile ids follow the PMTiles spec`() {
        // From the specification's worked examples.
        assertThat(PmtilesReader.tileId(0, 0, 0)).isEqualTo(0L)
        assertThat(PmtilesReader.tileId(1, 0, 0)).isEqualTo(1L)
        assertThat(PmtilesReader.tileId(1, 0, 1)).isEqualTo(2L)
        assertThat(PmtilesReader.tileId(1, 1, 1)).isEqualTo(3L)
        assertThat(PmtilesReader.tileId(1, 1, 0)).isEqualTo(4L)
        assertThat(PmtilesReader.tileId(2, 0, 0)).isEqualTo(5L)
        // Zoom 12 starts after all 5,592,405 tiles of zooms 0..11.
        assertThat(PmtilesReader.tileId(12, 0, 0)).isEqualTo(5_592_405L)
    }

    @Test
    fun `tile ids are unique within a zoom level`() {
        val ids = (0 until 8).flatMap { x -> (0 until 8).map { y -> PmtilesReader.tileId(3, x, y) } }

        assertThat(ids.toSet()).hasSize(64)
        assertThat(ids.min()).isEqualTo(PmtilesReader.tileId(3, 0, 0))
    }

    @Test
    fun `Braddon lands in the expected zoom-12 tile and round-trips through the bounds`() {
        // Worked out by hand from the Web Mercator formulas: floor((149.1357+180)/360*4096) and
        // floor((1 - ln(tan φ + sec φ)/π)/2*4096) for φ = -35.2708°.
        val z = 12
        val x = PmtilesReader.tileX(149.1357, z)
        val y = PmtilesReader.tileY(-35.2708, z)

        assertThat(x).isEqualTo(3744)
        assertThat(y).isEqualTo(2477)
        assertThat(PmtilesReader.longitudeOf(z, x, 0.0)).isAtMost(149.1357)
        assertThat(PmtilesReader.longitudeOf(z, x + 1, 0.0)).isAtLeast(149.1357)
        assertThat(PmtilesReader.latitudeOf(z, y, 0.0)).isAtLeast(-35.2708)
        assertThat(PmtilesReader.latitudeOf(z, y, 1.0)).isAtMost(-35.2708)
    }

    // ── The header ────────────────────────────────────────────────────────────────────

    @Test
    fun `the header carries the archive's stated coverage`() {
        val stated = MapBounds(minLon = 140.9, minLat = -37.6, maxLon = 153.7, maxLat = -28.1)
        val withBounds = archive(
            tiles = mapOf(Triple(12, bx, by) to placesTile(12, bx, by, listOf(braddon))),
            bounds = stated,
        )

        PmtilesReader.open(withBounds)!!.use { reader ->
            val bounds = reader.header.bounds!!
            assertThat(bounds.minLon).isWithin(1e-6).of(140.9)
            assertThat(bounds.minLat).isWithin(1e-6).of(-37.6)
            assertThat(bounds.maxLon).isWithin(1e-6).of(153.7)
            assertThat(bounds.maxLat).isWithin(1e-6).of(-28.1)
            assertThat(reader.header.maxZoom).isEqualTo(12)
        }
    }

    @Test
    fun `an archive that states no coverage has no bounds`() {
        val withoutBounds = archive(tiles = mapOf(Triple(12, bx, by) to placesTile(12, bx, by, listOf(braddon))))

        PmtilesReader.open(withoutBounds)!!.use { reader ->
            assertThat(reader.header.bounds).isNull()
        }
    }

    // ── Reading an archive ────────────────────────────────────────────────────────────

    @Test
    fun `reads a tile through the root directory and decodes its places`() {
        val archive = archive(
            tiles = mapOf(Triple(12, bx, by) to placesTile(12, bx, by, listOf(braddon, canberra))),
        )

        val reader = PmtilesReader.open(archive)!!
        val bytes = reader.tile(12, bx, by)
        assertThat(bytes).isNotNull()
        val places = VectorTileDecoder.places(bytes!!, 12, bx, by)
        reader.close()

        assertThat(places.map { it.name }).containsExactly("Braddon", "Canberra")
        val decoded = places.first { it.name == "Braddon" }
        assertThat(decoded.kind).isEqualTo("neighbourhood")
        assertThat(decoded.kindDetail).isEqualTo("suburb")
        assertThat(decoded.latitude).isWithin(0.001).of(braddon.latitude)
        assertThat(decoded.longitude).isWithin(0.001).of(braddon.longitude)
        assertThat(places.first { it.name == "Canberra" }.population).isEqualTo(466_566L)
    }

    @Test
    fun `reads a tile through a leaf directory`() {
        val archive = archive(
            tiles = mapOf(Triple(12, bx, by) to placesTile(12, bx, by, listOf(braddon))),
            useLeaf = true,
        )

        val reader = PmtilesReader.open(archive)!!
        val places = VectorTileDecoder.places(reader.tile(12, bx, by)!!, 12, bx, by)
        reader.close()

        assertThat(places.map { it.name }).containsExactly("Braddon")
    }

    @Test
    fun `a run-length entry serves every tile in its run`() {
        val same = placesTile(8, 234, 154, listOf(canberra))
        val archive = archive(
            tiles = mapOf(Triple(8, 234, 154) to same),
            runLength = 3,
        )

        val reader = PmtilesReader.open(archive)!!
        val first = PmtilesReader.tileId(8, 234, 154)
        // The two tile ids after the first in Hilbert order are covered by the same entry.
        assertThat(reader.tile(8, 234, 154)).isNotNull()
        assertThat(reader.tileByIdForTest(first + 2)).isNotNull()
        assertThat(reader.tileByIdForTest(first + 3)).isNull()
        reader.close()
    }

    @Test
    fun `an absent tile is null, not an error`() {
        val archive = archive(tiles = mapOf(Triple(12, bx, by) to placesTile(12, bx, by, listOf(braddon))))

        val reader = PmtilesReader.open(archive)!!
        assertThat(reader.tile(12, 100, 100)).isNull()
        assertThat(reader.tile(14, bx, by)).isNull() // beyond max zoom
        reader.close()
    }

    @Test
    fun `a file that is not PMTiles does not open`() {
        val junk = folder.newFile("junk.pmtiles").apply { writeBytes(ByteArray(300) { 7 }) }

        assertThat(PmtilesReader.open(junk)).isNull()
    }

    @Test
    fun `the decoder skips layers it was not asked for and non-point features`() {
        val tile = tileWithLayers(
            listOf(
                layer("roads", listOf(feature(type = 2, tags = emptyList(), geometry = listOf(9, 0, 0, 18, 10, 10)))),
                layer("places", listOf(pointFeature(braddon, 12, bx, by))),
            ),
        )

        val places = VectorTileDecoder.places(tile, 12, bx, by)

        assertThat(places.map { it.name }).containsExactly("Braddon")
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────

    private val braddon = PlacePoint("Braddon", "neighbourhood", "suburb", 0, -35.2708, 149.1357)
    private val canberra = PlacePoint("Canberra", "locality", "city", 466_566, -35.2965, 149.1013)

    /** The zoom-12 tile Braddon falls in; Canberra's point is a few kilometres away in a neighbour. */
    private val bx = PmtilesReader.tileX(braddon.longitude, 12)
    private val by = PmtilesReader.tileY(braddon.latitude, 12)

    private fun PmtilesReader.tileByIdForTest(tileId: Long): ByteArray? {
        // The public API takes z/x/y; walk zoom 8 to find the coordinates of this id.
        for (x in 0 until 256) for (y in 0 until 256) if (PmtilesReader.tileId(8, x, y) == tileId) return tile(8, x, y)
        return null
    }

    /** Writes a PMTiles v3 archive: header, root directory, (optional) leaf directory, tile data. */
    private fun archive(
        tiles: Map<Triple<Int, Int, Int>, ByteArray>,
        useLeaf: Boolean = false,
        runLength: Long = 1,
        bounds: MapBounds? = null,
    ): File {
        val tileData = ByteArrayOutputStream()
        val entries = tiles.entries
            .map { (zxy, bytes) -> PmtilesReader.tileId(zxy.first, zxy.second, zxy.third) to gzip(bytes) }
            .sortedBy { it.first }
            .map { (id, gz) ->
                val offset = tileData.size().toLong()
                tileData.write(gz)
                Entry(id, runLength, gz.size.toLong(), offset)
            }
        val leafBytes: ByteArray
        val rootBytes: ByteArray
        if (useLeaf) {
            leafBytes = gzip(directory(entries))
            rootBytes = gzip(directory(listOf(Entry(entries.first().tileId, 0, leafBytes.size.toLong(), 0))))
        } else {
            leafBytes = ByteArray(0)
            rootBytes = gzip(directory(entries))
        }
        val metadata = gzip("""{"vector_layers":[{"id":"places"}]}""".toByteArray())

        val rootOffset = 127L
        val metadataOffset = rootOffset + rootBytes.size
        val leafOffset = metadataOffset + metadata.size
        val dataOffset = leafOffset + leafBytes.size

        val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PMTiles".toByteArray()).put(3)
        header.putLong(rootOffset).putLong(rootBytes.size.toLong())
        header.putLong(metadataOffset).putLong(metadata.size.toLong())
        header.putLong(leafOffset).putLong(leafBytes.size.toLong())
        header.putLong(dataOffset).putLong(tileData.size().toLong())
        header.putLong(tiles.size.toLong()).putLong(entries.size.toLong()).putLong(tiles.size.toLong())
        header.put(1) // clustered
        header.put(2) // internal compression: gzip
        header.put(2) // tile compression: gzip
        header.put(1) // tile type: mvt
        header.put(0) // min zoom
        header.put(12) // max zoom
        // Bounds, in ten-millionths of a degree; all zero when the archive states none.
        header.putInt(((bounds?.minLon ?: 0.0) * 1e7).toInt())
        header.putInt(((bounds?.minLat ?: 0.0) * 1e7).toInt())
        header.putInt(((bounds?.maxLon ?: 0.0) * 1e7).toInt())
        header.putInt(((bounds?.maxLat ?: 0.0) * 1e7).toInt())
        header.put(0).putInt(0).putInt(0) // centre

        val file = folder.newFile("synthetic.pmtiles")
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(rootBytes)
            out.write(metadata)
            out.write(leafBytes)
            out.write(tileData.toByteArray())
        }
        return file
    }

    private class Entry(val tileId: Long, val runLength: Long, val length: Long, val offset: Long)

    private fun directory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        varint(out, entries.size.toLong())
        var last = 0L
        for (e in entries) {
            varint(out, e.tileId - last)
            last = e.tileId
        }
        entries.forEach { varint(out, it.runLength) }
        entries.forEach { varint(out, it.length) }
        entries.forEachIndexed { i, e ->
            // The spec's shorthand: 0 means "immediately after the previous entry".
            val contiguous = i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length
            varint(out, if (contiguous) 0L else e.offset + 1)
        }
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { buffer ->
        GZIPOutputStream(buffer).use { it.write(bytes) }
    }.toByteArray()

    // ── A tiny protobuf encoder for Mapbox Vector Tiles ───────────────────────────────

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val bits = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(bits)
                return
            }
            out.write(bits or 0x80)
        }
    }

    private fun field(out: ByteArrayOutputStream, number: Int, wireType: Int) = varint(out, ((number shl 3) or wireType).toLong())

    private fun bytesField(out: ByteArrayOutputStream, number: Int, payload: ByteArray) {
        field(out, number, 2)
        varint(out, payload.size.toLong())
        out.write(payload)
    }

    private fun stringValue(text: String): ByteArray = ByteArrayOutputStream().also { bytesField(it, 1, text.toByteArray()) }.toByteArray()

    private fun intValue(value: Long): ByteArray = ByteArrayOutputStream().also {
        field(it, 4, 0)
        varint(it, value)
    }.toByteArray()

    private fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)

    private fun feature(type: Int, tags: List<Int>, geometry: List<Long>): ByteArray = ByteArrayOutputStream().also { out ->
        val packedTags = ByteArrayOutputStream().also { p -> tags.forEach { varint(p, it.toLong()) } }.toByteArray()
        bytesField(out, 2, packedTags)
        field(out, 3, 0)
        varint(out, type.toLong())
        val packedGeometry = ByteArrayOutputStream().also { p -> geometry.forEach { varint(p, it) } }.toByteArray()
        bytesField(out, 4, packedGeometry)
    }.toByteArray()

    /** Keys and values are indexed in the order [placesTile] declares them. */
    private fun pointFeature(place: PlacePoint, z: Int, x: Int, y: Int, extent: Int = 4096): ByteArray {
        // Forward Web Mercator, so the decoder's inverse lands back on the same coordinate. A point
        // outside this tile (Canberra's, beside Braddon's) simply gets a coordinate beyond the
        // extent, which is what a tile buffer holds.
        val n = (1 shl z).toDouble()
        val fx = (place.longitude + 180.0) / 360.0 * n - x
        val radians = Math.toRadians(place.latitude)
        val fy = (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0 * n - y
        val px = Math.round(fx * extent)
        val py = Math.round(fy * extent)
        val moveTo = (1 or (1 shl 3)).toLong() // command 1, count 1
        return feature(
            type = 1,
            tags = listOf(0, valueIndex(place.name), 1, valueIndex(place.kind), 2, valueIndex(place.kindDetail ?: ""), 3, valueIndex(place.population.toString())),
            geometry = listOf(moveTo, zigzag(px), zigzag(py)),
        )
    }

    /** Value table shared by every feature in a synthesised layer: strings and the population as int. */
    private val values = mutableListOf<Pair<String, ByteArray>>()

    private fun valueIndex(key: String): Int {
        val existing = values.indexOfFirst { it.first == key }
        if (existing >= 0) return existing
        val encoded = key.toLongOrNull()?.let { intValue(it) } ?: stringValue(key)
        values += key to encoded
        return values.size - 1
    }

    private fun layer(name: String, features: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        field(out, 15, 0)
        varint(out, 2) // version
        bytesField(out, 1, name.toByteArray())
        features.forEach { bytesField(out, 2, it) }
        listOf("name", "kind", "kind_detail", "population").forEach { bytesField(out, 3, it.toByteArray()) }
        values.forEach { (_, encoded) -> bytesField(out, 4, encoded) }
        field(out, 5, 0)
        varint(out, 4096)
    }.toByteArray()

    private fun tileWithLayers(layers: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        layers.forEach { bytesField(out, 3, it) }
    }.toByteArray()

    private fun placesTile(z: Int, x: Int, y: Int, places: List<PlacePoint>): ByteArray {
        values.clear()
        val features = places.map { pointFeature(it, z, x, y) }
        return tileWithLayers(listOf(layer("places", features)))
    }
}
