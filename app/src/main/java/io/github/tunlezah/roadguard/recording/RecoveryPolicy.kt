package io.github.tunlezah.roadguard.recording

/**
 * Why a recording session stopped writing frames, and what getting it back involves.
 *
 * @param message what the driver is told, in the notification and on screen.
 * @param blocker the blocker to show while recovering, when there is one worth acting on.
 * @param needsRebind true when the camera session itself is suspect and must be rebuilt before
 *   the next attempt. False when the camera is fine and simply has to be waited for (it is
 *   reopening after another app used it) or when the problem is storage rather than the camera.
 */
enum class RecordingFailure(
    val message: String,
    val blocker: RecordingBlocker?,
    val needsRebind: Boolean,
) {
    /** The camera closed under the recording -- another app took it, or it hit an error. */
    CameraLost("The camera stopped supplying frames. Roadguard will resume as soon as it is back.", null, false),

    /** Frames stopped arriving without any error being reported. */
    Stalled("The camera stopped delivering frames. Restarting it.", null, true),

    /** The encoder or muxer failed mid-segment. */
    EncoderFailed("The video encoder failed. Restarting the recorder.", null, true),

    /** The recorder refused to start a new segment. */
    StartRejected("The recorder would not start a new clip. Restarting the camera.", null, true),

    /** CameraX could not be initialised at all. */
    CameraUnavailable("The camera could not be opened. Retrying.", null, true),

    /** No configuration the camera accepts could be bound, not even the safe fallback. */
    BindFailed("The camera could not be configured. Retrying.", null, true),

    /** The volume is full and the loop has nothing left it may delete. */
    StorageFull("Storage is full. Recording resumes as soon as there is room.", RecordingBlocker.StorageFull, false),

    /** The recording location cannot be written -- typically a memory card that was removed. */
    StorageUnavailable(
        "The recording location cannot be written. Recording resumes when it is available again.",
        RecordingBlocker.StorageUnavailable,
        false,
    ),
    ;

    /** Recovery from these waits on storage, not on the camera, so it checks storage first. */
    val isStorage: Boolean get() = this == StorageFull || this == StorageUnavailable
}

/**
 * How Roadguard gets a failed recording going again.
 *
 * ### Never give up while the session lives
 *
 * The recorder used to stop for good after five consecutive failures, about thirty seconds of
 * trying. That turned every transient problem longer than half a minute -- a video call holding
 * the camera, a camera HAL restart, a memory card re-seating itself -- into the end of recording
 * for the rest of the drive, with nothing but a notification to say so. A dashcam's user has
 * already decided they want footage; the recorder's job is to keep trying until that decision
 * changes (Stop, the power policy, or a nearly flat battery).
 *
 * So the schedule is fast at first, because most failures clear within seconds, and then slow
 * and indefinite. The slow phase costs almost nothing: an attempt is a rebind at most, and an
 * attempt that finds the camera still unavailable does not even do that.
 *
 * ### Not a battery drain
 *
 * Recovery keeps the CPU awake only for [WAKE_LOCK_BUDGET_MS]. After that it still retries, and
 * still reacts at once when the camera reopens, but only while something else has the phone
 * awake -- a long outage must not become a long wake lock.
 *
 * Pure, so the whole schedule is unit tested.
 */
object RecoveryPolicy {

    /** Delays before the first attempts, in order. Most failures clear inside this window. */
    val FAST_DELAYS_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

    /** Once the fast attempts are spent, try this often for as long as the session lasts. */
    const val SLOW_DELAY_MS = 60_000L

    /**
     * Every Nth attempt rebuilds the camera session even when the failure did not ask for it,
     * so a camera that CameraX believes it is reopening, but never does, cannot wedge recovery.
     */
    const val FORCED_REBIND_EVERY = 3

    /** How long recovery keeps the CPU awake before it waits for the next natural wake-up. */
    const val WAKE_LOCK_BUDGET_MS = 5L * 60 * 1000

    /** How long recovery must have lasted before the driver is alerted: past a hiccup, not a blip. */
    const val ALERT_AFTER_MS = 30_000L

    /**
     * How long a restarted recording must run before the episode counts as over. A recording
     * that starts and fails again seconds later is the same problem, so it keeps the backoff
     * position and the alert state rather than starting over at the fastest retry.
     */
    const val HEALTHY_AFTER_MS = 20_000L

    /** The delay before attempt number [attempt], counting from 1. */
    fun delayFor(attempt: Int): Long = when {
        attempt <= 1 -> FAST_DELAYS_MS.first()
        attempt <= FAST_DELAYS_MS.size -> FAST_DELAYS_MS[attempt - 1]
        else -> SLOW_DELAY_MS
    }

    /** True when attempt number [attempt] must rebuild the camera session regardless of the failure. */
    fun forcesRebind(attempt: Int): Boolean = attempt > 0 && attempt % FORCED_REBIND_EVERY == 0

    /** Whether recovery that has been running for [recoveringForMs] may still hold the wake lock. */
    fun holdsWakeLock(recoveringForMs: Long): Boolean = recoveringForMs < WAKE_LOCK_BUDGET_MS

    /** Whether recovery that has been running for [recoveringForMs] warrants an alert. */
    fun isOverdue(recoveringForMs: Long): Boolean = recoveringForMs >= ALERT_AFTER_MS
}
