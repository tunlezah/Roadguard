package io.github.tunlezah.roadguard.map

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Just enough of the PMTiles v3 container to tell a usable map archive from an unusable one.
 *
 * ### Why this exists rather than a checksum
 *
 * A checksum answers "are these the bytes I expected". That is the easy question, and it is also
 * the fragile one: the archives are republished by their own build workflow, and a pinned hash
 * would eventually make Roadguard reject a perfectly good map.
 *
 * The question that actually matters is "will the bundled style draw anything from this file", and
 * a checksum cannot answer it at all. A valid, complete, correctly-downloaded archive in the
 * *wrong schema* renders a blank map -- every layer silently matching nothing. That failure looks
 * like a bug in the app, is invisible until someone drives somewhere, and is exactly what this
 * class rejects at install time instead.
 *
 * So verification reads the header (magic, version, tile type, zoom range) and the embedded
 * metadata's `vector_layers`, and requires every source-layer the style draws to be present.
 *
 * ### Format
 *
 * The v3 header is a fixed 127 bytes, little-endian, starting with the ASCII magic `PMTiles` and a
 * one-byte version. Only the fields Roadguard needs are decoded; the tile directories are the
 * renderer's business, not the installer's.
 */
object PmtilesArchive {

    const val HEADER_BYTES = 127
    private const val MAGIC = "PMTiles"
    private const val SUPPORTED_VERSION = 3

    /** `tile_type` for Mapbox Vector Tiles, the only kind the vector style can draw. */
    private const val TILE_TYPE_MVT = 1

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_GZIP = 2

    /** Guards against reading a silly amount of JSON out of a hostile or corrupt file. */
    private const val MAX_METADATA_BYTES = 4 * 1024 * 1024

    private const val TAG = "RoadguardPmtiles"

    /**
     * What Roadguard could learn about an archive.
     *
     * @param vectorLayers ids from the embedded `vector_layers` metadata. Empty when the archive
     *   declares none, which is itself a reason to reject a vector archive.
     */
    data class Info(
        val version: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val vectorLayers: Set<String>,
    ) {
        val isVectorTiles: Boolean get() = tileType == TILE_TYPE_MVT
    }

    /** Reads [file]'s header and metadata, or null when it is not a readable PMTiles v3 archive. */
    fun read(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < HEADER_BYTES) return null
            val header = ByteArray(HEADER_BYTES)
            raf.readFully(header)

            val magic = String(header, 0, MAGIC.length, Charsets.US_ASCII)
            if (magic != MAGIC) {
                Log.w(TAG, "not a PMTiles archive: magic was '$magic'")
                return null
            }
            val version = header[7].toInt() and 0xFF
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val metadataOffset = buffer.getLong(24)
            val metadataLength = buffer.getLong(32)
            val internalCompression = header[97].toInt() and 0xFF
            val tileType = header[99].toInt() and 0xFF
            val minZoom = header[100].toInt() and 0xFF
            val maxZoom = header[101].toInt() and 0xFF

            Info(
                version = version,
                tileType = tileType,
                minZoom = minZoom,
                maxZoom = maxZoom,
                vectorLayers = readVectorLayers(raf, metadataOffset, metadataLength, internalCompression),
            )
        }
    }.getOrElse {
        Log.w(TAG, "could not read PMTiles header from ${file.name}", it)
        null
    }

    /**
     * Checks an archive is one the bundled style can actually draw.
     *
     * @param requiredLayers every `source-layer` the style references.
     * @return null when the archive is acceptable, or a short human-readable reason when it is not.
     *   The reason is logged and surfaced as the install failure's detail, because "the map data
     *   was incomplete or corrupt" on its own tells a user nothing they can act on.
     */
    fun rejectionReason(file: File, requiredLayers: Set<String>): String? {
        val info = read(file) ?: return "not a readable PMTiles archive"
        if (info.version != SUPPORTED_VERSION) {
            return "PMTiles version ${info.version} is not supported (need $SUPPORTED_VERSION)"
        }
        if (!info.isVectorTiles) {
            return "archive holds tile type ${info.tileType}, not vector tiles"
        }
        if (info.maxZoom < MIN_USEFUL_MAX_ZOOM) {
            return "archive stops at zoom ${info.maxZoom}, too coarse to navigate by"
        }
        if (info.vectorLayers.isEmpty()) {
            return "archive declares no vector layers"
        }
        val missing = (requiredLayers - info.vectorLayers).sorted()
        if (missing.isNotEmpty()) {
            // The wrong-schema case. Worth naming precisely: it is the one failure that would
            // otherwise install cleanly and render nothing.
            return "archive is in a different schema; it has no ${missing.joinToString(", ")} layer" +
                (if (missing.size > 1) "s" else "")
        }
        return null
    }

    /**
     * Below this the archive cannot show a road network at driving zoom at all, however complete it
     * otherwise is. Whole-country archives stop at 12, single states at 14.
     */
    const val MIN_USEFUL_MAX_ZOOM = 8

    private fun readVectorLayers(
        raf: RandomAccessFile,
        offset: Long,
        length: Long,
        compression: Int,
    ): Set<String> {
        if (length <= 0 || length > MAX_METADATA_BYTES) return emptySet()
        if (offset < 0 || offset + length > raf.length()) return emptySet()

        val raw = ByteArray(length.toInt())
        raf.seek(offset)
        raf.readFully(raw)

        val text = when (compression) {
            COMPRESSION_NONE -> raw.toString(Charsets.UTF_8)
            COMPRESSION_GZIP -> GZIPInputStream(raw.inputStream()).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            // Brotli and zstd are legal in the spec but need a codec Android does not ship. Treat
            // them as "cannot tell" rather than "bad": the caller distinguishes an empty layer set
            // from a mismatched one.
            else -> {
                Log.w(TAG, "metadata compression $compression is not supported")
                return emptySet()
            }
        }

        return runCatching {
            val root = JSONObject(text)
            // `vector_layers` is an array in the spec, but some producers embed it as a string of
            // JSON. Accept both rather than failing a good archive on a formatting nicety.
            val layers = when (val value = root.opt("vector_layers")) {
                is JSONArray -> value
                is String -> JSONArray(value)
                else -> return emptySet()
            }
            (0 until layers.length())
                .mapNotNull { layers.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
                .toSet()
        }.getOrElse {
            Log.w(TAG, "could not parse PMTiles metadata", it)
            emptySet()
        }
    }
}
