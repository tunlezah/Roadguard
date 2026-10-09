package io.github.tunlezah.roadguard.parking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * When the vehicle counts as *verifiably* still.
 *
 * Pausing a recording that should have carried on loses footage, so every scenario here leans one
 * way: stillness must be seen by the accelerometer for the whole period, any one check can veto
 * it, and nothing that a parked car routinely does -- a door closing, GNSS wandering, a single bad
 * fix -- is allowed to fake movement and stop it ever pausing either.
 */
class StillnessTrackerTest {

    private val tracker = StillnessTracker()
    private var nowMs = 0L

    /** Portrait in a windscreen mount: gravity runs down the phone's long axis. */
    private val mounted = Direction(0f, 1f, 0f)

    /** [seconds] one-second windows, each arriving as it ends. */
    private fun observe(seconds: Int, vibration: Float = SENSOR_NOISE, gravity: Direction? = mounted) {
        repeat(seconds) {
            tracker.onWindow(MotionWindow(nowMs, nowMs + 960, samples = 25, vibration = vibration, gravity = gravity), nowMs + 1_000)
            nowMs += 1_000
        }
    }

    private fun fix(metresNorth: Double = 0.0, accuracy: Float = 8f, speed: Float? = 0f) {
        tracker.onFix(ParkingFix(nowMs, BASE_LATITUDE + metresNorth / METRES_PER_DEGREE, BASE_LONGITUDE, accuracy, speed))
    }

    private val stillFor: Long get() = tracker.stillForMs(nowMs)

    /** The mounted pose, tipped back by [degrees]. */
    private fun tipped(degrees: Float): Direction {
        val radians = Math.toRadians(degrees.toDouble())
        return Direction(0f, cos(radians).toFloat(), sin(radians).toFloat())
    }

    // ── The accelerometer ──────────────────────────────────────────────────────────────

    @Test
    fun `a quiet phone is still for as long as it has been watched`() {
        observe(10 * 60)

        assertThat(stillFor).isAtLeast(10 * 60_000L - 1_000L)
        assertThat(tracker.lastMovement).isNull()
    }

    @Test
    fun `with no motion data nothing is ever still, however quiet GNSS is`() {
        repeat(20 * 60) {
            fix()
            nowMs += 1_000
        }

        assertThat(stillFor).isEqualTo(0)
    }

    @Test
    fun `windows too thin to judge do not count as stillness`() {
        repeat(10 * 60) {
            tracker.onWindow(MotionWindow(nowMs, nowMs + 960, samples = 2, vibration = 0f, gravity = mounted), nowMs + 1_000)
            nowMs += 1_000
        }

        assertThat(stillFor).isEqualTo(0)
    }

    @Test
    fun `when the data stops the clock stops, and a hole in it starts the count again`() {
        observe(5 * 60)
        assertThat(stillFor).isGreaterThan(0)

        nowMs += 10_000
        // Nothing has been seen for ten seconds: no claim either way.
        assertThat(stillFor).isEqualTo(0)

        observe(30)
        assertThat(stillFor).isAtMost(30_000)
        assertThat(tracker.lastMovement?.cue).isEqualTo(MovementCue.SensorGap)
    }

    @Test
    fun `a car on the move is never still`() {
        observe(10 * 60, vibration = 0.4f)

        assertThat(stillFor).isEqualTo(0)
        assertThat(tracker.lastMovement?.cue).isEqualTo(MovementCue.Motion)
    }

    @Test
    fun `the first seconds of a drive are not stillness, before the motion can be called sustained`() {
        var longest = 0L
        repeat(10) {
            observe(1, vibration = 0.4f)
            longest = maxOf(longest, stillFor)
        }

        assertThat(longest).isEqualTo(0)
    }

    @Test
    fun `a door closing or a gust rocking the car does not restart the count`() {
        observe(2 * 60)
        val before = stillFor

        observe(2, vibration = 0.9f)
        observe(2 * 60)

        assertThat(stillFor).isEqualTo(before + 122_000)
        assertThat(tracker.lastMovement).isNull()
    }

