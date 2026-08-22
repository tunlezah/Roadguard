package io.github.tunlezah.roadguard.power

/**
 * Battery and charging state, and what Roadguard should do about it.
 *
 * @param plugType one of [PlugType]; a dashcam is normally on vehicle USB, so a transition to
 *   [PlugType.None] usually means the ignition has been switched off.
 * @param temperatureC battery temperature, used only as a labelled fallback thermal signal.
 */
data class PowerState(
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val plugType: PlugType = PlugType.Unknown,
    val temperatureC: Float? = null,
    val isPowerSaveMode: Boolean = false,
) {
    val isOnExternalPower: Boolean get() = plugType != PlugType.None && plugType != PlugType.Unknown
}

enum class PlugType(val label: String) {
    None("On battery"),
    Usb("USB power"),
    AlternatingCurrent("AC power"),
    Wireless("Wireless charging"),
    Dock("Dock power"),
    Unknown("Unknown"),
}

/** What the power manager decided should happen. */
sealed interface PowerAction {
    data object None : PowerAction

    /** Start recording now, because the vehicle just supplied power. */
    data object StartRecording : PowerAction

    /** Ask the user whether to start; used when the setting says prompt. */
    data object PromptToStart : PowerAction

    /** Stop recording now. */
    data object StopRecording : PowerAction

    /** Stop after [seconds], so a brief unplug while re-seating a cable does not end a trip. */
    data class StopAfter(val seconds: Int) : PowerAction

    /** Drop to the battery-safe profile but keep recording. */
    data object BatterySafeProfile : PowerAction

    /** Battery is nearly flat; stop to avoid an unclean shutdown mid-segment. */
    data class StopForLowBattery(val batteryPercent: Int) : PowerAction
}
