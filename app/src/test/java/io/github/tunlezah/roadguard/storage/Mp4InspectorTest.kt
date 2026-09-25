package io.github.tunlezah.roadguard.storage

import android.media.MediaMetadataRetriever
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [Mp4Inspector] decides whether a recorded file is kept, re-indexed or quarantined, so what it
 * says about a file must follow from the file's bytes and nothing else.
 *
 * Two verdicts are pinned in particular. A file whose index box is cut short at the end -- what a
 * power failure leaves when it lands during the last write -- is truncated, even though the box
 * type is there. And a file whose structure is whole but whose metadata the platform could not
 * read is *not* truncated: it is reported as whole-but-unread, which no caller treats as a reason
 * to move or drop anything, because that read is a service that can fail for reasons of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Mp4InspectorTest {

    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() {
        ShadowMediaMetadataRetriever.reset()
    }

    @Test
    fun `a whole file with readable metadata is playable`() {
        val file = write("whole.mp4", mp4(indexBytes = 64))
        ShadowMediaMetadataRetriever.addMetadata(file.absolutePath, MediaMetadataRetriever.METADATA_KEY_DURATION, "180000")

        val verdict = Mp4Inspector.inspect(file)

        assertThat(verdict).isInstanceOf(Mp4Verdict.Playable::class.java)
        assertThat(verdict.isUsable).isTrue()
    }

    @Test
    fun `a whole file whose metadata cannot be read is whole-but-unread, not truncated`() {
        val file = write("unread.mp4", mp4(indexBytes = 64))

        val verdict = Mp4Inspector.inspect(file)

        assertThat(verdict).isInstanceOf(Mp4Verdict.IndexedButUnread::class.java)
        assertThat(verdict.isUsable).isTrue()
        assertThat(verdict.summary).contains("index present")
    }

    @Test
    fun `a file with media and no index is truncated`() {
        val file = write("cut.mp4", mp4(indexBytes = null))

        val verdict = Mp4Inspector.inspect(file)

        assertThat(verdict).isInstanceOf(Mp4Verdict.TruncatedNoIndex::class.java)
        assertThat(verdict.isUsable).isFalse()
    }

    @Test
    fun `an index box cut short at the end of the file does not count as an index`() {
        // The index claims 64 bytes; only 20 of them made it to the disk.
        val whole = mp4(indexBytes = 64)
        val file = write("tail-lost.mp4", whole.copyOf(whole.size - 44))

        val boxes = Mp4Inspector.topLevelBoxes(file)
        assertThat(boxes.last().type).isEqualTo("moov")
        assertThat(boxes.last().isWhole).isFalse()
        assertThat(Mp4Inspector.hasWholeIndex(boxes)).isFalse()

        val verdict = Mp4Inspector.inspect(file)
        assertThat(verdict).isInstanceOf(Mp4Verdict.TruncatedNoIndex::class.java)
    }

    @Test
    fun `the box scan reports every whole box and stops at one that overruns`() {
        val file = write("scan.mp4", mp4(indexBytes = 64))

        val boxes = Mp4Inspector.topLevelBoxes(file)

        assertThat(boxes.map { it.type }).containsExactly("ftyp", "mdat", "moov").inOrder()
        assertThat(boxes.all { it.isWhole }).isTrue()
        assertThat(boxes.sumOf { it.size }).isEqualTo(file.length())
    }

    @Test
    fun `a missing or tiny file is never mistaken for a recording`() {
        assertThat(Mp4Inspector.inspect(File(folder.root, "absent.mp4"))).isEqualTo(Mp4Verdict.Missing)
        val tiny = write("tiny.mp4", ByteArray(1024))
        assertThat(Mp4Inspector.inspect(tiny)).isInstanceOf(Mp4Verdict.Empty::class.java)
    }

    private fun write(name: String, bytes: ByteArray): File = File(folder.root, name).apply { writeBytes(bytes) }

    /** `ftyp`, then media, then -- unless [indexBytes] is null -- an index box of that payload size. */
    private fun mp4(indexBytes: Int?): ByteArray {
        val out = ByteArrayOutputStream()
        fun box(type: String, payload: ByteArray) {
            val size = 8 + payload.size
            out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(payload)
        }
        box("ftyp", "isom".toByteArray(Charsets.US_ASCII) + ByteArray(4))
        box("mdat", ByteArray(40 * 1024))
        if (indexBytes != null) box("moov", ByteArray(indexBytes))
        return out.toByteArray()
    }
}
