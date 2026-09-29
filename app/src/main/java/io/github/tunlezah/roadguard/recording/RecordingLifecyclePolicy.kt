package io.github.tunlezah.roadguard.recording

import io.github.tunlezah.roadguard.settings.PowerConnectedAction

/**
 * When the recording service may stand itself down.
 *
 * A `camera`-typed foreground service keeps the process important and, once promoted from a
 * visible Activity, keeps the process's while-in-use camera grant. That is exactly what recording
 * needs -- and exactly what must *end* when recording does. A service that lingers after Stop is a
 * process that still holds the camera grant, still runs the sensor, thermal and tick loops, and can
 * still be told to record again by a power event, which is how a phone that was stopped keeps
 * recording in the background. So the service stops itself the moment a session is over.
 *
 * Pure, so the one rule that decides whether a stopped recorder leaves a foreground service behind
 * is unit tested rather than discovered on a phone.
 */
object ServiceStandDown {

    /**
     * Whether the service should leave the foreground and stop.
     *
     * @param sessionActive a user- or policy-started session is still running (starting, recording
     *   or reconnecting). Never stand down under one.
     * @param holdsWakeLock a clip is still being finalised, or the recorder still needs the CPU.
     *   Standing down here would drop the wake lock before the last MP4 has its closing index.
     * @param armed true once a session has actually run this service instance, or the user has
     *   asked to stop. Without it, the idle state a freshly started service passes through before
     *   its first segment would stop the service before it ever recorded.
     */
    fun shouldStandDown(sessionActive: Boolean, holdsWakeLock: Boolean, armed: Boolean): Boolean =
        armed && !sessionActive && !holdsWakeLock
}

/**
 * Whether Roadguard may start recording on its own, without the driver pressing Record.
 *
 * Two automatic triggers exist -- opening the app, and vehicle power being connected -- and both
 * must yield to one thing: an explicit Stop. A driver who stops recording has said they are done;
 * re-arming on the next app open or the next time the phone sees power turns Stop into a suggestion.
 * So a manual Stop latches ([SessionJournal.wasUserStopped]) and both automatic triggers stand down
 * until the driver records manually again, which clears the latch.
 *
 * Pure, so the rule is unit tested rather than inferred from behaviour in a car park.
 */
object AutoStartPolicy {

    /** Whether bringing the app to the foreground should start recording. */
    fun shouldAutoStartOnOpen(
        setupComplete: Boolean,
        autoStartEnabled: Boolean,
        userStopped: Boolean,
    ): Boolean = setupComplete && autoStartEnabled && !userStopped

    /** Whether vehicle power being connected should start recording. */
    fun shouldStartOnPowerConnected(
        setupComplete: Boolean,
        action: PowerConnectedAction,
        userStopped: Boolean,
        sessionActive: Boolean,
    ): Boolean = setupComplete &&
        action == PowerConnectedAction.StartRecording &&
        !userStopped &&
        !sessionActive
}
