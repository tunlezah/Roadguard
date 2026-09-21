package io.github.tunlezah.roadguard.trip

import kotlin.math.cos

/**
 * Turns track points into a small picture of the route.
 *
 * The gallery draws a thumbnail of each trip's shape beside its name, from the GPX points alone:
 * no tiles are rendered in a list of dozens of trips, which is the difference between a list
 * that scrolls and a phone that heats up. The points are fitted into the unit square, keeping the
 * route's real proportions (longitude is shortened by the cosine of the latitude, so Canberra's
 * streets do not come out stretched) and centred, ready for a `Canvas` to scale to any box.
 *
 * Pure, and tolerant: fewer than two distinct points give a single centred dot.
 */
object RouteSketch {

    /**
     * @param points latitude/longitude pairs in track order.
     * @return x/y pairs in `0..1`, with y increasing downwards as a canvas expects.
     */
    fun normalise(points: List<Pair<Double, Double>>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        val meanLatitude = points.sumOf { it.first } / points.size
        val scaleX = cos(Math.toRadians(meanLatitude)).coerceAtLeast(0.05)
        val xs = points.map { it.second * scaleX }
        val ys = points.map { -it.first }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val extent = maxOf(maxX - minX, maxY - minY)
        if (extent <= 0.0) return listOf(0.5f to 0.5f)
        val offsetX = (extent - (maxX - minX)) / 2
        val offsetY = (extent - (maxY - minY)) / 2
        return xs.indices.map { i ->
            (((xs[i] - minX) + offsetX) / extent).toFloat() to (((ys[i] - minY) + offsetY) / extent).toFloat()
        }
    }
}
