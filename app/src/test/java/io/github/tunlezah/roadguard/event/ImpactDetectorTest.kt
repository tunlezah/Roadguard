package io.github.tunlezah.roadguard.event

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.data.EventKind
import io.github.tunlezah.roadguard.settings.EventSensitivity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

private const val SAMPLE_PERIOD_MS = 10L // 100 Hz, a typical SENSOR_DELAY_GAME rate
private const val TRACE_BASE_MS = 5_000L // non-zero elapsed-realtime base: nothing may assume t0 == 0
private val G = SensorSample.GRAVITY

private enum class Axis { X, Y, Z }

/**
 * Deterministic synthetic-trace builder: a 100 Hz stream with gravity along device +Y
 * (a phone upright in a windscreen cradle) onto which shaped pulses can be injected.
 *
 * Every value comes from the sample index, so a trace is byte-identical on every run --
 * no clocks, no randomness.
 */
private class TraceBuilder(
    private val periodMs: Long = SAMPLE_PERIOD_MS,
    private val baseMs: Long = TRACE_BASE_MS,
) {
    private val samples = mutableListOf<SensorSample>()
    private var cursorMs = 0L
    private var index = 0

    /** Elapsed-realtime, in ms, of the peak of the most recently injected half-sine pulse. */
    var lastPulsePeakMs: Long = -1L
        private set

    /** Steady mounted phone. [noiseG] adds a small deterministic wobble, in g, per axis. */
    fun quiet(durationMs: Long, noiseG: Float = 0f): TraceBuilder = apply {
        repeat((durationMs / periodMs).toInt()) {
            val i = index
            if (noiseG == 0f) {
                // Exact zeros, not the signed zeros a multiply would produce.
                add()
            } else {
                add(
                    lx = noiseG * G * sin(i * 0.37).toFloat(),
                    ly = noiseG * G * 0.6f * sin(i * 0.11).toFloat(),
                    lz = noiseG * G * 0.8f * cos(i * 0.53).toFloat(),
                )
            }
        }
    }

    /**
     * Gravity swings out to [peakRadians] and back over [durationMs] with no linear
     * acceleration at all -- a phone being picked up and put back, which is exactly the
     * signature the handling discriminator exists to catch.
     */
    fun gravitySwing(durationMs: Long, peakRadians: Double): TraceBuilder = apply {
        val steps = (durationMs / periodMs).toInt()
        repeat(steps) { step ->
            val phi = peakRadians * sin(PI * step * periodMs / durationMs)
            add(gx = (G * sin(phi)).toFloat(), gy = (G * cos(phi)).toFloat())
        }
    }

    /** A half-sine pulse of [amplitudeG] peaking at [durationMs] / 2, on a single axis. */
    fun halfSinePulse(amplitudeG: Float, durationMs: Long, axis: Axis): TraceBuilder = apply {
        val steps = (durationMs / periodMs).toInt()
        lastPulsePeakMs = baseMs + cursorMs + durationMs / 2
        for (step in 0..steps) {
            val value = amplitudeG * G * sin(PI * step * periodMs / durationMs).toFloat()
            addOnAxis(axis, value)
        }
    }

    /** A single-sample transient: the shape a dropped phone or a door slam makes. */
    fun spike(amplitudeG: Float, axis: Axis): TraceBuilder = apply {
        lastPulsePeakMs = baseMs + cursorMs
        addOnAxis(axis, amplitudeG * G)
    }

    fun build(): List<SensorSample> = samples.toList()

    private fun addOnAxis(axis: Axis, value: Float) = when (axis) {
        Axis.X -> add(lx = value)
        Axis.Y -> add(ly = value)
        Axis.Z -> add(lz = value)
    }

    private fun add(
        lx: Float = 0f,
        ly: Float = 0f,
        lz: Float = 0f,
        gx: Float = 0f,
        gy: Float = G,
        gz: Float = 0f,
    ) {
        samples += SensorSample((baseMs + cursorMs) * 1_000_000L, lx, ly, lz, gx, gy, gz)
        cursorMs += periodMs
        index++
    }
}

/** Lead-in, one pulse, tail. The default lead-in exceeds the handling look-back. */
private fun mountedPulseTrace(
    amplitudeG: Float,
    durationMs: Long,
    axis: Axis = Axis.X,
    leadInMs: Long = 3_000L,
    tailMs: Long = 1_000L,
): List<SensorSample> = TraceBuilder()
    .quiet(leadInMs)
    .halfSinePulse(amplitudeG, durationMs, axis)
    .quiet(tailMs)
    .build()

