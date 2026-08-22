package io.github.tunlezah.roadguard.event

import kotlin.math.sqrt

/**
 * One accelerometer sample, already split into gravity and linear components.
 *
 * @param elapsedRealtimeNanos the sensor event's own timestamp. Roadguard uses the sensor
 *   clock rather than wall time so that a clock change (NTP, user, time zone) can never
 *   distort a detection window; wall time is attached only when an event is recorded.
 * @param linearX linear (gravity-removed) acceleration in m/s^2, device axes.
 * @param gravityX the current gravity estimate in m/s^2, device axes. Used to separate
 *   vertical road inputs (potholes, speed bumps) from horizontal ones (collisions, braking).
 */
data class SensorSample(
    val elapsedRealtimeNanos: Long,
    val linearX: Float,
    val linearY: Float,
    val linearZ: Float,
    val gravityX: Float,
    val gravityY: Float,
    val gravityZ: Float,
) {
    /** Resultant linear acceleration magnitude, m/s^2. */
    val magnitude: Float get() = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)

    /** Resultant linear acceleration in g. */
    val magnitudeG: Float get() = magnitude / GRAVITY

    /**
     * Component of the linear acceleration along gravity, m/s^2.
     *
     * Signed: positive means the acceleration points the same way as gravity.
     */
    val verticalComponent: Float
        get() {
            val gravityMagnitude = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
            if (gravityMagnitude < 1e-3f) return 0f
            return (linearX * gravityX + linearY * gravityY + linearZ * gravityZ) / gravityMagnitude
        }

    /** Magnitude of the component perpendicular to gravity, i.e. in the road plane. */
    val horizontalMagnitude: Float
        get() {
            val vertical = verticalComponent
            val total = magnitude
            val squared = total * total - vertical * vertical
            return if (squared <= 0f) 0f else sqrt(squared)
        }

    companion object {
        const val GRAVITY = 9.80665f
    }
}

/**
 * A replayable sensor trace.
 *
 * Tests -- and, later, real recorded drives -- feed one of these through
 * [ImpactDetector] so detector tuning can be evaluated without a car. The format is
 * deliberately trivial (one sample per line) so a trace can be captured from a real drive
 * with the diagnostics exporter and replayed verbatim.
 */
data class SensorTrace(val name: String, val samples: List<SensorSample>) {
    companion object {
        /** `elapsedNanos,lx,ly,lz,gx,gy,gz` per line, `#` comments ignored. */
        fun parse(name: String, text: String): SensorTrace = SensorTrace(
            name,
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split(',')
                    require(parts.size >= 7) { "malformed trace line: $line" }
                    SensorSample(
                        elapsedRealtimeNanos = parts[0].trim().toLong(),
                        linearX = parts[1].trim().toFloat(),
                        linearY = parts[2].trim().toFloat(),
                        linearZ = parts[3].trim().toFloat(),
                        gravityX = parts[4].trim().toFloat(),
                        gravityY = parts[5].trim().toFloat(),
                        gravityZ = parts[6].trim().toFloat(),
                    )
                }
                .toList(),
        )
    }

    fun encode(): String = buildString {
        appendLine("# Roadguard sensor trace: $name")
        appendLine("# elapsedNanos,linearX,linearY,linearZ,gravityX,gravityY,gravityZ")
        samples.forEach {
            appendLine(
                "${it.elapsedRealtimeNanos},${it.linearX},${it.linearY},${it.linearZ}," +
                    "${it.gravityX},${it.gravityY},${it.gravityZ}",
            )
        }
    }
}

/** Vehicle context from GNSS at the moment of a candidate event. */
data class MotionContext(
    /** Speed just before the candidate, km/h, or null when no usable fix was available. */
    val speedBeforeKmh: Float?,
    /** Speed shortly after, km/h, or null. */
    val speedAfterKmh: Float?,
    val latitude: Double?,
    val longitude: Double?,
) {
    val deltaSpeedKmh: Float?
        get() = if (speedBeforeKmh != null && speedAfterKmh != null) speedAfterKmh - speedBeforeKmh else null

    companion object {
        val NONE = MotionContext(null, null, null, null)
    }
}
