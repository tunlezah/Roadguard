package io.github.tunlezah.roadguard.location

/**
 * Who wants GNSS updates, and how often.
 *
 * Pure bookkeeping, separated out because the rule it encodes is easy to state and easy to get
 * subtly wrong: **the receiver runs at the shortest interval any client asked for.** The failure
 * that matters is the thermal engine throttling the recorder to one fix every five seconds and
 * thereby freezing the map the driver is looking at. That must not happen, so the arbitration is
 * a tested function rather than a few lines inlined next to the `LocationManager` calls.
 */
class LocationRequests {

    private val wanted = LinkedHashMap<LocationEngine.Client, Long>()

    /** The interval to run at, or null when nobody wants updates. */
    val effectiveIntervalMs: Long? get() = wanted.values.minOrNull()

    val isActive: Boolean get() = wanted.isNotEmpty()

    val clients: Set<LocationEngine.Client> get() = wanted.keys.toSet()

    /**
     * Records or updates [client]'s wish.
     *
     * @return the interval the receiver should now run at.
     */
    fun request(client: LocationEngine.Client, intervalMs: Long): Long {
        wanted[client] = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        return wanted.values.min()
    }

    /**
     * Drops [client]'s wish.
     *
     * @return the interval to run at, or null when the receiver should stop.
     */
    fun release(client: LocationEngine.Client): Long? {
        wanted.remove(client)
        return wanted.values.minOrNull()
    }

    /**
     * Changes an existing client's interval, ignoring clients that hold no claim.
     *
     * This is the thermal path: it may only change what the recorder *asks* for. If the UI is on
     * screen wanting one second, the effective interval stays at one second.
     *
     * @return the interval to run at, or null when [client] holds no claim and nothing changed.
     */
    fun retune(client: LocationEngine.Client, intervalMs: Long): Long? {
        if (!wanted.containsKey(client)) return null
        return request(client, intervalMs)
    }

    fun clear() = wanted.clear()

    companion object {
        /** Floor on any requested interval; a zero would ask for updates as fast as possible. */
        const val MIN_INTERVAL_MS = 200L
    }
}
