package io.github.tunlezah.roadguard.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Contract tests for the settings *value* layer: the shipped defaults, the clamping done by
 * [SettingsRepository.validate], the overlay short-circuit, the [GpsStorageMode] flag table and
 * the preset lists.
 *
 * These are deliberately brittle. A dashcam is a witness device: the defaults decide what an
 * un-configured phone actually captures after a crash, and the clamps are the only thing standing
 * between a corrupt or hand-edited preference file and a recorder that is asked to keep a negative
 * pre-event buffer, a zero-byte loop budget or an 800x zoom. If somebody changes one of these
 * numbers they must do it by editing this test on purpose, not by accident.
 *
 * Pure JVM -- no Android types, no Robolectric.
 */
class SettingsValidationTest {

    // ── Defaults fixed by the product specification ───────────────────────────────────

    @Test
    fun `recording defaults are Auto so the device profile decides, never a hard-coded model`() {
        val defaults = Settings()
        assertThat(defaults.quality).isEqualTo(QualitySetting.Auto)
        assertThat(defaults.frameRate).isEqualTo(FrameRateSetting.Auto)
        // Auto carries no concrete fps: the runtime profile must resolve it.
        assertThat(defaults.frameRate.fps).isNull()
        // Auto must be the *first* entry so a UI that falls back to the first option is
        // still safe, and so ordinal-based persistence would not silently pick a fixed mode.
        assertThat(QualitySetting.entries.first()).isEqualTo(QualitySetting.Auto)
        assertThat(FrameRateSetting.entries.first()).isEqualTo(FrameRateSetting.Auto)
    }

    @Test
    fun `default segment length is three minutes`() {
        val defaults = Settings()
        assertThat(defaults.segmentLength).isEqualTo(SegmentLength.Minutes3)
        assertThat(defaults.segmentLength.seconds).isEqualTo(180)
    }

    @Test
    fun `default loop budget is exactly five GiB`() {
        assertThat(Settings().loopBudgetBytes).isEqualTo(5L * 1024 * 1024 * 1024)
        assertThat(Settings().loopBudgetBytes).isEqualTo(5_368_709_120L)
    }

    @Test
    fun `default event protection keeps thirty seconds before and sixty after`() {
        val defaults = Settings()
        assertThat(defaults.preEventSeconds).isEqualTo(30)
        assertThat(defaults.postEventSeconds).isEqualTo(60)
    }

    @Test
    fun `microphone is off by default`() {
        assertThat(Settings().microphoneEnabled).isFalse()
    }

    @Test
    fun `preview zoom defaults to Auto and recording zoom to one point zero`() {
        val defaults = Settings()
        assertThat(defaults.previewZoom).isEqualTo(PreviewZoom.Auto)
        assertThat(defaults.previewZoom.factor).isNull()
        // Recording zoom permanently narrows the recorded field of view, so it must start at 1.0x.
        assertThat(defaults.recordingZoom).isEqualTo(1.0f)
    }

    @Test
    fun `dual camera is off by default`() {
        assertThat(Settings().dualCameraEnabled).isFalse()
    }

    @Test
    fun `default speed unit is kilometres per hour`() {
        val defaults = Settings()
        assertThat(defaults.speedUnit).isEqualTo(SpeedUnit.KilometresPerHour)
        assertThat(defaults.speedUnit.suffix).isEqualTo("km/h")
        assertThat(defaults.speedUnit.fromMetresPerSecond).isEqualTo(3.6f)
    }

    @Test
    fun `auto start recording is on with a three second startup delay`() {
        val defaults = Settings()
        assertThat(defaults.autoStartRecording).isTrue()
        assertThat(defaults.startupDelaySeconds).isEqualTo(3)
        assertThat(STARTUP_DELAY_RANGE.contains(defaults.startupDelaySeconds)).isTrue()
    }

    @Test
    fun `setup complete and weather are off on a fresh install`() {
        val defaults = Settings()
        assertThat(defaults.setupComplete).isFalse()
        assertThat(defaults.weatherEnabled).isFalse()
    }

    @Test
    fun `validate is a no-op on the shipped defaults`() {
        // Every default must already satisfy every clamp, otherwise the app boots into a
        // state it immediately rewrites.
        assertThat(SettingsRepository.validate(Settings())).isEqualTo(Settings())
    }

    // ── validate: numeric clamping, boundary by boundary ──────────────────────────────

    private fun clampedZoom(value: Float): Float =
        SettingsRepository.validate(Settings(recordingZoom = value)).recordingZoom

