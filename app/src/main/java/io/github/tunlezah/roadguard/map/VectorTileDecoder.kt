package io.github.tunlezah.roadguard.map

import java.io.IOException

/**
 * Pulls the named points out of one Mapbox Vector Tile.
 *
 * Only what place naming needs is decoded: the layer called `places`, its point features and their
 * attributes. Roads, water and every polygon in the tile are skipped by length without being looked
 * at, which is what keeps a lookup cheap on the baseline phone. The protobuf wire format is small
 * enough that a dependency for it would cost more than these hundred lines.
 *
 * Attribute names follow the Protomaps Basemap schema as verified in the shipped archives: `name`,
 * `kind`, `kind_detail` and `population`.
 */
object VectorTileDecoder {

    private const val TILE_LAYER = 3

    private const val LAYER_NAME = 1
    private const val LAYER_FEATURE = 2
    private const val LAYER_KEY = 3
    private const val LAYER_VALUE = 4
    private const val LAYER_EXTENT = 5

    private const val FEATURE_TAGS = 2
    private const val FEATURE_TYPE = 3
    private const val FEATURE_GEOMETRY = 4

    private const val VALUE_STRING = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_DOUBLE = 3
    private const val VALUE_INT = 4
    private const val VALUE_UINT = 5
    private const val VALUE_SINT = 6
    private const val VALUE_BOOL = 7

    private const val GEOMETRY_POINT = 1
    private const val COMMAND_MOVE_TO = 1
    private const val DEFAULT_EXTENT = 4096

