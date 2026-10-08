package io.github.tunlezah.roadguard.parking

import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.event.SensorSample
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * The whole parking pipeline -- raw samples through [MotionMeter] into [StillnessTracker] and
 * [MovementWatch] -- on synthetic traces with random noise rather than tidy hand-made windows.
 *
 * The noise is seeded, so the traces are the same on every run, and sized from published phone
 * accelerometer figures: a few hundredths of a m/s^2 for a still phone, tenths for a cradled phone
 * on the move, whole m/s^2 for one walked about. Like [io.github.tunlezah.roadguard.event.SensorTrace],
 * these are stand-ins until real drives are captured; they prove the stages fit together and that
 * the thresholds sit where the reasoning says they should, not that a car behaves like this.
 */
class ParkingTraceTest {

    private val random = Random(20261008L)
    private var nowMs = 0L
    private val meter = MotionMeter()

    /** Portrait in a windscreen mount, leaning back a little with the glass. */
    private val mounted = Direction.of(0.0, 0.96, 0.28)!!

    /**
     * [seconds] of samples at [hz], each axis of linear acceleration Gaussian with [sigma] m/s^2,
     * and gravity pointing along [gravity] -- or along [gravityFor], second by second.
     */
    private fun samples(
        seconds: Int,
        hz: Int,
        sigma: Double,
        gravity: Direction = mounted,
        gravityFor: ((second: Int) -> Direction)? = null,
    ): Sequence<SensorSample> = sequence {
        val periodMs = 1_000L / hz
        repeat(seconds) { second ->
            val down = gravityFor?.invoke(second) ?: gravity
            repeat(hz) {
                yield(
                    SensorSample(
                        elapsedRealtimeNanos = nowMs * 1_000_000,
                        linearX = (random.nextGaussian() * sigma).toFloat(),
                        linearY = (random.nextGaussian() * sigma).toFloat(),
                        linearZ = (random.nextGaussian() * sigma).toFloat(),
                        gravityX = down.x * GRAVITY,
                        gravityY = down.y * GRAVITY,
                        gravityZ = down.z * GRAVITY,
                    ),
                )
                nowMs += periodMs
            }
        }
    }

    /** Runs samples through the meter, handing each completed window over as the batch lands. */
    private fun Sequence<SensorSample>.into(consume: (MotionWindow, Long) -> Unit) {
        forEach { sample -> meter.accept(sample)?.let { window -> consume(window, window.endMs + BATCH_LATENCY_MS) } }
    }

    private fun tilted(degrees: Double): Direction =
        Direction.of(0.0, cos(Math.toRadians(degrees)), sin(Math.toRadians(degrees)))!!

    @Test
    fun `a parked phone's own sensor noise is ten minutes of stillness`() {
        val tracker = StillnessTracker()

        samples(seconds = 10 * 60 + 2, hz = 100, sigma = STILL_PHONE_SIGMA).into(tracker::onWindow)

        assertThat(tracker.stillForMs(nowMs)).isAtLeast(10 * 60_000L)
        assertThat(tracker.lastMovement).isNull()
    }

    @Test
    fun `a drive's road vibration never lets the count start`() {
        val tracker = StillnessTracker()
        var longestStillMs = 0L

        samples(seconds = 10 * 60, hz = 100, sigma = ROAD_SIGMA).into { window, atMs ->
            tracker.onWindow(window, atMs)
            longestStillMs = maxOf(longestStillMs, tracker.stillForMs(atMs))
        }

        assertThat(longestStillMs).isEqualTo(0)
    }

    @Test
    fun `even a smooth road is not stillness`() {
        val tracker = StillnessTracker()
        var longestStillMs = 0L

        samples(seconds = 10 * 60, hz = 100, sigma = SMOOTH_ROAD_SIGMA).into { window, atMs ->
            tracker.onWindow(window, atMs)
            longestStillMs = maxOf(longestStillMs, tracker.stillForMs(atMs))
        }

        // A second or two now and then, never anything near the minutes a pause needs.
        assertThat(longestStillMs).isLessThan(60_000L)
    }

    @Test
    fun `drive, park, wait, drive off - each tier where it belongs`() {
        // Drive for two minutes, then stand still for ten and a bit.
        val tracker = StillnessTracker()
        samples(seconds = 2 * 60, hz = 100, sigma = ROAD_SIGMA).into(tracker::onWindow)
        samples(seconds = 10 * 60 + 10, hz = 100, sigma = STILL_PHONE_SIGMA).into(tracker::onWindow)
        assertThat(tracker.stillForMs(nowMs)).isAtLeast(10 * 60_000L)

        // Parked: the watch runs on the 25 Hz stream, from the pose the phone was still in.
        val watch = MovementWatch(tracker.pose)
        meter.reset()
        samples(seconds = 29 * 60, hz = 25, sigma = STILL_PHONE_SIGMA).into(watch::onWindow)
        assertThat(watch.isMoving).isFalse()

        // Driving off: moderate shaking, the phone still in its mount.
        val pullingAwayAt = nowMs
        var trippedAtMs: Long? = null
        samples(seconds = 20, hz = 25, sigma = PULLING_AWAY_SIGMA).into { window, atMs ->
            watch.onWindow(window, atMs)
            if (watch.isMoving && trippedAtMs == null) trippedAtMs = atMs
        }

        assertThat(trippedAtMs).isNotNull()
        assertThat(trippedAtMs!! - pullingAwayAt).isAtMost(10_000L)
    }

    @Test
    fun `walking off with the phone does not resume recording`() {
        val watch = MovementWatch(mounted)

        // A phone in a pocket: hard shaking, and swinging with every stride.
        samples(seconds = 5 * 60, hz = 25, sigma = WALKING_SIGMA, gravityFor = { second ->
            tilted(if (second % 2 == 0) 20.0 else -10.0)
        }).into(watch::onWindow)

        assertThat(watch.isMoving).isFalse()
    }

    @Test
    fun `a phone left lying on a seat in a car being driven does not count as the mounted phone`() {
        val watch = MovementWatch(mounted)

        samples(seconds = 2 * 60, hz = 25, sigma = PULLING_AWAY_SIGMA, gravity = Direction(0f, 0f, 1f))
            .into(watch::onWindow)

        assertThat(watch.isMoving).isFalse()
    }

    private companion object {
        const val GRAVITY = 9.81f

        /** The sensor hub holds samples for up to a quarter of a second before delivering them. */
        const val BATCH_LATENCY_MS = 250L

        /** Per axis, m/s^2: a still phone's accelerometer noise; about 0.035 combined. */
        const val STILL_PHONE_SIGMA = 0.02

        /** Per axis: a cradled phone on an ordinary sealed road; about 0.35 combined. */
        const val ROAD_SIGMA = 0.2

        /** Per axis: about as smooth as a road gets; about 0.14 combined. */
        const val SMOOTH_ROAD_SIGMA = 0.08

        /** Per axis: crawling out of a parking space; about 0.17 combined. */
        const val PULLING_AWAY_SIGMA = 0.1

        /** Per axis: a phone walked about in a pocket; about 2.6 combined. */
        const val WALKING_SIGMA = 1.5
    }
}
