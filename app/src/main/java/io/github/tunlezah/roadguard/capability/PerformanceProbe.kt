package io.github.tunlezah.roadguard.capability

import android.os.SystemClock

/**
 * A tiny, deterministic CPU benchmark run once at start-up.
 *
 * Core count and RAM are poor predictors of whether a phone can sustain video encoding: two
 * "octa-core" devices can differ by a factor of three. This probe gives the tier scorer one real
 * measurement to break ties with.
 *
 * It is deliberately modest:
 *
 *  * fixed work, no randomness, so the result is comparable between runs and devices;
 *  * mixed integer and floating-point work, because both matter to the camera pipeline;
 *  * about a hundred milliseconds, run once, on a background thread, and **never while
 *    recording** -- a benchmark that steals CPU from the encoder would be self-defeating;
 *  * reported in arbitrary units per millisecond, and labelled in Diagnostics as a
 *    single-thread CPU probe rather than as a recording benchmark, because that is all it is.
 */
object PerformanceProbe {

    /** Iterations of the inner loop. Sized for roughly 100 ms on low-end 2024-era hardware. */
    const val ITERATIONS = 400_000

    /** Warm-up iterations, discarded, so JIT compilation does not dominate the measurement. */
    const val WARMUP_ITERATIONS = 40_000

    fun run(): PerformanceProbeResult {
        work(WARMUP_ITERATIONS)
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val checksum = work(ITERATIONS)
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startNanos
        val elapsedMs = elapsedNanos / 1_000_000.0
        val score = if (elapsedMs <= 0.0) 0f else (ITERATIONS / elapsedMs).toFloat()
        return PerformanceProbeResult(
            score = score,
            elapsedMillis = elapsedMs,
            checksum = checksum,
        )
    }

    /**
     * The measured workload.
     *
     * Kept in one place, with a returned checksum, so the optimiser cannot elide it: if the
     * result were unused a JIT could legally delete the whole loop and the "measurement" would
     * be meaningless.
     */
    private fun work(iterations: Int): Long {
        var integerAccumulator = 1L
        var floatAccumulator = 1.0
        for (index in 1..iterations) {
            integerAccumulator = integerAccumulator * 31 + index
            integerAccumulator = integerAccumulator xor (integerAccumulator ushr 17)
            floatAccumulator += (index % 97).toDouble() / (1.0 + (index % 13))
            if (index % 1024 == 0) floatAccumulator = floatAccumulator % 1_000_000.0
        }
        return integerAccumulator xor floatAccumulator.toLong()
    }
}

data class PerformanceProbeResult(
    val score: Float,
    val elapsedMillis: Double,
    val checksum: Long,
) {
    fun describe(): String =
        "%.0f units/ms over %.1f ms (single-thread CPU probe)".format(score, elapsedMillis)
}
