package io.github.tunlezah.roadguard.event

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins down [SensorSample]'s gravity decomposition and the [SensorTrace] wire format.
 *
 * Everything the impact detector decides is built on these two things. The decomposition is
 * what separates a pothole (energy along gravity) from a collision (energy in the road plane),
 * so if `verticalComponent` / `horizontalMagnitude` are wrong -- or return NaN when the
 * gravity estimate has not settled yet, which really happens in the first moments after a
 * sensor is registered -- the detector silently mis-classifies road inputs as crashes. The
 * trace format matters because it is the only way a real drive can be captured once and
 * replayed against new tuning, so it has to round-trip byte-for-byte and refuse garbage
 * loudly instead of parsing it into plausible-looking nonsense.
 */
class SensorTraceTest {

    private val g = SensorSample.GRAVITY

    /** Gravity straight down device +Y, the usual windscreen-cradle orientation. */
    private fun sample(
        lx: Float,
        ly: Float,
        lz: Float,
        gx: Float = 0f,
        gy: Float = SensorSample.GRAVITY,
        gz: Float = 0f,
        nanos: Long = 0L,
    ) = SensorSample(nanos, lx, ly, lz, gx, gy, gz)

    // ----- magnitude ---------------------------------------------------------------------

    @Test
    fun `magnitude is the euclidean norm of the linear axes`() {
        assertThat(sample(3f, 4f, 0f).magnitude).isWithin(1e-4f).of(5f)
        assertThat(sample(0f, 0f, 0f).magnitude).isWithin(1e-6f).of(0f)
        assertThat(sample(-1f, 2f, -2f).magnitude).isWithin(1e-4f).of(3f)
    }

    @Test
    fun `magnitudeG divides the magnitude by standard gravity`() {
        assertThat(sample(g, 0f, 0f).magnitudeG).isWithin(1e-5f).of(1f)
        assertThat(sample(0f, 3f * g, 0f).magnitudeG).isWithin(1e-5f).of(3f)
        assertThat(sample(0f, 0f, 0f).magnitudeG).isWithin(1e-6f).of(0f)
    }

    // ----- decomposition against a known gravity direction -------------------------------

    @Test
    fun `vertical and horizontal split a known vector against plus Y gravity`() {
        val s = sample(3f, 4f, 0f)
        assertThat(s.verticalComponent).isWithin(1e-4f).of(4f)
        assertThat(s.horizontalMagnitude).isWithin(1e-3f).of(3f)
    }

    @Test
    fun `vertical component is signed relative to the gravity direction`() {
        // Gravity along -Z; acceleration also along -Z points *with* gravity, so positive.
        val withGravity = sample(0f, 0f, -5f, gx = 0f, gy = 0f, gz = -g)
        assertThat(withGravity.verticalComponent).isWithin(1e-4f).of(5f)

        val againstGravity = sample(0f, 0f, 5f, gx = 0f, gy = 0f, gz = -g)
        assertThat(againstGravity.verticalComponent).isWithin(1e-4f).of(-5f)
        // Sign must not leak into the horizontal magnitude.
        assertThat(againstGravity.horizontalMagnitude).isWithin(1e-3f).of(0f)
    }

    @Test
    fun `decomposition follows a tilted gravity vector rather than the device axes`() {
        // Gravity 45 degrees between +Y and +Z, i.e. a cradle tilted back.
        val k = g / sqrt(2f)
        val s = sample(0f, 2f, 0f, gx = 0f, gy = k, gz = k)
        val expected = 2f / sqrt(2f)
        assertThat(s.verticalComponent).isWithin(1e-4f).of(expected)
        assertThat(s.horizontalMagnitude).isWithin(1e-3f).of(expected)
    }

    @Test
    fun `a purely horizontal impact has no vertical component`() {
        val s = sample(4f * g, 0f, 0f)
        assertThat(s.verticalComponent).isWithin(1e-6f).of(0f)
        assertThat(s.horizontalMagnitude).isWithin(1e-4f).of(4f * g)
    }

    @Test
    fun `a zero gravity vector degrades to zero vertical instead of NaN`() {
        val s = sample(3f, 4f, 0f, gx = 0f, gy = 0f, gz = 0f)
        assertThat(s.verticalComponent).isEqualTo(0f)
        assertThat(s.verticalComponent.isNaN()).isFalse()
        // With no usable gravity estimate all of the energy is reported as in-plane.
        assertThat(s.horizontalMagnitude).isWithin(1e-4f).of(5f)
        assertThat(s.horizontalMagnitude.isNaN()).isFalse()
    }

    @Test
    fun `a gravity vector below the one milli threshold is treated as unusable`() {
        val tiny = sample(3f, 4f, 0f, gx = 5e-4f, gy = 5e-4f, gz = 0f)
        assertThat(tiny.verticalComponent).isEqualTo(0f)

        // Just above the threshold it is used again.
        val usable = sample(3f, 4f, 0f, gx = 0f, gy = 2e-3f, gz = 0f)
        assertThat(usable.verticalComponent).isWithin(1e-4f).of(4f)
    }

