package io.github.tunlezah.roadguard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip tests for [SettingsRepository] against a real Preferences DataStore.
 *
 * The contract pinned down here is that what the user chose is what the recorder gets back after
 * a process death: a fresh install reads the shipped defaults, every stored type survives a
 * write/read cycle, clearing the chosen storage volume really removes it, and -- critically for a
 * dashcam -- [SettingsRepository.validate] runs on the *write* path, so a value that is out of
 * range can never be sitting on disk waiting to be handed to the encoder on the next boot.
 *
 * Robolectric gives every test method a fresh application data directory, but the
 * `preferencesDataStore` property delegate in SettingsRepository.kt caches its DataStore in a
 * JVM-static field that Robolectric does *not* reset. Without [resetDataStoreSingleton] below,
 * the second test in this class would read the first test's in-memory cache (verified: writing
 * `microphoneEnabled = true` in one test made the next test observe `true` on a supposedly fresh
 * store). Nulling that cached instance before each test makes every test genuinely start from an
 * empty store and keeps the class order-independent. No production code is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        resetDataStoreSingleton()
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
    }

    /**
     * Drops the DataStore cached by the `by preferencesDataStore(...)` delegate so the next read
     * builds a new one against this test's data directory. Deliberately loud if the internals
     * move: a silent failure here would make these tests share state again.
     */
    private fun resetDataStoreSingleton() {
        val loader = SettingsRepository::class.java.classLoader!!
        val facade = Class.forName("io.github.tunlezah.roadguard.settings.SettingsRepositoryKt", true, loader)
        val delegateField = facade.getDeclaredField("settingsDataStore\$delegate")
        delegateField.isAccessible = true
        val delegate = requireNotNull(delegateField.get(null)) { "settingsDataStore delegate missing" }
        val instanceField = delegate.javaClass.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        instanceField.set(delegate, null)
    }

    // ── Fresh install ─────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh repository emits exactly the shipped defaults`() = runTest {
        assertThat(repository.settings.first()).isEqualTo(Settings())
    }

    // ── One round trip per stored preference type ─────────────────────────────────────

    @Test
    fun `update round-trips a string-backed enum`() = runTest {
        repository.update {
            it.copy(
                quality = QualitySetting.Uhd2160p,
                speedUnit = SpeedUnit.MilesPerHour,
                gpsStorage = GpsStorageMode.All,
            )
        }
        val stored = repository.settings.first()
        assertThat(stored.quality).isEqualTo(QualitySetting.Uhd2160p)
        assertThat(stored.speedUnit).isEqualTo(SpeedUnit.MilesPerHour)
        assertThat(stored.gpsStorage).isEqualTo(GpsStorageMode.All)
    }

    @Test
    fun `update round-trips booleans in both directions`() = runTest {
        // microphoneEnabled defaults to false, keepScreenOn defaults to true: flip both so a
        // "boolean absent means default" bug cannot pass by accident.
        assertThat(Settings().microphoneEnabled).isFalse()
        assertThat(Settings().keepScreenOn).isTrue()

        repository.update { it.copy(microphoneEnabled = true, keepScreenOn = false) }

        val stored = repository.settings.first()
        assertThat(stored.microphoneEnabled).isTrue()
        assertThat(stored.keepScreenOn).isFalse()
    }

    @Test
    fun `update round-trips an int`() = runTest {
        repository.update { it.copy(startupDelaySeconds = 12) }
        assertThat(repository.settings.first().startupDelaySeconds).isEqualTo(12)
    }

    @Test
    fun `update round-trips a long`() = runTest {
        val twentyGib = 20L * 1024 * 1024 * 1024
        repository.update { it.copy(loopBudgetBytes = twentyGib) }
        assertThat(repository.settings.first().loopBudgetBytes).isEqualTo(twentyGib)
    }

    @Test
    fun `update round-trips a float`() = runTest {
        repository.update { it.copy(recordingZoom = 2.5f) }
        assertThat(repository.settings.first().recordingZoom).isEqualTo(2.5f)
    }

    @Test
    fun `update round-trips a nullable string`() = runTest {
        assertThat(Settings().storageVolumeId).isNull()
        repository.update { it.copy(storageVolumeId = "sdcard-42") }
        assertThat(repository.settings.first().storageVolumeId).isEqualTo("sdcard-42")
    }

    @Test
    fun `update round-trips every field at once`() = runTest {
        val wanted = Settings(
            quality = QualitySetting.Hd720p,
            frameRate = FrameRateSetting.Fps60,
            segmentLength = SegmentLength.Minutes10,
            cameraFacing = CameraFacing.Front,
            dualCameraEnabled = true,
            videoStabilisation = TriState.Off,
            nightAssist = TriState.On,
            recordingZoom = 3.75f,
            microphoneEnabled = true,
            previewZoom = PreviewZoom.X1_75,
            mapVisible = false,
            theme = ThemeSetting.Oled,
            useDynamicColour = true,
            orientationMode = OrientationMode.LockLandscape,
            keepScreenOn = false,
            screenOffDimming = false,
            overlayDateTime = false,
            overlaySpeed = false,
            overlayCoordinates = true,
            overlayWeather = true,
            autoStartRecording = false,
            startupDelaySeconds = 7,
            eventDetectionEnabled = false,
            eventSensitivity = EventSensitivity.High,
            preEventSeconds = 15,
            postEventSeconds = 90,
            loopBudgetBytes = 30L * 1024 * 1024 * 1024,
            protectedWarningBytes = 3L * 1024 * 1024 * 1024,
            storageVolumeId = "usb-otg-0",
            locationEnabled = false,
            speedUnit = SpeedUnit.MilesPerHour,
            gpsStorage = GpsStorageMode.TrackOnly,
            onPowerConnected = PowerConnectedAction.Prompt,
            onPowerDisconnected = PowerDisconnectedAction.StopAfterDelay,
            powerDisconnectStopDelaySeconds = 45,
            batterySafeThresholdPercent = 40,
            weatherEnabled = true,
            mapFollowsVehicle = false,
            mapNorthUp = true,
            mapAutoDownload = false,
            setupComplete = true,
            acceptedRecordingDisclaimer = true,
        )
        // Everything in `wanted` is already in range, so nothing should be rewritten.
        assertThat(SettingsRepository.validate(wanted)).isEqualTo(wanted)

        repository.update { wanted }

        assertThat(repository.settings.first()).isEqualTo(wanted)
    }

    // ── validate runs on the write path ───────────────────────────────────────────────

    @Test
    fun `an out-of-range update is stored already clamped`() = runTest {
        val hostile = Settings(
            recordingZoom = 25.0f,
            startupDelaySeconds = 9_000,
            preEventSeconds = -30,
            postEventSeconds = 4_000,
            loopBudgetBytes = 1L,
            batterySafeThresholdPercent = 300,
            powerDisconnectStopDelaySeconds = -5,
        )
        // The value type itself does no clamping, so anything clamped below came from the
        // repository...
        assertThat(hostile.recordingZoom).isEqualTo(25.0f)
        assertThat(hostile.loopBudgetBytes).isEqualTo(1L)

        repository.update { hostile }

        // ...and Preferences.toSettings() does no clamping either, so a clamped read proves
        // validate() ran before the bytes hit the store.
        val stored = repository.settings.first()
        assertThat(stored.recordingZoom).isEqualTo(8.0f)
        assertThat(stored.startupDelaySeconds).isEqualTo(STARTUP_DELAY_RANGE.last)
        assertThat(stored.preEventSeconds).isEqualTo(0)
        assertThat(stored.postEventSeconds).isEqualTo(300)
        assertThat(stored.loopBudgetBytes).isEqualTo(LoopBudget.MIN_BYTES)
        assertThat(stored.batterySafeThresholdPercent).isEqualTo(100)
        assertThat(stored.powerDisconnectStopDelaySeconds).isEqualTo(0)
        assertThat(stored).isEqualTo(SettingsRepository.validate(hostile))
    }

    // ── Removal of the nullable key ───────────────────────────────────────────────────

    @Test
    fun `setting storageVolumeId to null removes it and reads back as null`() = runTest {
        repository.update { it.copy(storageVolumeId = "sdcard-42") }
        assertThat(repository.settings.first().storageVolumeId).isEqualTo("sdcard-42")

        repository.update { it.copy(storageVolumeId = null) }

        val cleared = repository.settings.first()
        assertThat(cleared.storageVolumeId).isNull()
        // Clearing the volume must not disturb anything else.
        assertThat(cleared).isEqualTo(Settings())
    }

    // ── Sequencing and single source of truth ─────────────────────────────────────────

    @Test
    fun `a later update sees the value written by an earlier one`() = runTest {
        repository.update { it.copy(preEventSeconds = 45, microphoneEnabled = true) }

        val seenByTransform = mutableListOf<Settings>()
        repository.update { current ->
            seenByTransform += current
            current.copy(postEventSeconds = 120)
        }

        assertThat(seenByTransform).hasSize(1)
        assertThat(seenByTransform.single().preEventSeconds).isEqualTo(45)
        assertThat(seenByTransform.single().microphoneEnabled).isTrue()

        val stored = repository.settings.first()
        assertThat(stored.preEventSeconds).isEqualTo(45)
        assertThat(stored.microphoneEnabled).isTrue()
        assertThat(stored.postEventSeconds).isEqualTo(120)
    }

    @Test
    fun `another repository over the same context observes the write`() = runTest {
        repository.update { it.copy(segmentLength = SegmentLength.Minutes1, setupComplete = true) }

        val other = SettingsRepository(context)
        val stored = other.settings.first()
        assertThat(stored.segmentLength).isEqualTo(SegmentLength.Minutes1)
        assertThat(stored.setupComplete).isTrue()
    }
}
