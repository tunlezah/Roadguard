package io.github.tunlezah.roadguard.recording

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.storage.StorageAssessment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The foreground service that owns the camera.
 *
 * ### Why a service at all
 *
 * Android only lets an app hold the camera in the background if it is running a foreground
 * service of type `camera`, and that service must be promoted while the app is visible: the
 * visibility at promotion time is what grants the process its while-in-use camera capability for
 * the life of the service. Once promoted, the capability survives the Activity being destroyed
 * and the screen turning off -- screen state is not part of the camera-access policy. Roadguard
 * therefore starts this service from a resumed Activity and never from a broadcast receiver.
 *
 * ### Why the controller and not the service holds the logic
 *
 * The service is intentionally thin: channels, the notification, the wake lock, and forwarding
 * intents. All recording behaviour lives in [RecordingController], which outlives any single
 * service instance, so a service restart does not lose the session's state.
 *
 * ### Promotion
 *
 * Every start command is answered with a promotion, because every `startForegroundService` must
 * be. The types claimed are the ones the permissions allow ([ForegroundServiceTypes]), falling
 * back to camera-only rather than failing: a type without its permission makes `startForeground`
 * throw, and a service that cannot promote cannot record.
 *
 * ### After Android kills the process
 *
 * `START_STICKY` brings the service back with no intent, in the background, where the camera
 * cannot legally be reopened. It does not try. If a session was running when the process died it
 * posts a one-tap "resume recording" notification instead, and stops.
 *
 * ### Wake lock
 *
 * A camera foreground service keeps the *process* important but does not by itself keep the CPU
 * awake with the screen off, and neither the camera nor the encoder holds a wake lock. The service
 * holds a partial wake lock for exactly as long as [RecordingUiState.holdsWakeLock] says: from the
 * start of a session until its last file has been finalised, which is later than "recording" ends.
 *
 * ### Shutdown
 *
 * When the phone powers off, the file being written would be lost without its index. The service
 * listens for the shutdown broadcast and uses the few seconds Android allows to close it.
 */
class RecordingService : LifecycleService() {

    private val container: RoadguardContainer by lazy { RoadguardContainer.from(this) }
    private lateinit var notifications: RecordingNotifications
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewedAtMs = 0L

