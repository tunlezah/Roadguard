package io.github.tunlezah.roadguard.event

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

private const val BASE_MS = 100_000L // non-zero elapsed-realtime base: nothing may assume t0 == 0
private val G = SensorSample.GRAVITY

/**
 * Feeds a speed profile in and returns the level after the last fix.
 *
 * @param speedsMps one entry per fix, oldest first; null models the filter reporting nothing.
 * @param intervalMs the fix interval -- 1000 normally, 5000 at thermal Critical.
 */
private fun BrakeDetector.replaySpeeds(
    speedsMps: List<Float?>,
    intervalMs: Long = 1_000L,
    startMs: Long = BASE_MS,
): BrakeLevel? {
    var at = startMs
    var level: BrakeLevel? = null
    speedsMps.forEach { speed ->
        onSpeed(speed, at)
        level = level(at)
        at += intervalMs
    }
    return level
}

/** A steady horizontal acceleration at 100 Hz, gravity along device +Y (cradled phone). */
private fun BrakeDetector.feedHorizontalAccel(amplitudeG: Float, fromMs: Long, durationMs: Long) {
    var at = fromMs
    while (at < fromMs + durationMs) {
        onSample(
            SensorSample(
                elapsedRealtimeNanos = at * 1_000_000L,
                linearX = amplitudeG * G,
                linearY = 0f,
                linearZ = 0f,
                gravityX = 0f,
                gravityY = G,
                gravityZ = 0f,
            ),
        )
        at += 10L
    }
}

/** km/h in, m/s out, so the profiles below read like a speedometer. */
private fun kmh(vararg values: Float): List<Float?> = values.map { it / 3.6f }

class BrakeDetectorTest {

    // ----- GNSS alone decides ------------------------------------------------------------

    @Test
    fun `steady cruise shows nothing`() {
        val level = BrakeDetector().replaySpeeds(kmh(60f, 60f, 60f, 60f, 60f, 60f))
        assertThat(level).isNull()
    }

    @Test
    fun `hard acceleration shows nothing`() {
        val level = BrakeDetector().replaySpeeds(kmh(20f, 35f, 50f, 65f, 80f))
        assertThat(level).isNull()
    }

    @Test
    fun `gentle engine-braking drift stays below the bar`() {
        // 1.1 m/s^2: noticeable lift-off deceleration, deliberately not indicated.
        val level = BrakeDetector().replaySpeeds(kmh(80f, 76f, 72f, 68f, 64f))
        assertThat(level).isNull()
    }

    @Test
    fun `a deliberate brake application lights the indicator`() {
        // 60 -> 44 km/h losing ~8 km/h per second: 2.2 m/s^2.
        val level = BrakeDetector().replaySpeeds(kmh(60f, 60f, 52f, 44f))
        assertThat(level).isEqualTo(BrakeLevel.Braking)
    }

    @Test
    fun `an emergency stop lights hard braking`() {
        // Losing ~19 km/h per second: 5.3 m/s^2.
        val level = BrakeDetector().replaySpeeds(kmh(80f, 80f, 61f, 42f))
        assertThat(level).isEqualTo(BrakeLevel.HardBraking)
    }

    @Test
    fun `the cruise before a short brake does not dilute the slope`() {
        // Ten seconds of steady 60 then two seconds of hard braking. A slope taken across a
        // fixed window would average the cruise in and miss it.
        val profile = kmh(60f, 60f, 60f, 60f, 60f, 60f, 60f, 60f, 60f, 60f, 42f, 25f)
        val level = BrakeDetector().replaySpeeds(profile)
        assertThat(level).isEqualTo(BrakeLevel.HardBraking)
    }

    @Test
    fun `parking-speed creep to a stop shows nothing`() {
        val level = BrakeDetector().replaySpeeds(kmh(6f, 4f, 2f, 0f))
        assertThat(level).isNull()
    }

    // ----- degraded GNSS -----------------------------------------------------------------

