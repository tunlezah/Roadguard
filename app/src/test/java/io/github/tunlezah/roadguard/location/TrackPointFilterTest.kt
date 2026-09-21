package io.github.tunlezah.roadguard.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which fixes become track points, and which movement counts as distance. Pure JVM. */
class TrackPointFilterTest {

    private val filter = TrackPointFilter()

    // Roughly 0.0001° of latitude is 11 m; 0.00002° is about 2 m.
    private val lat = -35.2000
    private val lon = 149.1500

    @Test
    fun `the first usable fix is always written and adds no distance`() {
        val decision = filter.consider(lat, lon, 5f, 0L)

        assertThat(decision.write).isTrue()
        assertThat(decision.distanceMetres).isEqualTo(0.0)
    }

    @Test
    fun `a fix that has not moved five metres is skipped`() {
        filter.consider(lat, lon, 5f, 0L)

        val parked = filter.consider(lat + 0.00002, lon, 5f, 1_000L)

        assertThat(parked.write).isFalse()
        assertThat(parked.distanceMetres).isEqualTo(0.0)
    }

    @Test
    fun `movement is written and counted`() {
        filter.consider(lat, lon, 5f, 0L)

        val moved = filter.consider(lat + 0.0010, lon, 5f, 1_000L) // ~111 m north

        assertThat(moved.write).isTrue()
        assertThat(moved.distanceMetres).isWithin(3.0).of(111.0)
    }

    @Test
    fun `a stationary heartbeat is written every thirty seconds but never counted`() {
        filter.consider(lat, lon, 5f, 0L)

        assertThat(filter.consider(lat, lon, 5f, 29_999L).write).isFalse()
        val heartbeat = filter.consider(lat + 0.00001, lon, 5f, 30_000L)
        assertThat(heartbeat.write).isTrue()
        assertThat(heartbeat.distanceMetres).isEqualTo(0.0)
        // The reference point stays where the car really is, so wander does not walk it away.
        assertThat(filter.consider(lat + 0.00003, lon, 5f, 31_000L).write).isFalse()
    }

    @Test
    fun `a fix with poor accuracy is ignored entirely`() {
        filter.consider(lat, lon, 5f, 0L)

        val poor = filter.consider(lat + 0.01, lon, 120f, 1_000L)

        assertThat(poor).isEqualTo(TrackPointFilter.Decision.SKIP)
        // And it did not move the reference point: the next good fix measures from the last written one.
        assertThat(filter.consider(lat + 0.0010, lon, 5f, 2_000L).distanceMetres).isWithin(3.0).of(111.0)
    }

    @Test
    fun `an unknown accuracy is accepted`() {
        assertThat(filter.consider(lat, lon, null, 0L).write).isTrue()
    }

    @Test
    fun `reset forgets the last point`() {
        filter.consider(lat, lon, 5f, 0L)
        filter.reset()

        assertThat(filter.consider(lat, lon, 5f, 1_000L)).isEqualTo(TrackPointFilter.Decision(write = true, distanceMetres = 0.0))
    }

    @Test
    fun `the thresholds are the documented ones`() {
        assertThat(TrackPointFilter.MIN_DISTANCE_M).isEqualTo(5.0)
        assertThat(TrackPointFilter.HEARTBEAT_MS).isEqualTo(30_000L)
        assertThat(TrackPointFilter.MAX_ACCURACY_M).isEqualTo(75f)
    }
}
