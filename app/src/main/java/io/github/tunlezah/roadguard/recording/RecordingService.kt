package io.github.tunlezah.roadguard.recording

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.tunlezah.roadguard.core.RoadguardContainer
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
 * ### Wake lock
 *
 * A camera foreground service keeps the *process* important but does not by itself keep the CPU
 * awake with the screen off. Video-only capture therefore holds a partial wake lock for exactly
 * as long as it is recording. Audio capture would hold one implicitly, but Roadguard records
 * without audio by default, so it cannot rely on that.
 */
class RecordingService : LifecycleService() {

    private val container: RoadguardContainer by lazy { RoadguardContainer.from(this) }
    private lateinit var notifications: RecordingNotifications
    private var wakeLock: PowerManager.WakeLock? = null
    private var promoted = false

    override fun onCreate() {
        super.onCreate()
        notifications = RecordingNotifications(this)
        notifications.ensureChannels()

        // Promote immediately: Android 14 requires a foreground service to post its notification
        // within a few seconds of being started, and the camera type must be claimed up front.
        promoteToForeground()

        container.recordingController.attach(this)

        lifecycleScope.launch {
            container.recordingController.state.collect { state ->
                if (promoted) {
                    val summary = container.storageManager.assessment.value?.let { assessment ->
                        "${assessment.loopUsedBytes / (1024 * 1024)} MB of " +
                            "${assessment.effectiveBudgetBytes / (1024 * 1024)} MB"
                    }
                    runCatching {
                        notifications.let { presenter ->
                            getSystemService(android.app.NotificationManager::class.java)
                                ?.notify(
                                    RecordingNotifications.NOTIFICATION_RECORDING,
                                    presenter.build(state, summary),
                                )
                        }
                    }
                }
                if (state.isRecording) acquireWakeLock() else releaseWakeLock()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> container.recordingController.start()
            ACTION_STOP -> container.recordingController.stop()
            ACTION_PROTECT -> container.recordingController.protectNow()
            ACTION_SHUTDOWN -> {
                container.recordingController.stop()
                stopSelf()
            }
        }
        // START_STICKY so that if Android kills the service under memory pressure it is
        // recreated; the controller then re-evaluates whether it should be recording.
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        container.recordingController.detach()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun promoteToForeground() {
        val state = container.recordingController.state.value
        val notification = notifications.build(state, null)
        val types = buildForegroundTypes()
        runCatching {
            ServiceCompat.startForeground(
                this,
                RecordingNotifications.NOTIFICATION_RECORDING,
                notification,
                types,
            )
            promoted = true
        }.onFailure { throwable ->
            // The commonest cause is being started from the background. Roadguard always starts
            // this service from a visible Activity, so this is logged loudly rather than hidden.
            Log.e(TAG, "could not promote to a foreground service", throwable)
            stopSelf()
        }
    }

    /**
     * The foreground service type bitmask.
     *
     * `microphone` is only claimed when audio recording is actually enabled: claiming a type the
     * app is not using is both a policy problem and an unnecessary privacy signal to the user.
     */
    private fun buildForegroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (container.settingsSnapshot().locationEnabled) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (container.settingsSnapshot().microphoneEnabled) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "RoadguardService"
        private const val WAKE_LOCK_TAG = "Roadguard:recording"

        /**
         * A timeout on the wake lock, renewed while recording.
         *
         * An untimed wake lock held by a crashed component is a battery bug that outlives the
         * bug that caused it; twelve hours is longer than any plausible drive and still bounded.
         */
        const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000

        const val ACTION_START = "io.github.tunlezah.roadguard.action.START"
        const val ACTION_STOP = "io.github.tunlezah.roadguard.action.STOP"
        const val ACTION_PROTECT = "io.github.tunlezah.roadguard.action.PROTECT"
        const val ACTION_SHUTDOWN = "io.github.tunlezah.roadguard.action.SHUTDOWN"

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
