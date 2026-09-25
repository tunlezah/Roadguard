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

    /**
     * A recording session is running but nothing is being written at this moment: the camera was
     * lost to another app or an error, the encoder failed, frames stopped arriving, or storage ran
     * out. Roadguard is bringing the recorder back, and keeps trying for as long as the session
     * lasts -- only the user, the power policy or a flat battery ends a session.
     */
    Recovering("Reconnecting"),

    /** Recording is stopping at the user's or the power policy's request. */
    Stopping("Stopping"),

    /**
     * Recording has stopped and will not restart by itself: the battery is nearly flat, a
     * permission is missing, or the recording service went away. The reason is in
     * [RecordingUiState.lastErrorMessage].
     */
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

    /**
     * Bytes written to the current segment. Refreshed every few seconds, not on every encoded
     * frame: the recorder reports progress per frame, and republishing state that often costs a
     * notification rebuild and a screen recomposition for each one.
     */
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

    /**
     * Recordings that have been started but whose MP4 has not been closed yet.
     *
     * Non-zero for a moment at every rollover and while a stop is finishing. It matters because
     * an MP4 is only playable once its index has been written at the end, so the CPU must stay
     * awake until this reaches zero -- see [holdsWakeLock].
     */
    val unfinalizedSegments: Int = 0,

    /**
     * When the current recovery episode began, on the elapsed-realtime clock, or null when the
     * recorder is not recovering. An episode lasts until a recording has run cleanly again, so
     * a restart that fails again seconds later continues the same episode.
     */
    val recoveringSinceElapsedMs: Long? = null,

    /** True once recovery has lasted long enough that the driver should be alerted. */
    val recoveryOverdue: Boolean = false,

    /**
     * True once recovery has run past its wake-lock budget and now waits for the next wake-up or
     * for the camera to come back, instead of keeping the CPU awake indefinitely.
     */
    val recoveryIdle: Boolean = false,

    /** Battery-safe mode is shaping the recording; see [io.github.tunlezah.roadguard.power.PowerPolicy.batterySafe]. */
    val batterySafe: Boolean = false,
) {
    /** Frames are being written right now. */
    val isRecording: Boolean
        get() = status == RecorderStatus.Recording || status == RecorderStatus.RollingOver

    /**
     * A recording session the user (or the power policy) started is still in progress, whether
     * or not frames are being written this instant. Stop is the right control, and a second start
     * must be refused rather than stacked on top.
     */
    val isSessionActive: Boolean
        get() = status == RecorderStatus.Starting || isRecording || status == RecorderStatus.Recovering

    /**
     * Protect is offered whenever there is footage from this session to protect -- including while
     * recovering, because the moment a camera fails may be the moment of an impact.
     */
    val canProtect: Boolean get() = isRecording || status == RecorderStatus.Recovering

    /**
     * Whether the recording service must hold its partial wake lock.
     *
     * Held from the moment a start begins until the last MP4 has been closed. A camera foreground
     * service does not keep the CPU awake with the screen off, and neither the camera nor the
     * encoder holds a wake lock of its own, so releasing any earlier -- at "Stopping", say, which
     * is how a low-battery or power-off stop usually happens with the screen off -- can leave the
     * final segment without its index when the phone dies moments later.
     */
    val holdsWakeLock: Boolean
        get() = unfinalizedSegments > 0 || when (status) {
            RecorderStatus.Starting,
            RecorderStatus.Recording,
            RecorderStatus.RollingOver,
            RecorderStatus.Stopping,
            -> true

            RecorderStatus.Recovering -> !recoveryIdle
            RecorderStatus.Idle, RecorderStatus.Failed -> false
        }

    /** The blocker worth showing first: actionable ones before informational ones. */
    val primaryBlocker: RecordingBlocker?
        get() = blockers.minByOrNull { if (it.actionable) 0 else 1 }
}
