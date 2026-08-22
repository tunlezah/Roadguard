package io.github.tunlezah.roadguard.thermal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.BATTERY_CRITICAL_C
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.BATTERY_ELEVATED_C
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.BATTERY_HIGH_C
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.DEESCALATE_HOLD_MS
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.HEADROOM_CRITICAL
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.HEADROOM_ELEVATED
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.HEADROOM_HIGH
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.THERMAL_STATUS_CRITICAL
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.THERMAL_STATUS_EMERGENCY
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.THERMAL_STATUS_LIGHT
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.THERMAL_STATUS_MODERATE
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.THERMAL_STATUS_NONE
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.THERMAL_STATUS_SEVERE
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.THERMAL_STATUS_SHUTDOWN
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.planFor
import org.junit.Test

/**
 * Pins down the thermal mitigation ladder in [ThermalPolicy] and [ThermalPlan].
 *
 * For a dashcam three properties of this ladder are load-bearing and none of them are
 * observable from a UI test:
 *
 *  * **Recording is never stopped for heat.** A dashcam that shuts its recorder down when the
 *    windscreen mount gets hot has failed at the only job it has, so every rung of the ladder
 *    must still describe a running recorder.
 *  * **Cheap mitigations are spent first.** At [ThermalLevel.Elevated] the map, the preview and
 *    the second camera are shed while recording quality is left completely alone. Evidence
 *    quality must not be paid out for heat until there is nothing else left to give.
 *  * **No flapping.** Escalation is immediate because heat is a real risk, but relaxing a
 *    mitigation needs the cooler reading to hold for [DEESCALATE_HOLD_MS] and then only steps
 *    down one rung. Otherwise the recorder rebinds its camera session -- and cuts a segment --
 *    every time the SoC breathes.
 *
 * Plain JVM tests: [ThermalPolicy] is deliberately Android-free and every timestamp is passed
 * in explicitly, so this suite never touches a clock.
 */
class ThermalPolicyTest {

    private fun reading(
        status: Int? = null,
        headroom: Float? = null,
        batteryTemperatureC: Float? = null,
        atMs: Long = 0L,
    ) = ThermalReading(
        status = status,
        headroom = headroom,
        batteryTemperatureC = batteryTemperatureC,
        sources = emptySet(),
        atElapsedRealtimeMs = atMs,
    )

    // ---------------------------------------------------------------- classify: status

    @Test
    fun `classify maps every platform thermal status onto the four level ladder`() {
        val policy = ThermalPolicy()
        val cases = listOf(
            THERMAL_STATUS_NONE to ThermalLevel.Normal,
            THERMAL_STATUS_LIGHT to ThermalLevel.Normal,
            THERMAL_STATUS_MODERATE to ThermalLevel.Elevated,
            THERMAL_STATUS_SEVERE to ThermalLevel.High,
            THERMAL_STATUS_CRITICAL to ThermalLevel.Critical,
            THERMAL_STATUS_EMERGENCY to ThermalLevel.Critical,
            THERMAL_STATUS_SHUTDOWN to ThermalLevel.Critical,
        )

        // All seven PowerManager constants are covered, in order, with no gaps.
        assertThat(cases.map { it.first }).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()

        for ((status, expected) in cases) {
            assertThat(policy.classify(reading(status = status)))
                .isEqualTo(expected)
        }
    }

    // -------------------------------------------------------------- classify: headroom

