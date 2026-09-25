package io.github.tunlezah.roadguard.recording

import android.content.pm.ServiceInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.settings.Settings
import org.junit.Test

/**
 * [ForegroundServiceTypes] decides which foreground-service types the recording service claims.
 *
 * On Android 14 and later, claiming a type whose runtime permission is not held makes
 * `startForeground` throw, and a service that cannot promote cannot record. The service used to
 * claim location whenever the location *setting* was on -- the default -- so a driver who
 * declined location during setup got a recorder that never recorded a frame. These tests pin the
 * rule that replaced it: the camera always, the extras only with both the setting and the
 * permission.
 */
class ForegroundServiceTypesTest {

    private val camera = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    @Test
    fun `the camera type is always claimed`() {
        for (locationOn in listOf(true, false)) {
            for (microphoneOn in listOf(true, false)) {
                for (locationGranted in listOf(true, false)) {
                    for (microphoneGranted in listOf(true, false)) {
                        val types = ForegroundServiceTypes.forRecording(
                            Settings(locationEnabled = locationOn, microphoneEnabled = microphoneOn),
                            locationGranted = locationGranted,
                            microphoneGranted = microphoneGranted,
                        )
                        assertWithMessage("location=$locationOn/$locationGranted mic=$microphoneOn/$microphoneGranted")
                            .that(types and camera).isEqualTo(camera)
                    }
                }
            }
        }
    }

    @Test
    fun `location is claimed only when the setting is on and the permission is granted`() {
        fun claims(settingOn: Boolean, granted: Boolean) = ForegroundServiceTypes.forRecording(
            Settings(locationEnabled = settingOn),
            locationGranted = granted,
            microphoneGranted = false,
        ) and location != 0

        assertThat(claims(settingOn = true, granted = true)).isTrue()
        assertThat(claims(settingOn = true, granted = false)).isFalse()
        assertThat(claims(settingOn = false, granted = true)).isFalse()
        assertThat(claims(settingOn = false, granted = false)).isFalse()
    }

    @Test
    fun `the microphone is claimed only when audio is on and the permission is granted`() {
        fun claims(settingOn: Boolean, granted: Boolean) = ForegroundServiceTypes.forRecording(
            Settings(microphoneEnabled = settingOn),
            locationGranted = false,
            microphoneGranted = granted,
        ) and microphone != 0

        assertThat(claims(settingOn = true, granted = true)).isTrue()
        assertThat(claims(settingOn = true, granted = false)).isFalse()
        assertThat(claims(settingOn = false, granted = true)).isFalse()
        assertThat(claims(settingOn = false, granted = false)).isFalse()
    }

    @Test
    fun `the default settings with location declined claim the camera alone`() {
        // The case that used to fail every start: location on by default, permission declined.
        val types = ForegroundServiceTypes.forRecording(Settings(), locationGranted = false, microphoneGranted = false)
        assertThat(types).isEqualTo(ForegroundServiceTypes.CAMERA_ONLY)
    }

    @Test
    fun `everything granted and wanted claims all three types`() {
        val types = ForegroundServiceTypes.forRecording(
            Settings(locationEnabled = true, microphoneEnabled = true),
            locationGranted = true,
            microphoneGranted = true,
        )
        assertThat(types).isEqualTo(camera or location or microphone)
    }
}
