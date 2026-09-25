package io.github.tunlezah.roadguard.power

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.settings.PowerConnectedAction
import io.github.tunlezah.roadguard.settings.PowerDisconnectedAction
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.thermal.MapRenderBudget
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.planFor
import org.junit.Test

/**
 * [PowerPolicy] and [BatterySafeGate]: what a power change means for recording.
 *
 * Three things are pinned here:
 *
 *  * a nearly flat battery stops recording *cleanly*, before the phone dies mid-write, and a
 *    charging phone is never stopped for its battery;
 *  * battery-safe mode switches on when it should -- Battery Saver, the user's threshold, or
 *    unplugging with the battery-safe profile chosen -- and never while charging; and
 *  * battery-safe mode only ever *tightens* what the thermal engine allows, and does not flap
 *    with a loose power plug, because each change costs a camera rebind.
 */
class PowerPolicyTest {

    private fun power(
        percent: Int?,
        charging: Boolean = false,
        plug: PlugType = if (charging) PlugType.AlternatingCurrent else PlugType.None,
        saver: Boolean = false,
    ) = PowerState(batteryPercent = percent, isCharging = charging, plugType = plug, isPowerSaveMode = saver)

    // ── Battery level ───────────────────────────────────────────────────────────────────

    @Test
    fun `a nearly flat battery stops recording`() {
        val floor = PowerPolicy.HARD_STOP_BATTERY_PERCENT
        assertThat(PowerPolicy.evaluateBattery(power(floor), Settings())).isEqualTo(PowerAction.StopForLowBattery(floor))
        assertThat(PowerPolicy.evaluateBattery(power(1), Settings())).isEqualTo(PowerAction.StopForLowBattery(1))
    }

    @Test
    fun `a charging phone is never stopped for its battery`() {
        assertThat(PowerPolicy.evaluateBattery(power(1, charging = true), Settings())).isEqualTo(PowerAction.None)
    }

    @Test
    fun `the user's threshold asks for battery-safe mode`() {
        val settings = Settings(batterySafeThresholdPercent = 20)
        assertThat(PowerPolicy.evaluateBattery(power(20), settings)).isEqualTo(PowerAction.BatterySafeProfile)
        assertThat(PowerPolicy.evaluateBattery(power(21), settings)).isEqualTo(PowerAction.None)
    }

    @Test
    fun `an unknown battery level changes nothing`() {
        assertThat(PowerPolicy.evaluateBattery(power(null), Settings())).isEqualTo(PowerAction.None)
    }

    // ── Power transitions ───────────────────────────────────────────────────────────────

    @Test
    fun `connecting power follows the setting`() {
        val expected = mapOf(
            PowerConnectedAction.StartRecording to PowerAction.StartRecording,
            PowerConnectedAction.DoNothing to PowerAction.None,
            PowerConnectedAction.Prompt to PowerAction.PromptToStart,
        )
        assertThat(expected.keys).containsExactlyElementsIn(PowerConnectedAction.entries)
        for ((setting, action) in expected) {
            assertThat(PowerPolicy.onPowerConnected(Settings(onPowerConnected = setting))).isEqualTo(action)
        }
    }

    @Test
    fun `disconnecting power follows the setting`() {
        val expected = mapOf(
            PowerDisconnectedAction.ContinueRecording to PowerAction.None,
            PowerDisconnectedAction.StopRecording to PowerAction.StopRecording,
            PowerDisconnectedAction.StopAfterDelay to PowerAction.StopAfter(120),
            PowerDisconnectedAction.BatterySafeProfile to PowerAction.BatterySafeProfile,
        )
        assertThat(expected.keys).containsExactlyElementsIn(PowerDisconnectedAction.entries)
        for ((setting, action) in expected) {
            val settings = Settings(onPowerDisconnected = setting, powerDisconnectStopDelaySeconds = 120)
            assertThat(PowerPolicy.onPowerDisconnected(settings)).isEqualTo(action)
        }
    }

    // ── When battery-safe mode applies ──────────────────────────────────────────────────

