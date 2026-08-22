package io.github.tunlezah.roadguard.thermal

import kotlin.math.max

/**
 * Turns thermal readings into a [ThermalPlan].
 *
 * Pure and Android-free so the whole escalation/de-escalation ladder can be unit tested and
 * driven by the simulated thermal source described in `docs/thermal-management.md`.
 *
 * ### Principles
 *
 *  1. **Recording reliability wins.** Recording is never stopped for heat. Even at
 *     [ThermalLevel.Critical] the plan keeps recording, just cheaply.
 *  2. **Spend the cheapest thing first.** The display, the map and the preview are shed
 *     before recording quality is touched, because they cost real power and contribute
 *     nothing to the evidence.
 *  3. **Never flap.** Escalation is immediate (heat is a real risk) but de-escalation needs
 *     the cooler reading to persist for [DEESCALATE_HOLD_MS], so the recorder is not
 *     rebinding its camera every 30 seconds.
 *  4. **Act before the platform throttles.** Thermal *headroom* is used as an early warning
 *     even when the coarse thermal *status* still says everything is fine.
 */
class ThermalPolicy(
    private val headroomElevated: Float = HEADROOM_ELEVATED,
    private val headroomHigh: Float = HEADROOM_HIGH,
    private val headroomCritical: Float = HEADROOM_CRITICAL,
    private val batteryElevatedC: Float = BATTERY_ELEVATED_C,
    private val batteryHighC: Float = BATTERY_HIGH_C,
    private val batteryCriticalC: Float = BATTERY_CRITICAL_C,
    private val deescalateHoldMs: Long = DEESCALATE_HOLD_MS,
) {

    private var currentLevel: ThermalLevel = ThermalLevel.Normal
    private var coolerSinceMs: Long? = null

    /** The level implied by a single reading, ignoring hysteresis. */
    fun classify(reading: ThermalReading): ThermalLevel {
        var level = ThermalLevel.Normal

        reading.status?.let { status ->
            level = level.atLeast(
                when {
                    status >= THERMAL_STATUS_CRITICAL -> ThermalLevel.Critical
                    status == THERMAL_STATUS_SEVERE -> ThermalLevel.High
                    status == THERMAL_STATUS_MODERATE -> ThermalLevel.Elevated
                    else -> ThermalLevel.Normal
                },
            )
        }

        reading.headroom?.takeIf { !it.isNaN() && it > 0f }?.let { headroom ->
            level = level.atLeast(
                when {
                    headroom >= headroomCritical -> ThermalLevel.Critical
                    headroom >= headroomHigh -> ThermalLevel.High
                    headroom >= headroomElevated -> ThermalLevel.Elevated
                    else -> ThermalLevel.Normal
                },
            )
        }

        // Battery temperature only escalates when nothing better is available: it lags the
        // SoC by minutes and is dominated by charging, so on its own it would over-react.
        val hasPlatformSignal = reading.status != null ||
            (reading.headroom?.let { !it.isNaN() } == true)
        if (!hasPlatformSignal) {
            reading.batteryTemperatureC?.let { temperature ->
                level = level.atLeast(
                    when {
                        temperature >= batteryCriticalC -> ThermalLevel.Critical
                        temperature >= batteryHighC -> ThermalLevel.High
                        temperature >= batteryElevatedC -> ThermalLevel.Elevated
                        else -> ThermalLevel.Normal
                    },
                )
            }
        }

        return level
    }

    /**
     * Feeds a reading through the hysteresis filter and returns the level to act on.
     *
     * Escalation takes effect immediately; a drop only takes effect once the cooler reading
     * has held for [deescalateHoldMs].
     */
    fun accept(reading: ThermalReading): ThermalLevel {
        val observed = classify(reading)
        when {
            observed.ordinalLevel > currentLevel.ordinalLevel -> {
                currentLevel = observed
                coolerSinceMs = null
            }

            observed.ordinalLevel < currentLevel.ordinalLevel -> {
                val since = coolerSinceMs ?: reading.atElapsedRealtimeMs.also { coolerSinceMs = it }
                if (reading.atElapsedRealtimeMs - since >= deescalateHoldMs) {
                    // Step down one level at a time so a brief cool patch cannot jump
                    // straight from Critical back to Normal.
                    currentLevel = ThermalLevel.entries
                        .first { it.ordinalLevel == max(observed.ordinalLevel, currentLevel.ordinalLevel - 1) }
                    coolerSinceMs = reading.atElapsedRealtimeMs
                }
            }

            else -> coolerSinceMs = null
        }
        return currentLevel
    }

    fun reset() {
        currentLevel = ThermalLevel.Normal
        coolerSinceMs = null
    }

    val level: ThermalLevel get() = currentLevel

    companion object {
        // Mirrors of PowerManager.THERMAL_STATUS_*, kept local so the policy stays Android-free.
        const val THERMAL_STATUS_NONE = 0
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_CRITICAL = 4
        const val THERMAL_STATUS_EMERGENCY = 5
        const val THERMAL_STATUS_SHUTDOWN = 6

        /**
         * Headroom thresholds.
         *
         * `getThermalHeadroom()` returns a normalised value where 1.0 means throttling is
         * imminent, so acting at 0.80 gives the recorder time to change profile at the next
         * segment boundary rather than being throttled mid-segment.
         */
        const val HEADROOM_ELEVATED = 0.80f
        const val HEADROOM_HIGH = 0.92f
        const val HEADROOM_CRITICAL = 0.99f

        /**
         * Battery-temperature fallback thresholds, only consulted when the platform reports
         * neither a thermal status nor headroom. Chosen conservatively because battery
         * temperature is a lagging proxy for SoC temperature.
         */
        const val BATTERY_ELEVATED_C = 41f
        const val BATTERY_HIGH_C = 44f
        const val BATTERY_CRITICAL_C = 47f

        /** How long a cooler reading must persist before Roadguard relaxes a mitigation. */
        const val DEESCALATE_HOLD_MS = 90_000L

        /** The mitigation ladder. */
        fun planFor(level: ThermalLevel): ThermalPlan = when (level) {
            ThermalLevel.Normal -> ThermalPlan(
                level = level,
                previewFrameRateCap = null,
                mapRenderBudget = MapRenderBudget.Full,
                allowVideoOverlay = true,
                allowSecondCamera = true,
                allowStabilisation = true,
                allowNightAssist = true,
                locationIntervalMs = 1_000L,
                weatherRefreshMinutes = 15,
                qualityStepDown = 0,
                frameRateCap = null,
                bitrateScale = 1.0f,
                reduceUiAnimation = false,
                detachPreviewWhenHidden = true,
                warnUser = false,
                userMessage = null,
            )

            // Shed optional work only. Recording quality is untouched, per the specification.
            ThermalLevel.Elevated -> ThermalPlan(
                level = level,
                previewFrameRateCap = 24,
                mapRenderBudget = MapRenderBudget.Reduced,
                allowVideoOverlay = true,
                allowSecondCamera = false,
                allowStabilisation = false,
                allowNightAssist = true,
                locationIntervalMs = 2_000L,
                weatherRefreshMinutes = 30,
                qualityStepDown = 0,
                frameRateCap = null,
                bitrateScale = 1.0f,
                reduceUiAnimation = true,
                detachPreviewWhenHidden = true,
                warnUser = false,
                userMessage = null,
            )

            ThermalLevel.High -> ThermalPlan(
                level = level,
                previewFrameRateCap = 15,
                mapRenderBudget = MapRenderBudget.Frozen,
                allowVideoOverlay = true,
                allowSecondCamera = false,
                allowStabilisation = false,
                allowNightAssist = false,
                locationIntervalMs = 2_000L,
                weatherRefreshMinutes = 60,
                qualityStepDown = 1,
                frameRateCap = 30,
                bitrateScale = 0.85f,
                reduceUiAnimation = true,
                detachPreviewWhenHidden = true,
                warnUser = true,
                userMessage = "Device is hot. Roadguard has reduced recording quality and paused the map to keep recording.",
            )

            // Keep a valid recording. Everything else goes.
            ThermalLevel.Critical -> ThermalPlan(
                level = level,
                previewFrameRateCap = 10,
                mapRenderBudget = MapRenderBudget.Disabled,
                allowVideoOverlay = false,
                allowSecondCamera = false,
                allowStabilisation = false,
                allowNightAssist = false,
                locationIntervalMs = 5_000L,
                weatherRefreshMinutes = 120,
                qualityStepDown = 2,
                frameRateCap = 24,
                bitrateScale = 0.70f,
                reduceUiAnimation = true,
                detachPreviewWhenHidden = true,
                warnUser = true,
                userMessage = "Device is very hot. Roadguard is recording at the lowest quality it can and has turned off the map, overlays and preview to stay running. Improve airflow or shade the phone.",
            )
        }
    }
}
