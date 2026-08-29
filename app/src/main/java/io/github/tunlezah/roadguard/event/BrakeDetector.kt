package io.github.tunlezah.roadguard.event

/**
 * How firmly the vehicle appears to be braking, for the burned-in brake indicator.
 *
 * Deliberately not an [io.github.tunlezah.roadguard.data.EventKind]: the brake indicator is an
 * overlay annotation and nothing more. It never protects footage, never writes an event row,
 * and never competes with the impact detector.
 */
enum class BrakeLevel { Braking, HardBraking }

/**
 * Detects braking from GNSS speed, with the accelerometer as a secondary witness.
 *
 * ### Why GNSS decides and the accelerometer only assists
 *
 * A phone in a cradle does not know which way the vehicle points, so a horizontal acceleration
 * on its own cannot distinguish braking from hard acceleration or a firm corner. The sign of the
 * speed change can. So the deciding signal is the slope of the filtered GNSS speed
 * ([io.github.tunlezah.roadguard.location.SpeedFilter] output) over the last few seconds, and
 * the accelerometer is allowed to do exactly one thing: escalate an already-GNSS-confirmed
 * braking to [BrakeLevel.HardBraking] when the sustained horizontal deceleration says so. It can
 * never light the indicator alone. On the baseline device the accelerometer path is also
 * physically weak for sustained events -- the no-gyroscope gravity fallback absorbs a long
 * deceleration into its gravity estimate within about a second -- which is one more reason not
 * to trust it with the decision.
 *
 * ### Thresholds
 *
 * [BRAKING_DECEL_MPS2] (0.15 g) is a deliberate brake application, comfortably above GNSS
 * Doppler noise over a multi-second window. [HARD_DECEL_MPS2] (0.40 g) is the figure insurance
 * telematics conventionally calls hard braking. Like every event-detection number in Roadguard
 * they are reasoned starting points, not measurements from a real drive -- see
 * `docs/event-detection.md`.
 *
 * ### Behaviour under degraded input
 *
 * The speed slope is the steepest deceleration ending at the newest fix, gated on still slowing
 * now (see [gnssDecelerationMps2]), so the thermal engine slowing fixes from 1 s to 2 s or 5 s
 * coarsens the estimate without breaking it, and duplicate deliveries of the same fix change
 * nothing. No usable speed -- tunnel, car park, location off -- simply means no indicator:
 * absence of evidence is shown as absence, exactly like every other overlay field.
 *
 * ### Cost
 *
 * A few comparisons per accelerometer sample and a tiny ring buffer touched once per GNSS fix.
 * Both inputs already flow for other reasons; this class adds arithmetic, not sensors, wakeups
 * or allocations on the sample path.
 *
 * Pure and deterministic: every timestamp is passed in, all on the elapsed-realtime clock.
 */
class BrakeDetector {

    private class SpeedPoint(var atMs: Long, var speedMps: Float)

    // A fixed pool covering WINDOW_MS at the fastest fix rate, reused so the 100 Hz-adjacent
    // path never allocates.
    private val points = ArrayDeque<SpeedPoint>()
    private val pool = ArrayDeque<SpeedPoint>()

    private var horizontalEmaMps2 = 0f
    private var lastAccelElapsedNanos = Long.MIN_VALUE

    private var level: BrakeLevel? = null
    private var supportedUntilMs = Long.MIN_VALUE

    /**
     * Feeds one filtered GNSS speed, or null when no trustworthy speed exists.
     *
     * Call once per fix; re-delivering the same (speed, timestamp) pair is harmless.
     */
    fun onSpeed(speedMps: Float?, atMs: Long) {
        if (speedMps == null) {
            clearPoints()
            return
        }
        val newest = points.lastOrNull()
        if (newest != null && atMs <= newest.atMs) return
        val point = pool.removeLastOrNull()?.also {
            it.atMs = atMs
            it.speedMps = speedMps
        } ?: SpeedPoint(atMs, speedMps)
        points.addLast(point)
        while (points.isNotEmpty() && points.first().atMs < atMs - WINDOW_MS) {
            pool.addLast(points.removeFirst())
        }
    }

    /**
     * Feeds one accelerometer sample. Cheap enough for the raw 100 Hz stream: one dt, one
     * square root (already computed by [SensorSample.horizontalMagnitude]) and one blend.
     */
    fun onSample(sample: SensorSample) {
        val nanos = sample.elapsedRealtimeNanos
        val last = lastAccelElapsedNanos
        lastAccelElapsedNanos = nanos
        if (last == Long.MIN_VALUE || nanos <= last) {
            horizontalEmaMps2 = sample.horizontalMagnitude
            return
        }
        val dtMs = (nanos - last) / 1_000_000f
        val blend = (dtMs / ACCEL_EMA_TAU_MS).coerceAtMost(1f)
        horizontalEmaMps2 += (sample.horizontalMagnitude - horizontalEmaMps2) * blend
    }

    /**
     * The braking level to indicate right now, or null for none.
     *
     * Engaging and escalating are immediate. Letting go is damped twice over: a lit level
     * releases at [RELEASE_FRACTION] of its engage threshold rather than at the threshold
     * itself, and once the evidence stops supporting a level the light holds it for another
     * [MIN_HOLD_MS] -- so one flat or noisy fix in the middle of a long brake cannot blink it,
     * and a once-a-second overlay refresh shows a steady light rather than a strobe.
     */
    fun level(nowMs: Long): BrakeLevel? {
        val decel = gnssDecelerationMps2(nowMs)
        val current = level

        val gnssLevel = when {
            decel == null -> null
            decel >= engageThreshold(BrakeLevel.HardBraking, current) -> BrakeLevel.HardBraking
            decel >= engageThreshold(BrakeLevel.Braking, current) -> BrakeLevel.Braking
            else -> null
        }

        // The accelerometer may only firm up a braking GNSS already believes in.
        val next = if (
            gnssLevel == BrakeLevel.Braking &&
            accelFresh(nowMs) &&
            horizontalEmaMps2 >= HARD_DECEL_MPS2
        ) {
            BrakeLevel.HardBraking
        } else {
            gnssLevel
        }

        if (rank(next) >= rank(current)) {
            level = next
            if (next != null) supportedUntilMs = nowMs + MIN_HOLD_MS
        } else if (nowMs >= supportedUntilMs) {
            level = next
            if (next != null) supportedUntilMs = nowMs + MIN_HOLD_MS
        }
        return level
    }