private fun ImpactDetector.replay(
    samples: List<SensorSample>,
    context: MotionContext,
): List<DetectedEvent> {
    val events = mutableListOf<DetectedEvent>()
    samples.forEach { sample -> onSample(sample) { context }?.let(events::add) }
    return events
}

/**
 * Pins down the multi-stage impact detector.
 *
 * For a dashcam the expensive mistake is not a missed threshold, it is a detector that cannot
 * tell a pothole from a collision: every false positive burns a protected slot and trains the
 * driver to ignore the alert, while a missed collision loses the only evidence that mattered.
 * These tests therefore drive synthetic traces through the real detector and assert the
 * discriminators that make the difference -- vertical versus in-plane energy, pulse duration,
 * gravity-direction stability before the peak, GNSS context (including its absence), the
 * cooldown, and the ordering of the three user-facing sensitivities.
 */
class ImpactDetectorTest {

    private val cruising = MotionContext(speedBeforeKmh = 60f, speedAfterKmh = 59f, latitude = 51.5, longitude = -0.12)
    private val collision = MotionContext(speedBeforeKmh = 60f, speedAfterKmh = 5f, latitude = 51.5, longitude = -0.12)
    private val medium = ImpactDetector.Tuning.forSensitivity(EventSensitivity.Medium)

    private fun detector(
        sensitivity: EventSensitivity = EventSensitivity.Medium,
        hasGyroscope: Boolean = true,
    ) = ImpactDetector(sensitivity = sensitivity, hasGyroscope = hasGyroscope)

    // ----- quiet driving ------------------------------------------------------------------

    @Test
    fun `quiet driving produces no candidate at all over a minute of samples`() {
        val samples = TraceBuilder().quiet(60_000L, noiseG = 0.5f).build()
        assertThat(samples).hasSize(6_000)
        // The wobble must be well under even the most sensitive threshold, or the test is a lie.
        assertThat(samples.maxOf { it.magnitudeG }).isLessThan(1f)

        for (sensitivity in EventSensitivity.entries) {
            assertWithMessage("quiet driving at $sensitivity")
                .that(detector(sensitivity).replay(samples, cruising))
                .isEmpty()
        }
    }

    // ----- the headline accept ------------------------------------------------------------

    @Test
    fun `a three g horizontal half sine over eighty ms is accepted with speed context`() {
        val event = detector().replay(mountedPulseTrace(3f, 80L), collision).single()

        assertThat(event.accepted).isTrue()
        assertThat(event.rejectionReasons).isEmpty()
        assertThat(event.kind).isEqualTo(EventKind.Impact)
        assertThat(event.features.peakG).isWithin(1e-3f).of(3f)
        assertThat(event.features.horizontalFraction).isWithin(1e-3f).of(1f)
        assertThat(event.features.durationAboveHalfPeakMs).isAtLeast(medium.minDurationMs)
        assertThat(event.confidence).isAtLeast(medium.confidenceThreshold)
        assertThat(event.handlingScore).isWithin(1e-4f).of(0f)
        assertThat(event.context).isEqualTo(collision)
        // The event is stamped with the peak, not with the sample that closed the window.
        assertThat(event.peakElapsedRealtimeNanos).isEqualTo(8_040L * ImpactDetector.NANOS_PER_MS)
        assertThat(event.describe()).contains("Protected")
        assertThat(event.describe()).contains("impact")
    }

    // ----- the headline false positive ----------------------------------------------------

    @Test
    fun `a vertical pothole pulse of the same peak is rejected as road surface`() {
        val pothole = detector().replay(mountedPulseTrace(3f, 120L, Axis.Y), collision).single()

        assertThat(pothole.accepted).isFalse()
        assertThat(pothole.features.horizontalFraction).isLessThan(medium.minHorizontalFraction)
        assertThat(pothole.rejectionReasons).hasSize(1)
        val reason = pothole.rejectionReasons.single()
        assertWithMessage("rejection names the vertical discriminator").that(reason).contains("vertical")
        assertWithMessage("rejection names the road surface").that(reason).contains("road surface")

        // Identical peak and duration in the road plane instead: accepted. Only the axis differs.
        val collisionEvent = detector().replay(mountedPulseTrace(3f, 120L, Axis.X), collision).single()
        assertThat(collisionEvent.accepted).isTrue()
        assertThat(collisionEvent.features.peakG).isWithin(1e-3f).of(pothole.features.peakG)
        assertThat(collisionEvent.features.durationAboveHalfPeakMs)
            .isEqualTo(pothole.features.durationAboveHalfPeakMs)
        assertThat(collisionEvent.confidence).isGreaterThan(pothole.confidence)
    }

