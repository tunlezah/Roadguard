package io.github.tunlezah.roadguard.trip

import android.util.Log
import io.github.tunlezah.roadguard.data.SegmentDao
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.data.TripDao
import io.github.tunlezah.roadguard.data.TripEntity
import io.github.tunlezah.roadguard.data.TripState
import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.location.TrackRecorder
import io.github.tunlezah.roadguard.map.PlaceLookup
import io.github.tunlezah.roadguard.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the trip rows: opening one when recording starts, advancing its end as clips finalise,
 * closing and naming it when recording stops, and repairing the table at start-up.
 *
 * ### Continuation
 *
 * A recording that starts within [TripAssembler.MAX_GAP_MS] of the previous trip's end continues
 * that trip rather than starting a new one. That is what makes a crash and relaunch, or a stop
 * and immediate restart, one drive in the gallery -- the same rule the reconciler applies to
 * clips that predate trips, so the two paths cannot disagree.
 *
 * ### Naming is best effort
 *
 * Names come from the offline map through [PlaceLookup]. With no map installed the trip keeps
 * its coordinates and a time-based title, and [resolveNames] is retried by the gallery once a
 * map is present. A trip whose ends were never fixed (location off) is named by time for good.
 */
class TripRepository(
    private val trips: TripDao,
    private val segments: SegmentDao,
    private val storage: StorageManager,
    private val places: PlaceLookup,
) {
    /** The trip currently being recorded, which no pruning step may touch. */
    val activeTripId: Long? get() = storage.activeTripId

    /**
     * Starts a trip for a recording beginning at [startedAtEpochMs], or continues the latest one.
     */
    suspend fun openOrContinue(startedAtEpochMs: Long, location: LocationState): TripEntity = withContext(Dispatchers.IO) {
        val latest = trips.latest()
        val trip = if (latest != null &&
            latest.startedAtEpochMs <= startedAtEpochMs &&
            TripAssembler.continues(latest.endedAtEpochMs, startedAtEpochMs)
        ) {
            latest.copy(state = TripState.Recording.name).also { trips.update(it) }
        } else {
            val fresh = TripEntity(
                startedAtEpochMs = startedAtEpochMs,
                endedAtEpochMs = startedAtEpochMs,
                state = TripState.Recording.name,
                startLatitude = location.latitude?.takeIf { location.hasPosition },
                startLongitude = location.longitude?.takeIf { location.hasPosition },
            )
            fresh.copy(id = trips.insert(fresh))
        }
        storage.activeTripId = trip.id
        Log.i(TAG, if (trip === latest) "continuing trip ${trip.id}" else "opened trip ${trip.id}")
        trip
    }

    suspend fun setTrackFile(tripId: Long, fileName: String?) = withContext(Dispatchers.IO) {
        trips.setTrackFile(tripId, fileName)
    }

    /** Records what the track recorder has written so far, without closing the trip. */
    suspend fun recordTrack(tripId: Long, track: TrackRecorder.Summary?) = withContext(Dispatchers.IO) {
        if (track == null) return@withContext
        trips.byId(tripId)?.let { trip ->
            trips.update(
                trip.copy(
                    trackFileName = track.file.name,
                    trackPointCount = track.pointCount,
                    distanceMetres = track.distanceMetres,
                ),
            )
        }
    }

    /**
     * Advances the trip's end to the clip that just finished.
     *
     * Also fills in a start position that was missing because the first fix arrived after the
     * trip opened, which is the normal cold-start case.
     */
    suspend fun onSegmentFinalised(tripId: Long, endedAtEpochMs: Long, location: LocationState) = withContext(Dispatchers.IO) {
        val trip = trips.byId(tripId) ?: return@withContext
        val latitude = location.latitude?.takeIf { location.hasPosition }
        val longitude = location.longitude?.takeIf { location.hasPosition }
        var updated = trip.copy(
            endedAtEpochMs = maxOf(trip.endedAtEpochMs, endedAtEpochMs),
            endLatitude = latitude ?: trip.endLatitude,
            endLongitude = longitude ?: trip.endLongitude,
        )
        if (updated.startLatitude == null && latitude != null) {
            updated = updated.copy(startLatitude = latitude, startLongitude = longitude)
        }
        trips.update(updated)
    }

    /**
     * Closes the trip, records its track, and names its ends from the map when one is installed.
     *
     * @param endedAtEpochMs when recording stopped; the trip's end is the later of this and the
     *   last finalised clip.
     */
    suspend fun close(
        tripId: Long,
        track: TrackRecorder.Summary?,
        location: LocationState,
        endedAtEpochMs: Long,
    ): TripEntity? = withContext(Dispatchers.IO) {
        val trip = trips.byId(tripId) ?: return@withContext null
        val latitude = location.latitude?.takeIf { location.hasPosition }
        val longitude = location.longitude?.takeIf { location.hasPosition }
        val closed = trip.copy(
            state = TripState.Closed.name,
            endedAtEpochMs = maxOf(trip.endedAtEpochMs, endedAtEpochMs),
            endLatitude = latitude ?: trip.endLatitude,
            endLongitude = longitude ?: trip.endLongitude,
            startLatitude = trip.startLatitude ?: latitude,
            startLongitude = trip.startLongitude ?: longitude,
            trackFileName = track?.file?.name ?: trip.trackFileName,
            trackPointCount = track?.pointCount ?: trip.trackPointCount,
            distanceMetres = track?.distanceMetres ?: trip.distanceMetres,
        )
        trips.update(closed)
        if (storage.activeTripId == tripId) storage.activeTripId = null
        Log.i(TAG, "closed trip $tripId after ${closed.durationMs / 1000} s, ${closed.trackPointCount} track points")
        resolveNames(closed)
    }

    /**
     * Names both ends of [trip] from the offline map.
     *
     * Returns the trip unchanged when no map is installed, leaving `namesResolved` false so the
     * gallery tries again later. Once a lookup has run the flag is set whatever it found: a trip
     * in open country may genuinely have no place within reach.
     */
    suspend fun resolveNames(trip: TripEntity): TripEntity = withContext(Dispatchers.IO) {
        if (!places.isAvailable) return@withContext trip
        val start = resolve(trip.startLatitude, trip.startLongitude)
        val end = resolve(trip.endLatitude, trip.endLongitude)
        val named = trip.copy(
            startPlace = start?.place,
            startArea = start?.area,
            endPlace = end?.place,
            endArea = end?.area,
            namesResolved = true,
        )
        if (named != trip) trips.update(named)
        named
    }

    private suspend fun resolve(latitude: Double?, longitude: Double?): PlaceNames? {
        if (latitude == null || longitude == null) return null
        return runCatching { places.resolve(latitude, longitude) }
            .onFailure { Log.w(TAG, "place lookup failed", it) }
            .getOrNull()
    }

    // ── Start-up reconciliation ───────────────────────────────────────────────────────────────

    /**
     * Groups clips that have no trip -- recorded before trips existed, or adopted from disk -- into
     * trips by the gap rule, and returns how many clips were assigned.
     *
     * A group that starts within the gap of the latest trip's end joins that trip; every other
     * group becomes a closed trip of its own. The trip's ends are taken from its first clip's
     * start fix and its last clip's end fix, falling back to the last clip's start fix for clips
     * recorded before end fixes were stored.
     */
    suspend fun assignUnassigned(): Int = withContext(Dispatchers.IO) {
        val unassigned = segments.unassigned()
        if (unassigned.isEmpty()) return@withContext 0
        val byId = unassigned.associateBy { it.id }
        val groups = TripAssembler.group(unassigned.map { TripAssembler.Clip(it.id, it.startedAtEpochMs, it.durationMs) })
        var latest = trips.latest()
        var assigned = 0
        for (group in groups) {
            val first = byId.getValue(group.clips.first().id)
            val last = byId.getValue(group.clips.last().id)
            val current = latest
            val tripId = if (current != null &&
                current.id != storage.activeTripId &&
                group.startedAtEpochMs >= current.startedAtEpochMs &&
                TripAssembler.continues(current.endedAtEpochMs, group.startedAtEpochMs)
            ) {
                val extended = current.copy(
                    endedAtEpochMs = maxOf(current.endedAtEpochMs, group.endedAtEpochMs),
                    endLatitude = last.endLatitude ?: last.startLatitude ?: current.endLatitude,
                    endLongitude = last.endLongitude ?: last.startLongitude ?: current.endLongitude,
                    namesResolved = false,
                )
                trips.update(extended)
                latest = extended
                extended.id
            } else {
                val fresh = TripEntity(
                    startedAtEpochMs = group.startedAtEpochMs,
                    endedAtEpochMs = group.endedAtEpochMs,
                    state = TripState.Closed.name,
                    startLatitude = first.startLatitude,
                    startLongitude = first.startLongitude,
                    endLatitude = last.endLatitude ?: last.startLatitude,
                    endLongitude = last.endLongitude ?: last.startLongitude,
                )
                val id = trips.insert(fresh)
                latest = fresh.copy(id = id)
                id
            }
            group.ids.chunked(ASSIGN_BATCH).forEach { ids -> segments.assignTrip(ids, tripId) }
            assigned += group.ids.size
        }
        assigned
    }

    /** Closes trips a previous process left recording. Returns how many. */
    suspend fun closeInterrupted(): Int = withContext(Dispatchers.IO) {
        var closed = 0
        for (trip in trips.byState(TripState.Recording.name)) {
            if (trip.id == storage.activeTripId) continue
            trips.update(trip.copy(state = TripState.Closed.name))
            closed++
        }
        closed
    }

    /** Removes trips with no clips left, and their tracks. Returns how many. */
    suspend fun pruneEmpty(): Int = withContext(Dispatchers.IO) {
        storage.pruneEmptyTrips(trips.all().map { it.id })
    }

    /**
     * Deletes GPX files no trip refers to.
     *
     * The rule the product owner chose is that a track lives exactly as long as its trip's clips,
     * so a file with no trip is a leftover, not footage to preserve.
     */
    suspend fun deleteOrphanTracks(): Int = withContext(Dispatchers.IO) {
        val referenced = trips.allTrackFileNames().toHashSet()
        var deleted = 0
        for (file in storage.trackFiles()) {
            if (file.name in referenced) continue
            if (file.delete()) deleted++
        }
        deleted
    }

    /** The clips of a trip, oldest first. */
    suspend fun clipsOf(tripId: Long): List<SegmentEntity> = withContext(Dispatchers.IO) { segments.forTrip(tripId) }

    suspend fun byId(tripId: Long): TripEntity? = withContext(Dispatchers.IO) { trips.byId(tripId) }

    /** The title and detail line for a trip, from whatever names it has. */
    fun labelFor(trip: TripEntity, fallbackTitle: String): TripLabel = TripNaming.label(
        start = PlaceNames(trip.startPlace, trip.startArea),
        end = PlaceNames(trip.endPlace, trip.endArea),
        fallbackTitle = fallbackTitle,
    )

    companion object {
        private const val TAG = "RoadguardTrips"

        /** SQLite limits the variables in one statement; assignments are batched well under it. */
        const val ASSIGN_BATCH = 500
    }
}