    /**
     * Point features of [layerName] in [tile], positioned on the globe from the tile's address.
     *
     * Malformed input raises [IOException]; callers treat a bad tile as "no names", never as a crash.
     */
    @Throws(IOException::class)
    fun places(tile: ByteArray, z: Int, x: Int, y: Int, layerName: String = "places"): List<PlacePoint> {
        val out = mutableListOf<PlacePoint>()
        val reader = ProtoReader(tile, 0, tile.size)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            if (tag.field == TILE_LAYER && tag.wireType == WIRE_LENGTH) {
                val (start, end) = reader.readLengthDelimited()
                decodeLayer(ProtoReader(tile, start, end), layerName, z, x, y, out)
            } else {
                reader.skip(tag.wireType)
            }
        }
        return out
    }

    private fun decodeLayer(reader: ProtoReader, wanted: String, z: Int, x: Int, y: Int, out: MutableList<PlacePoint>) {
        var name: String? = null
        var extent = DEFAULT_EXTENT
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val features = mutableListOf<IntRange>()
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when {
                tag.field == LAYER_NAME && tag.wireType == WIRE_LENGTH -> name = reader.readString()
                tag.field == LAYER_EXTENT && tag.wireType == WIRE_VARINT -> extent = reader.readVarint().toInt()
                tag.field == LAYER_KEY && tag.wireType == WIRE_LENGTH -> keys += reader.readString()
                tag.field == LAYER_VALUE && tag.wireType == WIRE_LENGTH -> {
                    val (start, end) = reader.readLengthDelimited()
                    values += decodeValue(ProtoReader(reader.bytes, start, end))
                }
                tag.field == LAYER_FEATURE && tag.wireType == WIRE_LENGTH -> {
                    val (start, end) = reader.readLengthDelimited()
                    features += start until end
                }
                else -> reader.skip(tag.wireType)
            }
        }
        if (name != wanted) return
        if (extent <= 0) throw IOException("layer $name has extent $extent")
        for (range in features) {
            decodeFeature(ProtoReader(reader.bytes, range.first, range.last + 1), keys, values, extent, z, x, y)?.let(out::add)
        }
    }

    private fun decodeValue(reader: ProtoReader): Any? {
        var value: Any? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            value = when (tag.field) {
                VALUE_STRING -> reader.readString()
                VALUE_FLOAT -> reader.readFixed32().let { java.lang.Float.intBitsToFloat(it) }
                VALUE_DOUBLE -> reader.readFixed64().let { java.lang.Double.longBitsToDouble(it) }
                VALUE_INT, VALUE_UINT -> reader.readVarint()
                VALUE_SINT -> reader.readVarint().let { (it ushr 1) xor -(it and 1) }
                VALUE_BOOL -> reader.readVarint() != 0L
                else -> {
                    reader.skip(tag.wireType)
                    value
                }
            }
        }
        return value
    }

    private fun decodeFeature(
        reader: ProtoReader,
        keys: List<String>,
        values: List<Any?>,
        extent: Int,
        z: Int,
        x: Int,
        y: Int,
    ): PlacePoint? {
        var type = 0
        val tags = mutableListOf<Int>()
        var geometry: List<Long> = emptyList()
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when {
                tag.field == FEATURE_TYPE && tag.wireType == WIRE_VARINT -> type = reader.readVarint().toInt()
                tag.field == FEATURE_TAGS && tag.wireType == WIRE_LENGTH -> tags += reader.readPackedVarints().map { it.toInt() }
                tag.field == FEATURE_GEOMETRY && tag.wireType == WIRE_LENGTH -> geometry = reader.readPackedVarints()
                else -> reader.skip(tag.wireType)
            }
        }
        if (type != GEOMETRY_POINT || geometry.size < 3) return null
        val command = geometry[0]
        if ((command and 0x7) != COMMAND_MOVE_TO.toLong()) return null
        // The first point of a (multi)point is the place. Coordinates are zigzag-encoded deltas.
        val px = zigzag(geometry[1])
        val py = zigzag(geometry[2])

        var name: String? = null
        var kind: String? = null
        var kindDetail: String? = null
        var population = 0L
        var i = 0
        while (i + 1 < tags.size) {
            val key = keys.getOrNull(tags[i])
            val value = values.getOrNull(tags[i + 1])
            when (key) {
                "name" -> name = value as? String
                "kind" -> kind = value as? String
                "kind_detail" -> kindDetail = value as? String
                "population" -> population = when (value) {
                    is Long -> value
                    is Double -> value.toLong()
                    is Float -> value.toLong()
                    else -> 0L
                }
            }
            i += 2
        }
        if (name.isNullOrBlank() || kind.isNullOrBlank()) return null
        return PlacePoint(
            name = name,
            kind = kind,
            kindDetail = kindDetail,
            population = population,
            latitude = PmtilesReader.latitudeOf(z, y, py.toDouble() / extent),
            longitude = PmtilesReader.longitudeOf(z, x, px.toDouble() / extent),
        )
    }

    private fun zigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    private class Tag(val field: Int, val wireType: Int)

    /** A cursor over one protobuf message, bounded so a corrupt length can never run off the tile. */
    private class ProtoReader(val bytes: ByteArray, private var position: Int, private val end: Int) {

        fun hasMore(): Boolean = position < end

        fun readTag(): Tag {
            val key = readVarint()
            return Tag(field = (key ushr 3).toInt(), wireType = (key and 0x7).toInt())
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= end) throw IOException("truncated varint")
                val b = bytes[position++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("varint too long")
            }
        }

        fun readLengthDelimited(): Pair<Int, Int> {
            val length = readVarint()
            if (length < 0 || position + length > end) throw IOException("length $length overruns message")
            val start = position
            position += length.toInt()
            return start to position
        }

        fun readString(): String {
            val (start, stop) = readLengthDelimited()
            return String(bytes, start, stop - start, Charsets.UTF_8)
        }

        fun readPackedVarints(): List<Long> {
            val (start, stop) = readLengthDelimited()
            val inner = ProtoReader(bytes, start, stop)
            val out = mutableListOf<Long>()
            while (inner.hasMore()) out += inner.readVarint()
            return out
        }

        fun readFixed32(): Int {
            if (position + 4 > end) throw IOException("truncated fixed32")
            var value = 0
            for (i in 0 until 4) value = value or ((bytes[position + i].toInt() and 0xFF) shl (8 * i))
            position += 4
            return value
        }

        fun readFixed64(): Long {
            if (position + 8 > end) throw IOException("truncated fixed64")
            var value = 0L
            for (i in 0 until 8) value = value or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
            position += 8
            return value
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> position += 8
                WIRE_LENGTH -> readLengthDelimited()
                WIRE_FIXED32 -> position += 4
                else -> throw IOException("unsupported wire type $wireType")
            }
            if (position > end) throw IOException("skip overran message")
        }
    }
}
