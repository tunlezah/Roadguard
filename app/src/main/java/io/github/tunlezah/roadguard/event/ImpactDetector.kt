package io.github.tunlezah.roadguard.event

import io.github.tunlezah.roadguard.data.EventKind
import io.github.tunlezah.roadguard.settings.EventSensitivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Multi-stage impact detector.
 *
 * A single "acceleration above a threshold" test is not usable in a car: a pothole at 60 km/h
 * and a low-speed collision produce peaks of the same order at a windscreen mount, and simply
 * picking a threshold trades false alarms for missed crashes. So this detector runs several
 * stages and combines their outputs into a confidence score:
 *
 *  1. **Rolling history.** Every sample goes into a ring buffer covering
 *     [HISTORY_SECONDS] seconds. Nothing is decided from an instantaneous value.
 *  2. **Candidate.** A sample whose magnitude exceeds the sensitivity's peak threshold opens
 *     a candidate window; the window closes [WINDOW_AFTER_MS] after the peak.
 *  3. **Feature extraction.** Over the window: peak, energy (the integral of magnitude, which
 *     separates a broad collision signature from a sharp single-spike road input), the
 *     duration spent above half the peak, and how the energy divides between the vertical and
 *     horizontal axes.
 *  4. **Discrimination.** Vertical-dominated events are road surface, not collisions.
 *     Handling the phone is rejected by watching how much the gravity vector moved in the
 *     seconds *before* the peak -- a phone being picked up rotates, a mounted phone does not.
 *  5. **Context.** GNSS speed before and after, when available, raises confidence for a real
 *     impact and suppresses events while stationary. With no fix the bar is raised instead of
 *     the check being skipped.
 *  6. **Confidence and cooldown.** The weighted score must clear the sensitivity's bar, and a
 *     cooldown prevents one collision producing a burst of events.
 *
 * ### Honesty about the numbers
 *
 * Every threshold here is a documented starting point derived from published collision and
 * telematics figures (see `docs/research/event-detection.md`) and from the physics of the
 * discriminators, **not** from Roadguard measurements on a real vehicle. They are
 * deliberately gathered into [Tuning] so they can be replaced wholesale once real drive
 * traces exist, and `docs/event-detection.md` states plainly that this is not a certified
 * crash detector.
 */