    @Test
    fun `Battery Saver turns battery-safe mode on, however full the battery`() {
        assertThat(PowerPolicy.batterySafe(power(100, saver = true), Settings())).isTrue()
    }

    @Test
    fun `charging turns battery-safe mode off, even below the threshold`() {
        assertThat(PowerPolicy.batterySafe(power(5, charging = true), Settings())).isFalse()
        val unplugSetting = Settings(onPowerDisconnected = PowerDisconnectedAction.BatterySafeProfile)
        assertThat(PowerPolicy.batterySafe(power(5, charging = true), unplugSetting)).isFalse()
    }

    @Test
    fun `unplugging turns battery-safe mode on only when the user chose that`() {
        val chosen = Settings(onPowerDisconnected = PowerDisconnectedAction.BatterySafeProfile)
        assertThat(PowerPolicy.batterySafe(power(90), chosen)).isTrue()
        assertThat(PowerPolicy.batterySafe(power(90), Settings())).isFalse()
    }

    @Test
    fun `an unknown plug state is not treated as unplugged`() {
        // Before the first battery broadcast the plug is Unknown; the first reading of a session
        // must not flip the recording profile by accident.
        val chosen = Settings(onPowerDisconnected = PowerDisconnectedAction.BatterySafeProfile)
        assertThat(PowerPolicy.batterySafe(power(90, plug = PlugType.Unknown), chosen)).isFalse()
    }

    @Test
    fun `at or below the threshold on battery, battery-safe mode turns on`() {
        val settings = Settings(batterySafeThresholdPercent = 15)
        assertThat(PowerPolicy.batterySafe(power(15), settings)).isTrue()
        assertThat(PowerPolicy.batterySafe(power(16), settings)).isFalse()
    }

    @Test
    fun `a charger too weak to keep up still counts as on battery`() {
        // Plugged in but discharging: the battery is still running down.
        val weak = power(10, charging = false, plug = PlugType.Usb)
        assertThat(PowerPolicy.batterySafe(weak, Settings(batterySafeThresholdPercent = 15))).isTrue()
    }

    @Test
    fun `with no battery reading, battery-safe mode stays off`() {
        assertThat(PowerPolicy.batterySafe(power(null), Settings())).isFalse()
    }

    // ── What battery-safe mode changes ──────────────────────────────────────────────────

    @Test
    fun `restrain changes nothing when battery-safe mode is off`() {
        for (level in ThermalLevel.entries) {
            val plan = planFor(level)
            assertWithMessage(level.name).that(PowerPolicy.restrain(plan, batterySafe = false)).isEqualTo(plan)
        }
    }

    @Test
    fun `battery-safe mode only ever tightens the thermal plan`() {
        for (level in ThermalLevel.entries) {
            val plan = planFor(level)
            val safe = PowerPolicy.restrain(plan, batterySafe = true)
            assertWithMessage("$level map").that(safe.mapRenderBudget.ordinal).isAtLeast(plan.mapRenderBudget.ordinal)
            assertWithMessage("$level second camera").that(!safe.allowSecondCamera || plan.allowSecondCamera).isTrue()
            assertWithMessage("$level stabilisation").that(!safe.allowStabilisation || plan.allowStabilisation).isTrue()
            assertWithMessage("$level night assist").that(!safe.allowNightAssist || plan.allowNightAssist).isTrue()
            assertWithMessage("$level location").that(safe.locationIntervalMs).isAtLeast(plan.locationIntervalMs)
            assertWithMessage("$level weather").that(safe.weatherRefreshMinutes).isAtLeast(plan.weatherRefreshMinutes)
            assertWithMessage("$level quality steps").that(safe.qualityStepDown).isEqualTo(plan.qualityStepDown)
            val planCap = plan.frameRateCap
            if (planCap != null) assertWithMessage("$level fps").that(safe.frameRateCap).isAtMost(planCap)
        }
    }

