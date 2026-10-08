package io.github.tunlezah.roadguard.recording

/** What the recorder should do about parking, decided once a second. */
enum class ParkingAction {
    /** Nothing to do. */
    None,

    /** The vehicle has been verifiably still for long enough: close the clip and pause. */
    Park,

    /** Parked, and the vehicle is moving again -- or pausing is no longer wanted: record. */
    Resume,

    /** Parked for the whole watch period without moving: end the session, so that nothing runs. */
    Sleep,
}

/**
 * When a recording pauses for a parked vehicle, and when it comes back.
 *
 * ### Three tiers
 *
 *  1. **Recording.** While the session records, [io.github.tunlezah.roadguard.parking.StillnessTracker]
 *     measures how long the vehicle has verifiably not moved. Once that reaches the configured time
 *     (ten minutes by default) the session **parks**.
 *  2. **Parked** ([RecorderStatus.Parked]). The clip is finalised and the camera, encoder and GNSS
 *     are released, but the session and its foreground service stay, because only a service that is
 *     already running may reopen the camera without the app on screen. The accelerometer alone
 *     watches for the vehicle moving off, and recording **resumes** the moment it does.
 *  3. **Asleep.** Parked for the whole watch period (thirty minutes by default) without moving, the
 *     session **sleeps**: it ends like a stop, the service stands down, and nothing of Roadguard runs
 *     until the app is opened, power is connected while it is open, or the driver taps the
 *     notification that says what happened.
 *
 * A session that is starting or stopping is left alone: one has not been observed yet, and the
 * other is ending anyway.
 *
 * Pure, so the whole lifecycle is unit tested rather than discovered in a car park.
 */
object ParkingPolicy {

    /**
     * @param enabled the driver wants recording paused while parked.
     * @param stillForMs how long the vehicle has verifiably been still.
     * @param parkAfterMs how long it must be still before the recording parks.
     * @param moving the parked watch has seen the vehicle move.
     * @param parkedForMs how long the session has been parked, or null when it is not.
     * @param watchForMs how long a parked session watches for movement before it sleeps.
     */
    fun decide(
        enabled: Boolean,
        status: RecorderStatus,
        stillForMs: Long,
        parkAfterMs: Long,
        moving: Boolean,
        parkedForMs: Long?,
        watchForMs: Long,
    ): ParkingAction = when (status) {
        RecorderStatus.Recording, RecorderStatus.RollingOver, RecorderStatus.Recovering ->
            if (enabled && stillForMs >= parkAfterMs) ParkingAction.Park else ParkingAction.None

        RecorderStatus.Parked -> when {
            // Moving wins over everything, including a watch period that ran out this very second:
            // footage of a car driving off is the point of the app.
            moving || !enabled -> ParkingAction.Resume
            parkedForMs != null && parkedForMs >= watchForMs -> ParkingAction.Sleep
            else -> ParkingAction.None
        }

        RecorderStatus.Idle,
        RecorderStatus.Starting,
        RecorderStatus.Stopping,
        RecorderStatus.Failed,
        -> ParkingAction.None
    }
}