    /** Drops all state, for example when recording stops. */
    fun reset() {
        clearPoints()
        horizontalEmaMps2 = 0f
        lastAccelElapsedNanos = Long.MIN_VALUE
        level = null
        supportedUntilMs = Long.MIN_VALUE
    }

    /**
     * The braking deceleration ending at the newest speed point, m/s^2, or null when there is
     * nothing to measure. Positive means slowing.
     *
     * Two-part measurement, chosen against two failure modes a naive fixed-window slope has:
     *
     *  1. **"Still slowing now"** -- the newest pair of points must itself show deceleration
     *     ([STILL_SLOWING_MPS2]). Without this gate, the steep drop of a finished stop keeps
     *     the light on for seconds of flat tail.
     *  2. **The steepest pair ending now** -- among older points within [LOOKBACK_MS] and at
     *     least [MIN_SPAN_MS] back, the strongest deceleration to the newest point wins.
     *     Without this, the cruise before a 2 s brake dilutes the slope below threshold, and
     *     the thermal engine's 5 s fix interval leaves no pair inside a short window at all.
     *
     * At one fix a second this is a walk over at most a dozen points, once a second.
     */
    fun gnssDecelerationMps2(nowMs: Long): Float? {
        if (points.size < 2) return null
        val newest = points.last()
        if (nowMs - newest.atMs > STALE_MS) return null

        val previous = points[points.size - 2]
        val recentSpanSeconds = (newest.atMs - previous.atMs) / 1000f
        if ((previous.speedMps - newest.speedMps) / recentSpanSeconds < STILL_SLOWING_MPS2) {
            return 0f
        }

        var steepest = 0f
        for (index in points.size - 2 downTo 0) {
            val point = points[index]
            val spanMs = newest.atMs - point.atMs
            if (spanMs > LOOKBACK_MS) break
            if (spanMs < MIN_SPAN_MS) continue
            if (point.speedMps < MIN_SPEED_MPS) continue
            val decel = (point.speedMps - newest.speedMps) / (spanMs / 1000f)
            if (decel > steepest) steepest = decel
        }
        return steepest
    }

    private fun engageThreshold(target: BrakeLevel, current: BrakeLevel?): Float {
        val engage = if (target == BrakeLevel.HardBraking) HARD_DECEL_MPS2 else BRAKING_DECEL_MPS2
        // Once lit, a level lets go at a fraction of its engage threshold, so a reading
        // hovering at the boundary does not flick the light on and off.
        val holdsTarget = current == target ||
            (current == BrakeLevel.HardBraking && target == BrakeLevel.Braking)
        return if (holdsTarget) engage * RELEASE_FRACTION else engage
    }

    private fun rank(level: BrakeLevel?): Int = when (level) {
        null -> 0
        BrakeLevel.Braking -> 1
        BrakeLevel.HardBraking -> 2
    }

    private fun accelFresh(nowMs: Long): Boolean {
        val last = lastAccelElapsedNanos
        return last != Long.MIN_VALUE && nowMs - last / 1_000_000 <= ACCEL_STALE_MS
    }

    private fun clearPoints() {
        while (points.isNotEmpty()) pool.addLast(points.removeFirst())
    }

    companion object {
        /** Deliberate braking: about 5.4 km/h lost per second, 0.15 g. */
        const val BRAKING_DECEL_MPS2 = 1.5f

        /** Hard braking: 0.40 g, the conventional telematics figure. */
        const val HARD_DECEL_MPS2 = 3.9f

        /** A lit level releases at this fraction of its engage threshold. */
        const val RELEASE_FRACTION = 0.6f

        /**
         * Speed points older than this are dropped. Comfortably past [LOOKBACK_MS] even at the
         * thermal engine's worst fix interval (5 s at Critical).
         */
        const val WINDOW_MS = 12_000L

        /** How far back a slope pair may reach. */
        const val LOOKBACK_MS = 6_000L

        /**
         * The newest pair must itself show at least this deceleration, m/s^2, or the light is
         * out: braking that has ended is not braking. Above 1 Hz Doppler noise, far below any
         * braking that could have engaged the light.
         */
        const val STILL_SLOWING_MPS2 = 0.3f

        /** The slope needs at least this much time between its endpoints. */
        const val MIN_SPAN_MS = 800L

        /** With the newest speed older than this, the indicator is off, not stale-on. */
        const val STALE_MS = 4_000L

        /** Below ~7 km/h the residual creep to a stop is not worth indicating. */
        const val MIN_SPEED_MPS = 2.0f

        /**
         * Once the evidence stops supporting a level, the light keeps it this much longer --
         * long enough to bridge one flat or lost fix at the normal 1 Hz rate.
         */
        const val MIN_HOLD_MS = 1_200L

        /** EMA time constant for the horizontal acceleration, ms. */
        const val ACCEL_EMA_TAU_MS = 400f

        /** Accelerometer readings older than this cannot escalate. */
        const val ACCEL_STALE_MS = 1_500L
    }
}
