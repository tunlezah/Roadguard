package io.github.tunlezah.roadguard.overlay

import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.event.BrakeLevel
import io.github.tunlezah.roadguard.location.FixQuality
import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.settings.GpsStorageMode
import io.github.tunlezah.roadguard.settings.Settings
import org.junit.Test

/**
 * The brake light is GPS-derived, so it must obey exactly the gates the speed field obeys:
 * a user who said "no GPS in the overlay", or turned the speed overlay off, gets no light.
 */
class OverlayComposerTest {

    private val composer = OverlayComposer()
    private val moving = LocationState(
        quality = FixQuality.Good,
        latitude = -33.86882,
        longitude = 151.20930,
        speedMetresPerSecond = 16.7f,
        accuracyMetres = 5f,
        permissionGranted = true,
    )

    private fun compose(settings: Settings, brake: BrakeLevel?) = composer.compose(
        settings = settings,
        location = moving,
        weather = null,
        nowEpochMs = 1_756_000_000_000L,
        brake = brake,
    )

    @Test
    fun `braking lights the overlay LED under default settings`() {
        val content = compose(Settings(), BrakeLevel.Braking)
        assertThat(content.brake).isEqualTo(BrakeLevel.Braking)
        assertThat(content.isEmpty).isFalse()
    }

    @Test
    fun `no braking means no LED`() {
        assertThat(compose(Settings(), null).brake).isNull()
    }

    @Test
    fun `the LED obeys the GPS storage gate`() {
        for (mode in GpsStorageMode.entries) {
            val content = compose(Settings(gpsStorage = mode), BrakeLevel.HardBraking)
            if (mode.overlay) {
                assertThat(content.brake).isEqualTo(BrakeLevel.HardBraking)
            } else {
                assertThat(content.brake).isNull()
            }
        }
    }

    @Test
    fun `the LED obeys the speed overlay toggle`() {
        val content = compose(Settings(overlaySpeed = false), BrakeLevel.HardBraking)
        assertThat(content.brake).isNull()
        assertThat(content.speedText).isNull()
    }
}