    @Test
    fun `an engine left running keeps the count from starting, and it starts once the shaking stops`() {
        observe(5 * 60)
        observe(30, vibration = 0.3f)
        assertThat(stillFor).isEqualTo(0)

        observe(60)

        // A few seconds for the shaking to fall out of the history, then it counts from there.
        assertThat(stillFor).isAtLeast(50_000)
        assertThat(stillFor).isAtMost(60_000)
    }

    @Test
    fun `the phone being turned in its mount counts as movement when it goes on`() {
        observe(2 * 60)

        repeat(10) { index -> observe(1, gravity = tipped(10f * (index + 1))) }

        assertThat(stillFor).isEqualTo(0)
        assertThat(tracker.lastMovement?.cue).isEqualTo(MovementCue.Motion)
    }

    @Test
    fun `one knock that re-seats the phone does not restart the count`() {
        observe(2 * 60)
        val before = stillFor

        observe(61, gravity = tipped(20f))

        assertThat(stillFor).isEqualTo(before + 61_000)
    }

    @Test
    fun `the parked pose is where gravity points through the still phone`() {
        val leaning = Direction.of(0.0, 0.8, 0.6)!!

        observe(30, gravity = leaning)

        assertThat(tracker.pose!!.degreesTo(leaning)).isLessThan(0.5f)
    }

    // ── GNSS speed ─────────────────────────────────────────────────────────────────────

    @Test
    fun `one fix reporting walking pace is drift, two in a row are the car moving`() {
        observe(60)
        fix(speed = 3f)
        observe(1)
        fix(speed = 0f)
        observe(60)
        assertThat(tracker.lastMovement).isNull()

        fix(speed = 3f)
        observe(1)
        fix(speed = 3f)

        assertThat(stillFor).isEqualTo(0)
        assertThat(tracker.lastMovement?.cue).isEqualTo(MovementCue.Speed)
    }

    @Test
    fun `no trustworthy speed is no evidence of movement`() {
        observe(60)
        repeat(60) {
            fix(speed = null)
            observe(1)
        }

        assertThat(tracker.lastMovement).isNull()
        assertThat(stillFor).isAtLeast(119_000)
    }

    // ── GNSS position ──────────────────────────────────────────────────────────────────

    @Test
    fun `a parked receiver wandering inside its own accuracy never restarts the count`() {
        fix()
        repeat(10 * 60) { index ->
            observe(1)
            fix(metresNorth = if (index % 2 == 0) 30.0 else -30.0)
        }

        assertThat(tracker.lastMovement).isNull()
        assertThat(stillFor).isAtLeast(10 * 60_000L - 1_000L)
    }

    @Test
    fun `one stray position is ignored, two in a row beyond the radius are movement`() {
        fix()
        observe(60)
        fix(metresNorth = 200.0)
        observe(1)
        fix()
        observe(60)
        assertThat(tracker.lastMovement).isNull()

        fix(metresNorth = 200.0)
        observe(1)
        fix(metresNorth = 210.0)

        assertThat(stillFor).isEqualTo(0)
        assertThat(tracker.lastMovement?.cue).isEqualTo(MovementCue.Position)
    }

    @Test
    fun `a vague position is no evidence either way`() {
        fix()
        observe(10)
        repeat(10) { index ->
            fix(metresNorth = 500.0 * (index + 1), accuracy = 80f)
            observe(1)
        }

        assertThat(tracker.lastMovement).isNull()
    }

    @Test
    fun `poor accuracy widens what drift can explain`() {
        fix(accuracy = 25f)
        observe(5)
        // 60 m is inside 25 + 25 + 25 m: two fixes that could each be 25 m out, plus the margin.
        fix(metresNorth = 60.0, accuracy = 25f)
        observe(1)
        fix(metresNorth = 60.0, accuracy = 25f)

        assertThat(tracker.lastMovement).isNull()
    }

    private companion object {
        /** What a still phone's accelerometer reads: well under the stillness threshold. */
        const val SENSOR_NOISE = 0.02f

        const val BASE_LATITUDE = -35.2809
        const val BASE_LONGITUDE = 149.1300

        /** Close enough along a meridian for metres-scale offsets. */
        const val METRES_PER_DEGREE = 111_320.0
    }
}