class ImpactDetector(
    private val sensitivity: EventSensitivity = EventSensitivity.Medium,
    private val tuning: Tuning = Tuning.forSensitivity(sensitivity),
    private val hasGyroscope: Boolean = false,
) {

    private val history = ArrayDeque<SensorSample>()
    private var candidate: Candidate? = null
    private var cooldownUntilNanos: Long = Long.MIN_VALUE

    /**
     * Feeds one sample in.
     *
     * @return a [DetectedEvent] on the sample that closes a successful candidate window, or
     *   null. Callers must treat a non-null result as "protect footage now".
     */
    fun onSample(sample: SensorSample, context: () -> MotionContext = { MotionContext.NONE }): DetectedEvent? {
        appendHistory(sample)

        val open = candidate
        if (open != null) {
            open.accumulate(sample)
            if (sample.elapsedRealtimeNanos - open.peakNanos >= WINDOW_AFTER_MS * NANOS_PER_MS) {
                candidate = null
                return evaluate(open, context())
            }
            return null
        }

        if (sample.elapsedRealtimeNanos < cooldownUntilNanos) return null
        if (sample.magnitudeG < tuning.peakThresholdG) return null

        candidate = Candidate(sample, handlingScore = handlingScore(sample.elapsedRealtimeNanos))
            .also { it.accumulate(sample) }
        return null
    }

    /** Drops all state, for example when recording stops or the sensor is unregistered. */
    fun reset() {
        history.clear()
        candidate = null
        cooldownUntilNanos = Long.MIN_VALUE
    }

    /** The rolling history, oldest first. Exposed so a real drive can be captured to a trace. */
    fun historySnapshot(): List<SensorSample> = history.toList()

    private fun appendHistory(sample: SensorSample) {
        history.addLast(sample)
        val cutoff = sample.elapsedRealtimeNanos - HISTORY_SECONDS * NANOS_PER_SECOND
        while (history.isNotEmpty() && history.first().elapsedRealtimeNanos < cutoff) {
            history.removeFirst()
        }
    }

    /**
     * How much the gravity direction moved over the [HANDLING_LOOKBACK_MS] before a peak, in
     * radians.
     *
     * A phone clipped into a cradle keeps a near-constant gravity direction; a phone being
     * picked up, adjusted or dropped swings it. This is the cheapest reliable way to tell a
     * road event from a human event, and it needs no gyroscope.
     */
    private fun handlingScore(peakNanos: Long): Float {
        val from = peakNanos - HANDLING_LOOKBACK_MS * NANOS_PER_MS
        val window = history.filter { it.elapsedRealtimeNanos in from until peakNanos }
        if (window.size < 4) return 0f

        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        window.forEach { sample ->
            val magnitude = sqrt(
                (sample.gravityX * sample.gravityX + sample.gravityY * sample.gravityY +
                    sample.gravityZ * sample.gravityZ).toDouble(),
            )
            if (magnitude > 1e-3) {
                sumX += sample.gravityX / magnitude
                sumY += sample.gravityY / magnitude
                sumZ += sample.gravityZ / magnitude
            }
        }
        val count = window.size
        val meanLength = sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ) / count
        // Mean resultant length is 1.0 when perfectly steady; convert to an angular spread.
        return (1.0 - meanLength.coerceIn(0.0, 1.0)).toFloat() * ANGULAR_SPREAD_SCALE
    }

    private fun evaluate(candidate: Candidate, context: MotionContext): DetectedEvent? {
        val features = candidate.features()

        // --- Stage 4: reject road-surface inputs -------------------------------------------
        val verticalDominated = features.horizontalFraction < tuning.minHorizontalFraction
        val tooBrief = features.durationAboveHalfPeakMs < tuning.minDurationMs

        // --- Stage 5: context --------------------------------------------------------------
        val speedBefore = context.speedBeforeKmh
        val stationary = speedBefore != null && speedBefore < tuning.minSpeedKmh
        val speedKnown = speedBefore != null

        // --- Confidence --------------------------------------------------------------------
        val peakTerm = normalise(features.peakG - tuning.peakThresholdG, tuning.peakThresholdG)
        val energyTerm = normalise(features.energyGSeconds, tuning.energyReferenceGSeconds)
        val horizontalTerm = normalise(
            features.horizontalFraction - tuning.minHorizontalFraction,
            1f - tuning.minHorizontalFraction,
        )
        val speedTerm = when {
            !speedKnown -> NEUTRAL_SPEED_TERM
            else -> {
                val magnitudeTerm = normalise(speedBefore, SPEED_REFERENCE_KMH)
                val changeTerm = context.deltaSpeedKmh
                    ?.let { normalise(abs(it), DELTA_SPEED_REFERENCE_KMH) }
                    ?: 0.5f
                (0.5f * magnitudeTerm + 0.5f * changeTerm)
            }
        }
        val handlingPenalty = normalise(candidate.handlingScore, HANDLING_REFERENCE_RAD)

        var confidence = (
            WEIGHT_PEAK * peakTerm +
                WEIGHT_ENERGY * energyTerm +
                WEIGHT_HORIZONTAL * horizontalTerm +
                WEIGHT_SPEED * speedTerm
            ) * (1f - HANDLING_PENALTY_WEIGHT * handlingPenalty)

        // Without a gyroscope Roadguard has one fewer independent signal, so it is a little
        // less willing to call an event: the accelerometer-only path is real but weaker.
        if (!hasGyroscope) confidence *= NO_GYRO_CONFIDENCE_SCALE

        val rejections = buildList {
            if (verticalDominated) add("vertical-dominated: looks like road surface")
            if (tooBrief) add("too brief: ${features.durationAboveHalfPeakMs} ms above half peak")
            if (stationary) add("vehicle stationary at ${speedBefore?.toInt()} km/h")
            if (handlingPenalty > HANDLING_REJECT_FRACTION) add("phone appears to have been handled")
        }

        val kind = classify(features, context)
        val bar = if (kind == EventKind.HardBraking) tuning.hardBrakingConfidence else tuning.confidenceThreshold
        val accepted = rejections.isEmpty() && confidence >= bar

        if (accepted) {
            cooldownUntilNanos = candidate.peakNanos + tuning.cooldownMs * NANOS_PER_MS
        }

        return DetectedEvent(
            kind = kind,
            accepted = accepted,
            confidence = confidence.coerceIn(0f, 1f),
            features = features,
            context = context,
            peakElapsedRealtimeNanos = candidate.peakNanos,
            rejectionReasons = rejections,
            handlingScore = candidate.handlingScore,
        ).takeIf { accepted || it.rejectionReasons.isNotEmpty() || confidence > 0f }
    }

    /**
     * Impact versus hard braking.
     *
     * A collision is a short, high peak. Hard braking is a lower but sustained horizontal
     * deceleration with a clear speed drop. Distinguishing them lets Roadguard protect both
     * while describing them honestly.
     */
    private fun classify(features: EventFeatures, context: MotionContext): EventKind {
        val sustained = features.durationAboveHalfPeakMs >= HARD_BRAKING_MIN_DURATION_MS
        val moderatePeak = features.peakG < HARD_BRAKING_MAX_PEAK_G
        val decelerating = context.deltaSpeedKmh?.let { it <= -HARD_BRAKING_MIN_DELTA_KMH } ?: false
        return if (sustained && moderatePeak && decelerating) EventKind.HardBraking else EventKind.Impact
    }

    private fun normalise(value: Float, reference: Float): Float =
        if (reference <= 0f) 0f else (value / reference).coerceIn(0f, 1f)

    /** Mutable accumulator for one candidate window. */
    private class Candidate(peakSample: SensorSample, val handlingScore: Float) {
        var peakNanos: Long = peakSample.elapsedRealtimeNanos
        var peakG: Float = peakSample.magnitudeG
        private var previousNanos: Long = peakSample.elapsedRealtimeNanos
        private var energy = 0f
        private var verticalEnergy = 0f
        private var horizontalEnergy = 0f
        private val samples = mutableListOf<SensorSample>()

        fun accumulate(sample: SensorSample) {
            val dtSeconds = ((sample.elapsedRealtimeNanos - previousNanos).coerceAtLeast(0L)).toFloat() /
                NANOS_PER_SECOND
            previousNanos = sample.elapsedRealtimeNanos
            samples += sample
            energy += sample.magnitudeG * dtSeconds
            verticalEnergy += abs(sample.verticalComponent) / SensorSample.GRAVITY * dtSeconds
            horizontalEnergy += sample.horizontalMagnitude / SensorSample.GRAVITY * dtSeconds
            if (sample.magnitudeG > peakG) {
                peakG = sample.magnitudeG
                peakNanos = sample.elapsedRealtimeNanos
            }
        }

        fun features(): EventFeatures {
            val half = peakG / 2f
            val above = samples.filter { it.magnitudeG >= half }
            val durationMs = if (above.size < 2) {
                0L
            } else {
                (above.last().elapsedRealtimeNanos - above.first().elapsedRealtimeNanos) / NANOS_PER_MS
            }
            val total = verticalEnergy + horizontalEnergy
            return EventFeatures(
                peakG = peakG,
                energyGSeconds = energy,
                durationAboveHalfPeakMs = durationMs,
                horizontalFraction = if (total <= 0f) 0f else horizontalEnergy / total,
                sampleCount = samples.size,
            )
        }
    }

    /**
     * All tunable numbers in one place.
     *
     * @param peakThresholdG opens a candidate window. Published telematics and airbag
     *   literature puts even minor collisions well above 2 g at the vehicle body, while
     *   potholes and speed bumps at a windscreen mount commonly reach 1-2 g, which is why the
     *   medium default sits at 2.5 g and relies on the discriminators rather than on the
     *   threshold alone.
     * @param minHorizontalFraction how much of the event energy must be in the road plane.
     * @param minDurationMs a genuine impact is not a single sample.
     * @param minSpeedKmh below this the vehicle is treated as stationary.
     */
    data class Tuning(
        val peakThresholdG: Float,
        val confidenceThreshold: Float,
        val hardBrakingConfidence: Float,
        val minHorizontalFraction: Float,
        val minDurationMs: Long,
        val minSpeedKmh: Float,
        val cooldownMs: Long,
        val energyReferenceGSeconds: Float,
    ) {
        companion object {
            fun forSensitivity(sensitivity: EventSensitivity): Tuning = when (sensitivity) {
                EventSensitivity.Low -> Tuning(
                    peakThresholdG = 3.5f,
                    confidenceThreshold = 0.62f,
                    hardBrakingConfidence = 0.70f,
                    minHorizontalFraction = 0.45f,
                    minDurationMs = 30L,
                    minSpeedKmh = 8f,
                    cooldownMs = 20_000L,
                    energyReferenceGSeconds = 0.50f,
                )

                EventSensitivity.Medium -> Tuning(
                    peakThresholdG = 2.5f,
                    confidenceThreshold = 0.50f,
                    hardBrakingConfidence = 0.60f,
                    minHorizontalFraction = 0.35f,
                    minDurationMs = 20L,
                    minSpeedKmh = 5f,
                    cooldownMs = 15_000L,
                    energyReferenceGSeconds = 0.45f,
                )

                EventSensitivity.High -> Tuning(
                    peakThresholdG = 1.8f,
                    confidenceThreshold = 0.40f,
                    hardBrakingConfidence = 0.50f,
                    minHorizontalFraction = 0.28f,
                    minDurationMs = 15L,
                    minSpeedKmh = 3f,
                    cooldownMs = 12_000L,
                    energyReferenceGSeconds = 0.40f,
                )
            }
        }
    }

    companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** How much sensor history is retained for feature extraction and trace capture. */
        const val HISTORY_SECONDS = 4L

        /** How long after the peak the candidate window stays open. */
        const val WINDOW_AFTER_MS = 350L

        /** How far back the handling check looks. */
        const val HANDLING_LOOKBACK_MS = 2_000L

        /** Converts a mean-resultant-length deficit into an approximate angular spread. */
        const val ANGULAR_SPREAD_SCALE = 1.6f

        /** Angular spread treated as "definitely being handled". */
        const val HANDLING_REFERENCE_RAD = 0.35f

        /** Above this fraction of the handling reference the candidate is rejected outright. */
        const val HANDLING_REJECT_FRACTION = 0.85f

        const val WEIGHT_PEAK = 0.35f
        const val WEIGHT_ENERGY = 0.25f
        const val WEIGHT_HORIZONTAL = 0.20f
        const val WEIGHT_SPEED = 0.20f
        const val HANDLING_PENALTY_WEIGHT = 0.60f

        /** Used for the speed term when no GNSS fix is available: neither helps nor hurts. */
        const val NEUTRAL_SPEED_TERM = 0.45f
        const val SPEED_REFERENCE_KMH = 40f
        const val DELTA_SPEED_REFERENCE_KMH = 20f

        /** Confidence multiplier when the device has no gyroscope. */
        const val NO_GYRO_CONFIDENCE_SCALE = 0.92f

        const val HARD_BRAKING_MIN_DURATION_MS = 250L
        const val HARD_BRAKING_MAX_PEAK_G = 1.2f
        const val HARD_BRAKING_MIN_DELTA_KMH = 20f
    }
}