    @Test
    fun `validate clamps recording zoom into one to eight`() {
        val cases: List<Triple<String, Float, Float>> = listOf(
            Triple("zero", 0.0f, 1.0f),
            Triple("negative", -4.0f, 1.0f),
            Triple("just below lower bound", 0.99f, 1.0f),
            Triple("lower bound", 1.0f, 1.0f),
            Triple("inside range", 2.5f, 2.5f),
            Triple("upper bound", 8.0f, 8.0f),
            Triple("just above upper bound", 8.01f, 8.0f),
            Triple("far above upper bound", 1000.0f, 8.0f),
        )
        for ((name, input, expected) in cases) {
            assertWithMessage("recordingZoom %s (%s)", name, input)
                .that(clampedZoom(input))
                .isEqualTo(expected)
        }
    }

    private fun clampedStartupDelay(value: Int): Int =
        SettingsRepository.validate(Settings(startupDelaySeconds = value)).startupDelaySeconds

    @Test
    fun `validate clamps startup delay into STARTUP_DELAY_RANGE`() {
        val low = STARTUP_DELAY_RANGE.first
        val high = STARTUP_DELAY_RANGE.last
        assertThat(low).isEqualTo(0)
        assertThat(high).isEqualTo(30)

        assertThat(clampedStartupDelay(low - 1)).isEqualTo(low)
        assertThat(clampedStartupDelay(Int.MIN_VALUE)).isEqualTo(low)
        assertThat(clampedStartupDelay(low)).isEqualTo(low)
        assertThat(clampedStartupDelay(10)).isEqualTo(10)
        assertThat(clampedStartupDelay(high)).isEqualTo(high)
        assertThat(clampedStartupDelay(high + 1)).isEqualTo(high)
        assertThat(clampedStartupDelay(Int.MAX_VALUE)).isEqualTo(high)
    }

    private fun clampedPreEvent(value: Int): Int =
        SettingsRepository.validate(Settings(preEventSeconds = value)).preEventSeconds

    @Test
    fun `validate clamps pre-event seconds into zero to one hundred and twenty`() {
        assertThat(clampedPreEvent(-1)).isEqualTo(0)
        assertThat(clampedPreEvent(Int.MIN_VALUE)).isEqualTo(0)
        assertThat(clampedPreEvent(0)).isEqualTo(0)
        assertThat(clampedPreEvent(45)).isEqualTo(45)
        assertThat(clampedPreEvent(120)).isEqualTo(120)
        assertThat(clampedPreEvent(121)).isEqualTo(120)
        assertThat(clampedPreEvent(Int.MAX_VALUE)).isEqualTo(120)
    }

    private fun clampedPostEvent(value: Int): Int =
        SettingsRepository.validate(Settings(postEventSeconds = value)).postEventSeconds

    @Test
    fun `validate clamps post-event seconds into zero to three hundred`() {
        assertThat(clampedPostEvent(-1)).isEqualTo(0)
        assertThat(clampedPostEvent(Int.MIN_VALUE)).isEqualTo(0)
        assertThat(clampedPostEvent(0)).isEqualTo(0)
        assertThat(clampedPostEvent(90)).isEqualTo(90)
        assertThat(clampedPostEvent(300)).isEqualTo(300)
        assertThat(clampedPostEvent(301)).isEqualTo(300)
        assertThat(clampedPostEvent(Int.MAX_VALUE)).isEqualTo(300)
    }

    private fun clampedLoopBudget(value: Long): Long =
        SettingsRepository.validate(Settings(loopBudgetBytes = value)).loopBudgetBytes

    @Test
    fun `validate raises the loop budget to at least LoopBudget MIN_BYTES`() {
        val min = LoopBudget.MIN_BYTES
        assertThat(min).isEqualTo(512L * 1024 * 1024)

        assertThat(clampedLoopBudget(0L)).isEqualTo(min)
        assertThat(clampedLoopBudget(-1L)).isEqualTo(min)
        assertThat(clampedLoopBudget(Long.MIN_VALUE)).isEqualTo(min)
        assertThat(clampedLoopBudget(min - 1)).isEqualTo(min)
        assertThat(clampedLoopBudget(min)).isEqualTo(min)
        // Inside the range the value passes through untouched; there is no upper clamp.
        assertThat(clampedLoopBudget(min + 1)).isEqualTo(min + 1)
        assertThat(clampedLoopBudget(10L * 1024 * 1024 * 1024)).isEqualTo(10L * 1024 * 1024 * 1024)
    }

    private fun clampedBatteryThreshold(value: Int): Int =
        SettingsRepository.validate(
            Settings(batterySafeThresholdPercent = value),
        ).batterySafeThresholdPercent

