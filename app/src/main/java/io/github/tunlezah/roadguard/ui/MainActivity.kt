package io.github.tunlezah.roadguard.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.recording.RecordingService
import io.github.tunlezah.roadguard.settings.OrientationMode
import io.github.tunlezah.roadguard.settings.Settings as RoadguardSettings
import io.github.tunlezah.roadguard.ui.theme.RoadguardTheme
import kotlinx.coroutines.launch

/**
 * Roadguard's only activity.
 *
 * ### Why the activity is thin
 *
 * The camera and the recording loop belong to [RecordingService], not here. That is not tidiness:
 * Android grants a process its while-in-use camera capability at the moment a camera foreground
 * service is promoted *while the app is visible*, and once granted it survives this activity being
 * destroyed and the screen turning off. So the activity's real jobs are to be visible when the
 * service starts, to render state the recorder publishes, and to get out of the way.
 *
 * ### Orientation
 *
 * The requested orientation is applied at runtime from the user's setting rather than fixed in the
 * manifest. The default follows the *device*, including when the system rotation lock is on, because
 * a phone clipped into a landscape cradle should give a landscape UI and a landscape recording -- and
 * the recording's orientation is derived from the physical device orientation regardless of what the
 * window does.
 */
class MainActivity : ComponentActivity() {

    private val container: RoadguardContainer by lazy { RoadguardContainer.from(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Starting the service is only legal from a visible activity, so it is done here, after the
        // user has answered, rather than from a callback somewhere deeper in the app.
        if (granted[Manifest.permission.CAMERA] == true) startRecordingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by container.settings.collectAsState()
            ApplyWindowPolicy(settings)

            RoadguardTheme(
                themeSetting = settings.theme,
                useDynamicColour = settings.useDynamicColour,
            ) {
                RoadguardApp(
                    container = container,
                    onRequestCorePermissions = ::requestCorePermissions,
                    onRequestMicrophonePermission = ::requestMicrophonePermission,
                    onOpenAppSettings = ::openAppSettings,
                    onStartRecording = ::startRecordingService,
                    onStopRecording = { RecordingService.send(this, RecordingService.ACTION_STOP) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.mapRepository.refresh(container.settings.value.mapPackageId)
        container.weatherRepository.start()
        // The viewfinder is only worth producing frames for while the UI is on screen.
        container.recordingController.setPreviewEnabled(true)
    }

    override fun onPause() {
        super.onPause()
        // Detaching the preview surface stops preview frames and their GPU compositing without
        // touching the recording: the Preview use case stays bound, it simply has nowhere to draw.
        if (container.settingsSnapshot().screenOffDimming) {
            container.recordingController.setPreviewEnabled(false)
        }
        container.weatherRepository.stop()
    }

    /** Applies the orientation and screen-on policy from settings. */
    @Composable
    private fun ApplyWindowPolicy(settings: RoadguardSettings) {
        LaunchedEffect(settings.orientationMode) {
            requestedOrientation = when (settings.orientationMode) {
                // fullSensor rather than sensor: a cradle-mounted phone may sit at 180 degrees, and
                // it deliberately ignores the system rotation lock, which a dashcam should.
                OrientationMode.FollowDevice -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                OrientationMode.FollowSystem -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                OrientationMode.LockPortrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationMode.LockLandscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        LaunchedEffect(settings.keepScreenOn) {
            if (settings.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /**
     * Requests the permissions recording actually needs.
     *
     * The microphone is deliberately excluded: it is requested only when the user turns audio
     * recording on, so a user who never wants audio is never asked for it.
     */
    private fun requestCorePermissions() {
        val wanted = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            // minSdk 34: POST_NOTIFICATIONS is always a runtime permission here.
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isEmpty()) {
            startRecordingService()
        } else {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            lifecycleScope.launch {
                container.settingsRepository.update { it.copy(microphoneEnabled = true) }
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun startRecordingService() {
        RecordingService.start(this, RecordingService.ACTION_START)
    }

    /** Opens this app's system settings page, for a permanently denied permission. */
    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null)),
            )
        }.onFailure { if (it !is ActivityNotFoundException) throw it }
    }
}
