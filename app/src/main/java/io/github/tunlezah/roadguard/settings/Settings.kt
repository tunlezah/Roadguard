package io.github.tunlezah.roadguard.settings

/**
 * Every user-visible Roadguard setting, with the defaults the product specification calls
 * for.
 *
 * This is a plain immutable value type with no Android dependencies so that policy code
 * (profile selection, thermal policy, storage budgeting, event detection) can be unit
 * tested against it directly. [SettingsRepository] is the only thing that knows how it is
 * persisted.
 *
 * Every default below is either mandated by the product specification or a reasoned choice, and
 * `docs/feature-research.md` records which is which for each one -- including the fact that no
 * survey of commercial dashcams was carried out, so nothing here rests on one:
 *
 *  * recording quality and frame rate are **Auto** -- resolved at runtime from probed device
 *    capability, never from a hard-coded model name;
 *  * segments are **3 minutes**;
 *  * the loop budget is **5 GB**;
 *  * event protection keeps **30 s before** and **60 s after**;
 *  * the microphone is **off**;
 *  * preview zoom is **Auto** and never affects the recorded stream;
 *  * recording zoom is **1.0x** because it permanently narrows the recorded field of view.
 */
data class Settings(
    // ── Recording ──────────────────────────────────────────────────────────────────
    val quality: QualitySetting = QualitySetting.Auto,
    val frameRate: FrameRateSetting = FrameRateSetting.Auto,
    val segmentLength: SegmentLength = SegmentLength.Minutes3,
    val cameraFacing: CameraFacing = CameraFacing.Rear,
    val dualCameraEnabled: Boolean = false,
    val videoStabilisation: TriState = TriState.Auto,
    val nightAssist: TriState = TriState.Auto,
    val recordingZoom: Float = 1.0f,
    val microphoneEnabled: Boolean = false,

    // ── Preview / UI ───────────────────────────────────────────────────────────────
    val previewZoom: PreviewZoom = PreviewZoom.Auto,
    val mapVisible: Boolean = true,
    val theme: ThemeSetting = ThemeSetting.System,
    val useDynamicColour: Boolean = false,
    val orientationMode: OrientationMode = OrientationMode.FollowDevice,
    val keepScreenOn: Boolean = true,
    val screenOffDimming: Boolean = true,

    // ── Overlays burned into the recorded video ────────────────────────────────────
    val overlayDateTime: Boolean = true,
    val overlaySpeed: Boolean = true,
    val overlayCoordinates: Boolean = false,
    val overlayWeather: Boolean = false,

    // ── Startup ────────────────────────────────────────────────────────────────────
    val autoStartRecording: Boolean = true,
    val startupDelaySeconds: Int = 3,

    // ── Event detection ────────────────────────────────────────────────────────────
    val eventDetectionEnabled: Boolean = true,
    val eventSensitivity: EventSensitivity = EventSensitivity.Medium,
    val preEventSeconds: Int = 30,
    val postEventSeconds: Int = 60,

    // ── Storage ────────────────────────────────────────────────────────────────────
    val loopBudgetBytes: Long = 5L * 1024 * 1024 * 1024,
    val protectedWarningBytes: Long = 2L * 1024 * 1024 * 1024,
    val storageVolumeId: String? = null,

    // ── Location ───────────────────────────────────────────────────────────────────
    val locationEnabled: Boolean = true,
    val speedUnit: SpeedUnit = SpeedUnit.KilometresPerHour,
    val gpsStorage: GpsStorageMode = GpsStorageMode.OverlayAndMetadata,

    // ── Power ──────────────────────────────────────────────────────────────────────
    val onPowerConnected: PowerConnectedAction = PowerConnectedAction.StartRecording,
    val onPowerDisconnected: PowerDisconnectedAction = PowerDisconnectedAction.ContinueRecording,
    val powerDisconnectStopDelaySeconds: Int = 300,
    val batterySafeThresholdPercent: Int = 15,

    // ── Weather (optional; see docs/research/weather-australia.md) ─────────────────
    val weatherEnabled: Boolean = false,

    // ── Map ────────────────────────────────────────────────────────────────────────
    val mapFollowsVehicle: Boolean = true,
    val mapNorthUp: Boolean = false,
    val mapAutoDownload: Boolean = true,

    // ── First run ──────────────────────────────────────────────────────────────────
    val setupComplete: Boolean = false,
    val acceptedRecordingDisclaimer: Boolean = false,
) {
    /** True when any overlay is enabled and therefore a burn-in GPU pass is required. */
    val anyVideoOverlayEnabled: Boolean
        get() = overlayDateTime || overlaySpeed || overlayCoordinates || overlayWeather
}

/** Requested recording resolution. `Auto` defers to the runtime device profile. */
enum class QualitySetting(val label: String) {
    Auto("Auto"),
    Sd480p("480p"),
    Hd720p("720p"),
    FullHd1080p("1080p"),
    Uhd2160p("2160p (4K)"),
}