    @Test
    fun `validate clamps battery safe threshold into a percentage`() {
        assertThat(clampedBatteryThreshold(-1)).isEqualTo(0)
        assertThat(clampedBatteryThreshold(Int.MIN_VALUE)).isEqualTo(0)
        assertThat(clampedBatteryThreshold(0)).isEqualTo(0)
        assertThat(clampedBatteryThreshold(15)).isEqualTo(15)
        assertThat(clampedBatteryThreshold(100)).isEqualTo(100)
        assertThat(clampedBatteryThreshold(101)).isEqualTo(100)
        assertThat(clampedBatteryThreshold(Int.MAX_VALUE)).isEqualTo(100)
    }

    private fun clampedStopDelay(value: Int): Int =
        SettingsRepository.validate(
            Settings(powerDisconnectStopDelaySeconds = value),
        ).powerDisconnectStopDelaySeconds

    @Test
    fun `validate clamps power disconnect stop delay into zero to one hour`() {
        assertThat(clampedStopDelay(-1)).isEqualTo(0)
        assertThat(clampedStopDelay(Int.MIN_VALUE)).isEqualTo(0)
        assertThat(clampedStopDelay(0)).isEqualTo(0)
        assertThat(clampedStopDelay(300)).isEqualTo(300)
        assertThat(clampedStopDelay(3600)).isEqualTo(3600)
        assertThat(clampedStopDelay(3601)).isEqualTo(3600)
        assertThat(clampedStopDelay(Int.MAX_VALUE)).isEqualTo(3600)
    }

    @Test
    fun `validate leaves non-numeric settings untouched`() {
        val hostile = Settings(
            quality = QualitySetting.Uhd2160p,
            frameRate = FrameRateSetting.Fps60,
            microphoneEnabled = true,
            speedUnit = SpeedUnit.MilesPerHour,
            gpsStorage = GpsStorageMode.All,
            storageVolumeId = "sdcard-1",
            recordingZoom = 99.0f,
        )
        val validated = SettingsRepository.validate(hostile)
        assertThat(validated.quality).isEqualTo(QualitySetting.Uhd2160p)
        assertThat(validated.frameRate).isEqualTo(FrameRateSetting.Fps60)
        assertThat(validated.microphoneEnabled).isTrue()
        assertThat(validated.speedUnit).isEqualTo(SpeedUnit.MilesPerHour)
        assertThat(validated.gpsStorage).isEqualTo(GpsStorageMode.All)
        assertThat(validated.storageVolumeId).isEqualTo("sdcard-1")
        // ...while still clamping the one numeric field that was out of range.
        assertThat(validated.recordingZoom).isEqualTo(8.0f)
    }

    @Test
    fun `validate is idempotent`() {
        val once = SettingsRepository.validate(
            Settings(
                recordingZoom = -3f,
                startupDelaySeconds = 900,
                preEventSeconds = -10,
                postEventSeconds = 9_000,
                loopBudgetBytes = 1L,
                batterySafeThresholdPercent = 250,
                powerDisconnectStopDelaySeconds = -60,
            ),
        )
        assertThat(SettingsRepository.validate(once)).isEqualTo(once)
    }

    // ── anyVideoOverlayEnabled ────────────────────────────────────────────────────────

    private val noOverlays = Settings(
        overlayDateTime = false,
        overlaySpeed = false,
        overlayCoordinates = false,
        overlayWeather = false,
    )

    @Test
    fun `anyVideoOverlayEnabled is false only when every overlay flag is off`() {
        assertThat(noOverlays.anyVideoOverlayEnabled).isFalse()
    }

    @Test
    fun `anyVideoOverlayEnabled is true when any single overlay flag is on`() {
        val singleFlagCases: List<Pair<String, Settings>> = listOf(
            "overlayDateTime" to noOverlays.copy(overlayDateTime = true),
            "overlaySpeed" to noOverlays.copy(overlaySpeed = true),
            "overlayCoordinates" to noOverlays.copy(overlayCoordinates = true),
            "overlayWeather" to noOverlays.copy(overlayWeather = true),
        )
        for ((flag, settings) in singleFlagCases) {
            assertWithMessage("only %s set", flag)
                .that(settings.anyVideoOverlayEnabled)
                .isTrue()
        }
    }

    @Test
    fun `anyVideoOverlayEnabled is true when all four overlay flags are on`() {
        val everything = noOverlays.copy(
            overlayDateTime = true,
            overlaySpeed = true,
            overlayCoordinates = true,
            overlayWeather = true,
        )
        assertThat(everything.anyVideoOverlayEnabled).isTrue()
    }

