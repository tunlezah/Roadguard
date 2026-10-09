package io.github.tunlezah.roadguard.event

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The derived gravity filter keeps the same half-second time constant at any sample rate.
 *
 * It used to be a fixed coefficient of 0.98 per sample, which is half a second only at exactly
 * 100 Hz. The parked watch samples at 25 Hz, and a platform is free to deliver slower than asked,
 * so the coefficient is now worked out from the time between samples.
 */
class GravitySmoothingTest {

    @Test
    fun `at 100 Hz it is the coefficient it always was`() {
        assertThat(EventSensorSource.gravitySmoothing(10_000_000L)).isWithin(0.001f).of(0.98f)
    }

    @Test
    fun `at 25 Hz it forgets faster per sample, so it forgets as fast per second`() {
        val per25Hz = EventSensorSource.gravitySmoothing(40_000_000L)
        val per100Hz = EventSensorSource.gravitySmoothing(10_000_000L)
        assertThat(per25Hz).isWithin(0.001f).of(0.926f)
        // One second at either rate leaves about the same share of the old estimate.
        val afterOneSecondAt25 = Math.pow(per25Hz.toDouble(), 25.0)
        val afterOneSecondAt100 = Math.pow(per100Hz.toDouble(), 100.0)
        assertThat(afterOneSecondAt25).isWithin(0.03).of(afterOneSecondAt100)
    }

    @Test
    fun `a long gap mostly forgets the old estimate`() {
        assertThat(EventSensorSource.gravitySmoothing(5_000_000_000L)).isLessThan(0.1f)
    }

    @Test
    fun `a nonsensical interval leaves the estimate alone`() {
        assertThat(EventSensorSource.gravitySmoothing(0L)).isEqualTo(1f)
        assertThat(EventSensorSource.gravitySmoothing(-5_000_000L)).isEqualTo(1f)
    }
}