    @Test
    fun `a single sample spike is rejected for duration`() {
        val samples = TraceBuilder().quiet(3_000L).spike(4f, Axis.X).quiet(1_000L).build()
        val event = detector().replay(samples, collision).single()

        assertThat(event.accepted).isFalse()
        assertThat(event.features.peakG).isWithin(1e-3f).of(4f)
        assertThat(event.features.durationAboveHalfPeakMs).isEqualTo(0L)
        assertThat(event.rejectionReasons).contains("too brief: 0 ms above half peak")
    }

    // ----- GNSS context -------------------------------------------------------------------

    @Test
    fun `a strong horizontal pulse while stationary is rejected as stationary`() {
        val samples = mountedPulseTrace(4f, 200L)
        val parked = MotionContext(speedBeforeKmh = 2f, speedAfterKmh = 0f, latitude = null, longitude = null)
        assertThat(parked.speedBeforeKmh!!).isLessThan(medium.minSpeedKmh)

        val event = detector().replay(samples, parked).single()
        assertThat(event.accepted).isFalse()
        assertThat(event.rejectionReasons).contains("vehicle stationary at 2 km/h")
        // It is the context alone that rejected it: the signal itself cleared the bar.
        assertThat(event.confidence).isAtLeast(medium.confidenceThreshold)
        assertThat(detector().replay(samples, collision).single().accepted).isTrue()
    }

    @Test
    fun `a strong wide horizontal pulse is accepted with no GNSS context at all`() {
        val samples = mountedPulseTrace(4f, 200L)
        for (hasGyroscope in listOf(true, false)) {
            val event = detector(hasGyroscope = hasGyroscope).replay(samples, MotionContext.NONE).single()
            assertWithMessage("losing GPS must not disable detection (gyro=$hasGyroscope)")
                .that(event.accepted).isTrue()
            assertThat(event.rejectionReasons).isEmpty()
            assertThat(event.context.speedBeforeKmh).isNull()
            assertThat(event.context.deltaSpeedKmh).isNull()
            assertThat(event.kind).isEqualTo(EventKind.Impact)
        }
    }

    // ----- phone handling -----------------------------------------------------------------

    @Test
    fun `handling the phone before the pulse rejects an otherwise accepted impact`() {
        val mounted = TraceBuilder()
            .quiet(3_000L)
            .halfSinePulse(3f, 120L, Axis.X)
            .quiet(1_000L)
            .build()
        val handled = TraceBuilder()
            .quiet(1_000L)
            .gravitySwing(2_000L, peakRadians = 2.2) // ~126 degrees out and back: picked up
            .halfSinePulse(3f, 120L, Axis.X)
            .quiet(1_000L)
            .build()

        // Same timing, same linear signal. Only the gravity history before the peak differs.
        assertThat(handled).hasSize(mounted.size)
        assertThat(handled.map { it.elapsedRealtimeNanos }).isEqualTo(mounted.map { it.elapsedRealtimeNanos })
        assertThat(handled.map { listOf(it.linearX, it.linearY, it.linearZ) })
            .isEqualTo(mounted.map { listOf(it.linearX, it.linearY, it.linearZ) })

        val mountedEvent = detector().replay(mounted, collision).single()
        val handledEvent = detector().replay(handled, collision).single()

        assertThat(mountedEvent.accepted).isTrue()
        assertThat(handledEvent.accepted).isFalse()
        assertThat(handledEvent.rejectionReasons).contains("phone appears to have been handled")
        assertThat(handledEvent.handlingScore).isGreaterThan(mountedEvent.handlingScore)
        assertThat(mountedEvent.handlingScore).isWithin(1e-4f).of(0f)
        // Materially lower, not marginally: less than half the mounted confidence.
        assertThat(handledEvent.confidence).isLessThan(mountedEvent.confidence / 2f)
        assertThat(handledEvent.features.peakG).isWithin(1e-3f).of(mountedEvent.features.peakG)
    }

