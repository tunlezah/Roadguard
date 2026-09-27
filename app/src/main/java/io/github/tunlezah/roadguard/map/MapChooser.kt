package io.github.tunlezah.roadguard.map

/**
 * Decides which installed map to show, and which to name places from.
 *
 * Several regions can be installed at once -- the whole country at main-road detail alongside a
 * state at street level -- and the driver should never have to pick between them. The rule is the
 * obvious one: the most detailed map that covers where the vehicle is, judged by each archive's
 * own stated coverage. When two equally detailed maps both cover the point (state extracts overlap
 * a little at their borders), the one the point lies deepest inside wins, which is the state the
 * vehicle is actually in.
 *
 * Switching maps reloads the style and empties the tile cache, so it must not happen on every fix
 * near a border. Once a map is showing it is kept for as long as it covers the vehicle; a more
 * detailed one takes over only when the vehicle is well inside it, [ENTER_INSET_DEGREES] from its
 * nearest edge. Leaving a map's coverage switches away at once, because a map with no data under
 * the vehicle is worse than a coarse one.
 *
 * Pure, so every case is unit tested.
 */
object MapChooser {

    /** How far inside a more detailed map the vehicle must be before the map switches to it: about 5 km. */
    const val ENTER_INSET_DEGREES = 0.05

    /**
     * The map to show.
     *
     * @param current the map showing now, kept where the rules above allow. It is matched by id,
     *   so a stale description of a reinstalled map still counts as the same map.
     */
    fun choose(installed: List<InstalledMap>, latitude: Double?, longitude: Double?, current: InstalledMap?): InstalledMap? {
        if (installed.isEmpty()) return null
        val stillInstalled = current?.let { showing -> installed.firstOrNull { it.id == showing.id } }
        val wholeCountry = installed.firstOrNull { it.pack.coversWholeCountry }
        if (latitude == null || longitude == null) {
            // Nothing to judge by: keep what is showing, else the map that works everywhere.
            return stillInstalled ?: wholeCountry ?: installed.first()
        }
        val covering = installed
            .filter { it.covers(latitude, longitude) }
            .sortedWith(
                compareByDescending<InstalledMap> { it.maxZoom }
                    .thenByDescending { it.bounds?.depth(latitude, longitude) ?: 0.0 }
                    .thenBy { it.pack.coversWholeCountry },
            )
        val stay = stillInstalled?.takeIf { it.covers(latitude, longitude) }
        if (stay != null) {
            val better = covering.firstOrNull { candidate ->
                candidate.maxZoom > stay.maxZoom && candidate.covers(latitude, longitude, ENTER_INSET_DEGREES)
            }
            return better ?: stay
        }
        return covering.firstOrNull() ?: wholeCountry ?: stillInstalled ?: installed.first()
    }

    /** The most detailed installed map covering a point, with no memory of what is showing. */
    fun bestFor(installed: List<InstalledMap>, latitude: Double, longitude: Double): InstalledMap? =
        choose(installed, latitude, longitude, current = null)
}