    @Test
    fun `battery-safe mode caps recording at 720p30 with no extras`() {
        val safe = PowerPolicy.restrain(planFor(ThermalLevel.Normal), batterySafe = true)
        assertThat(safe.qualityCeiling).isEqualTo("HD")
        assertThat(safe.frameRateCap).isEqualTo(30)
        assertThat(safe.allowStabilisation).isFalse()
        assertThat(safe.allowSecondCamera).isFalse()
        assertThat(safe.allowNightAssist).isFalse()
        assertThat(safe.mapRenderBudget).isEqualTo(MapRenderBudget.Reduced)
        assertThat(safe.reduceUiAnimation).isTrue()
    }

    @Test
    fun `battery-safe mode leaves the evidence overlay and bitrate alone`() {
        for (level in ThermalLevel.entries) {
            val plan = planFor(level)
            val safe = PowerPolicy.restrain(plan, batterySafe = true)
            assertWithMessage(level.name).that(safe.allowVideoOverlay).isEqualTo(plan.allowVideoOverlay)
            assertWithMessage(level.name).that(safe.bitrateScale).isEqualTo(plan.bitrateScale)
        }
    }

    @Test
    fun `a stricter thermal ceiling survives battery-safe mode`() {
        val lower = planFor(ThermalLevel.Normal).copy(qualityCeiling = "SD", frameRateCap = 24)
        val safe = PowerPolicy.restrain(lower, batterySafe = true)
        assertThat(safe.qualityCeiling).isEqualTo("SD")
        assertThat(safe.frameRateCap).isEqualTo(24)
    }

    @Test
    fun `restraining twice is the same as restraining once`() {
        for (level in ThermalLevel.entries) {
            val once = PowerPolicy.restrain(planFor(level), batterySafe = true)
            assertWithMessage(level.name).that(PowerPolicy.restrain(once, batterySafe = true)).isEqualTo(once)
        }
    }

    @Test
    fun `entering battery-safe mode is applied at a segment boundary`() {
        // Resolution and frame rate are baked into the camera session, so the change is queued for
        // the next rollover rather than applied mid-clip.
        val normal = planFor(ThermalLevel.Normal)
        assertThat(PowerPolicy.restrain(normal, batterySafe = true).requiresRebindFrom(normal)).isTrue()
    }

    // ── Debounce ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a session starts from the current value with no debounce`() {
        val gate = BatterySafeGate(holdMs = 60_000)
        gate.reset(true)
        assertThat(gate.applied).isTrue()
        gate.reset(false)
        assertThat(gate.applied).isFalse()
    }

    @Test
    fun `a change is applied only once it has held for the hold time`() {
        val gate = BatterySafeGate(holdMs = 60_000)
        gate.reset(false)
        assertThat(gate.accept(true, nowMs = 0)).isFalse()
        assertThat(gate.accept(true, nowMs = 59_999)).isFalse()
        assertThat(gate.applied).isFalse()
        assertThat(gate.accept(true, nowMs = 60_000)).isTrue()
        assertThat(gate.applied).isTrue()
        // Once applied, the same value reports no further change.
        assertThat(gate.accept(true, nowMs = 120_000)).isFalse()
    }

    @Test
    fun `a flickering power supply never changes the profile`() {
        // A loose cigarette-lighter plug: the wanted value flips every ten seconds for an hour.
        val gate = BatterySafeGate(holdMs = 60_000)
        gate.reset(false)
        var now = 0L
        var wanted = true
        repeat(360) {
            assertThat(gate.accept(wanted, now)).isFalse()
            wanted = !wanted
            now += 10_000
        }
        assertThat(gate.applied).isFalse()
    }

    @Test
    fun `going back to the applied value cancels a pending change`() {
        val gate = BatterySafeGate(holdMs = 60_000)
        gate.reset(false)
        gate.accept(true, nowMs = 0)
        gate.accept(false, nowMs = 30_000)
        // The hold starts again from the next request, not from the first one.
        assertThat(gate.accept(true, nowMs = 40_000)).isFalse()
        assertThat(gate.accept(true, nowMs = 99_999)).isFalse()
        assertThat(gate.accept(true, nowMs = 100_000)).isTrue()
    }
}
