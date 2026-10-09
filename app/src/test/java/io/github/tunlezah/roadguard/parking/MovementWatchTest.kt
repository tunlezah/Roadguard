package io.github.tunlezah.roadguard.parking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * When a parked recording comes back by itself.
 *
 * The watch has two ways to be wrong, and they are not equal. Missing the car driving off loses
 * footage, so a cradled phone shaken the way a car shakes it must trip it within seconds. Tripping
 * when the car has not moved only costs battery -- unless the phone is in the driver's pocket, in
 * which case it records the inside of a pocket. So the shaking must be moderate, steady and with
 * the phone still sitting the way it sat when the car parked.
 */
class MovementWatchTest {

    private var nowMs = 0L

    /** Portrait in a windscreen mount: gravity runs down the phone's long axis. */
    private val mounted = Direction(0f, 1f, 0f)

    /** Lying on a seat or a table, screen up. */
    private val lyingFlat = Direction(0f, 0f, 1f)

    /** [seconds] one-second windows, each arriving as it ends. */
    private fun MovementWatch.observe(seconds: Int, vibration: Float, gravity: Direction? = mounted) {
        repeat(seconds) {
            onWindow(MotionWindow(nowMs, nowMs + 960, samples = 25, vibration = vibration, gravity = gravity), nowMs + 1_000)
            nowMs += 1_000
        }
    }

    private fun MovementWatch.fix(speed: Float?) {
        onFix(ParkingFix(nowMs, -35.2809, 149.1300, 8f, speed))
        nowMs += 1_000
    }

    private fun tipped(degrees: Float): Direction {
        val radians = Math.toRadians(degrees.toDouble())
        return Direction(0f, cos(radians).toFloat(), sin(radians).toFloat())
    }

    @Test
    fun `a car pulling away with the phone in its mount trips the watch within seconds`() {
        val watch = MovementWatch(mounted)

        watch.observe(4, vibration = 0.3f)
        assertThat(watch.isMoving).isFalse()

        watch.observe(1, vibration = 0.3f)
        assertThat(watch.isMoving).isTrue()
        assertThat(watch.movement?.cue).isEqualTo(MovementCue.Motion)
    }

    @Test
    fun `a car tilted by a slope or by braking is still a car`() {
        val watch = MovementWatch(mounted)

        watch.observe(5, vibration = 0.3f, gravity = tipped(15f))

        assertThat(watch.isMoving).isTrue()
    }

    @Test
    fun `the same shaking with the phone lying flat is not the car`() {
        val watch = MovementWatch(mounted)

        watch.observe(2 * 60, vibration = 0.3f, gravity = lyingFlat)

        assertThat(watch.isMoving).isFalse()
    }

    @Test
    fun `walking with the phone shakes it far harder than a car does`() {
        val watch = MovementWatch(mounted)

        // Even upright in a shirt pocket, which can match the mounted pose.
        watch.observe(2 * 60, vibration = 4f)

        assertThat(watch.isMoving).isFalse()
    }

    @Test
    fun `a phone turning in a hand does not count, however it shakes`() {
        val watch = MovementWatch(mounted)

        repeat(60) { index -> watch.observe(1, vibration = 0.3f, gravity = tipped(if (index % 2 == 0) 10f else -10f)) }

        assertThat(watch.isMoving).isFalse()
    }

    @Test
    fun `a door closing or someone climbing in for a moment is not driving off`() {
        val watch = MovementWatch(mounted)

        watch.observe(3, vibration = 0.6f)
        watch.observe(60, vibration = 0.02f)

        assertThat(watch.isMoving).isFalse()
    }

    @Test
    fun `a parked car's sensor noise never trips it`() {
        val watch = MovementWatch(mounted)

        watch.observe(30 * 60, vibration = 0.03f)

        assertThat(watch.isMoving).isFalse()
    }

    @Test
    fun `seconds either side of a silence in the data are not one run`() {
        val watch = MovementWatch(mounted)

        watch.observe(4, vibration = 0.3f)
        nowMs += 10_000
        watch.observe(4, vibration = 0.3f)
        assertThat(watch.isMoving).isFalse()

        watch.observe(1, vibration = 0.3f)
        assertThat(watch.isMoving).isTrue()
    }

    @Test
    fun `GNSS at driving pace on consecutive fixes trips it, whatever the pose`() {
        val watch = MovementWatch(parkedPose = null)

        watch.fix(speed = 8f)
        watch.fix(speed = 8f)
        assertThat(watch.isMoving).isFalse()

        watch.fix(speed = 8f)
        assertThat(watch.isMoving).isTrue()
        assertThat(watch.movement?.cue).isEqualTo(MovementCue.Speed)
    }

    @Test
    fun `walking or jogging pace on GNSS is not driving`() {
        val watch = MovementWatch(mounted)

        repeat(60) { watch.fix(speed = if (it % 2 == 0) 1.5f else 3.5f) }

        assertThat(watch.isMoving).isFalse()
    }

    @Test
    fun `with no parked pose only GNSS can trip it`() {
        val watch = MovementWatch(parkedPose = null)

        watch.observe(60, vibration = 0.3f)

        assertThat(watch.isMoving).isFalse()
    }

    @Test
    fun `once tripped it stays tripped`() {
        val watch = MovementWatch(mounted)
        watch.observe(5, vibration = 0.3f)
        val tripped = watch.movement

        watch.observe(60, vibration = 0.02f)

        assertThat(watch.movement).isEqualTo(tripped)
    }
}
