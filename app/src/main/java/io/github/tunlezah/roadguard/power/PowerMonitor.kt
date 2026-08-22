package io.github.tunlezah.roadguard.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches battery and charging state.
 *
 * Uses the sticky `ACTION_BATTERY_CHANGED` broadcast rather than polling: it is delivered on
 * every meaningful change and reading the sticky intent gives the current state immediately at
 * registration, so there is nothing to poll and no wake-ups Roadguard did not need. The
 * `ACTION_POWER_CONNECTED`/`DISCONNECTED` broadcasts are also observed, because they are the
 * events that map to "the ignition came on" and "the ignition went off" in a vehicle.
 */
class PowerMonitor(private val context: Context) {

    private val _state = MutableStateFlow(PowerState())
    val state: StateFlow<PowerState> = _state.asStateFlow()

    private val _transitions = MutableStateFlow<PowerTransition?>(null)

    /** The most recent connect/disconnect, for the recorder's power policy to act on. */
    val transitions: StateFlow<PowerTransition?> = _transitions.asStateFlow()

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> _state.value = intent.toPowerState()
                Intent.ACTION_POWER_CONNECTED -> _transitions.value =
                    PowerTransition.Connected(System.currentTimeMillis())

                Intent.ACTION_POWER_DISCONNECTED -> _transitions.value =
                    PowerTransition.Disconnected(System.currentTimeMillis())
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching {
            // The sticky battery intent is returned here, giving the current state for free.
            context.registerReceiver(receiver, filter)?.let { sticky ->
                _state.value = sticky.toPowerState()
            }
            registered = true
        }.onFailure { Log.w(TAG, "power monitor registration failed", it) }
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun consumeTransition() {
        _transitions.value = null
    }

    private fun Intent.toPowerState(): PowerState {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temperature = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }
        return PowerState(
            batteryPercent = percent,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            plugType = when (plugged) {
                0 -> PlugType.None
                BatteryManager.BATTERY_PLUGGED_USB -> PlugType.Usb
                BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AlternatingCurrent
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.Wireless
                BatteryManager.BATTERY_PLUGGED_DOCK -> PlugType.Dock
                else -> PlugType.Unknown
            },
            temperatureC = temperature,
            isPowerSaveMode = powerManager?.isPowerSaveMode == true,
        )
    }

    private companion object {
        const val TAG = "RoadguardPower"
    }
}

sealed interface PowerTransition {
    val atEpochMs: Long

    data class Connected(override val atEpochMs: Long) : PowerTransition
    data class Disconnected(override val atEpochMs: Long) : PowerTransition
}