/** Features extracted from a candidate window. */
data class EventFeatures(
    val peakG: Float,
    val energyGSeconds: Float,
    val durationAboveHalfPeakMs: Long,
    /** Fraction of the event energy in the road plane rather than along gravity. */
    val horizontalFraction: Float,
    val sampleCount: Int,
)

/**
 * A candidate the detector evaluated.
 *
 * Rejected candidates are still returned (with [accepted] false) so the diagnostics screen
 * can show *near misses* -- which is how a user, or a developer with a drive trace, learns
 * whether the sensitivity is set sensibly.
 */
data class DetectedEvent(
    val kind: EventKind,
    val accepted: Boolean,
    val confidence: Float,
    val features: EventFeatures,
    val context: MotionContext,
    val peakElapsedRealtimeNanos: Long,
    val rejectionReasons: List<String>,
    val handlingScore: Float,
) {
    fun describe(): String = buildString {
        append(if (accepted) "Protected" else "Ignored")
        append(" ${kind.name.lowercase()}: ")
        append("${"%.1f".format(features.peakG)} g, ")
        append("${(confidence * 100).toInt()}% confidence")
        if (rejectionReasons.isNotEmpty()) append(" (${rejectionReasons.joinToString(", ")})")
    }
}

/** Utilities shared by the detector and its tests. */
internal fun clampUnit(value: Float): Float = max(0f, min(1f, value))
