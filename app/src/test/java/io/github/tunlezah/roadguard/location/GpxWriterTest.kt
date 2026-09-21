package io.github.tunlezah.roadguard.location

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The track file: valid at every moment, appendable after a relaunch, renameable once the trip
 * is known, and readable back for the route thumbnail.
 */
class GpxWriterTest {

    @get:Rule val folder = TemporaryFolder()

    private fun parse(text: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(text.byteInputStream())

    @Test
    fun `the file is a complete GPX document before any point and after each one`() {
        val file = folder.newFile("t.gpx")
        val writer = GpxWriter(file)
        writer.open("Roadguard trip")

        assertThat(parse(file.readText()).documentElement.tagName).isEqualTo("gpx")

        writer.append(-35.1991, 149.1561, 580.0, 1_000L, 12.5f, 4.0f, 9)
        assertThat(parse(file.readText()).getElementsByTagName("trkpt").length).isEqualTo(1)

        writer.append(-35.2000, 149.1500, null, 2_000L, null, null, null)
        val document = parse(file.readText())
        assertThat(document.getElementsByTagName("trkpt").length).isEqualTo(2)
        assertThat(writer.points).isEqualTo(2)
        writer.close()

        // Optional fields are omitted, not written as empty elements.
        val second = document.getElementsByTagName("trkpt").item(1)
        assertThat(second.textContent).doesNotContain("ele")
        assertThat(file.readText()).endsWith(GpxWriter.FOOTER)
    }

    @Test
    fun `reopening an existing track appends after its last point`() {
        val file = folder.newFile("t.gpx")
        GpxWriter(file).use {
            it.open("first run")
            it.append(-35.1, 149.1, null, 1_000L, null, null, null)
        }

        GpxWriter(file).use {
            it.open("ignored on reopen")
            it.append(-35.2, 149.2, null, 2_000L, null, null, null)
        }

        val document = parse(file.readText())
        assertThat(document.getElementsByTagName("trkpt").length).isEqualTo(2)
        assertThat(document.getElementsByTagName("name").item(0).textContent).isEqualTo("first run")
    }

    @Test
    fun `speed is written as the Garmin extension consumers read`() {
        val file = folder.newFile("t.gpx")
        GpxWriter(file).use {
            it.open("t")
            it.append(-35.1, 149.1, null, 1_000L, 27.78f, 5f, 8)
        }

        val text = file.readText()
        assertThat(text).contains("<gpxtpx:speed>27.78</gpxtpx:speed>")
        assertThat(text).contains("<sat>8</sat>")
        assertThat(text).contains("<hdop>5.0</hdop>")
    }

    @Test
    fun `rename rewrites both name elements and nothing else`() {
        val file = folder.newFile("t.gpx")
        GpxWriter(file).use {
            it.open("Roadguard trip 19 Sep 2026 08:12")
            it.append(-35.1991, 149.1561, null, 1_000L, null, null, null)
        }
        val before = file.readText()

        assertThat(GpxWriter.rename(file, "Harrison → Braddon · 19 Sep 2026 08:12")).isTrue()

        val after = file.readText()
        val names = parse(after).getElementsByTagName("name")
        assertThat(names.length).isEqualTo(2)
        assertThat(names.item(0).textContent).isEqualTo("Harrison → Braddon · 19 Sep 2026 08:12")
        assertThat(names.item(1).textContent).isEqualTo("Harrison → Braddon · 19 Sep 2026 08:12")
        // Only the two name lines changed.
        assertThat(after.lines().size).isEqualTo(before.lines().size)
        assertThat(after.lines().filter { "<trkpt" in it }).isEqualTo(before.lines().filter { "<trkpt" in it })
        assertThat(folder.root.listFiles()!!.map { it.name }).containsExactly("t.gpx")
    }

    @Test
    fun `rename escapes markup in the name`() {
        val file = folder.newFile("t.gpx")
        GpxWriter(file).use { it.open("t") }

        GpxWriter.rename(file, "Smith & Sons <depot>")

        assertThat(parse(file.readText()).getElementsByTagName("name").item(0).textContent)
            .isEqualTo("Smith & Sons <depot>")
    }

    @Test
    fun `rename of a missing file reports failure rather than creating one`() {
        val missing = folder.root.resolve("nope.gpx")

        assertThat(GpxWriter.rename(missing, "x")).isFalse()
        assertThat(missing.exists()).isFalse()
    }

    @Test
    fun `readPoints returns the track thinned to the requested count in order`() {
        val file = folder.newFile("t.gpx")
        GpxWriter(file).use { writer ->
            writer.open("t")
            repeat(100) { i -> writer.append(-35.0 - i * 0.001, 149.0 + i * 0.001, null, i * 1_000L, null, null, null) }
        }

        val all = GpxWriter.readPoints(file, maxPoints = 1_000)
        val thinned = GpxWriter.readPoints(file, maxPoints = 10)

        assertThat(all).hasSize(100)
        assertThat(all.first()).isEqualTo(-35.0 to 149.0)
        assertThat(thinned).hasSize(10)
        assertThat(thinned.first()).isEqualTo(all.first())
        assertThat(thinned.zipWithNext().all { (a, b) -> a.first > b.first }).isTrue()
    }

    @Test
    fun `readPoints of a missing or empty file is empty`() {
        assertThat(GpxWriter.readPoints(folder.root.resolve("nope.gpx"))).isEmpty()
        assertThat(GpxWriter.readPoints(folder.newFile("empty.gpx"))).isEmpty()
    }

    @Test
    fun `with a sync interval the writer still flushes on close`() {
        var now = 0L
        val file = folder.newFile("t.gpx")
        val writer = GpxWriter(file, syncIntervalMs = 5_000L, clock = { now })
        writer.open("t")
        now = 1_000L
        writer.append(-35.1, 149.1, null, now, null, null, null)
        now = 2_000L
        writer.append(-35.2, 149.2, null, now, null, null, null)
        writer.close()

        assertThat(parse(file.readText()).getElementsByTagName("trkpt").length).isEqualTo(2)
    }
}
