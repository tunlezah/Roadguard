package io.github.tunlezah.roadguard.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.ui.MainActivity

/**
 * The foreground-service notification, and the few alerts Roadguard is allowed to raise.
 *
 * The recording notification is deliberately quiet: no sound, no vibration, minimum importance
 * that still keeps it visible, and no progress bar. It exists because Android requires it and
 * because it is the user's only proof that recording is still running when the screen is off --
 * so it always states the truth, including when recording has *stopped*.
 *
 * Alerts interrupt only for things a driver would want to know at the next set of lights:
 * recording that has been interrupted for more than a moment, recording that stopped without
 * being asked to, and a recording Android cut short that can be resumed with a tap.
 *
 * ### Cost
 *
 * The notification is described by a [Content] value and the service posts it only when that
 * value changes. It used to be rebuilt -- with three fresh `PendingIntent`s, each a binder call --
 * and re-posted for every progress report the recorder made, which is every encoded frame.
 */
class RecordingNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    // Created once and reused: each PendingIntent lookup is a call into the system server.
    private val openIntent: PendingIntent by lazy { activityIntent(REQUEST_OPEN, resume = false) }
    private val resumeIntent: PendingIntent by lazy { activityIntent(REQUEST_RESUME, resume = true) }
    private val protectIntent: PendingIntent by lazy { servicePendingIntent(RecordingService.ACTION_PROTECT, REQUEST_PROTECT) }
    private val stopIntent: PendingIntent by lazy { servicePendingIntent(RecordingService.ACTION_STOP, REQUEST_STOP) }
    private val startIntent: PendingIntent by lazy { servicePendingIntent(RecordingService.ACTION_START, REQUEST_START) }

    /** The actions a notification offers. */
    enum class Actions { ProtectAndStop, StopOnly, Record, None }

    /**
     * Everything the ongoing notification shows. A value type, so the service can tell a real
     * change from a progress report that changes nothing a person would see.
     */
    data class Content(
        val title: String,
        val text: String,
        val iconRes: Int,
        val ongoing: Boolean,
        val actions: Actions,
    )

    fun ensureChannels() {
        val recording = NotificationChannel(
            CHANNEL_RECORDING,
            "Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows that Roadguard is recording. Required by Android for background recording."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "Recording problems",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Tells you when recording is interrupted or stops, and when it can be resumed."
            setShowBadge(true)
        }
        manager?.createNotificationChannel(recording)
        manager?.createNotificationChannel(alerts)
    }

    fun build(state: RecordingUiState, storageSummary: String?): android.app.Notification =
        build(contentFor(state, storageSummary))

    fun build(content: Content): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(content.iconRes)
            .setContentTitle(content.title)
            .setContentText(content.text.ifEmpty { "Tap to open Roadguard" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setOngoing(content.ongoing)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)

        when (content.actions) {
            Actions.ProtectAndStop -> {
                builder.addAction(R.drawable.ic_lock, "Protect", protectIntent)
                builder.addAction(R.drawable.ic_stop, "Stop", stopIntent)
            }

            Actions.StopOnly -> builder.addAction(R.drawable.ic_stop, "Stop", stopIntent)
            Actions.Record -> builder.addAction(R.drawable.ic_fiber_manual_record, "Record", startIntent)
            Actions.None -> Unit
        }
        return builder.build()
    }

    /** Recording has been interrupted for longer than a blip, and Roadguard is still trying. */
    fun notifyInterrupted(detail: String) = notifyAlert(
        id = NOTIFICATION_INTERRUPTED,
        title = "Recording interrupted",
        message = detail,
    )

    /** Recording stopped and will not restart by itself. */
    fun notifyStopped(detail: String) = notifyAlert(
        id = NOTIFICATION_STOPPED,
        title = "Roadguard stopped recording",
        message = detail,
    )

    /**
     * Android closed Roadguard while it was recording. A camera cannot be reopened from the
     * background, so the best the app can do is make resuming one tap: the tap opens the app,
     * and a visible app may start the camera.
     */
    fun notifyResumePrompt() {
        val message = "Android closed Roadguard while it was recording. Tap to resume recording."
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("Recording was interrupted")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(resumeIntent)
            .addAction(R.drawable.ic_fiber_manual_record, "Resume recording", resumeIntent)
            .build()
        runCatching { manager?.notify(NOTIFICATION_RESUME, notification) }
    }

    fun notifyAlert(id: Int, title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        runCatching { manager?.notify(id, notification) }
    }

    fun cancelAlert(id: Int) {
        runCatching { manager?.cancel(id) }
    }

    private fun activityIntent(requestCode: Int, resume: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (resume) putExtra(MainActivity.EXTRA_RESUME_RECORDING, true)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL_RECORDING = "roadguard.recording"
        const val CHANNEL_ALERTS = "roadguard.alerts"

        const val NOTIFICATION_RECORDING = 1001
        const val NOTIFICATION_STORAGE = 1002
        const val NOTIFICATION_THERMAL = 1003
        const val NOTIFICATION_RESUME = 1004
        const val NOTIFICATION_INTERRUPTED = 1005
        const val NOTIFICATION_STOPPED = 1006

        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_PROTECT = 3
        private const val REQUEST_START = 4
        private const val REQUEST_RESUME = 5

        /**
         * What the ongoing notification should say for [state]. Pure, so what the driver is told
         * in every state is unit tested.
         */
        fun contentFor(state: RecordingUiState, storageSummary: String?): Content {
            val title = when (state.status) {
                RecorderStatus.Recording, RecorderStatus.RollingOver -> "Roadguard is recording"
                RecorderStatus.Starting -> "Roadguard is starting"
                RecorderStatus.Recovering -> "Recording interrupted - reconnecting"
                RecorderStatus.Stopping -> "Roadguard is stopping"
                RecorderStatus.Failed -> "Roadguard has stopped recording"
                RecorderStatus.Idle -> "Roadguard is not recording"
            }
            val detail = buildList {
                state.profile?.let { add(it.label) }
                storageSummary?.let { add(it) }
                if (state.thermalLevel != ThermalLevel.Normal) add("Temperature: ${state.thermalLevel.label}")
                if (state.batterySafe) add("Battery-safe mode")
                state.primaryBlocker?.let { add(it.message) }
                state.lastErrorMessage?.let { add(it) }
            }.distinct().joinToString(" - ")
            val icon = when (state.status) {
                RecorderStatus.Recording, RecorderStatus.RollingOver -> R.drawable.ic_fiber_manual_record
                RecorderStatus.Recovering -> R.drawable.ic_warning
                RecorderStatus.Failed -> R.drawable.ic_error
                RecorderStatus.Starting, RecorderStatus.Stopping, RecorderStatus.Idle -> R.drawable.ic_videocam
            }
            val actions = when {
                state.canProtect -> Actions.ProtectAndStop
                state.isSessionActive -> Actions.StopOnly
                state.status == RecorderStatus.Idle || state.status == RecorderStatus.Failed -> Actions.Record
                else -> Actions.None
            }
            return Content(
                title = title,
                text = detail,
                iconRes = icon,
                ongoing = state.isSessionActive,
                actions = actions,
            )
        }
    }
}