    @Test
    fun `a small mounting vibration does not count as handling`() {
        val samples = TraceBuilder()
            .quiet(1_000L)
            .gravitySwing(2_000L, peakRadians = 0.05) // ~3 degrees of cradle wobble
            .halfSinePulse(3f, 120L, Axis.X)
            .quiet(1_000L)
            .build()
        val event = detector().replay(samples, collision).single()

        assertThat(event.accepted).isTrue()
        assertThat(event.handlingScore).isLessThan(
            ImpactDetector.HANDLING_REJECT_FRACTION * ImpactDetector.HANDLING_REFERENCE_RAD,
        )
    }

    // ----- cooldown -----------------------------------------------------------------------

    @Test
    fun `a second identical pulse inside the cooldown produces no event`() {
        data class Case(val name: String, val quietGapMs: Long, val expectedEvents: Int)
        val cases = listOf(
            Case("2 s after the peak", 2_000L, 1),
            Case("5 s after the peak", 5_000L, 1),
            Case("just inside the 15 s cooldown", 14_000L, 1),
            Case("just outside the 15 s cooldown", 16_000L, 2),
            Case("20 s after the peak", 20_000L, 2),
        )
        assertThat(medium.cooldownMs).isEqualTo(15_000L)

        for (case in cases) {
            val builder = TraceBuilder().quiet(3_000L)
            builder.halfSinePulse(4f, 200L, Axis.X)
            val firstPeakMs = builder.lastPulsePeakMs
            builder.quiet(case.quietGapMs)
            builder.halfSinePulse(4f, 200L, Axis.X)
            val secondPeakMs = builder.lastPulsePeakMs
            builder.quiet(1_000L)

            val events = detector().replay(builder.build(), collision)
            assertWithMessage("${case.name}: accepted events").that(events.size).isEqualTo(case.expectedEvents)
            assertWithMessage("${case.name}: all accepted").that(events.all { it.accepted }).isTrue()
            assertThat(events.first().peakElapsedRealtimeNanos)
                .isEqualTo(firstPeakMs * ImpactDetector.NANOS_PER_MS)
            if (case.expectedEvents == 2) {
                assertWithMessage("${case.name}: second peak")
                    .that(events[1].peakElapsedRealtimeNanos)
                    .isEqualTo(secondPeakMs * ImpactDetector.NANOS_PER_MS)
                assertThat(secondPeakMs - firstPeakMs).isGreaterThan(medium.cooldownMs)
            }
        }
    }

    // ----- sensitivity ordering -----------------------------------------------------------

    @Test
    fun `higher sensitivity accepts at least as readily as lower for the same pulse`() {
        data class Pulse(val name: String, val amplitudeG: Float, val durationMs: Long)
        val pulses = listOf(
            Pulse("2.0 g over 120 ms", 2f, 120L),
            Pulse("2.6 g over 120 ms", 2.6f, 120L),
            Pulse("3.0 g over 80 ms", 3f, 80L),
            Pulse("3.0 g over 120 ms", 3f, 120L),
            Pulse("3.6 g over 120 ms", 3.6f, 120L),
            Pulse("4.0 g over 200 ms", 4f, 200L),
        )
        val ladder = listOf(EventSensitivity.Low, EventSensitivity.Medium, EventSensitivity.High)

        for (pulse in pulses) {
            val samples = mountedPulseTrace(pulse.amplitudeG, pulse.durationMs)
            val outcomes = ladder.map { sensitivity ->
                val events = detector(sensitivity).replay(samples, collision)
                assertWithMessage("${pulse.name} at $sensitivity: at most one candidate")
                    .that(events.size).isAtMost(1)
                val event = events.singleOrNull()
                (event?.accepted ?: false) to (event?.confidence ?: 0f)
            }
            val (low, mediumOutcome, high) = outcomes

            assertWithMessage("${pulse.name}: medium accepted implies high accepted")
                .that(!mediumOutcome.first || high.first).isTrue()
            assertWithMessage("${pulse.name}: low accepted implies medium accepted")
                .that(!low.first || mediumOutcome.first).isTrue()
            assertWithMessage("${pulse.name}: high confidence >= medium")
                .that(high.second).isAtLeast(mediumOutcome.second)
            assertWithMessage("${pulse.name}: medium confidence >= low")
                .that(mediumOutcome.second).isAtLeast(low.second)
        }
    }

