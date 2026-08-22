package io.github.tunlezah.roadguard.thermal

/**
 * Roadguard's four-level view of thermal pressure, as the specification defines it.
 *
 * Deliberately coarser than the platform's seven `PowerManager.THERMAL_STATUS_*` values:
 * the recorder needs a small number of stable operating points, not a continuously varying
 * one, because several of the mitigations can only be applied at a segment boundary.
 */
enum class ThermalLevel(val ordinalLevel: Int, val label: String) {
    /** Nothing to do. Run the recommended profile. */
    Normal(0, "Normal"),

    /** Warm. Shed optional work, but do not touch recording quality. */
    Elevated(1, "Elevated"),

    /** Hot. Reduce recording cost and stop rendering the map. */
    High(2, "High"),

    /** Throttling or close to it. Preserve a valid recording above all else. */
    Critical(3, "Critical"),
    ;

    fun atLeast(other: ThermalLevel): ThermalLevel =
        if (ordinalLevel >= other.ordinalLevel) this else other
}

/** Where a thermal reading came from, so diagnostics never present a guess as a measurement. */
enum class ThermalSignalSource {
    /** `PowerManager.getCurrentThermalStatus()`. */
    PlatformThermalStatus,

    /** `PowerManager.getThermalHeadroom()`. */
    PlatformThermalHeadroom,

    /** `ACTION_BATTERY_CHANGED`'s `EXTRA_TEMPERATURE`; a lagging proxy, used only as a fallback. */
    BatteryTemperature,

    /** Injected by the thermal test harness. */
    Simulated,

    /** No signal available on this device. */
    Unavailable,
}

/**
 * A single thermal observation.
 *
 * @param status raw `PowerManager.THERMAL_STATUS_*`, or null when unavailable.
 * @param headroom `getThermalHeadroom()` result: 0.0 means cool, 1.0 means throttling is
 *   imminent. `NaN`/null when the device does not report it.
 * @param batteryTemperatureC battery temperature in degrees Celsius, when known.
 */
data class ThermalReading(
    val status: Int?,
    val headroom: Float?,
    val batteryTemperatureC: Float?,
    val sources: Set<ThermalSignalSource>,
    val atElapsedRealtimeMs: Long,
) {
    companion object {
        fun unavailable(atElapsedRealtimeMs: Long) = ThermalReading(
            status = null,
            headroom = null,
            batteryTemperatureC = null,
            sources = setOf(ThermalSignalSource.Unavailable),
            atElapsedRealtimeMs = atElapsedRealtimeMs,
        )
    }
}

/** How much of the map subsystem is allowed to run. */
enum class MapRenderBudget(val label: String) {
    /** Normal interactive rendering. */
    Full("Full"),

    /** Rendering continues but position updates are throttled. */
    Reduced("Reduced"),

    /** The last rendered frame is kept on screen; no new frames are drawn. */
    Frozen("Frozen"),

    /** The map view is torn down entirely and replaced with a placeholder. */
    Disabled("Off"),
}

/**
 * The complete set of mitigations for a [ThermalLevel].
 *
 * Every field is a *ceiling*, not a command: the recorder combines it with the user's
 * settings and the device profile, and only ever reduces.
 *
 * @property qualityStepDown how many steps down the resolution ladder to move.
 * @property requiresRebind true when applying this plan needs the camera use cases rebound,
 *   which Roadguard only ever does at a segment boundary so a recording is never cut short.
 */
data class ThermalPlan(
    val level: ThermalLevel,
    val previewFrameRateCap: Int?,
    val mapRenderBudget: MapRenderBudget,
    val allowVideoOverlay: Boolean,
    val allowSecondCamera: Boolean,
    val allowStabilisation: Boolean,
    val allowNightAssist: Boolean,
    val locationIntervalMs: Long,
    val weatherRefreshMinutes: Int,
    val qualityStepDown: Int,
    val frameRateCap: Int?,
    val bitrateScale: Float,
    val reduceUiAnimation: Boolean,
    val detachPreviewWhenHidden: Boolean,
    val warnUser: Boolean,
    val userMessage: String?,
) {
    /**
     * True when moving from [previous] to this plan changes something the camera session
     * bakes in at bind time (resolution, frame rate, effects, second camera).
     */
    fun requiresRebindFrom(previous: ThermalPlan?): Boolean {
        if (previous == null) return false
        return previous.qualityStepDown != qualityStepDown ||
            previous.frameRateCap != frameRateCap ||
            previous.bitrateScale != bitrateScale ||
            previous.allowVideoOverlay != allowVideoOverlay ||
            previous.allowSecondCamera != allowSecondCamera ||
            previous.allowStabilisation != allowStabilisation
    }
}