    @Test
    fun `five-second fixes at thermal Critical still detect sustained braking`() {
        val braking = BrakeDetector().replaySpeeds(kmh(90f, 90f, 50f), intervalMs = 5_000L)
        assertThat(braking).isEqualTo(BrakeLevel.Braking)

        val hard = BrakeDetector().replaySpeeds(kmh(90f, 90f, 12f), intervalMs = 5_000L)
        assertThat(hard).isEqualTo(BrakeLevel.HardBraking)
    }

    @Test
    fun `losing the speed puts the light out`() {
        val detector = BrakeDetector()
        detector.replaySpeeds(kmh(60f, 60f, 52f, 44f))
        assertThat(detector.level(BASE_MS + 3_000L)).isEqualTo(BrakeLevel.Braking)

        detector.onSpeed(null, BASE_MS + 4_000L)
        // The minimum hold keeps it lit briefly, then it goes out.
        assertThat(detector.level(BASE_MS + 3_000L + BrakeDetector.MIN_HOLD_MS + 1))
            .isNull()
    }

    @Test
    fun `a stale fix cannot keep the light on`() {
        val detector = BrakeDetector()
        detector.replaySpeeds(kmh(60f, 60f, 52f, 44f))
        val later = BASE_MS + 3_000L + BrakeDetector.STALE_MS + BrakeDetector.MIN_HOLD_MS + 1
        assertThat(detector.level(later)).isNull()
    }

    @Test
    fun `re-delivering the same fix changes nothing`() {
        val detector = BrakeDetector()
        var at = BASE_MS
        kmh(60f, 60f, 52f, 44f).forEach { speed ->
            detector.onSpeed(speed, at)
            detector.onSpeed(speed, at) // the tick loop re-emits state; must be harmless
            at += 1_000L
        }
        assertThat(detector.level(at - 1_000L)).isEqualTo(BrakeLevel.Braking)
    }

    // ----- ending and holding ------------------------------------------------------------

    @Test
    fun `the light goes out promptly once the speed stops falling`() {
        val detector = BrakeDetector()
        // Brake, then two seconds of steady speed.
        detector.replaySpeeds(kmh(60f, 60f, 42f, 25f, 25f, 25f))
        val end = BASE_MS + 5_000L
        // The steep drop is still inside any lookback, but braking has ended.
        assertThat(detector.level(end + BrakeDetector.MIN_HOLD_MS + 1)).isNull()
    }

    @Test
    fun `one flat fix in the middle of a brake does not blink the light`() {
        // Braking, one noisy flat fix, braking again: the hold bridges the gap.
        val detector = BrakeDetector()
        var at = BASE_MS
        val levels = kmh(60f, 52f, 52f, 44f).map { speed ->
            detector.onSpeed(speed, at)
            detector.level(at).also { at += 1_000L }
        }
        assertThat(levels.drop(1)).containsExactly(BrakeLevel.Braking, BrakeLevel.Braking, BrakeLevel.Braking)
    }

    @Test
    fun `an upgrade to hard braking is never held back`() {
        val detector = BrakeDetector()
        detector.replaySpeeds(kmh(80f, 80f, 72f)) // braking at 2.2 m/s^2
        assertThat(detector.level(BASE_MS + 2_000L)).isEqualTo(BrakeLevel.Braking)
        detector.onSpeed(52f / 3.6f, BASE_MS + 3_000L) // now 5.6 m/s^2
        assertThat(detector.level(BASE_MS + 3_000L)).isEqualTo(BrakeLevel.HardBraking)
    }

    @Test
    fun `a lit level releases with hysteresis not at the engage bar`() {
        // Engage at 2.2 m/s^2, then ease off to a slope between the release bar (0.9) and the
        // engage bar (1.5): the light must stay on rather than flap at the boundary.
        val detector = BrakeDetector()
        val level = detector.replaySpeeds(kmh(80f, 80f, 72f, 69f, 66f))
        assertWithMessage("eased braking should stay lit").that(level).isEqualTo(BrakeLevel.Braking)
    }

