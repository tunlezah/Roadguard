package io.github.tunlezah.roadguard.ui

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.ui.gallery.PlaybackProblems
import org.junit.Test

/**
 * What the player says when playback fails. A driver who has just lost footage needs to know
 * whether the file is gone, damaged, or merely beyond this phone's decoder, and each wording is
 * pinned here so it cannot drift into "an error occurred".
 */
class PlaybackProblemsTest {

    private fun failure(code: Int) = PlaybackException("test", null, code)

    @Test
    fun `a missing file says so, and where it may have gone`() {
        val text = PlaybackProblems.describe(failure(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertThat(text).contains("no longer there")
        assertThat(text).contains("quarantine")
    }

    @Test
    fun `a read failure points at the storage`() {
        for (code in listOf(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)) {
            assertWithMessage("$code").that(PlaybackProblems.describe(failure(code))).contains("could not be read")
        }
    }

    @Test
    fun `a damaged file is called damaged, and the file is not written off`() {
        val text = PlaybackProblems.describe(failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertThat(text).contains("damaged")
        assertThat(text).contains("repair tool")
    }

    @Test
    fun `a decoder failure suggests another device rather than blaming the file`() {
        for (code in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)) {
            val text = PlaybackProblems.describe(failure(code))
            assertWithMessage("$code").that(text).contains("could not decode")
            assertWithMessage("$code").that(text).contains("another device")
        }
    }

    @Test
    fun `every message names the underlying code in words`() {
        val text = PlaybackProblems.describe(failure(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertThat(text).contains("decoding failed")
        assertThat(text).doesNotContain("ERROR_CODE")
    }

    @Test
    fun `an unknown code still produces a sentence`() {
        val text = PlaybackProblems.describe(failure(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertThat(text).startsWith("Playback failed")
    }
}
