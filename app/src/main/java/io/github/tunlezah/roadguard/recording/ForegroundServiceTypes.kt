package io.github.tunlezah.roadguard.recording

import android.content.pm.ServiceInfo
import io.github.tunlezah.roadguard.settings.Settings

/**
 * The foreground-service types the recording service claims.
 *
 * ### Only what the permissions allow
 *
 * On Android 14 and later, `startForeground` throws a `SecurityException` for any type whose
 * runtime permission is not held. The service used to claim `location` whenever the location
 * *setting* was on -- which it is by default -- and `microphone` whenever audio was switched on,
 * without looking at the permissions. A driver who declined location during setup (setup lets
 * them), or who later revoked the microphone in system settings, therefore got a service that
 * failed to start, stopped itself, and never recorded a frame, every time.
 *
 * So each optional type is claimed only when both the setting wants it and its permission is
 * granted, and the service falls back to [CAMERA_ONLY] if even that is refused. Recording needs
 * the camera and nothing else; location and audio are extras it can do without.
 */
object ForegroundServiceTypes {

    /** The one type recording cannot do without. */
    const val CAMERA_ONLY: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

    fun forRecording(settings: Settings, locationGranted: Boolean, microphoneGranted: Boolean): Int {
        var types = CAMERA_ONLY
        if (settings.locationEnabled && locationGranted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (settings.microphoneEnabled && microphoneGranted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }
}
