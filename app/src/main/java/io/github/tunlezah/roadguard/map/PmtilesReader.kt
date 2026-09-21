package io.github.tunlezah.roadguard.map

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Reads individual tiles out of a PMTiles v3 archive.
 *
 * [PmtilesArchive] reads the header and metadata to decide whether an archive is usable; this class
 * goes one step further and walks the tile directories so Roadguard can read a specific tile itself.
 * That is what makes offline place names possible: the `places` layer the map draws is already on
 * the phone, and nine tile reads around a coordinate are enough to name it.
 *
 * ### The container, briefly
 *
 * After the 127-byte header comes a root directory: a list of entries sorted by *tile id*, each
 * either pointing at tile bytes (with a run length, so a run of identical ocean tiles is one
 * entry) or, with a run length of zero, at a leaf directory holding the entries for the tile ids
 * from that point on. Directories are varint-encoded and usually gzipped. Tile ids number the
 * tiles of every zoom level along a Hilbert curve, which is why [tileId] looks the way it does.
 *
 * MapLibre keeps its own reader for rendering; this one is read-only and shares the file happily.
 * Not thread-safe for concurrent callers on the same instance: [tile] serialises on the file.
 */
class PmtilesReader private constructor(
    private val file: RandomAccessFile,
    val header: Header,
) : AutoCloseable {

    data class Header(
        val rootDirectoryOffset: Long,
        val rootDirectoryLength: Long,
        val leafDirectoriesOffset: Long,
        val tileDataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
    )

    private class Entry(val tileId: Long, val runLength: Long, val length: Long, val offset: Long)

    private val rootDirectory: List<Entry> by lazy {
        parseDirectory(readBlock(header.rootDirectoryOffset, header.rootDirectoryLength, header.internalCompression))
    }

    /** Leaf directories are a few kilobytes each and the same handful serve a whole drive. */
    private val leafCache = object : LinkedHashMap<Long, List<Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?): Boolean = size > LEAF_CACHE_SIZE
    }

    /**
     * The decompressed bytes of tile ([z], [x], [y]), or null when the archive holds no such tile.
     *
     * Absence is normal -- open sea and empty desert produce no tile at all -- so it is not an error.
     */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = tileId(z, x, y)
        var entry = find(rootDirectory, id) ?: return null
        if (entry.runLength == 0L) {
            val leaf = synchronized(leafCache) { leafCache[entry.offset] } ?: parseDirectory(
                readBlock(header.leafDirectoriesOffset + entry.offset, entry.length, header.internalCompression),
            ).also { parsed -> synchronized(leafCache) { leafCache[entry.offset] = parsed } }
            entry = find(leaf, id) ?: return null
            if (entry.runLength == 0L) return null
        }
        return readBlock(header.tileDataOffset + entry.offset, entry.length, header.tileCompression)
    }

    override fun close() {
        runCatching { file.close() }
    }

    private fun readBlock(offset: Long, length: Long, compression: Int): ByteArray {
        if (length <= 0 || length > MAX_BLOCK_BYTES) throw IOException("implausible block length $length")
        val raw = ByteArray(length.toInt())
        synchronized(file) {
            file.seek(offset)
            file.readFully(raw)
        }
        return when (compression) {
            COMPRESSION_NONE, COMPRESSION_UNKNOWN -> raw
            COMPRESSION_GZIP -> GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            else -> throw IOException("unsupported compression $compression")
        }
    }

    companion object {
        private const val TAG = "RoadguardPmtiles"
        private const val HEADER_BYTES = 127
        private const val MAGIC = "PMTiles"
        private const val VERSION = 3
        private const val COMPRESSION_UNKNOWN = 0
        private const val COMPRESSION_NONE = 1
        private const val COMPRESSION_GZIP = 2
        private const val LEAF_CACHE_SIZE = 8

        /** A single tile or directory bigger than this is not something a phone should inflate. */
        private const val MAX_BLOCK_BYTES = 32L * 1024 * 1024

        /** Opens [archive], or returns null when it is not a PMTiles v3 file this reader can use. */
        fun open(archive: File): PmtilesReader? {
            val raf = runCatching { RandomAccessFile(archive, "r") }.getOrElse { return null }
            val header = runCatching { readHeader(raf) }.getOrNull()
            if (header == null) {
                raf.close()
                Log.w(TAG, "${archive.name} is not a readable PMTiles v3 archive")
                return null
            }
            return PmtilesReader(raf, header)
        }

        private fun readHeader(raf: RandomAccessFile): Header? {
            if (raf.length() < HEADER_BYTES) return null
            val bytes = ByteArray(HEADER_BYTES)
            raf.seek(0)
            raf.readFully(bytes)
            if (String(bytes, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return null
            if ((bytes[7].toInt() and 0xFF) != VERSION) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return Header(
                rootDirectoryOffset = buffer.getLong(8),
                rootDirectoryLength = buffer.getLong(16),
                leafDirectoriesOffset = buffer.getLong(40),
                tileDataOffset = buffer.getLong(56),
                internalCompression = bytes[97].toInt() and 0xFF,
                tileCompression = bytes[98].toInt() and 0xFF,
                tileType = bytes[99].toInt() and 0xFF,
                minZoom = bytes[100].toInt() and 0xFF,
                maxZoom = bytes[101].toInt() and 0xFF,
            )
        }

        private fun parseDirectory(bytes: ByteArray): List<Entry> {
            val reader = VarintReader(bytes)
            val count = reader.next().toInt()
            if (count < 0 || count > MAX_DIRECTORY_ENTRIES) throw IOException("implausible directory size $count")
            val ids = LongArray(count)
            var last = 0L
            for (i in 0 until count) {
                last += reader.next()
                ids[i] = last
            }
            val runs = LongArray(count) { reader.next() }
            val lengths = LongArray(count) { reader.next() }
            val offsets = LongArray(count)
            for (i in 0 until count) {
                val value = reader.next()
                offsets[i] = if (value == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else value - 1
            }
            return List(count) { i -> Entry(ids[i], runs[i], lengths[i], offsets[i]) }
        }

        private const val MAX_DIRECTORY_ENTRIES = 4_000_000

        /**
         * The entry covering [tileId], or null.
         *
         * A binary search over the sorted ids, then the standard PMTiles rule: a run-length entry
         * covers `[tileId, tileId + runLength)`, and a leaf entry (run length zero) covers everything
         * from its id up to the next entry's.
         */
        private fun find(entries: List<Entry>, tileId: Long): Entry? {
            var low = 0
            var high = entries.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val entry = entries[mid]
                when {
                    tileId < entry.tileId -> high = mid - 1
                    tileId > entry.tileId -> low = mid + 1
                    else -> return entry
                }
            }
            if (high < 0) return null
            val entry = entries[high]
            return when {
                entry.runLength == 0L -> entry
                tileId < entry.tileId + entry.runLength -> entry
                else -> null
            }
        }

        /** The PMTiles id of tile ([z], [x], [y]): the tiles of lower zooms, then a Hilbert walk of zoom [z]. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            var accumulated = 0L
            for (t in 0 until z) accumulated += 1L shl (2 * t)
            val n = 1L shl z
            var d = 0L
            var s = n / 2
            var tx = x.toLong()
            var ty = y.toLong()
            while (s > 0) {
                val rx = if ((tx and s) != 0L) 1L else 0L
                val ry = if ((ty and s) != 0L) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = n - 1 - tx
                        ty = n - 1 - ty
                    }
                    val swap = tx
                    tx = ty
                    ty = swap
                }
                s /= 2
            }
            return accumulated + d
        }

        /** Web Mercator tile column for [longitude] at zoom [z]. */
        fun tileX(longitude: Double, z: Int): Int {
            val n = 1 shl z
            return floor((longitude + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        }

        /** Web Mercator tile row for [latitude] at zoom [z]. */
        fun tileY(latitude: Double, z: Int): Int {
            val n = 1 shl z
            val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
            val y = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * n
            return floor(y).toInt().coerceIn(0, n - 1)
        }

        /** Longitude of a point [fraction] of the way across tile column [x] at zoom [z]. */
        fun longitudeOf(z: Int, x: Int, fraction: Double): Double {
            val n = (1 shl z).toDouble()
            return (x + fraction) / n * 360.0 - 180.0
        }

        /** Latitude of a point [fraction] of the way down tile row [y] at zoom [z]. */
        fun latitudeOf(z: Int, y: Int, fraction: Double): Double {
            val n = (1 shl z).toDouble()
            return Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * (y + fraction) / n))))
        }
    }

    /** Reads unsigned LEB128 varints, which is how PMTiles directories are encoded. */
    private class VarintReader(private val bytes: ByteArray) {
        private var position = 0

        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= bytes.size) throw IOException("truncated directory")
                val b = bytes[position++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("varint too long")
            }
        }
    }
}