    @Test
    fun `classify maps headroom alone with inclusive thresholds`() {
        val policy = ThermalPolicy()
        val cases = listOf(
            0.0f to ThermalLevel.Normal,
            0.10f to ThermalLevel.Normal,
            0.79f to ThermalLevel.Normal,
            HEADROOM_ELEVATED to ThermalLevel.Elevated,
            0.85f to ThermalLevel.Elevated,
            0.919f to ThermalLevel.Elevated,
            HEADROOM_HIGH to ThermalLevel.High,
            0.95f to ThermalLevel.High,
            0.989f to ThermalLevel.High,
            HEADROOM_CRITICAL to ThermalLevel.Critical,
            1.0f to ThermalLevel.Critical,
            1.60f to ThermalLevel.Critical,
        )

        assertThat(HEADROOM_ELEVATED).isEqualTo(0.80f)
        assertThat(HEADROOM_HIGH).isEqualTo(0.92f)
        assertThat(HEADROOM_CRITICAL).isEqualTo(0.99f)

        for ((headroom, expected) in cases) {
            assertThat(policy.classify(reading(headroom = headroom)))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `classify takes the maximum of the signals so a cool status cannot mask hot headroom`() {
        val policy = ThermalPolicy()

        // The platform's coarse status still says everything is fine, but headroom is the
        // early warning the specification asks Roadguard to act on.
        assertThat(policy.classify(reading(status = THERMAL_STATUS_NONE, headroom = 0.95f)))
            .isEqualTo(ThermalLevel.High)
        assertThat(policy.classify(reading(status = THERMAL_STATUS_LIGHT, headroom = HEADROOM_CRITICAL)))
            .isEqualTo(ThermalLevel.Critical)

        // ...and the reverse: a hot status is not softened by generous headroom.
        assertThat(policy.classify(reading(status = THERMAL_STATUS_SEVERE, headroom = 0.10f)))
            .isEqualTo(ThermalLevel.High)

        // Equal signals agree.
        assertThat(policy.classify(reading(status = THERMAL_STATUS_MODERATE, headroom = HEADROOM_ELEVATED)))
            .isEqualTo(ThermalLevel.Elevated)
    }

    @Test
    fun `classify ignores NaN headroom instead of reading it as cool or as an escalation`() {
        val policy = ThermalPolicy()

        // NaN alone means "no headroom signal", not "zero headroom" and not "throttling".
        assertThat(policy.classify(reading(headroom = Float.NaN)))
            .isEqualTo(ThermalLevel.Normal)

        // NaN must not drag a genuine status signal down...
        assertThat(policy.classify(reading(status = THERMAL_STATUS_SEVERE, headroom = Float.NaN)))
            .isEqualTo(ThermalLevel.High)

        // ...nor push it up.
        assertThat(policy.classify(reading(status = THERMAL_STATUS_NONE, headroom = Float.NaN)))
            .isEqualTo(ThermalLevel.Normal)
    }

    // --------------------------------------------------------- classify: battery fallback

    @Test
    fun `battery temperature escalates only when no platform signal exists`() {
        val policy = ThermalPolicy()
        assertThat(BATTERY_ELEVATED_C).isEqualTo(41f)
        assertThat(BATTERY_HIGH_C).isEqualTo(44f)
        assertThat(BATTERY_CRITICAL_C).isEqualTo(47f)

        val cases = listOf(
            25.0f to ThermalLevel.Normal,
            40.9f to ThermalLevel.Normal,
            BATTERY_ELEVATED_C to ThermalLevel.Elevated,
            43.9f to ThermalLevel.Elevated,
            BATTERY_HIGH_C to ThermalLevel.High,
            46.9f to ThermalLevel.High,
            BATTERY_CRITICAL_C to ThermalLevel.Critical,
            55.0f to ThermalLevel.Critical,
        )

        for ((temperature, expected) in cases) {
            assertThat(policy.classify(reading(batteryTemperatureC = temperature)))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `a platform status suppresses the battery fallback entirely`() {
        val policy = ThermalPolicy()

        // 50 C is above BATTERY_CRITICAL_C, yet the platform -- which knows the SoC, not a
        // charging battery -- says NONE. Battery temperature lags and is dominated by
        // charging, so it must not over-react over the top of a real signal.
        assertThat(policy.classify(reading(status = THERMAL_STATUS_NONE, batteryTemperatureC = 50f)))
            .isEqualTo(ThermalLevel.Normal)

        // A headroom reading is a platform signal too, even a very cool one.
        assertThat(policy.classify(reading(headroom = 0.05f, batteryTemperatureC = 50f)))
            .isEqualTo(ThermalLevel.Normal)

        // NaN headroom is *not* a signal, so the fallback comes back into play.
        assertThat(policy.classify(reading(headroom = Float.NaN, batteryTemperatureC = 50f)))
            .isEqualTo(ThermalLevel.Critical)

        // A hot battery never lowers a hot platform signal either.
        assertThat(policy.classify(reading(status = THERMAL_STATUS_SEVERE, batteryTemperatureC = 20f)))
            .isEqualTo(ThermalLevel.High)
    }

    @Test
    fun `a reading with no signal at all classifies as Normal`() {
        val policy = ThermalPolicy()
        assertThat(policy.classify(ThermalReading.unavailable(atElapsedRealtimeMs = 1_234L)))
            .isEqualTo(ThermalLevel.Normal)
    }

    // ------------------------------------------------------------------ accept: hysteresis

    @Test
    fun `escalation is immediate and skips rungs`() {
        val policy = ThermalPolicy()
        assertThat(policy.level).isEqualTo(ThermalLevel.Normal)

        // One reading is enough to go straight from Normal to Critical: heat is a real risk
        // and the mitigations at Critical are all reversible.
        assertThat(policy.accept(reading(status = THERMAL_STATUS_CRITICAL, atMs = 0L)))
            .isEqualTo(ThermalLevel.Critical)
        assertThat(policy.level).isEqualTo(ThermalLevel.Critical)
    }

    @Test
    fun `escalation steps up through the ladder one reading at a time`() {
        val policy = ThermalPolicy()
        val timeline = listOf(
            THERMAL_STATUS_NONE to ThermalLevel.Normal,
            THERMAL_STATUS_MODERATE to ThermalLevel.Elevated,
            THERMAL_STATUS_SEVERE to ThermalLevel.High,
            THERMAL_STATUS_EMERGENCY to ThermalLevel.Critical,
        )
        timeline.forEachIndexed { index, (status, expected) ->
            assertThat(policy.accept(reading(status = status, atMs = index * 1_000L)))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `de-escalation needs the hold to elapse and then drops exactly one level`() {
        val policy = ThermalPolicy()
        val hot = THERMAL_STATUS_EMERGENCY
        val cool = THERMAL_STATUS_NONE

        // t=0: straight to Critical.
        assertThat(policy.accept(reading(status = hot, atMs = 0L))).isEqualTo(ThermalLevel.Critical)

        // The cool run starts here. Nothing relaxes until the hold has fully elapsed.
        assertThat(policy.accept(reading(status = cool, atMs = 1_000L))).isEqualTo(ThermalLevel.Critical)
        assertThat(policy.accept(reading(status = cool, atMs = 45_000L))).isEqualTo(ThermalLevel.Critical)
        assertThat(policy.accept(reading(status = cool, atMs = 1_000L + DEESCALATE_HOLD_MS - 1)))
            .isEqualTo(ThermalLevel.Critical)

        // Exactly at the hold boundary the level relaxes by ONE rung, not all the way.
        assertThat(policy.accept(reading(status = cool, atMs = 1_000L + DEESCALATE_HOLD_MS)))
            .isEqualTo(ThermalLevel.High)

        // Each further rung costs another full hold.
        val afterHigh = 1_000L + DEESCALATE_HOLD_MS
        assertThat(policy.accept(reading(status = cool, atMs = afterHigh + DEESCALATE_HOLD_MS - 1)))
            .isEqualTo(ThermalLevel.High)
        assertThat(policy.accept(reading(status = cool, atMs = afterHigh + DEESCALATE_HOLD_MS)))
            .isEqualTo(ThermalLevel.Elevated)

        val afterElevated = afterHigh + DEESCALATE_HOLD_MS
        assertThat(policy.accept(reading(status = cool, atMs = afterElevated + DEESCALATE_HOLD_MS - 1)))
            .isEqualTo(ThermalLevel.Elevated)
        assertThat(policy.accept(reading(status = cool, atMs = afterElevated + DEESCALATE_HOLD_MS)))
            .isEqualTo(ThermalLevel.Normal)

        // Once at Normal a cool reading is a no-op, not an underflow.
        assertThat(policy.accept(reading(status = cool, atMs = 10_000_000L)))
            .isEqualTo(ThermalLevel.Normal)
    }

    @Test
    fun `a brief cool patch interrupted before the hold elapses does not relax anything`() {
        val policy = ThermalPolicy()
        assertThat(policy.accept(reading(status = THERMAL_STATUS_EMERGENCY, atMs = 0L)))
            .isEqualTo(ThermalLevel.Critical)

        // Cool for a while -- but not long enough.
        assertThat(policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 10_000L)))
            .isEqualTo(ThermalLevel.Critical)
        assertThat(policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 60_000L)))
            .isEqualTo(ThermalLevel.Critical)

        // Hot again: the cool run is abandoned, not banked.
        assertThat(policy.accept(reading(status = THERMAL_STATUS_EMERGENCY, atMs = 70_000L)))
            .isEqualTo(ThermalLevel.Critical)

        // A fresh cool run must serve the whole hold from *its own* start. 70_001 +
        // (HOLD - 1) would already be past the abandoned run's deadline, so this reading
        // proves the timer really restarted.
        assertThat(policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 70_001L)))
            .isEqualTo(ThermalLevel.Critical)
        assertThat(policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 70_001L + DEESCALATE_HOLD_MS - 1)))
            .isEqualTo(ThermalLevel.Critical)
        assertThat(policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 70_001L + DEESCALATE_HOLD_MS)))
            .isEqualTo(ThermalLevel.High)
    }

    @Test
    fun `a partial cool-down settles at the observed level and no lower`() {
        val policy = ThermalPolicy()
        policy.accept(reading(status = THERMAL_STATUS_EMERGENCY, atMs = 0L))

        // Still Elevated-hot, so the ladder may relax to Elevated but must stop there.
        assertThat(policy.accept(reading(status = THERMAL_STATUS_MODERATE, atMs = 1_000L)))
            .isEqualTo(ThermalLevel.Critical)
        assertThat(policy.accept(reading(status = THERMAL_STATUS_MODERATE, atMs = 1_000L + DEESCALATE_HOLD_MS)))
            .isEqualTo(ThermalLevel.High)
        assertThat(
            policy.accept(reading(status = THERMAL_STATUS_MODERATE, atMs = 1_000L + 2 * DEESCALATE_HOLD_MS)),
        ).isEqualTo(ThermalLevel.Elevated)
        assertThat(
            policy.accept(reading(status = THERMAL_STATUS_MODERATE, atMs = 1_000L + 9 * DEESCALATE_HOLD_MS)),
        ).isEqualTo(ThermalLevel.Elevated)
    }

    @Test
    fun `reset returns to Normal and clears the pending cool-down`() {
        val policy = ThermalPolicy()
        policy.accept(reading(status = THERMAL_STATUS_EMERGENCY, atMs = 0L))
        policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 1_000L))
        assertThat(policy.level).isEqualTo(ThermalLevel.Critical)

        policy.reset()
        assertThat(policy.level).isEqualTo(ThermalLevel.Normal)

        // The abandoned cool run must not be credited after a reset either.
        assertThat(policy.accept(reading(status = THERMAL_STATUS_SEVERE, atMs = 2_000L)))
            .isEqualTo(ThermalLevel.High)
        assertThat(policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 2_001L)))
            .isEqualTo(ThermalLevel.High)
    }

    @Test
    fun `constructor overrides let the hold be tuned`() {
        val policy = ThermalPolicy(deescalateHoldMs = 10_000L)
        policy.accept(reading(status = THERMAL_STATUS_SEVERE, atMs = 0L))
        assertThat(policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 100L)))
            .isEqualTo(ThermalLevel.High)
        assertThat(policy.accept(reading(status = THERMAL_STATUS_NONE, atMs = 10_100L)))
            .isEqualTo(ThermalLevel.Elevated)
    }

    // ----------------------------------------------------------------------- planFor

    @Test
    fun `Normal reduces nothing`() {
        val plan = planFor(ThermalLevel.Normal)
        assertThat(plan.level).isEqualTo(ThermalLevel.Normal)
        assertThat(plan.qualityStepDown).isEqualTo(0)
        assertThat(plan.frameRateCap).isNull()
        assertThat(plan.bitrateScale).isEqualTo(1.0f)
        assertThat(plan.previewFrameRateCap).isNull()
        assertThat(plan.mapRenderBudget).isEqualTo(MapRenderBudget.Full)
        assertThat(plan.allowVideoOverlay).isTrue()
        assertThat(plan.allowSecondCamera).isTrue()
        assertThat(plan.allowStabilisation).isTrue()
        assertThat(plan.allowNightAssist).isTrue()
        assertThat(plan.reduceUiAnimation).isFalse()
        assertThat(plan.warnUser).isFalse()
        assertThat(plan.userMessage).isNull()
    }

    @Test
    fun `Elevated sheds optional work but leaves recording quality completely untouched`() {
        val normal = planFor(ThermalLevel.Normal)
        val plan = planFor(ThermalLevel.Elevated)

        // THE contract of this file: heat must not cost recording quality until every cheap
        // mitigation has been spent. Elevated is identical to Normal on the recording axes.
        assertThat(plan.qualityStepDown).isEqualTo(0)
        assertThat(plan.frameRateCap).isNull()
        assertThat(plan.bitrateScale).isEqualTo(1.0f)
        assertThat(plan.qualityStepDown).isEqualTo(normal.qualityStepDown)
        assertThat(plan.frameRateCap).isEqualTo(normal.frameRateCap)
        assertThat(plan.bitrateScale).isEqualTo(normal.bitrateScale)

        // Applying Elevated still needs a rebind, but only because stabilisation and the
        // second camera are bind-time settings -- not because the encoder profile moved.
        assertThat(plan.requiresRebindFrom(normal)).isTrue()
        assertThat(plan.copy(allowStabilisation = true, allowSecondCamera = true).requiresRebindFrom(normal))
            .isFalse()

        // ...while the optional, evidence-irrelevant work is already being shed.
        assertThat(plan.mapRenderBudget).isEqualTo(MapRenderBudget.Reduced)
        assertThat(plan.allowSecondCamera).isFalse()
        assertThat(plan.previewFrameRateCap).isEqualTo(24)
        assertThat(plan.locationIntervalMs).isGreaterThan(normal.locationIntervalMs)
        assertThat(plan.weatherRefreshMinutes).isGreaterThan(normal.weatherRefreshMinutes)
        assertThat(plan.reduceUiAnimation).isTrue()

        // Nothing is worth alarming the driver about yet.
        assertThat(plan.warnUser).isFalse()
        assertThat(plan.userMessage).isNull()
    }

    @Test
    fun `High reduces recording cost stops the map and tells the driver`() {
        val plan = planFor(ThermalLevel.High)
        assertThat(plan.qualityStepDown).isGreaterThan(0)
        assertThat(plan.frameRateCap).isNotNull()
        assertThat(plan.frameRateCap!!).isGreaterThan(0)
        assertThat(plan.bitrateScale).isLessThan(1.0f)
        assertThat(plan.bitrateScale).isGreaterThan(0f)
        assertThat(plan.mapRenderBudget).isAnyOf(MapRenderBudget.Frozen, MapRenderBudget.Disabled)
        assertThat(plan.allowSecondCamera).isFalse()
        assertThat(plan.allowStabilisation).isFalse()
        assertThat(plan.allowNightAssist).isFalse()
        assertThat(plan.warnUser).isTrue()
        assertThat(plan.userMessage).isNotNull()

        // The overlay is a timestamp/GPS burn-in: evidentially useful, so it survives High.
        assertThat(plan.allowVideoOverlay).isTrue()
    }

    @Test
    fun `Critical is strictly more aggressive than High on every numeric axis`() {
        val high = planFor(ThermalLevel.High)
        val plan = planFor(ThermalLevel.Critical)

        assertThat(plan.qualityStepDown).isGreaterThan(high.qualityStepDown)
        assertThat(plan.bitrateScale).isLessThan(high.bitrateScale)
        assertThat(plan.frameRateCap!!).isLessThan(high.frameRateCap!!)
        assertThat(plan.previewFrameRateCap!!).isLessThan(high.previewFrameRateCap!!)
        assertThat(plan.locationIntervalMs).isGreaterThan(high.locationIntervalMs)
        assertThat(plan.weatherRefreshMinutes).isGreaterThan(high.weatherRefreshMinutes)

        assertThat(plan.allowVideoOverlay).isFalse()
        assertThat(plan.mapRenderBudget).isEqualTo(MapRenderBudget.Disabled)
        assertThat(plan.warnUser).isTrue()
        assertThat(plan.userMessage).isNotNull()
    }

    @Test
    fun `no rung of the ladder ever stops recording`() {
        // Recording reliability wins: the bottom rung must still describe a running recorder.
        // Every numeric axis stays inside a usable range, and the plan has no field that could
        // express "stop" -- if one is ever added, this test is the place that says why not.
        val stopShaped = Regex("stop|halt|suspend|abort|pauserecord", RegexOption.IGNORE_CASE)
        val fieldNames = ThermalPlan::class.java.declaredFields.map { it.name }
        assertThat(fieldNames).isNotEmpty()
        for (name in fieldNames) {
            assertThat(stopShaped.containsMatchIn(name)).isFalse()
        }

        for (level in ThermalLevel.entries) {
            val plan = planFor(level)
            assertThat(plan.bitrateScale).isGreaterThan(0f)
            assertThat(plan.bitrateScale).isAtMost(1.0f)
            assertThat(plan.qualityStepDown).isAtLeast(0)
            plan.frameRateCap?.let { assertThat(it).isAtLeast(24) }
        }

        val critical = planFor(ThermalLevel.Critical)
        assertThat(critical.frameRateCap!!).isAtLeast(24)
        assertThat(critical.bitrateScale).isAtLeast(0.5f)
        assertThat(critical.userMessage).contains("recording")
    }

    @Test
    fun `planFor stamps the level it was asked for`() {
        for (level in ThermalLevel.entries) {
            assertThat(planFor(level).level).isEqualTo(level)
        }
    }

    @Test
    fun `mitigations only ever tighten as the level rises`() {
        val ladder = ThermalLevel.entries.sortedBy { it.ordinalLevel }
        assertThat(ladder.map { it.ordinalLevel }).containsExactly(0, 1, 2, 3).inOrder()

        val plans = ladder.map { planFor(it) }
        for (i in 1 until plans.size) {
            val previous = plans[i - 1]
            val current = plans[i]
            val step = "${ladder[i - 1].label} -> ${ladder[i].label}"

            assertWithMessage("qualityStepDown $step").that(current.qualityStepDown)
                .isAtLeast(previous.qualityStepDown)
            assertWithMessage("bitrateScale $step").that(current.bitrateScale)
                .isAtMost(previous.bitrateScale)
            assertWithMessage("locationIntervalMs $step").that(current.locationIntervalMs)
                .isAtLeast(previous.locationIntervalMs)
            assertWithMessage("weatherRefreshMinutes $step").that(current.weatherRefreshMinutes)
                .isAtLeast(previous.weatherRefreshMinutes)
            // null == unlimited, i.e. the loosest possible cap.
            assertWithMessage("previewFrameRateCap $step").that(current.previewFrameRateCap ?: Int.MAX_VALUE)
                .isAtMost(previous.previewFrameRateCap ?: Int.MAX_VALUE)
            assertWithMessage("frameRateCap $step").that(current.frameRateCap ?: Int.MAX_VALUE)
                .isAtMost(previous.frameRateCap ?: Int.MAX_VALUE)
            // Permissions are monotone too: once withdrawn, never granted back by more heat.
            assertThat(!current.allowSecondCamera || previous.allowSecondCamera).isTrue()
            assertThat(!current.allowStabilisation || previous.allowStabilisation).isTrue()
            assertThat(!current.allowNightAssist || previous.allowNightAssist).isTrue()
            assertThat(!current.allowVideoOverlay || previous.allowVideoOverlay).isTrue()
            assertThat(current.mapRenderBudget.ordinal).isAtLeast(previous.mapRenderBudget.ordinal)
        }
    }

    // -------------------------------------------------------------- requiresRebindFrom

    @Test
    fun `requiresRebindFrom is false against itself and against no previous plan`() {
        for (level in ThermalLevel.entries) {
            val plan = planFor(level)
            assertThat(plan.requiresRebindFrom(plan)).isFalse()
            // The first plan of a session is applied at bind time anyway, so there is
            // nothing to rebind and no segment to cut.
            assertThat(plan.requiresRebindFrom(null)).isFalse()
        }
    }

    @Test
    fun `requiresRebindFrom is true for every session-baked field`() {
        val base = planFor(ThermalLevel.Normal)
        val sessionChanges = listOf<Pair<String, ThermalPlan>>(
            "qualityStepDown" to base.copy(qualityStepDown = 1),
            "frameRateCap" to base.copy(frameRateCap = 30),
            "bitrateScale" to base.copy(bitrateScale = 0.85f),
            "allowVideoOverlay" to base.copy(allowVideoOverlay = false),
            "allowSecondCamera" to base.copy(allowSecondCamera = false),
            "allowStabilisation" to base.copy(allowStabilisation = false),
        )
        for ((field, changed) in sessionChanges) {
            assertWithMessage("$field changed").that(changed.requiresRebindFrom(base)).isTrue()
            assertWithMessage("$field restored").that(base.requiresRebindFrom(changed)).isTrue()
        }
    }

    @Test
    fun `requiresRebindFrom is false when only non-session fields differ`() {
        val base = planFor(ThermalLevel.Normal)
        val nonSessionChanges = listOf<Pair<String, ThermalPlan>>(
            "locationIntervalMs" to base.copy(locationIntervalMs = 5_000L),
            "weatherRefreshMinutes" to base.copy(weatherRefreshMinutes = 120),
            "userMessage" to base.copy(userMessage = "Device is hot."),
            "warnUser" to base.copy(warnUser = true),
            "previewFrameRateCap" to base.copy(previewFrameRateCap = 10),
            "mapRenderBudget" to base.copy(mapRenderBudget = MapRenderBudget.Disabled),
            "reduceUiAnimation" to base.copy(reduceUiAnimation = true),
            "detachPreviewWhenHidden" to base.copy(detachPreviewWhenHidden = false),
            "allowNightAssist" to base.copy(allowNightAssist = false),
            "level" to base.copy(level = ThermalLevel.Critical),
        )
        for ((field, changed) in nonSessionChanges) {
            assertWithMessage("$field changed").that(changed.requiresRebindFrom(base)).isFalse()
        }
    }

    @Test
    fun `stepping between adjacent rungs rebinds only where recording actually changes`() {
        val normal = planFor(ThermalLevel.Normal)
        val elevated = planFor(ThermalLevel.Elevated)
        val high = planFor(ThermalLevel.High)
        val critical = planFor(ThermalLevel.Critical)

        // Elevated changes stabilisation and the second camera, both bind-time settings.
        assertThat(elevated.requiresRebindFrom(normal)).isTrue()
        assertThat(high.requiresRebindFrom(elevated)).isTrue()
        assertThat(critical.requiresRebindFrom(high)).isTrue()
    }
}