    // ----- the accelerometer's limited role ------------------------------------------------

    @Test
    fun `the accelerometer can escalate GNSS-confirmed braking to hard`() {
        val detector = BrakeDetector()
        // GNSS sees a 2.2 m/s^2 brake; the accelerometer reports a sustained 0.45 g.
        detector.feedHorizontalAccel(0.45f, fromMs = BASE_MS + 1_000L, durationMs = 2_000L)
        val level = detector.replaySpeeds(kmh(60f, 60f, 52f, 44f))
        assertThat(level).isEqualTo(BrakeLevel.HardBraking)
    }

    @Test
    fun `the accelerometer alone can never light the indicator`() {
        val detector = BrakeDetector()
        // A hard corner or hard acceleration: big sustained horizontal g, no speed drop.
        detector.feedHorizontalAccel(0.6f, fromMs = BASE_MS, durationMs = 3_000L)
        assertWithMessage("no GNSS at all").that(detector.level(BASE_MS + 3_000L)).isNull()

        detector.replaySpeeds(kmh(60f, 60f, 60f, 60f))
        assertWithMessage("GNSS steady").that(detector.level(BASE_MS + 3_000L)).isNull()
    }

    @Test
    fun `a stale accelerometer reading cannot escalate`() {
        val detector = BrakeDetector()
        detector.feedHorizontalAccel(0.45f, fromMs = BASE_MS - 10_000L, durationMs = 2_000L)
        val level = detector.replaySpeeds(kmh(60f, 60f, 52f, 44f))
        assertThat(level).isEqualTo(BrakeLevel.Braking)
    }

    // ----- lifecycle ----------------------------------------------------------------------

    @Test
    fun `reset drops everything`() {
        val detector = BrakeDetector()
        detector.replaySpeeds(kmh(80f, 80f, 61f, 42f))
        detector.reset()
        assertThat(detector.level(BASE_MS + 3_000L)).isNull()
        // And it works again afterwards.
        assertThat(detector.replaySpeeds(kmh(60f, 60f, 52f, 44f), startMs = BASE_MS + 60_000L))
            .isEqualTo(BrakeLevel.Braking)
    }

    // ----- cost ---------------------------------------------------------------------------

    /**
     * The battery question, answered the only way a JVM can: throughput.
     *
     * A drive feeds this detector 100 accelerometer samples and one fix per second. This test
     * pushes an hour of that -- 360,000 samples and 3,600 fixes, with a level read per fix --
     * through in one go and requires it to finish inside two seconds of wall clock, i.e. under
     * ~5.5 us per sample on a build machine. The real per-sample work is a handful of float
     * operations, so the margin is enormous; if this test ever gets slow, the sample path has
     * gained something that does not belong on it (allocation, logging, locking).
     */
    @Test
    fun `an hour of drive input processes in seconds -- the sample path stays trivial`() {
        val detector = BrakeDetector()
        val sample = SensorSample(0L, 0.5f, 0f, 0.3f, 0f, G, 0f)

        val startedAt = System.nanoTime()
        var at = BASE_MS
        var levelReads = 0
        repeat(3_600) { second ->
            repeat(100) { i ->
                detector.onSample(sample.copy(elapsedRealtimeNanos = (at + i * 10L) * 1_000_000L))
            }
            // A plausible mix: mostly cruise, a brake application every 50 s.
            val speed = if (second % 50 < 3) 60f - 8f * (second % 50) else 60f
            detector.onSpeed(speed / 3.6f, at)
            detector.level(at)
            levelReads++
            at += 1_000L
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertThat(levelReads).isEqualTo(3_600)
        assertWithMessage("an hour of sensor input should process in well under 2 s; took ${elapsedMs} ms")
            .that(elapsedMs)
            .isLessThan(2_000L)
    }
}
