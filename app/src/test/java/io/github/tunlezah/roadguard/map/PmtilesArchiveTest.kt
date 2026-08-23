package io.github.tunlezah.roadguard.map

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * The header and schema check that stands between a download and a blank map.
 *
 * Archives are synthesised here rather than fixtured: a real one is 250 MB, and every field that
 * matters is in the first 127 bytes plus a small metadata blob, so a hand-built file exercises the
 * parser more precisely than a sample would.
 *
 * Robolectric supplies `org.json`, which [PmtilesArchive] uses to read the embedded metadata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PmtilesArchiveTest {

    @get:Rule val folder = TemporaryFolder()

    private val required = setOf("earth", "roads", "water")

    // ── Well-formed archives ──────────────────────────────────────────────────────────

    @Test
    fun `reads version, tile type and zoom range from the header`() {
        val info = PmtilesArchive.read(archive(minZoom = 0, maxZoom = 14))

        assertThat(info).isNotNull()
        assertThat(info!!.version).isEqualTo(3)
        assertThat(info.isVectorTiles).isTrue()
        assertThat(info.minZoom).isEqualTo(0)
        assertThat(info.maxZoom).isEqualTo(14)
    }

    @Test
    fun `reads the vector layer ids out of gzipped metadata`() {
        val info = PmtilesArchive.read(archive(layers = listOf("earth", "roads", "water", "places")))

        assertThat(info!!.vectorLayers).containsExactly("earth", "roads", "water", "places")
    }

    @Test
    fun `reads uncompressed metadata too`() {
        val info = PmtilesArchive.read(archive(gzipMetadata = false))

        assertThat(info!!.vectorLayers).containsAtLeastElementsIn(required)
    }

    @Test
    fun `accepts vector_layers embedded as a JSON string, which some producers emit`() {
        val info = PmtilesArchive.read(archive(layersAsString = true))

        assertThat(info!!.vectorLayers).containsAtLeastElementsIn(required)
    }

    @Test
    fun `a street-level archive is accepted`() {
        assertThat(PmtilesArchive.rejectionReason(archive(maxZoom = 14), required)).isNull()
    }

    @Test
    fun `a whole-country archive at zoom 12 is accepted`() {
        // Coarser, but still navigable, and it is the default package. It must not be rejected.
        assertThat(PmtilesArchive.rejectionReason(archive(maxZoom = 12), required)).isNull()
    }

    @Test
    fun `extra layers beyond what the style draws are fine`() {
        val file = archive(layers = required.toList() + listOf("pois", "buildings", "landcover"))

        assertThat(PmtilesArchive.rejectionReason(file, required)).isNull()
    }

    // ── The failure this class exists for ─────────────────────────────────────────────

    @Test
    fun `an archive in a different schema is rejected, and says so`() {
        // The Shortbread schema: a complete, valid, correctly-downloaded archive whose layer names
        // the style does not match. It would install cleanly and render nothing.
        val shortbread = archive(layers = listOf("ocean", "land", "water_polygons", "streets"))

        val reason = PmtilesArchive.rejectionReason(shortbread, required)

        assertThat(reason).isNotNull()
        assertThat(reason).contains("different schema")
        assertThat(reason).contains("earth")
        assertThat(reason).contains("roads")
    }

    @Test
    fun `a single missing layer is named in the singular`() {
        val reason = PmtilesArchive.rejectionReason(
            archive(layers = listOf("earth", "water")),
            required,
        )

        assertThat(reason).contains("roads layer")
        assertThat(reason).doesNotContain("layers")
    }

    // ── Malformed archives ────────────────────────────────────────────────────────────

    @Test
    fun `a file that is not PMTiles at all is rejected`() {
        val html = folder.newFile("error.pmtiles").apply {
            writeText("<!DOCTYPE html><html><body>404 Not Found</body></html>".padEnd(200, ' '))
        }

        assertThat(PmtilesArchive.read(html)).isNull()
        assertThat(PmtilesArchive.rejectionReason(html, required))
            .isEqualTo("not a readable PMTiles archive")
    }

    @Test
    fun `a truncated file shorter than the header is rejected`() {
        val stub = folder.newFile("stub.pmtiles").apply { writeBytes("PMTiles".toByteArray()) }

        assertThat(PmtilesArchive.read(stub)).isNull()
    }

    @Test
    fun `an unsupported container version is rejected`() {
        val reason = PmtilesArchive.rejectionReason(archive(version = 2), required)

        assertThat(reason).contains("version 2")
    }

    @Test
    fun `raster tiles are rejected, because a vector style cannot draw them`() {
        val reason = PmtilesArchive.rejectionReason(archive(tileType = 2), required)

        assertThat(reason).contains("not vector tiles")
    }

    @Test
    fun `an archive too coarse to navigate by is rejected`() {
        val reason = PmtilesArchive.rejectionReason(archive(maxZoom = 5), required)

        assertThat(reason).contains("zoom 5")
    }

    @Test
    fun `an archive declaring no vector layers is rejected`() {
        val reason = PmtilesArchive.rejectionReason(archive(layers = emptyList()), required)

        assertThat(reason).isEqualTo("archive declares no vector layers")
    }

    @Test
    fun `metadata in a compression Android cannot decode does not crash the parser`() {
        // Brotli is legal in the spec. Roadguard cannot read it, so it reports no layers rather
        // than throwing -- and the caller then rejects it for having none, which is correct.
        val info = PmtilesArchive.read(archive(internalCompression = 3))

        assertThat(info).isNotNull()
        assertThat(info!!.vectorLayers).isEmpty()
    }

    @Test
    fun `metadata that is not JSON does not crash the parser`() {
        val info = PmtilesArchive.read(archive(metadataOverride = "not json at all"))

        assertThat(info!!.vectorLayers).isEmpty()
    }

    @Test
    fun `a metadata offset pointing past the end of the file is ignored safely`() {
        val info = PmtilesArchive.read(archive(metadataOffsetOverride = 1L shl 40))

        assertThat(info).isNotNull()
        assertThat(info!!.vectorLayers).isEmpty()
    }

    // ── Builder ───────────────────────────────────────────────────────────────────────

    /**
     * Writes a minimal PMTiles v3 file: the fixed 127-byte header followed by the metadata blob.
     * No tile directories, because verification never reads them.
     */
    private fun archive(
        version: Int = 3,
        tileType: Int = 1,
        minZoom: Int = 0,
        maxZoom: Int = 14,
        layers: List<String> = required.toList(),
        gzipMetadata: Boolean = true,
        internalCompression: Int? = null,
        layersAsString: Boolean = false,
        metadataOverride: String? = null,
        metadataOffsetOverride: Long? = null,
    ): File {
        val layerJson = layers.joinToString(",") { """{"id":"$it","fields":{}}""" }
        val metadata = metadataOverride ?: if (layersAsString) {
            // The array as an escaped JSON *string* value, which is how some producers embed it.
            val escaped = "[$layerJson]".replace("\\", "\\\\").replace("\"", "\\\"")
            """{"name":"test","vector_layers":"$escaped"}"""
        } else {
            """{"name":"test","vector_layers":[$layerJson]}"""
        }

        val body = if (gzipMetadata) {
            ByteArrayOutputStream().also { sink ->
                GZIPOutputStream(sink).use { it.write(metadata.toByteArray()) }
            }.toByteArray()
        } else {
            metadata.toByteArray()
        }

        val header = ByteArray(PmtilesArchive.HEADER_BYTES)
        "PMTiles".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[7] = version.toByte()
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(8, 0L)                                              // root dir offset
            putLong(16, 0L)                                             // root dir length
            putLong(24, metadataOffsetOverride ?: header.size.toLong()) // metadata offset
            putLong(32, body.size.toLong())                             // metadata length
        }
        // 1 = none, 2 = gzip, 3 = brotli.
        header[97] = (internalCompression ?: if (gzipMetadata) 2 else 1).toByte()
        header[98] = 1
        header[99] = tileType.toByte()
        header[100] = minZoom.toByte()
        header[101] = maxZoom.toByte()

        return folder.newFile("archive-${counter++}.pmtiles").apply {
            outputStream().use { it.write(header); it.write(body) }
        }
    }

    private var counter = 0
}
