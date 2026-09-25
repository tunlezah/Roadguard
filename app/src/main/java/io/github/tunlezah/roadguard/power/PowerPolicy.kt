package io.github.tunlezah.roadguard.power

import io.github.tunlezah.roadguard.settings.PowerConnectedAction
import io.github.tunlezah.roadguard.settings.PowerDisconnectedAction
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.thermal.MapRenderBudget
import io.github.tunlezah.roadguard.thermal.ThermalPlan

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
     * Whether recording should run in battery-safe mode right now.
     *
     * Three things switch it on, and one switches it off:
     *
     *  * **Battery Saver.** The user has told the whole phone to save power; Roadguard follows.
     *  * **Charging** switches it off. A charging phone is not running out, and battery-safe
     *    mode costs recording quality.
     *  * **Unplugged, with "Switch to a battery-safe profile" chosen** for power disconnection.
     *    [PlugType.Unknown] -- no battery broadcast yet -- is not treated as unplugged, so the
     *    very first reading of a session cannot flip the profile by accident.
     *  * **At or below the battery-safe threshold** (15 % by default) while not charging --
     *    including plugged into a charger too weak to keep up, which reports discharging.
     */
    fun batterySafe(state: PowerState, settings: Settings): Boolean = when {
        state.isPowerSaveMode -> true
        state.isCharging -> false
        settings.onPowerDisconnected == PowerDisconnectedAction.BatterySafeProfile &&
            state.plugType == PlugType.None -> true

        else -> state.batteryPercent?.let { it <= settings.batterySafeThresholdPercent } ?: false
    }

    /**
     * The ceilings battery-safe mode adds to [plan].
     *
     * Every field only ever tightens, so combining this with any thermal level can never loosen
     * what the thermal engine asked for. What it gives up, in order of what it saves:
     *
     *  * the **screen's keep-awake** -- applied by the activity, since it is not a recording
     *    property; the display is the largest single draw on a phone;
     *  * the **map's follow animation**, which otherwise re-renders the map continuously while
     *    moving ([MapRenderBudget.Reduced] moves the map once per fix instead);
     *  * **resolution above 720p**, **frame rates above 30 fps**, **stabilisation**, **night
     *    assist** and the **second camera** -- each of which costs sensor, ISP and encoder power
     *    for every frame;
     *  * **GNSS every second**, relaxed to every two.
     *
     * Burned-in overlays and bitrate are deliberately left alone: the timestamp and speed are
     * evidence, and bitrate costs far more storage than power.
     */
    fun restrain(plan: ThermalPlan, batterySafe: Boolean): ThermalPlan {
        if (!batterySafe) return plan
        return plan.copy(
            mapRenderBudget = stricter(plan.mapRenderBudget, MapRenderBudget.Reduced),
            allowSecondCamera = false,
            allowStabilisation = false,
            allowNightAssist = false,
            locationIntervalMs = maxOf(plan.locationIntervalMs, BATTERY_SAFE_LOCATION_INTERVAL_MS),
            weatherRefreshMinutes = maxOf(plan.weatherRefreshMinutes, BATTERY_SAFE_WEATHER_MINUTES),
            frameRateCap = minOf(plan.frameRateCap ?: BATTERY_SAFE_FRAME_RATE, BATTERY_SAFE_FRAME_RATE),
            qualityCeiling = stricterQuality(plan.qualityCeiling, BATTERY_SAFE_QUALITY_CEILING),
            reduceUiAnimation = true,
        )
    }

    /** Frame-rate ceiling in battery-safe mode. */
    const val BATTERY_SAFE_FRAME_RATE = 30

    /** Resolution ceiling in battery-safe mode, as a CameraX quality name: 720p. */
    const val BATTERY_SAFE_QUALITY_CEILING = "HD"

    /** GNSS interval in battery-safe mode: still enough for the speed overlay and the track. */
    const val BATTERY_SAFE_LOCATION_INTERVAL_MS = 2_000L

    const val BATTERY_SAFE_WEATHER_MINUTES = 30

    /** CameraX quality names, richest first, for comparing ceilings. */
    private val QUALITY_ORDER = listOf("UHD", "FHD", "HD", "SD")

    private fun stricterQuality(current: String?, ceiling: String): String {
        if (current == null) return ceiling
        val currentRank = QUALITY_ORDER.indexOf(current)
        val ceilingRank = QUALITY_ORDER.indexOf(ceiling)
        return if (currentRank >= ceilingRank) current else ceiling
    }

    private fun stricter(current: MapRenderBudget, floor: MapRenderBudget): MapRenderBudget =
        if (current.ordinal >= floor.ordinal) current else floor
}

/**
 * Holds battery-safe mode steady against a flickering power supply.
 *
 * Entering or leaving battery-safe mode changes the recording profile, and a profile change
 * costs a camera rebind at the next segment boundary -- about a second of footage. A loose
 * cigarette-lighter plug that connects and disconnects every few seconds would otherwise rebind
 * the camera every twenty seconds for the whole drive. So a change is only applied once the new
 * value has held for [holdMs]; draining for one extra minute before saving power is harmless.
 *
 * A session starts from the value that holds at that moment ([reset]); only changes during the
 * session are debounced. Pure: time is passed in.
 */
class BatterySafeGate(private val holdMs: Long = HOLD_MS) {

    /** The value currently in force. */
    var applied: Boolean = false
        private set

    private var candidate: Boolean? = null
    private var candidateSinceMs: Long = 0L

    /** Starts from [value] with no debounce, for the beginning of a session. */
    fun reset(value: Boolean) {
        applied = value
        candidate = null
    }

    /**
     * Offers the value the policy wants now.
     *
     * @return true when [applied] changed as a result.
     */
    fun accept(wanted: Boolean, nowMs: Long): Boolean {
        if (wanted == applied) {
            candidate = null
            return false
        }
        if (candidate != wanted) {
            candidate = wanted
            candidateSinceMs = nowMs
            return false
        }
        if (nowMs - candidateSinceMs < holdMs) return false
        applied = wanted
        candidate = null
        return true
    }

    companion object {
        /** How long a change must hold before it is applied. */
        const val HOLD_MS = 60_000L
    }
}
