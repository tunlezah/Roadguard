package io.github.tunlezah.roadguard.parking

/**
 * What the parking logic currently believes, published by the recorder for Diagnostics.
 *
 * The thresholds behind it have never been measured in a car, so the one useful thing a tester can
 * be given is the live evidence: how much the phone is shaking, how long it has been still, and what
 * last convinced Roadguard the vehicle had moved.
 *
 * @param observing motion data is arriving. Without it the vehicle is never judged still.
 * @param stillForMs how long the vehicle has verifiably been still, while recording.
 * @param vibration the latest second's vibration, in m/s^2.
 * @param lastMovement the most recent evidence that the vehicle moved, while recording.
 * @param parkedForMs how long the session has been parked, or null when it is not parked.
 * @param watching the parked watch is running: the accelerometer is in its low-power mode.
 */
data class ParkingSnapshot(
    val observing: Boolean = false,
    val stillForMs: Long = 0L,
    val vibration: Float? = null,
    val lastMovement: Movement? = null,
    val parkedForMs: Long? = null,
    val watching: Boolean = false,
)
