package io.github.tunlezah.roadguard.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SessionJournal] is how Roadguard notices, after Android killed it mid-drive, that a recording
 * was interrupted -- and so offers a one-tap resume instead of silently not recording.
 *
 * Run against real SharedPreferences under Robolectric. A new journal instance stands in for the
 * restarted process: the state must survive it, and reading it must clear it so one kill gives
 * one prompt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionJournalTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Independent of whether the runner resets preferences between tests.
        context.getSharedPreferences("roadguard_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a fresh install has no interrupted session`() {
        assertThat(SessionJournal(context).takeInterruptedSession()).isNull()
    }

    @Test
    fun `a session that never stopped is reported after a restart, once`() {
        SessionJournal(context).markRecording(nowEpochMs = 1_700_000_000_000L)

        val restarted = SessionJournal(context)
        assertThat(restarted.takeInterruptedSession()).isEqualTo(1_700_000_000_000L)
        assertThat(restarted.takeInterruptedSession()).isNull()
        assertThat(SessionJournal(context).takeInterruptedSession()).isNull()
    }

    @Test
    fun `a session that stopped on purpose is not reported`() {
        val journal = SessionJournal(context)
        journal.markRecording(nowEpochMs = 1_700_000_000_000L)
        journal.markStopped()

        assertThat(SessionJournal(context).takeInterruptedSession()).isNull()
    }

    @Test
    fun `the latest session start is the one reported`() {
        val journal = SessionJournal(context)
        journal.markRecording(nowEpochMs = 1_000L)
        journal.markStopped()
        journal.markRecording(nowEpochMs = 2_000L)

        assertThat(SessionJournal(context).takeInterruptedSession()).isEqualTo(2_000L)
    }
}