    /** The foreground-service types currently claimed, or null before the first promotion. */
    private var promotedTypes: Int? = null
    private var attached = false
    private var shutdownReceiverRegistered = false
    private var postedContent: RecordingNotifications.Content? = null
    private var alertedEpisode: Long? = null
    private var previousStatus: RecorderStatus = RecorderStatus.Idle

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SHUTDOWN) return
            // Hold the ordered broadcast open while the recorder closes its file; Android waits a
            // bounded time for shutdown receivers before it cuts the power.
            val pending = goAsync()
            container.applicationScope.launch {
                try {
                    container.recordingController.finalizeForShutdown(SHUTDOWN_FINALIZE_BUDGET_MS)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = RecordingNotifications(this)
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            onRestartedAfterKill()
            return START_NOT_STICKY
        }
        // Android 14 requires the notification within seconds of startForegroundService, and the
        // camera type must be claimed before the camera is touched.
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        attachIfNeeded()
        when (intent.action) {
            ACTION_START -> container.recordingController.start()
            ACTION_STOP -> container.recordingController.stop()
            ACTION_PROTECT -> container.recordingController.protectNow()
            // The promotion above has already re-read the types the permissions now allow.
            ACTION_REFRESH_TYPES -> Unit
            ACTION_SHUTDOWN -> {
                container.recordingController.stop()
                stopSelf()
            }
        }
        // START_STICKY so that if Android kills the process the service comes back and can at
        // least offer to resume; see onRestartedAfterKill.
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (shutdownReceiverRegistered) {
            runCatching { unregisterReceiver(shutdownReceiver) }
            shutdownReceiverRegistered = false
        }
        if (attached) container.recordingController.detach()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): android.os.IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * Restarted by Android, with no intent, after the process was killed. We are in the
     * background, where Android does not allow a camera to be reopened, so recording cannot simply
     * carry on. If a session was running, tell the driver it can be resumed with a tap.
     */
    private fun onRestartedAfterKill() {
        if (container.sessionJournal.takeInterruptedSession() != null) {
            Log.w(TAG, "restarted after the process was killed during a recording")
            notifications.notifyResumePrompt()
        }
        stopSelf()
    }

    /**
     * Makes this a foreground service with the types recording needs now.
     *
     * @return false only when not even the camera type could be claimed and the service was not
     *   already in the foreground.
     */
    private fun promoteToForeground(): Boolean {
        val wanted = desiredTypes()
        if (promotedTypes == wanted) return true
        val notification = notifications.build(
            container.recordingController.state.value,
            storageSummary(container.storageManager.assessment.value),
        )
        for (types in listOf(wanted, ForegroundServiceTypes.CAMERA_ONLY).distinct()) {
            try {
                ServiceCompat.startForeground(this, RecordingNotifications.NOTIFICATION_RECORDING, notification, types)
                promotedTypes = types
                if (types != wanted) Log.w(TAG, "in the foreground with the camera type only")
                return true
            } catch (error: Exception) {
                // The usual causes: a permission revoked since the check, or a start from the
                // background. Try the narrower claim before giving up.
                Log.e(TAG, "could not promote to a foreground service with types $types", error)
            }
        }
        return promotedTypes != null
    }

    private fun desiredTypes(): Int = ForegroundServiceTypes.forRecording(
        settings = container.settingsSnapshot(),
        locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        microphoneGranted = granted(Manifest.permission.RECORD_AUDIO),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun attachIfNeeded() {
        if (attached) return
        attached = true
        container.recordingController.attach(this)
        runCatching {
            registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN))
            shutdownReceiverRegistered = true
        }.onFailure { Log.w(TAG, "shutdown receiver registration failed", it) }

        lifecycleScope.launch {
            combine(
                container.recordingController.state,
                container.storageManager.assessment,
            ) { state, assessment -> state to assessment }
                .collect { (state, assessment) ->
                    updateWakeLock(state)
                    updateNotification(state, assessment)
                    updateAlerts(state)
                }
        }

        // Turning audio or location on, or granting their permission, needs the matching type
        // added. That is only allowed while the app is visible -- which is exactly when a setting
        // can be changed -- so re-promote then, and never from the background.
        lifecycleScope.launch {
            container.settings.collect {
                if (promotedTypes != null && desiredTypes() != promotedTypes && isAppVisible()) promoteToForeground()
            }
        }
    }

    private fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun updateNotification(state: RecordingUiState, assessment: StorageAssessment?) {
        if (promotedTypes == null) return
        val content = RecordingNotifications.contentFor(state, storageSummary(assessment))
        // Most state changes -- progress, counters -- change nothing a person would see.
        if (content == postedContent) return
        postedContent = content
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(RecordingNotifications.NOTIFICATION_RECORDING, notifications.build(content))
        }
    }

    private fun updateAlerts(state: RecordingUiState) {
        // Recovery that has lasted past a blip: say so once per episode, clear it when it is over.
        val episode = state.recoveringSinceElapsedMs
        if (state.recoveryOverdue && episode != null && alertedEpisode != episode) {
            alertedEpisode = episode
            notifications.notifyInterrupted(state.lastErrorMessage ?: "Recording is interrupted. Roadguard keeps trying.")
        } else if (episode == null && alertedEpisode != null) {
            alertedEpisode = null
            notifications.cancelAlert(RecordingNotifications.NOTIFICATION_INTERRUPTED)
        }

        val status = state.status
        if (status != previousStatus) {
            // A session that ended without anyone ending it -- a nearly flat battery, say.
            if (status == RecorderStatus.Failed && previousStatus != RecorderStatus.Idle) {
                notifications.notifyStopped(state.lastErrorMessage ?: "Recording stopped.")
            }
            if (state.isRecording) {
                notifications.cancelAlert(RecordingNotifications.NOTIFICATION_STOPPED)
                notifications.cancelAlert(RecordingNotifications.NOTIFICATION_RESUME)
            }
            previousStatus = status
        }
    }

    private fun storageSummary(assessment: StorageAssessment?): String? = assessment?.let {
        "${it.loopUsedBytes / (1024 * 1024)} MB of ${it.effectiveBudgetBytes / (1024 * 1024)} MB"
    }

    private fun updateWakeLock(state: RecordingUiState) {
        if (state.holdsWakeLock) acquireOrRenewWakeLock() else releaseWakeLock()
    }

    /**
     * Holds the wake lock, renewing its timeout while it is needed.
     *
     * The timeout is a safety net, not the mechanism: an untimed wake lock held by a crashed
     * component is a battery bug that outlives the bug that caused it. It is renewed well before
     * it can lapse, so a long drive never loses it.
     */
    private fun acquireOrRenewWakeLock() {
        val now = SystemClock.elapsedRealtime()
        val lock = wakeLock ?: run {
            val powerManager = getSystemService(PowerManager::class.java) ?: return
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        }
        if (lock.isHeld && now - wakeLockRenewedAtMs < WAKE_LOCK_RENEW_MS) return
        // For a non-reference-counted lock, acquiring again replaces the pending timeout.
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
        wakeLockRenewedAtMs = now
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    companion object {
        private const val TAG = "RoadguardService"
        private const val WAKE_LOCK_TAG = "Roadguard:recording"

        /** The wake lock's own timeout; renewed every [WAKE_LOCK_RENEW_MS] while it is needed. */
        const val WAKE_LOCK_TIMEOUT_MS = 60L * 60 * 1000

        const val WAKE_LOCK_RENEW_MS = 10L * 60 * 1000

        /**
         * How long a shutdown may spend closing the current file. Android waits about ten
         * seconds for all shutdown receivers together; finalising takes well under a second.
         */
        const val SHUTDOWN_FINALIZE_BUDGET_MS = 6_000L

        const val ACTION_START = "io.github.tunlezah.roadguard.action.START"
        const val ACTION_STOP = "io.github.tunlezah.roadguard.action.STOP"
        const val ACTION_PROTECT = "io.github.tunlezah.roadguard.action.PROTECT"
        const val ACTION_SHUTDOWN = "io.github.tunlezah.roadguard.action.SHUTDOWN"

        /** Re-reads which foreground-service types the permissions allow, for a visible app. */
        const val ACTION_REFRESH_TYPES = "io.github.tunlezah.roadguard.action.REFRESH_TYPES"

        /**
         * Starts the service.
         *
         * Must be called from a visible Activity: that is what latches the process's camera
         * capability. Roadguard's UI is the only caller.
         */
        fun start(context: Context, action: String = ACTION_START) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure { Log.e(TAG, "could not start the recording service", it) }
        }

        fun send(context: Context, action: String) {
            runCatching {
                context.startService(Intent(context, RecordingService::class.java).setAction(action))
            }.onFailure { Log.w(TAG, "could not deliver $action", it) }
        }
    }
}
