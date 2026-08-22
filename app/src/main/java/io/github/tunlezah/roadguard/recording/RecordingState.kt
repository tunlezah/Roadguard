package io.github.tunlezah.roadguard.recording

import io.github.tunlezah.roadguard.capability.RecordingProfile
import io.github.tunlezah.roadguard.thermal.ThermalLevel

/** The recorder's top-level state, as the UI and the notification see it. */
enum class RecorderStatus(val label: String) {
    /** No camera bound; nothing is being written. */
    Idle("Not recording"),

    /** Camera opening, or waiting out the configured start-up delay. */
    Starting("Starting"),

    /** Recording normally. */
    Recording("Recording"),

    /** Between segments. Expected to last milliseconds. */
    RollingOver("Rolling over"),

    /** Recording is stopping at the user's or the power policy's request. */
    Stopping("Stopping"),

    /** Recording stopped because of an error; recovery is scheduled. */
    Failed("Recording stopped"),
}

/**
 * Why the recorder cannot currently record.
 *
 * These are surfaced verbatim in the UI: a dashcam that has silently stopped is worse than one
 * that never started, so every blocking condition has a specific, actionable message.
 */
enum class RecordingBlocker(val message: String, val actionable: Boolean) {
    CameraPermission("Camera permission is required to record", actionable = true),
    NotificationPermission("Allow notifications so Roadguard can keep recording in the background", actionable = true),
    MicrophonePermission("Microphone permission is required for audio recording", actionable = true),
    NoCamera("No usable camera was found on this device", actionable = false),
    StorageFull("Storage is full and cannot be freed", actionable = true),
    StorageUnavailable("The selected storage volume is not available", actionable = true),
    CameraInUse("Another app is using the camera", actionable = true),
    CameraDisabled("The camera has been disabled by a device policy or privacy toggle", actionable = true),
    CameraFatal("The camera reported a fatal error", actionable = false),
    EncoderFailed("The video encoder failed", actionable = false),
    LowBattery("Battery is too low to record safely", actionable = true),
}

/**
 * Everything the recording UI needs, in one immutable snapshot.
 *
 * A single state object (rather than a dozen flows) keeps the main screen consistent: it can
 * never show "recording" next to a segment counter from a previous session.
 */
data class RecordingUiState(
    val status: RecorderStatus = RecorderStatus.Idle,
    val blockers: List<RecordingBlocker> = emptyList(),
    val profile: RecordingProfile? = null,
    val thermalLevel: ThermalLevel = ThermalLevel.Normal,

    /** Wall-clock time the current segment started, or null when not recording. */
    val segmentStartedAtEpochMs: Long? = null,
    val segmentTargetMs: Long = 0,
    val segmentBytes: Long = 0,
    val segmentIndex: Long = 0,

    /** Total time recorded in this session, across segments. */
    val sessionDurationMs: Long = 0,
    val sessionSegmentCount: Int = 0,

    /** Frames the recorder reported as dropped, when the platform tells us. */
    val droppedFrames: Long = 0,
    val audioEnabled: Boolean = false,
    val audioMuted: Boolean = false,

    /** Set for a few seconds after a successful protect, so the UI can confirm it. */
    val lastProtectionMessage: String? = null,
    val lastErrorMessage: String? = null,

    /** Countdown shown during the configured start-up delay. */
    val startupCountdownSeconds: Int? = null,
) {
    val isRecording: Boolean
        get() = status == RecorderStatus.Recording || status == RecorderStatus.RollingOver

    val canProtect: Boolean get() = isRecording

    /** The blocker worth showing first: actionable ones before informational ones. */
    val primaryBlocker: RecordingBlocker?
        get() = blockers.minByOrNull { if (it.actionable) 0 else 1 }
}
