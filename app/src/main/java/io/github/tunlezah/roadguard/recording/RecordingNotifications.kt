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
 * The foreground-service notification, and the two alerts Roadguard is allowed to raise.
 *
 * The recording notification is deliberately quiet: no sound, no vibration, minimum importance
 * that still keeps it visible, and no progress bar. It exists because Android requires it and
 * because it is the user's only proof that recording is still running when the screen is off --
 * so it always states the truth, including when recording has *stopped*.
 *
 * Only two things ever interrupt: storage that can no longer be freed, and thermal conditions
 * that forced a change. Both are things a driver would want to know at the next set of lights.
 */
class RecordingNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

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
            description = "Storage and temperature warnings that affect recording."
            setShowBadge(true)
        }
        manager?.createNotificationChannel(recording)
        manager?.createNotificationChannel(alerts)
    }

    fun build(state: RecordingUiState, storageSummary: String?): android.app.Notification {
        val title = when (state.status) {
            RecorderStatus.Recording, RecorderStatus.RollingOver -> "Roadguard is recording"
            RecorderStatus.Starting -> "Roadguard is starting"
            RecorderStatus.Stopping -> "Roadguard is stopping"
            RecorderStatus.Failed -> "Roadguard has stopped recording"
            RecorderStatus.Idle -> "Roadguard is not recording"
        }
        val detail = buildList {
            state.profile?.let { add(it.label) }
            storageSummary?.let { add(it) }
            if (state.thermalLevel != ThermalLevel.Normal) add("Temperature: ${state.thermalLevel.label}")
            state.primaryBlocker?.let { add(it.message) }
            state.lastErrorMessage?.let { add(it) }
        }.joinToString(" - ")

        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(iconFor(state.status))
            .setContentTitle(title)
            .setContentText(detail.ifEmpty { "Tap to open Roadguard" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(state.isRecording)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())

        if (state.isRecording) {
            builder.addAction(
                R.drawable.ic_lock,
                "Protect",
                servicePendingIntent(RecordingService.ACTION_PROTECT, REQUEST_PROTECT),
            )
            builder.addAction(
                R.drawable.ic_stop,
                "Stop",
                servicePendingIntent(RecordingService.ACTION_STOP, REQUEST_STOP),
            )
        } else if (state.status == RecorderStatus.Idle || state.status == RecorderStatus.Failed) {
            builder.addAction(
                R.drawable.ic_fiber_manual_record,
                "Record",
                servicePendingIntent(RecordingService.ACTION_START, REQUEST_START),
            )
        }
        return builder.build()
    }

    fun notifyAlert(id: Int, title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { manager?.notify(id, notification) }
    }

    fun cancelAlert(id: Int) {
        runCatching { manager?.cancel(id) }
    }

    private fun iconFor(status: RecorderStatus): Int = when (status) {
        RecorderStatus.Recording, RecorderStatus.RollingOver -> R.drawable.ic_fiber_manual_record
        RecorderStatus.Failed -> R.drawable.ic_error
        else -> R.drawable.ic_videocam
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
        const val NOTIFICATION_RESUME_AFTER_BOOT = 1004

        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_PROTECT = 3
        private const val REQUEST_START = 4
    }
}
