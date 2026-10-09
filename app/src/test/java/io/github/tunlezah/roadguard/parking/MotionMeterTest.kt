package io.github.tunlezah.roadguard.parking

import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.event.SensorSample
import org.junit.Test

/**
 * How the accelerometer stream is cut into the one-second windows every parking decision reads.
 *
 * Two properties matter more than the rest: a constant offset in the linear acceleration -- the
 * small bias a fused sensor carries on a budget phone -- must read as *still*, or a parked phone
 * would look as if it were moving forever; and gravity must be averaged as a direction, skipping
 * the zero placeholder the sensor source reports before its first gravity reading, or the parked
 * pose that later decides whether a moving phone is still in its mount would be wrong.
 */
class MotionMeterTest {

    private fun sample(
        atMs: Long,
        linearX: Float = 0f,
        linearY: Float = 0f,
        linearZ: Float = 0f,
        gravityX: Float = 0f,
        gravityY: Float = 9.81f,
        gravityZ: Float = 0f,
    ) = SensorSample(atMs * 1_000_000, linearX, linearY, linearZ, gravityX, gravityY, gravityZ)

    /** Feeds 25 samples spread over one second from [fromMs]; returns any windows they completed. */
    private fun MotionMeter.second(fromMs: Long, make: (index: Int, atMs: Long) -> SensorSample): List<MotionWindow> =
        (0 until 25).mapNotNull { index ->
            val at = fromMs + index * 40L
            accept(make(index, at))
        }

    @Test
    fun `a window is completed by the first sample a second after it began`() {
        val meter = MotionMeter()
        assertThat(meter.second(0) { _, at -> sample(at) }).isEmpty()

        val completed = meter.accept(sample(1_000))

        assertThat(completed).isNotNull()
        assertThat(completed!!.samples).isEqualTo(25)
        assertThat(completed.startMs).isEqualTo(0)
        assertThat(completed.endMs).isEqualTo(960)
    }

    @Test
    fun `a constant bias in the linear acceleration reads as still`() {
        val meter = MotionMeter()
        meter.second(0) { _, at -> sample(at, linearX = 0.3f, linearZ = -0.2f) }

        val window = meter.accept(sample(1_000))!!

        assertThat(window.vibration).isWithin(1e-4f).of(0f)
    }

    @Test
    fun `vibration is the spread across all three axes`() {
        val meter = MotionMeter()
        // Alternating +-0.2 on two axes, an even number of times so each mean is exactly zero:
        // each axis then has a standard deviation of 0.2, and together sqrt(0.08).
        for (index in 0 until 26) {
            val sign = if (index % 2 == 0) 1f else -1f
            assertThat(meter.accept(sample(index * 38L, linearX = 0.2f * sign, linearY = -0.2f * sign))).isNull()
        }

        val window = meter.accept(sample(1_000))!!

        assertThat(window.samples).isEqualTo(26)
        assertThat(window.vibration).isWithin(1e-3f).of(0.2828f)
    }

    @Test
    fun `gravity is averaged as a direction and the source's zero placeholder is skipped`() {
        val meter = MotionMeter()
        meter.second(0) { index, at ->
            if (index < 3) sample(at, gravityY = 0f) else sample(at, gravityX = 0f, gravityY = 6f, gravityZ = 8f)
        }

        val gravity = meter.accept(sample(1_000))!!.gravity!!

        assertThat(gravity.x).isWithin(1e-4f).of(0f)
        assertThat(gravity.y).isWithin(1e-4f).of(0.6f)
        assertThat(gravity.z).isWithin(1e-4f).of(0.8f)
    }

    @Test
    fun `a window with no gravity reading has no direction`() {
        val meter = MotionMeter()
        meter.second(0) { _, at -> sample(at, gravityY = 0f) }

        assertThat(meter.accept(sample(1_000))!!.gravity).isNull()
    }

    @Test
    fun `a clock that steps backwards closes the window rather than mixing two streams`() {
        val meter = MotionMeter()
        meter.accept(sample(5_000))
        meter.accept(sample(5_040))

        val completed = meter.accept(sample(100))

        assertThat(completed!!.samples).isEqualTo(2)
    }

    @Test
    fun `reset forgets the window in progress`() {
        val meter = MotionMeter()
        meter.second(0) { _, at -> sample(at) }
        meter.reset()

        // The next window starts afresh rather than closing the forgotten one.
        assertThat(meter.accept(sample(1_000))).isNull()
    }

    @Test
    fun `directions measure the angle between them`() {
        val down = Direction(0f, 1f, 0f)
        assertThat(down.degreesTo(down)).isWithin(1e-3f).of(0f)
        assertThat(down.degreesTo(Direction(0f, 0f, 1f))).isWithin(1e-3f).of(90f)
        assertThat(down.degreesTo(Direction(0f, -1f, 0f))).isWithin(1e-3f).of(180f)
        assertThat(Direction.of(0.0, 0.0, 0.0)).isNull()
        assertThat(Direction.of(0.0, 3.0, 4.0)).isEqualTo(Direction(0f, 0.6f, 0.8f))
    }
}