    @Test
    fun `magnitude squared equals vertical squared plus horizontal squared`() {
        data class Case(val name: String, val s: SensorSample)
        val cases = listOf(
            Case("pure horizontal", sample(4f * g, 0f, 0f)),
            Case("pure vertical", sample(0f, 3f * g, 0f)),
            Case("mixed", sample(3f, 4f, 12f)),
            Case("negative axes", sample(-7.5f, -2.25f, 0.5f)),
            Case("tilted gravity", sample(1f, 2f, 3f, gx = 1f, gy = 2f, gz = 2f)),
            Case("zero gravity", sample(1f, 2f, 3f, gx = 0f, gy = 0f, gz = 0f)),
            Case("all zero", sample(0f, 0f, 0f)),
        )
        for (case in cases) {
            val s = case.s
            val total = s.magnitude * s.magnitude
            val split = s.verticalComponent * s.verticalComponent + s.horizontalMagnitude * s.horizontalMagnitude
            val tolerance = 1e-4f * (1f + abs(total))
            assertThat(abs(total - split)).isLessThan(tolerance)
        }
    }

    // ----- trace format ------------------------------------------------------------------

    private val roundTripSamples = listOf(
        SensorSample(0L, 0f, 0f, 0f, 0f, 9.80665f, 0f),
        SensorSample(10_000_000L, 1.5f, -0.25f, 0.1f, 0.01f, 9.8f, -0.02f),
        SensorSample(20_000_000L, -29.419949f, 3.3333333f, 1e-7f, -9.80665f, 0f, 0f),
        SensorSample(9_223_372_036_854_775_000L, 1234.5678f, -0.000123f, 42f, 0f, 0f, 9.80665f),
    )

    @Test
    fun `encode then parse round trips every sample exactly`() {
        val original = SensorTrace("round trip", roundTripSamples)
        val parsed = SensorTrace.parse("round trip", original.encode())
        assertThat(parsed.samples).containsExactlyElementsIn(roundTripSamples).inOrder()
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `encode is stable so a trace can be diffed and re-parsed`() {
        val original = SensorTrace("stable", roundTripSamples)
        val once = original.encode()
        val twice = SensorTrace.parse("stable", once).encode()
        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `encode names the trace and its columns in comment lines`() {
        val lines = SensorTrace("drive-42", roundTripSamples).encode().trim().lines()
        assertThat(lines[0]).isEqualTo("# Roadguard sensor trace: drive-42")
        assertThat(lines[1]).isEqualTo("# elapsedNanos,linearX,linearY,linearZ,gravityX,gravityY,gravityZ")
        assertThat(lines.size).isEqualTo(2 + roundTripSamples.size)
    }

    @Test
    fun `parse ignores blank lines comments and surrounding whitespace`() {
        val text = """
            # a comment
              # an indented comment

              1000,1.0,2.0,3.0,0.0,9.80665,0.0

            	2000, 4.0 , 5.0 , 6.0 , 0.0 , 9.80665 , 0.0
              
        """.trimIndent()
        val parsed = SensorTrace.parse("mixed", text)
        assertThat(parsed.samples).containsExactly(
            SensorSample(1000L, 1f, 2f, 3f, 0f, 9.80665f, 0f),
            SensorSample(2000L, 4f, 5f, 6f, 0f, 9.80665f, 0f),
        ).inOrder()
    }

    @Test
    fun `parse of an empty or comment only text yields no samples`() {
        assertThat(SensorTrace.parse("empty", "").samples).isEmpty()
        assertThat(SensorTrace.parse("comments", "# one\n\n#two\n").samples).isEmpty()
    }

    @Test
    fun `parse tolerates extra trailing columns so the format can grow`() {
        val parsed = SensorTrace.parse("extra", "1000,1,2,3,0,9.80665,0,99,extra")
        assertThat(parsed.samples).containsExactly(SensorSample(1000L, 1f, 2f, 3f, 0f, 9.80665f, 0f))
    }

    @Test
    fun `a malformed line throws IllegalArgumentException rather than being skipped`() {
        data class Case(val name: String, val text: String)
        val cases = listOf(
            Case("too few columns", "1000,1,2,3,0,9.80665"),
            Case("single column", "1000"),
            Case("non numeric linear axis", "1000,x,2,3,0,9.80665,0"),
            Case("non numeric timestamp", "not-a-time,1,2,3,0,9.80665,0"),
            Case("empty field", "1000,,2,3,0,9.80665,0"),
            Case("bad line after a good one", "1000,1,2,3,0,9.80665,0\n2000,1,2")
        )
        for (case in cases) {
            assertThrows(case.name, IllegalArgumentException::class.java) {
                SensorTrace.parse(case.name, case.text)
            }
        }
    }
}
