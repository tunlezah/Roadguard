package io.github.tunlezah.roadguard.storage

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Decides whether a recorded file is actually playable, and says honestly when it is not.
 *
 * A dashcam is killed at inconvenient moments: the phone loses power at the ignition, Android
 * reclaims the process, the user force-stops it. An MP4 written by `MediaMuxer` keeps its index
 * (`moov`) until the muxer is stopped, so a file killed mid-write contains the video data but no
 * index and no normal player will open it.
 *
 * Roadguard therefore checks every incomplete file on start-up with a cheap top-level box scan
 * -- no decoder, no metadata retriever, a few reads -- and classifies it. It deliberately does
 * **not** claim to repair anything: rebuilding a `moov` atom means walking every sample in the
 * `mdat` and reconstructing the sample tables, which is a video-repair tool, not something to
 * attempt inside a recorder that is about to start recording again. Unrecoverable files are
 * moved to the quarantine directory, counted, and reported -- never silently deleted, because
 * the file the user most wants may be exactly the one that got truncated.
 */
object Mp4Inspector {

    /** Smaller than this and there cannot be a usable frame in there. */
    const val MIN_PLAUSIBLE_BYTES = 32L * 1024

    fun inspect(file: File): Mp4Verdict {
        if (!file.exists()) return Mp4Verdict.Missing
        val length = file.length()
        if (length < MIN_PLAUSIBLE_BYTES) return Mp4Verdict.Empty(length)

        val boxes = runCatching { topLevelBoxes(file) }.getOrElse { throwable ->
            Log.w(TAG, "could not scan ${file.name}", throwable)
            return Mp4Verdict.Unreadable(throwable.message ?: throwable.javaClass.simpleName)
        }

        val types = boxes.map { it.type }
        val hasFileType = "ftyp" in types
        val hasIndex = hasWholeIndex(boxes)
        val hasMedia = "mdat" in types

        return when {
            !hasFileType -> Mp4Verdict.NotMp4
            hasIndex && hasMedia -> {
                val metadata = readMetadata(file)
                if (metadata != null && metadata.durationMs > 0) {
                    return Mp4Verdict.Playable(metadata)
                }
                // The platform could not describe the file, but the index it just found states
                // the clip's duration itself (`moov/mvhd`), and that is a handful of bytes to read.
                // A clip whose metadata the platform never manages to read -- it happens, and it
                // happens to the same file every time -- would otherwise stay "left for the next
                // start" on every start, which for an unindexed file means never in the gallery.
                val stated = boxes.firstOrNull { it.type == "moov" && it.isWhole }
                    ?.let { moov -> runCatching { readStatedDuration(file, moov) }.getOrNull() }
                if (stated != null && stated > 0) {
                    return Mp4Verdict.Playable((metadata ?: Mp4Metadata.unknown()).copy(durationMs = stated))
                }
                // The structure is whole: index and media are both there. Only the metadata
                // read failed, and that read is a platform service that can fail for reasons
                // of its own. Saying so, rather than "truncated", is what stops a good file
                // being moved to quarantine on the strength of a passing failure.
                Mp4Verdict.IndexedButUnread(length)
            }

            hasMedia -> Mp4Verdict.TruncatedNoIndex(length, bytesOfMedia(boxes))
            else -> Mp4Verdict.Unreadable("no media data")
        }
    }

    /**
     * True when an index box is present *and fits inside the file*. The muxer writes the index
     * last, so a file cut short by a power loss can end part-way through it; a box whose declared
     * size runs past the end of the file is exactly that, and does not count.
     */
    fun hasWholeIndex(boxes: List<Mp4Box>): Boolean = boxes.any { it.type == "moov" && it.isWhole }