    @Test
    fun `the sensitivity ordering is not vacuous`() {
        // A pulse only High takes.
        val faint = mountedPulseTrace(2.6f, 120L)
        assertThat(detector(EventSensitivity.High).replay(faint, collision).single().accepted).isTrue()
        assertThat(detector(EventSensitivity.Medium).replay(faint, collision).single().accepted).isFalse()
        assertThat(detector(EventSensitivity.Low).replay(faint, collision)).isEmpty()

        // A pulse High and Medium take but Low does not.
        val moderate = mountedPulseTrace(3.6f, 120L)
        assertThat(detector(EventSensitivity.High).replay(moderate, collision).single().accepted).isTrue()
        assertThat(detector(EventSensitivity.Medium).replay(moderate, collision).single().accepted).isTrue()
        assertThat(detector(EventSensitivity.Low).replay(moderate, collision).single().accepted).isFalse()

        // A pulse all three take.
        val severe = mountedPulseTrace(4f, 200L)
        for (sensitivity in EventSensitivity.entries) {
            assertWithMessage("4 g over 200 ms at $sensitivity")
                .that(detector(sensitivity).replay(severe, collision).single().accepted).isTrue()
        }
    }

    // ----- hard braking -------------------------------------------------------------------

    /**
     * Medium tuning with the candidate threshold dropped below
     * [ImpactDetector.HARD_BRAKING_MAX_PEAK_G] so the hard-braking branch is reachable at all
     * -- see `no stock sensitivity can reach the hard braking classification` below.
     */
    private val brakingTuning = medium.copy(peakThresholdG = 0.8f)

    private fun brakingDetector(hasGyroscope: Boolean = true) = ImpactDetector(
        sensitivity = EventSensitivity.Medium,
        tuning = brakingTuning,
        hasGyroscope = hasGyroscope,
    )

    @Test
    fun `a sustained sub one point two g deceleration is classified as hard braking`() {
        val samples = mountedPulseTrace(1.1f, 500L, tailMs = 1_500L)
        val braking = MotionContext(speedBeforeKmh = 60f, speedAfterKmh = 20f, latitude = null, longitude = null)

        val event = brakingDetector().replay(samples, braking).single()

        assertThat(event.kind).isEqualTo(EventKind.HardBraking)
        assertThat(event.accepted).isTrue()
        assertThat(event.rejectionReasons).isEmpty()
        assertThat(event.features.peakG).isLessThan(ImpactDetector.HARD_BRAKING_MAX_PEAK_G)
        assertThat(event.features.durationAboveHalfPeakMs)
            .isAtLeast(ImpactDetector.HARD_BRAKING_MIN_DURATION_MS)
        assertThat(event.features.horizontalFraction).isWithin(1e-3f).of(1f)
        assertThat(event.context.deltaSpeedKmh!!).isWithin(1e-3f).of(-40f)
        // Hard braking is held to its own, higher bar.
        assertThat(event.confidence).isAtLeast(brakingTuning.hardBrakingConfidence)
        assertThat(event.describe()).contains("hardbraking")
    }

    @Test
    fun `hard braking needs a moderate peak a sustained window and a real speed drop`() {
        data class Case(
            val name: String,
            val amplitudeG: Float,
            val durationMs: Long,
            val context: MotionContext,
        )
        val cases = listOf(
            Case("peak too high for braking", 1.5f, 500L, MotionContext(60f, 20f, null, null)),
            Case("speed barely changed", 1.1f, 500L, MotionContext(60f, 55f, null, null)),
            Case("window too short", 1.1f, 200L, MotionContext(60f, 20f, null, null)),
            Case("no GNSS so no speed drop", 1.1f, 500L, MotionContext.NONE),
        )
        for (case in cases) {
            val event = brakingDetector()
                .replay(mountedPulseTrace(case.amplitudeG, case.durationMs, tailMs = 1_500L), case.context)
                .single()
            assertWithMessage("${case.name}: classified").that(event.kind).isEqualTo(EventKind.Impact)
        }
    }

