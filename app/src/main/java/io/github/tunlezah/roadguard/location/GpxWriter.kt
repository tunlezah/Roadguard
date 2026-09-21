package io.github.tunlezah.roadguard.location

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a GPX 1.1 track incrementally and crash-safely.
 *
 * A dashcam cannot buffer a whole drive and write the file at the end: the process may be
 * killed at any moment, and the drive that gets killed is exactly the one worth keeping. So
 * every point is appended in place and the closing tags are rewritten each time.
 *
 * The trick that makes that cheap: the file always ends with the closing
 * `</trkseg></trk></gpx>` tags, and the next append seeks back over them, writes the new
 * point, and writes the tags again. The file on disk is therefore *always* a valid,
 * openable GPX document -- even if the phone loses power mid-drive -- and no rewrite of
 * earlier content is ever needed.
 *
 * ### How often it reaches the disk
 *
 * Every append is a write into the page cache; `fsync` runs at most once per [syncIntervalMs].
 * One fix a second with an fsync each would be 3,600 flushes an hour on the same eMMC the video
 * encoder is writing to, for the sake of at most one second of track. A few seconds of
 * exposure is the right trade, and [flush] and [close] force the last points out.
 *
 * GPS never leaves the device: this file is written to Roadguard's own storage and is only
 * shared if the user explicitly shares it.
 */
class GpxWriter(
    private val file: File,
    private val creator: String = "Roadguard",
    private val syncIntervalMs: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val timestampFormat = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private var handle: RandomAccessFile? = null
    private var pointCount = 0
    private var lastSyncAt = 0L
    private var dirty = false

    /** Points appended through this writer; a reopened file's earlier points are not counted. */
    val points: Int get() = pointCount

    /** Creates the file with an empty track, or reopens an existing one for appending. */
    @Throws(IOException::class)
    fun open(trackName: String) {
        file.parentFile?.mkdirs()
        val existing = file.exists() && file.length() > 0
        val raf = RandomAccessFile(file, "rw")
        handle = raf
        if (!existing) {
            raf.setLength(0)
            raf.write(header(trackName).toByteArray())
            raf.write(FOOTER.toByteArray())
            raf.fd.sync()
        } else {
            // Reopening an existing track: position just before the closing tags.
            val trailer = FOOTER.toByteArray()
            if (raf.length() >= trailer.size) raf.seek(raf.length() - trailer.size)
        }
        lastSyncAt = clock()
    }

    /**
     * Appends one track point.
     *
     * @param speedMps optional; written as a `gpxtpx`-style extension, which is what Garmin,
     *   Strava and most desktop tools read. Consumers that do not understand the extension
     *   simply ignore it, so the file stays maximally portable.
     */
    @Throws(IOException::class)
    fun append(
        latitude: Double,
        longitude: Double,
        altitudeMetres: Double?,
        epochMs: Long,
        speedMps: Float?,
        accuracyMetres: Float?,
        satellites: Int?,
    ) {
        val raf = handle ?: throw IOException("GpxWriter.open must be called first")
        val trailer = FOOTER.toByteArray()
        val insertAt = (raf.length() - trailer.size).coerceAtLeast(0L)
        raf.seek(insertAt)
        raf.write(point(latitude, longitude, altitudeMetres, epochMs, speedMps, accuracyMetres, satellites).toByteArray())
        raf.write(trailer)
        pointCount++
        dirty = true
        val now = clock()
        if (syncIntervalMs <= 0L || now - lastSyncAt >= syncIntervalMs) flush(now)
    }

    /** Forces everything written so far onto storage. */
    fun flush(now: Long = clock()) {
        val raf = handle ?: return
        if (!dirty) return
        runCatching { raf.fd.sync() }
        dirty = false
        lastSyncAt = now
    }

    override fun close() {
        flush()
        runCatching { handle?.close() }
        handle = null
    }

    private fun header(trackName: String): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<gpx version="1.1" creator="$creator" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""",
        )
        appendLine("  <metadata>")
        appendLine("    <name>${escape(trackName)}</name>")
        appendLine("    <time>${timestampFormat.format(Date(clock()))}</time>")
        appendLine("  </metadata>")
        appendLine("  <trk>")
        appendLine("    <name>${escape(trackName)}</name>")
        appendLine("    <trkseg>")
    }

    private fun point(
        latitude: Double,
        longitude: Double,
        altitudeMetres: Double?,
        epochMs: Long,
        speedMps: Float?,
        accuracyMetres: Float?,
        satellites: Int?,
    ): String = buildString {
        append("""      <trkpt lat="${"%.6f".format(Locale.US, latitude)}" """)
        appendLine("""lon="${"%.6f".format(Locale.US, longitude)}">""")
        altitudeMetres?.let { appendLine("        <ele>${"%.1f".format(Locale.US, it)}</ele>") }
        appendLine("        <time>${timestampFormat.format(Date(epochMs))}</time>")
        satellites?.takeIf { it > 0 }?.let { appendLine("        <sat>$it</sat>") }
        accuracyMetres?.let { appendLine("        <hdop>${"%.1f".format(Locale.US, it)}</hdop>") }
        if (speedMps != null) {
            appendLine("        <extensions>")
            appendLine("          <gpxtpx:TrackPointExtension>")
            appendLine("            <gpxtpx:speed>${"%.2f".format(Locale.US, speedMps)}</gpxtpx:speed>")
            appendLine("          </gpxtpx:TrackPointExtension>")
            appendLine("        </extensions>")
        }
        appendLine("      </trkpt>")
    }

    companion object {
        private const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"

        /** Kept as a constant because [append] seeks back over exactly this many bytes. */
        const val FOOTER = "    </trkseg>\n  </trk>\n</gpx>\n"

        private val NAME_LINE = Regex("""(?m)^([ \t]*)<name>.*?</name>$""")

        /**
         * Renames a finished track in place.
         *
         * The name is written into the header when the file is opened, which is before the trip
         * has been named. Once the ends are known ("Harrison → Braddon") the two `<name>` elements
         * are rewritten through a temporary file and an atomic rename, so a crash mid-rewrite
         * leaves the old file rather than half of the new one. Track points carry no `<name>`, so
         * the pattern cannot touch them.
         */
        fun rename(file: File, trackName: String): Boolean = runCatching {
            if (!file.isFile) return false
            val text = file.readText()
            val replaced = NAME_LINE.replace(text) { match -> "${match.groupValues[1]}<name>${escape(trackName)}</name>" }
            if (replaced == text) return true
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(replaced)
            if (temp.renameTo(file)) return true
            file.writeText(replaced)
            temp.delete()
            true
        }.getOrDefault(false)

        /**
         * Reads the track points back, thinned to at most [maxPoints], for drawing a route sketch.
         *
         * A regular expression over Roadguard's own output rather than an XML parser: the file was
         * written by [point] above, one attribute pair per `trkpt`, and this is a picture, not a
         * measurement.
         */
        fun readPoints(file: File, maxPoints: Int = 64): List<Pair<Double, Double>> = runCatching {
            if (!file.isFile) return emptyList()
            val all = TRACK_POINT.findAll(file.readText())
                .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                .toList()
            if (all.size <= maxPoints) return all
            val step = all.size.toDouble() / maxPoints
            List(maxPoints) { i -> all[(i * step).toInt().coerceAtMost(all.size - 1)] }
        }.getOrDefault(emptyList())

        private val TRACK_POINT = Regex("""<trkpt lat="(-?[0-9.]+)" lon="(-?[0-9.]+)">""")

        private fun escape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