    /**
     * Reads the top-level box chain.
     *
     * Stops at [MAX_BOXES] so a corrupt length field cannot spin forever, and treats a
     * zero-size box as "extends to end of file" per ISO/IEC 14496-12. A box that claims more
     * bytes than the file holds is recorded with [Mp4Box.isWhole] false and ends the scan.
     */
    fun topLevelBoxes(file: File): List<Mp4Box> {
        val boxes = mutableListOf<Mp4Box>()
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            val length = raf.length()
            while (offset + 8 <= length && boxes.size < MAX_BOXES) {
                raf.seek(offset)
                val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)
                val (size, headerSize) = when (size32) {
                    1L -> raf.readLong() to 16L
                    0L -> (length - offset) to 8L
                    else -> size32 to 8L
                }
                if (size < headerSize) break
                val whole = offset + size <= length
                boxes += Mp4Box(type = type, offset = offset, size = size, isWhole = whole)
                if (!whole) break
                offset += size
            }
        }
        return boxes
    }

    private fun bytesOfMedia(boxes: List<Mp4Box>): Long =
        boxes.filter { it.type == "mdat" }.sumOf { it.size }

    /**
     * The duration the index itself declares, in milliseconds, or null when it declares none.
     *
     * `moov` holds a movie header (`mvhd`) whose timescale and duration describe the whole
     * presentation (ISO/IEC 14496-12 §8.2.2). Only the headers of `moov`'s direct children are
     * read, so this costs a few seeks however large the sample tables are. A duration of all ones
     * is the specification's "unknown" and does not count.
     */
    private fun readStatedDuration(file: File, moov: Mp4Box): Long? {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(moov.offset)
            val headerSize = if ((raf.readInt().toLong() and 0xFFFFFFFFL) == 1L) 16L else 8L
            val end = moov.offset + moov.size
            var offset = moov.offset + headerSize
            var children = 0
            while (offset + 8 <= end && children++ < MAX_BOXES) {
                raf.seek(offset)
                val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)
                val (size, childHeader) = when (size32) {
                    1L -> raf.readLong() to 16L
                    0L -> (end - offset) to 8L
                    else -> size32 to 8L
                }
                if (size < childHeader || offset + size > end) return null
                if (type == "mvhd") {
                    val version = raf.readByte().toInt()
                    raf.skipBytes(3) // flags
                    val timescale: Long
                    val duration: Long
                    if (version == 1) {
                        raf.skipBytes(16) // creation and modification time, 64-bit each
                        timescale = raf.readInt().toLong() and 0xFFFFFFFFL
                        duration = raf.readLong()
                        if (duration == -1L) return null
                    } else {
                        raf.skipBytes(8) // creation and modification time, 32-bit each
                        timescale = raf.readInt().toLong() and 0xFFFFFFFFL
                        duration = raf.readInt().toLong() and 0xFFFFFFFFL
                        if (duration == 0xFFFFFFFFL) return null
                    }
                    if (timescale <= 0L || duration <= 0L) return null
                    return duration * 1_000L / timescale
                }
                offset += size
            }
        }
        return null
    }

    private fun readMetadata(file: File): Mp4Metadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Mp4Metadata(
                durationMs = duration,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0,
                bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull() ?: 0,
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                captureFrameRate = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull(),
            )
        } catch (throwable: Throwable) {
            Log.w(TAG, "metadata read failed for ${file.name}: ${throwable.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private const val TAG = "RoadguardMp4"
    private const val MAX_BOXES = 512
}

data class Mp4Box(val type: String, val offset: Long, val size: Long, val isWhole: Boolean = true)

data class Mp4Metadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val bitrateBps: Int,
    val mimeType: String?,
    val hasAudio: Boolean,
    val captureFrameRate: Float?,
) {
    companion object {
        /** What is known about a file the platform could not describe at all: nothing yet. */
        fun unknown() = Mp4Metadata(
            durationMs = 0L,
            width = 0,
            height = 0,
            rotationDegrees = 0,
            bitrateBps = 0,
            mimeType = null,
            hasAudio = false,
            captureFrameRate = null,
        )
    }
}

/** What [Mp4Inspector] concluded about a file. */
sealed interface Mp4Verdict {
    /**
     * The file opens and reports a real duration -- from the platform's metadata reader when it
     * works, else from the movie header in the file's own index. Fields that reader alone could
     * supply (dimensions, codec) are zero or null when it did not.
     */
    data class Playable(val metadata: Mp4Metadata) : Mp4Verdict

    /**
     * Media data is present but there is no usable index, which is what a hard kill during
     * recording produces. Not repairable in-app; quarantined and reported.
     */
    data class TruncatedNoIndex(val fileBytes: Long, val mediaBytes: Long) : Mp4Verdict

    /**
     * Index and media are both present and whole, but the platform's metadata reader could not
     * describe the file this time and its index states no duration either. The file is left
     * exactly where it is: a player may well open it, and the next check may succeed. Never a
     * reason to move or drop anything.
     */
    data class IndexedButUnread(val fileBytes: Long) : Mp4Verdict

    data class Empty(val fileBytes: Long) : Mp4Verdict
    data object NotMp4 : Mp4Verdict
    data object Missing : Mp4Verdict
    data class Unreadable(val reason: String) : Mp4Verdict

    /** Structurally whole: worth keeping, indexing and handing to a player. */
    val isUsable: Boolean get() = this is Playable || this is IndexedButUnread

    val summary: String
        get() = when (this) {
            is Playable -> "playable, ${metadata.durationMs} ms"
            is TruncatedNoIndex -> "truncated: $mediaBytes bytes of video with no index"
            is IndexedButUnread -> "index present but the metadata could not be read this time"
            is Empty -> "empty ($fileBytes bytes)"
            NotMp4 -> "not an MP4"
            Missing -> "file missing"
            is Unreadable -> "unreadable: $reason"
        }
}