/*
 * There is deliberately no codec setting.
 *
 * CameraX 1.6.x -- the stable release Roadguard records with -- gives an application no way to
 * choose the video encoder: `Recorder` derives it from the device's own encoder profiles, and
 * the only public API that would let an app override it, `Recorder.Builder.setVideoMimeType`,
 * exists solely in the 1.7 alpha line behind an experimental opt-in. Roadguard will not put an
 * alpha camera stack in the path of the one thing it must never get wrong, and it will not
 * offer a setting it cannot honour. The codec actually in use is detected and shown on the
 * Diagnostics screen instead. See docs/research/codecs-and-encoding.md and docs/architecture.md.
 */
enum class FrameRateSetting(val label: String, val fps: Int?) {
    Auto("Auto", null),
    Fps24("24 fps", 24),
    Fps30("30 fps", 30),
    Fps60("60 fps", 60),
}

enum class SegmentLength(val label: String, val seconds: Int) {
    Minutes1("1 minute", 60),
    Minutes3("3 minutes", 180),
    Minutes5("5 minutes", 300),
    Minutes10("10 minutes", 600),
}

enum class CameraFacing(val label: String) { Rear("Rear camera"), Front("Front camera") }

/** A capability the device may or may not support; `Auto` lets the device profile decide. */
enum class TriState(val label: String) { Auto("Auto"), On("On"), Off("Off") }

/**
 * Display-only magnification of the live preview.
 *
 * Preview zoom is applied as a view transform over the viewfinder and provably cannot
 * reach the encoder; see [io.github.tunlezah.roadguard.camera.PreviewFit].
 */
enum class PreviewZoom(val label: String, val factor: Float?) {
    Auto("Auto", null),
    X1_0("1.0x", 1.0f),
    X1_1("1.1x", 1.1f),
    X1_25("1.25x", 1.25f),
    X1_5("1.5x", 1.5f),
    X1_75("1.75x", 1.75f),
    X2_0("2.0x", 2.0f),
}

enum class ThemeSetting(val label: String) {
    System("Follow system"),
    Light("Light"),
    Dark("Dark"),
    Oled("OLED black"),
}

enum class OrientationMode(val label: String) {
    FollowDevice("Follow device"),
    FollowSystem("Follow system rotation setting"),
    LockPortrait("Lock portrait"),
    LockLandscape("Lock landscape"),
}

enum class EventSensitivity(val label: String) {
    Low("Low (fewer alerts)"),
    Medium("Medium (recommended)"),
    High("High (more alerts)"),
}

enum class SpeedUnit(val label: String, val suffix: String, val fromMetresPerSecond: Float) {
    KilometresPerHour("km/h", "km/h", 3.6f),
    MilesPerHour("mph", "mph", 2.236936f),
}

/** Where GPS information is retained. Nothing is ever uploaded. */
enum class GpsStorageMode(val label: String, val overlay: Boolean, val metadata: Boolean, val track: Boolean) {
    None("Do not store", overlay = false, metadata = false, track = false),
    OverlayOnly("Overlay only", overlay = true, metadata = false, track = false),
    MetadataOnly("Video metadata only", overlay = false, metadata = true, track = false),
    TrackOnly("GPX track only", overlay = false, metadata = false, track = true),
    OverlayAndMetadata("Overlay and video metadata", overlay = true, metadata = true, track = false),
    All("Overlay, metadata and GPX track", overlay = true, metadata = true, track = true),
}

enum class PowerConnectedAction(val label: String) {
    StartRecording("Start recording"),
    DoNothing("Do nothing"),
    Prompt("Ask me"),
}

enum class PowerDisconnectedAction(val label: String) {
    ContinueRecording("Keep recording"),
    StopRecording("Stop recording"),
    StopAfterDelay("Stop after a delay"),
    BatterySafeProfile("Switch to a battery-safe profile"),
}

/** Loop-storage budget presets offered in Settings, plus a validated custom value. */
object LoopBudget {
    val presets: List<Long> = listOf(
        2L * 1024 * 1024 * 1024,
        5L * 1024 * 1024 * 1024,
        10L * 1024 * 1024 * 1024,
        20L * 1024 * 1024 * 1024,
        30L * 1024 * 1024 * 1024,
    )
    const val MIN_BYTES: Long = 512L * 1024 * 1024
}

/** Pre-event buffer presets, in seconds. */
val PRE_EVENT_OPTIONS: List<Int> = listOf(10, 15, 30, 45, 60)

/** Post-event protection presets, in seconds. */
val POST_EVENT_OPTIONS: List<Int> = listOf(30, 60, 90, 120)

/** Startup delay bounds, in seconds. */
val STARTUP_DELAY_RANGE: IntRange = 0..30
