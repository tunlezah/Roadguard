package io.github.tunlezah.roadguard.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where thermal readings come from.
 *
 * Abstracted so the whole mitigation ladder can be exercised by
 * [SimulatedThermalSource] in tests and in the developer thermal harness, without a hot phone.
 */
interface ThermalSource {
    val reading: StateFlow<ThermalReading>
    fun start()
    fun stop()
    val describeCapability: String
}

/**
 * Reads the platform's thermal APIs, and is honest about what this device actually reports.
 *
 * Three signals, in descending order of trust:
 *
 *  1. `PowerManager.getCurrentThermalStatus()` plus `addThermalStatusListener` -- the platform's
 *     own verdict, event-driven and free.
 *  2. `PowerManager.getThermalHeadroom(forecastSeconds)` -- a forward-looking number that lets
 *     Roadguard act *before* the platform starts throttling. It is documented to be rate
 *     limited and to return `NaN` when called too often or when unsupported, so it is polled on
 *     a slow cadence and `NaN` is treated as "no signal", never as zero.
 *  3. Battery temperature from `ACTION_BATTERY_CHANGED` -- a lagging proxy used only when the
 *     first two are unavailable, and labelled as such everywhere it surfaces.
 *
 * Which of these the device really answers is recorded in [ThermalReading.sources] and shown on
 * the Diagnostics screen, so a reader can tell a measurement from an inference.
 */
class AndroidThermalSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = HEADROOM_POLL_MS,
) : ThermalSource {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val _reading = MutableStateFlow(ThermalReading.unavailable(SystemClock.elapsedRealtime()))
    override val reading: StateFlow<ThermalReading> = _reading.asStateFlow()

    private var pollJob: Job? = null
    private var batteryTemperatureC: Float? = null
    private var headroomSupported = true

    private val statusListener = PowerManager.OnThermalStatusChangedListener { status ->
        publish(status = status)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tenthsOfDegree = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?: return
            if (tenthsOfDegree == Int.MIN_VALUE) return
            batteryTemperatureC = tenthsOfDegree / 10f
            publish()
        }
    }

    override fun start() {
        runCatching {
            powerManager?.addThermalStatusListener(statusListener)
        }.onFailure { Log.w(TAG, "thermal status listener unavailable", it) }

        runCatching {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.onFailure { Log.w(TAG, "battery receiver registration failed", it) }

        publish(status = runCatching { powerManager?.currentThermalStatus }.getOrNull())

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                publish()
                delay(pollIntervalMs)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        runCatching { powerManager?.removeThermalStatusListener(statusListener) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    override val describeCapability: String
        get() = buildList {
            add("thermal status: ${if (powerManager != null) "available" else "unavailable"}")
            add("thermal headroom: ${if (headroomSupported) "available" else "not reported"}")
            add(
                "headroom thresholds: " +
                    if (Build.VERSION.SDK_INT >= 35) "queryable on this API level" else "requires API 35",
            )
            add("battery temperature: ${batteryTemperatureC?.let { "%.1f C".format(it) } ?: "not reported"}")
        }.joinToString("; ")

    private fun publish(status: Int? = _reading.value.status) {
        val headroom = readHeadroom()
        val sources = buildSet {
            if (status != null) add(ThermalSignalSource.PlatformThermalStatus)
            if (headroom != null) add(ThermalSignalSource.PlatformThermalHeadroom)
            if (batteryTemperatureC != null) add(ThermalSignalSource.BatteryTemperature)
            if (isEmpty()) add(ThermalSignalSource.Unavailable)
        }
        _reading.value = ThermalReading(
            status = status,
            headroom = headroom,
            batteryTemperatureC = batteryTemperatureC,
            sources = sources,
            atElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Reads thermal headroom, tolerating every documented failure mode.
     *
     * The API is rate limited, may throw on some implementations, and returns `NaN` when it has
     * nothing to say. All three become "no signal" rather than a misleading 0.0.
     */
    private fun readHeadroom(): Float? {
        if (!headroomSupported) return null
        val value = runCatching { powerManager?.getThermalHeadroom(HEADROOM_FORECAST_SECONDS) }
            .getOrElse {
                Log.w(TAG, "getThermalHeadroom is unavailable on this device", it)
                headroomSupported = false
                null
            } ?: return null
        return if (value.isNaN()) null else value
    }

    companion object {
        private const val TAG = "RoadguardThermal"

        /**
         * How far ahead to forecast.
         *
         * Long enough that Roadguard can change profile at the next segment boundary before the
         * platform throttles, short enough that the forecast still means something.
         */
        const val HEADROOM_FORECAST_SECONDS = 30

        /**
         * Poll cadence.
         *
         * `getThermalHeadroom` is documented to be rate limited; polling every 20 seconds stays
         * comfortably inside any published limit and matches how slowly a phone's temperature
         * actually moves.
         */
        const val HEADROOM_POLL_MS = 20_000L
    }
}

/**
 * Injects thermal readings, for tests and for the developer thermal harness.
 *
 * The harness exists because a hot car is not available during development, and pretending
 * otherwise would be dishonest. Anything it produces is tagged [ThermalSignalSource.Simulated]
 * and the Diagnostics screen shows "SIMULATED" in place of a reading, so no simulated figure can
 * ever be mistaken for a measurement.
 */
class SimulatedThermalSource(
    initial: ThermalReading = ThermalReading(
        status = ThermalPolicy.THERMAL_STATUS_NONE,
        headroom = 0.1f,
        batteryTemperatureC = 28f,
        sources = setOf(ThermalSignalSource.Simulated),
        atElapsedRealtimeMs = 0L,
    ),
) : ThermalSource {

    private val _reading = MutableStateFlow(initial)
    override val reading: StateFlow<ThermalReading> = _reading.asStateFlow()

    override fun start() = Unit
    override fun stop() = Unit
    override val describeCapability: String get() = "SIMULATED thermal source (developer harness)"

    fun emit(status: Int, headroom: Float?, batteryTemperatureC: Float?, atElapsedRealtimeMs: Long) {
        _reading.value = ThermalReading(
            status = status,
            headroom = headroom,
            batteryTemperatureC = batteryTemperatureC,
            sources = setOf(ThermalSignalSource.Simulated),
            atElapsedRealtimeMs = atElapsedRealtimeMs,
        )
    }

    /** Named scenarios matching the thermal test plan in `docs/thermal-management.md`. */
    fun emitScenario(scenario: ThermalScenario, atElapsedRealtimeMs: Long) = emit(
        status = scenario.status,
        headroom = scenario.headroom,
        batteryTemperatureC = scenario.batteryTemperatureC,
        atElapsedRealtimeMs = atElapsedRealtimeMs,
    )
}

/** The simulated conditions the thermal harness can drive. */
enum class ThermalScenario(
    val label: String,
    val status: Int,
    val headroom: Float,
    val batteryTemperatureC: Float,
) {
    Normal("Normal", ThermalPolicy.THERMAL_STATUS_NONE, 0.20f, 28f),
    Warm("Warm", ThermalPolicy.THERMAL_STATUS_LIGHT, 0.65f, 36f),
    Elevated("Elevated", ThermalPolicy.THERMAL_STATUS_MODERATE, 0.84f, 41f),
    Throttling("Throttling", ThermalPolicy.THERMAL_STATUS_SEVERE, 0.95f, 44f),
    Severe("Severe", ThermalPolicy.THERMAL_STATUS_CRITICAL, 1.0f, 48f),
}
