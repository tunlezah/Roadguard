package io.github.tunlezah.roadguard.recording

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers, across process death, whether a recording session was running.
 *
 * ### Why this exists
 *
 * If Android kills Roadguard mid-drive -- memory pressure, or a vendor battery manager -- the
 * recording service is restarted without an intent and in the background, where Android does not
 * allow a camera to be reopened. Recording therefore cannot resume by itself. What the app *can*
 * do is notice that a session was interrupted and put a "tap to resume" notification in front of
 * the driver, because a tap opens the app and a visible app may start the camera.
 *
 * Written only when a session starts writing and when it ends, never per segment, so the cost is
 * two small preference writes per drive.
 */
open class SessionJournal(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** A session has started writing footage. */
    open fun markRecording(nowEpochMs: Long) {
        preferences.edit {
            putBoolean(KEY_ACTIVE, true)
            putLong(KEY_SINCE, nowEpochMs)
        }
    }

    /** The session ended on purpose: Stop, the power policy, a flat battery or a shutdown. */
    open fun markStopped() {
        preferences.edit { putBoolean(KEY_ACTIVE, false) }
    }

    /**
     * When the interrupted session began, if the last one never ended on purpose, or null.
     *
     * Reading it clears it, so one kill produces one resume prompt.
     */
    open fun takeInterruptedSession(): Long? {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return null
        val since = preferences.getLong(KEY_SINCE, 0L)
        preferences.edit { putBoolean(KEY_ACTIVE, false) }
        return since
    }

    /**
     * The driver pressed Stop. Remembered across process death so the next app open, and the next
     * time the phone sees power, do not silently start recording again: an explicit Stop must not
     * be undone by an automatic trigger. Cleared by [clearUserStopped] when the driver next records
     * by hand. See [AutoStartPolicy].
     */
    open fun markUserStopped() {
        preferences.edit { putBoolean(KEY_USER_STOPPED, true) }
    }

    /** The driver started recording by hand, so the automatic triggers are welcome again. */
    open fun clearUserStopped() {
        preferences.edit { putBoolean(KEY_USER_STOPPED, false) }
    }

    /** True while an explicit Stop is still in force, i.e. no manual start has happened since. */
    open fun wasUserStopped(): Boolean = preferences.getBoolean(KEY_USER_STOPPED, false)

    private companion object {
        const val FILE_NAME = "roadguard_session"
        const val KEY_ACTIVE = "recording_active"
        const val KEY_SINCE = "recording_since"
        const val KEY_USER_STOPPED = "user_stopped"
    }
}