    @Test
    fun `no stock sensitivity can reach the hard braking classification`() {
        // Documents current behaviour: every stock candidate threshold sits above the
        // hard-braking peak ceiling, so classify() can never return HardBraking in shipped
        // configuration. See the report accompanying these tests.
        for (sensitivity in EventSensitivity.entries) {
            val tuning = ImpactDetector.Tuning.forSensitivity(sensitivity)
            assertWithMessage("$sensitivity peak threshold vs hard braking ceiling")
                .that(tuning.peakThresholdG)
                .isGreaterThan(ImpactDetector.HARD_BRAKING_MAX_PEAK_G)
        }

        val textbookBraking = mountedPulseTrace(1.1f, 500L, tailMs = 1_500L)
        val braking = MotionContext(speedBeforeKmh = 60f, speedAfterKmh = 20f, latitude = null, longitude = null)
        for (sensitivity in EventSensitivity.entries) {
            assertWithMessage("stock $sensitivity on a textbook hard-braking trace")
                .that(detector(sensitivity).replay(textbookBraking, braking))
                .isEmpty()
        }
    }

    // ----- gyroscope ----------------------------------------------------------------------

    @Test
    fun `no gyroscope yields lower confidence than a gyroscope for the identical trace`() {
        val samples = mountedPulseTrace(3f, 120L)
        val withGyro = detector(hasGyroscope = true).replay(samples, collision).single()
        val withoutGyro = detector(hasGyroscope = false).replay(samples, collision).single()

        assertThat(withoutGyro.features).isEqualTo(withGyro.features)
        assertThat(withoutGyro.confidence).isLessThan(withGyro.confidence)
        assertThat(withoutGyro.confidence)
            .isWithin(1e-4f)
            .of(withGyro.confidence * ImpactDetector.NO_GYRO_CONFIDENCE_SCALE)
        // The penalty is a nudge, not a veto: a clear impact still gets protected.
        assertThat(withoutGyro.accepted).isTrue()
    }

    @Test
    fun `the missing gyroscope penalty can decide a borderline pulse`() {
        val samples = mountedPulseTrace(3f, 80L)
        assertThat(detector(hasGyroscope = true).replay(samples, collision).single().accepted).isTrue()
        assertThat(detector(hasGyroscope = false).replay(samples, collision).single().accepted).isFalse()
    }

    // ----- state --------------------------------------------------------------------------

    @Test
    fun `reset clears history and cooldown`() {
        val builder = TraceBuilder().quiet(3_000L)
        builder.halfSinePulse(4f, 200L, Axis.X)
        builder.quiet(5_000L) // well inside the 15 s cooldown
        builder.halfSinePulse(4f, 200L, Axis.X)
        builder.quiet(1_000L)
        val samples = builder.build()

        val subject = detector()
        val events = mutableListOf<DetectedEvent>()
        samples.forEach { sample ->
            subject.onSample(sample) { collision }?.let { event ->
                events += event
                subject.reset()
                assertThat(subject.historySnapshot()).isEmpty()
            }
        }

        // Without the reset the cooldown swallows the second pulse (see the cooldown test).
        assertThat(events).hasSize(2)
        assertThat(events.map { it.accepted }).containsExactly(true, true)
    }

    @Test
    fun `historySnapshot retains at most HISTORY_SECONDS of samples`() {
        val budgetNanos = ImpactDetector.HISTORY_SECONDS * ImpactDetector.NANOS_PER_SECOND
        val samples = TraceBuilder().quiet(10_000L, noiseG = 0.2f).build()
        val subject = detector()

        samples.forEach { sample ->
            subject.onSample(sample)
            val history = subject.historySnapshot()
            val span = history.last().elapsedRealtimeNanos - history.first().elapsedRealtimeNanos
            assertWithMessage("history span never exceeds the budget").that(span).isAtMost(budgetNanos)
        }

        val history = subject.historySnapshot()
        val newest = samples.last().elapsedRealtimeNanos
        assertThat(history.last().elapsedRealtimeNanos).isEqualTo(newest)
        assertThat(history.first().elapsedRealtimeNanos).isEqualTo(newest - budgetNanos)
        assertThat(history).hasSize(401) // 4 s inclusive of both ends at 100 Hz
        assertThat(history.map { it.elapsedRealtimeNanos }).isInOrder()
        assertThat(history).isEqualTo(samples.takeLast(401))
    }

    @Test
    fun `history is not trimmed before the budget is reached`() {
        val samples = TraceBuilder().quiet(2_000L).build()
        val subject = detector()
        samples.forEach { subject.onSample(it) }
        assertThat(subject.historySnapshot()).isEqualTo(samples)
    }
}
