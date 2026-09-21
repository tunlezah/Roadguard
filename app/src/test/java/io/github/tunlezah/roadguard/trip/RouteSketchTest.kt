package io.github.tunlezah.roadguard.trip

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteSketchTest {

    @Test
    fun `points are fitted into the unit square with the route's proportions kept`() {
        // A north-south run: latitude changes, longitude does not.
        val sketch = RouteSketch.normalise(listOf(-35.20 to 149.15, -35.25 to 149.15, -35.30 to 149.15))

        assertThat(sketch).hasSize(3)
        // Same x throughout, centred; y runs top to bottom as the car heads south.
        assertThat(sketch.map { it.first }.distinct()).hasSize(1)
        assertThat(sketch.first().first).isWithin(1e-4f).of(0.5f)
        assertThat(sketch.first().second).isWithin(1e-4f).of(0f)
        assertThat(sketch.last().second).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `longitude is shortened by the cosine of the latitude`() {
        // One degree east and one degree south: at 35°S a degree of longitude is ~0.82 of a degree
        // of latitude, so the sketch is taller than it is wide.
        val sketch = RouteSketch.normalise(listOf(-35.0 to 149.0, -36.0 to 150.0))

        val width = sketch.maxOf { it.first } - sketch.minOf { it.first }
        val height = sketch.maxOf { it.second } - sketch.minOf { it.second }
        assertThat(height).isWithin(1e-4f).of(1f)
        assertThat(width).isLessThan(0.9f)
        assertThat(width).isGreaterThan(0.7f)
    }

    @Test
    fun `a single point or no movement becomes one centred dot`() {
        assertThat(RouteSketch.normalise(listOf(-35.2 to 149.1))).containsExactly(0.5f to 0.5f)
        assertThat(RouteSketch.normalise(listOf(-35.2 to 149.1, -35.2 to 149.1))).containsExactly(0.5f to 0.5f)
    }

    @Test
    fun `no points means nothing to draw`() {
        assertThat(RouteSketch.normalise(emptyList())).isEmpty()
    }
}
