package io.github.tunlezah.roadguard.location

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the GPX track for the trip being recorded.
 *
 * The recorder feeds it every location update; it discards the ones that are stale, untrustworthy
 * or stationary (see [TrackPointFilter]), appends the rest through a [GpxWriter], and keeps the
 * running point count and distance the trip row is updated from. File work happens on
 * [dispatcher] under a mutex, so the recorder's own coroutines never block on an fsync and two
 * updates can never interleave inside the writer.
 *
 * Closing returns a [Summary] rather than writing to the index itself: the trip repository owns
 * the row, this class owns the file.
 */
class TrackRecorder(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {

    /** What was written, for the trip row. */
    data class Summary(val file: File, val pointCount: Int, val distanceMetres: Long)

    private val mutex = Mutex()
    private var writer: GpxWriter? = null
    private var file: File? = null
    private var filter = TrackPointFilter()
    private var lastFixEpochMs: Long? = null
    private var pointCount = 0
    private var distanceMetres = 0.0

    @Volatile
    private var open = false

    /** True while a track file is open for appending. Cheap: read on every location update. */
    val isOpen: Boolean get() = open

    /**
     * Opens [target] for appending, creating it when it does not exist.
     *
     * @param existingPoints points already in the file, when a trip is being continued after a
     *   relaunch, so the summary counts the whole track and not just this process's share.
     */
    suspend fun open(target: File, trackName: String, existingPoints: Int = 0, existingDistanceMetres: Long = 0L): Boolean =
        withContext(dispatcher) {
            mutex.withLock {
                closeLocked()
                val opened = runCatching {
                    GpxWriter(target, syncIntervalMs = SYNC_INTERVAL_MS).also { it.open(trackName) }
                }.onFailure { Log.w(TAG, "could not open track ${target.name}", it) }.getOrNull()
                if (opened == null) return@withLock false
                writer = opened
                file = target
                filter = TrackPointFilter()
                lastFixEpochMs = null
                pointCount = existingPoints
                distanceMetres = existingDistanceMetres.toDouble()
                open = true
                true
            }
        }

    /**
     * Offers a location update.
     *
     * Deduplicated on the fix timestamp, because [LocationEngine.state] re-emits the same fix on
     * every tick, and rejected unless the fix is a usable position.
     */
    suspend fun accept(location: LocationState) {
        if (!open) return
        val latitude = location.latitude ?: return
        val longitude = location.longitude ?: return
        val fixEpochMs = location.fixEpochMs ?: return
        if (!location.quality.hasFix) return
        if (fixEpochMs == lastFixEpochMs) return
        lastFixEpochMs = fixEpochMs
        withContext(dispatcher) {
            mutex.withLock {
                val target = writer ?: return@withLock
                val decision = filter.consider(latitude, longitude, location.accuracyMetres, fixEpochMs)
                if (!decision.write) return@withLock
                runCatching {
                    target.append(
                        latitude = latitude,
                        longitude = longitude,
                        altitudeMetres = location.altitudeMetres,
                        epochMs = fixEpochMs,
                        speedMps = location.speedMetresPerSecond,
                        accuracyMetres = location.accuracyMetres,
                        satellites = location.satellitesUsed.takeIf { it > 0 },
                    )
                    pointCount++
                    distanceMetres += decision.distanceMetres
                }.onFailure { Log.w(TAG, "could not append a track point", it) }
            }
        }
    }

    /** Closes the track and reports what it holds, or null when nothing was open. */
    suspend fun close(): Summary? = withContext(dispatcher) {
        mutex.withLock { closeLocked() }
    }

    private fun closeLocked(): Summary? {
        val target = file
        val current = writer
        open = false
        writer = null
        file = null
        if (current == null || target == null) return null
        runCatching { current.close() }.onFailure { Log.w(TAG, "could not close track ${target.name}", it) }
        return Summary(target, pointCount, distanceMetres.toLong())
    }

    companion object {
        private const val TAG = "RoadguardTrack"

        /** How long a written point may sit in the page cache before it is forced to storage. */
        const val SYNC_INTERVAL_MS = 5_000L
    }
}
