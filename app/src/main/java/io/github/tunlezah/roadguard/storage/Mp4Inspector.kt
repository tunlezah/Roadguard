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
        val hasIndex = "moov" in types
        val hasMedia = "mdat" in types

        return when {
            !hasFileType -> Mp4Verdict.NotMp4
            hasIndex && hasMedia -> {
                val metadata = readMetadata(file)
                if (metadata != null && metadata.durationMs > 0) {
                    Mp4Verdict.Playable(metadata)
                } else {
                    // Index and media present but nothing readable: treat as truncated rather
                    // than claiming a duration we could not obtain.
                    Mp4Verdict.TruncatedNoIndex(length, bytesOfMedia(boxes))
                }
            }

            hasMedia -> Mp4Verdict.TruncatedNoIndex(length, bytesOfMedia(boxes))
            else -> Mp4Verdict.Unreadable("no media data")
        }
    }

    /**
     * Reads the top-level box chain.
     *
     * Stops at [MAX_BOXES] so a corrupt length field cannot spin forever, and treats a
     * zero-size box as "extends to end of file" per ISO/IEC 14496-12.
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
                boxes += Mp4Box(type = type, offset = offset, size = size)
                offset += size
            }
        }
        return boxes
    }

    private fun bytesOfMedia(boxes: List<Mp4Box>): Long =
        boxes.filter { it.type == "mdat" }.sumOf { it.size }

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

data class Mp4Box(val type: String, val offset: Long, val size: Long)

data class Mp4Metadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val bitrateBps: Int,
    val mimeType: String?,
    val hasAudio: Boolean,
    val captureFrameRate: Float?,
)

/** What [Mp4Inspector] concluded about a file. */
sealed interface Mp4Verdict {
    /** The file opens and reports a real duration. */
    data class Playable(val metadata: Mp4Metadata) : Mp4Verdict

    /**
     * Media data is present but there is no usable index, which is what a hard kill during
     * recording produces. Not repairable in-app; quarantined and reported.
     */
    data class TruncatedNoIndex(val fileBytes: Long, val mediaBytes: Long) : Mp4Verdict

    data class Empty(val fileBytes: Long) : Mp4Verdict
    data object NotMp4 : Mp4Verdict
    data object Missing : Mp4Verdict
    data class Unreadable(val reason: String) : Mp4Verdict

    val isUsable: Boolean get() = this is Playable

    val summary: String
        get() = when (this) {
            is Playable -> "playable, ${metadata.durationMs} ms"
            is TruncatedNoIndex -> "truncated: $mediaBytes bytes of video with no index"
            is Empty -> "empty ($fileBytes bytes)"
            NotMp4 -> "not an MP4"
            Missing -> "file missing"
            is Unreadable -> "unreadable: $reason"
        }
}
