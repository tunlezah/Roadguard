package io.github.tunlezah.roadguard.ui.gallery

import androidx.media3.common.PlaybackException

/**
 * Turns a playback failure into a sentence a driver can act on.
 *
 * ExoPlayer reports a failure through a listener and then sits idle; without this the screen
 * showed a black rectangle and a play button that did nothing, which tells someone who has just
 * lost footage nothing about whether the file is gone, damaged, or merely beyond this phone's
 * decoder. Pure, so every wording is unit tested.
 */
internal object PlaybackProblems {

    fun describe(error: PlaybackException): String {
        val code = error.errorCode
        val detail = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
        return when {
            code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "The file is no longer there. It may have been deleted by the loop or moved to quarantine."

            code in IO_ERRORS ->
                "The file could not be read ($detail). If it is on a memory card, check the card."

            code in CONTAINER_ERRORS ->
                "The file is damaged or is not a video this phone understands ($detail). Roadguard keeps " +
                    "such files rather than deleting them, in case a repair tool can recover them."

            code in DECODER_ERRORS ->
                "This phone could not decode the video ($detail). Sharing the clip to another device " +
                    "or player may still work."

            else -> "Playback failed ($detail)."
        }
    }

    /** media3 groups its error codes by thousand: 2xxx input/output, 3xxx parsing, 4xxx decoding. */
    private val IO_ERRORS = 2000..2999
    private val CONTAINER_ERRORS = 3000..3999
    private val DECODER_ERRORS = 4000..4999
}
