package io.github.tunlezah.roadguard.power

import io.github.tunlezah.roadguard.settings.PowerConnectedAction
import io.github.tunlezah.roadguard.settings.PowerDisconnectedAction
import io.github.tunlezah.roadguard.settings.Settings

/**
 * Decides what a power-state change means for recording.
 *
 * Pure, so the whole ignition-on/ignition-off story is unit tested rather than discovered in a
 * car park. Two properties are non-negotiable and are asserted by the tests:
 *
 *  * a power transition never *corrupts* a recording -- every stop this policy asks for is a
 *    graceful stop that finalises the current segment; and
 *  * a low battery stops recording *before* the phone dies, because an unclean shutdown is
 *    the one thing most likely to leave an unplayable final segment.
 */
object PowerPolicy {

    /**
     * Battery level at which recording stops regardless of settings.
     *
     * Below this a phone can power off with little warning, so Roadguard closes the segment
     * while it still can rather than being killed mid-muxer-write.
     */
    const val HARD_STOP_BATTERY_PERCENT = 3

    fun onPowerConnected(settings: Settings): PowerAction = when (settings.onPowerConnected) {
        PowerConnectedAction.StartRecording -> PowerAction.StartRecording
        PowerConnectedAction.DoNothing -> PowerAction.None
        PowerConnectedAction.Prompt -> PowerAction.PromptToStart
    }

    fun onPowerDisconnected(settings: Settings): PowerAction = when (settings.onPowerDisconnected) {
        PowerDisconnectedAction.ContinueRecording -> PowerAction.None
        PowerDisconnectedAction.StopRecording -> PowerAction.StopRecording
        PowerDisconnectedAction.StopAfterDelay ->
            PowerAction.StopAfter(settings.powerDisconnectStopDelaySeconds)

        PowerDisconnectedAction.BatterySafeProfile -> PowerAction.BatterySafeProfile
    }

    /**
     * Evaluated on every battery change while recording.
     *
     * Returns [PowerAction.StopForLowBattery] at the hard floor, [PowerAction.BatterySafeProfile]
     * once the user's threshold is crossed on battery power, and [PowerAction.None] otherwise.
     * Charging suppresses both, since a charging phone is not about to die.
     */
    fun evaluateBattery(state: PowerState, settings: Settings): PowerAction {
        val percent = state.batteryPercent ?: return PowerAction.None
        if (state.isCharging) return PowerAction.None
        return when {
            percent <= HARD_STOP_BATTERY_PERCENT -> PowerAction.StopForLowBattery(percent)
            percent <= settings.batterySafeThresholdPercent -> PowerAction.BatterySafeProfile
            else -> PowerAction.None
        }
    }

    /**
     * The extra thermal-style restraint applied while on battery.
     *
     * Recording quality is not reduced here -- the thermal engine owns that -- but optional
     * work is shed, because on battery the phone has neither the power budget nor, in a hot
     * car, the cooling that mains power at least implies.
     */
    fun batterySafeStepDown(state: PowerState, settings: Settings): Boolean =
        !state.isCharging && (state.batteryPercent ?: 100) <= settings.batterySafeThresholdPercent
}