    @Test
    fun `the shipped defaults require a burn-in pass because date-time and speed are on`() {
        val defaults = Settings()
        assertThat(defaults.overlayDateTime).isTrue()
        assertThat(defaults.overlaySpeed).isTrue()
        assertThat(defaults.overlayCoordinates).isFalse()
        assertThat(defaults.overlayWeather).isFalse()
        assertThat(defaults.anyVideoOverlayEnabled).isTrue()
    }

    // ── GpsStorageMode flag table ─────────────────────────────────────────────────────

    /** The (overlay, metadata, track) triple each mode's name promises. */
    private val expectedGpsFlags: Map<GpsStorageMode, Triple<Boolean, Boolean, Boolean>> = mapOf(
        GpsStorageMode.None to Triple(false, false, false),
        GpsStorageMode.OverlayOnly to Triple(true, false, false),
        GpsStorageMode.MetadataOnly to Triple(false, true, false),
        GpsStorageMode.TrackOnly to Triple(false, false, true),
        GpsStorageMode.OverlayAndMetadata to Triple(true, true, false),
        GpsStorageMode.All to Triple(true, true, true),
    )

    @Test
    fun `every GpsStorageMode entry has a declared expectation so a new mode cannot slip in`() {
        assertThat(expectedGpsFlags.keys).containsExactlyElementsIn(GpsStorageMode.entries)
    }

    @Test
    fun `every GpsStorageMode entry exposes the flags its name promises`() {
        for (mode in GpsStorageMode.entries) {
            val expected = requireNotNull(expectedGpsFlags[mode]) {
                "No expected flag triple declared for GpsStorageMode.$mode"
            }
            assertWithMessage("%s.overlay", mode.name).that(mode.overlay).isEqualTo(expected.first)
            assertWithMessage("%s.metadata", mode.name).that(mode.metadata).isEqualTo(expected.second)
            assertWithMessage("%s.track", mode.name).that(mode.track).isEqualTo(expected.third)
            assertWithMessage("%s.label", mode.name).that(mode.label).isNotEmpty()
        }
    }

    @Test
    fun `None stores nothing and All stores everything`() {
        val none = GpsStorageMode.None
        assertThat(listOf(none.overlay, none.metadata, none.track))
            .containsExactly(false, false, false)
        val all = GpsStorageMode.All
        assertThat(listOf(all.overlay, all.metadata, all.track))
            .containsExactly(true, true, true)
    }

    @Test
    fun `the default GPS mode burns in an overlay and tags metadata but writes no GPX track`() {
        val mode = Settings().gpsStorage
        assertThat(mode).isEqualTo(GpsStorageMode.OverlayAndMetadata)
        assertThat(mode.overlay).isTrue()
        assertThat(mode.metadata).isTrue()
        assertThat(mode.track).isFalse()
    }

    // ── Preset lists ──────────────────────────────────────────────────────────────────

    @Test
    fun `pre-event options are the specified presets in ascending order`() {
        assertThat(PRE_EVENT_OPTIONS).containsExactly(10, 15, 30, 45, 60).inOrder()
        assertThat(PRE_EVENT_OPTIONS).contains(Settings().preEventSeconds)
        // Every offered preset must survive validate() untouched.
        for (option in PRE_EVENT_OPTIONS) {
            assertWithMessage("pre-event preset %s", option)
                .that(clampedPreEvent(option))
                .isEqualTo(option)
        }
    }

    @Test
    fun `post-event options are the specified presets in ascending order`() {
        assertThat(POST_EVENT_OPTIONS).containsExactly(30, 60, 90, 120).inOrder()
        assertThat(POST_EVENT_OPTIONS).contains(Settings().postEventSeconds)
        for (option in POST_EVENT_OPTIONS) {
            assertWithMessage("post-event preset %s", option)
                .that(clampedPostEvent(option))
                .isEqualTo(option)
        }
    }

    @Test
    fun `loop budget presets are two five ten twenty and thirty GiB`() {
        val gib = 1024L * 1024 * 1024
        assertThat(LoopBudget.presets)
            .containsExactly(2 * gib, 5 * gib, 10 * gib, 20 * gib, 30 * gib)
            .inOrder()
    }

    @Test
    fun `the default loop budget is one of the offered presets`() {
        assertThat(LoopBudget.presets).contains(Settings().loopBudgetBytes)
    }

    @Test
    fun `every loop budget preset survives validate untouched`() {
        for (preset in LoopBudget.presets) {
            assertWithMessage("preset %s", preset).that(preset).isAtLeast(LoopBudget.MIN_BYTES)
            assertWithMessage("preset %s", preset).that(clampedLoopBudget(preset)).isEqualTo(preset)
        }
    }
}
