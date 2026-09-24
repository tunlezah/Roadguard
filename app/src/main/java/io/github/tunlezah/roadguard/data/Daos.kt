package io.github.tunlezah.roadguard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(segment: SegmentEntity): Long

    @Update
    suspend fun update(segment: SegmentEntity)

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun byId(id: Long): SegmentEntity?

    @Query("SELECT * FROM segments WHERE fileName = :fileName")
    suspend fun byFileName(fileName: String): SegmentEntity?

    @Query("SELECT * FROM segments ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE isProtected = 1 ORDER BY startedAtEpochMs DESC")
    fun observeProtected(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments ORDER BY startedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SegmentEntity>

    /**
     * Loop-deletion candidates: unprotected, complete, oldest first.
     *
     * Protected segments are excluded in SQL rather than filtered in Kotlin so there is no
     * code path in which a protected file can be handed to the deleter.
     */
    @Query(
        "SELECT * FROM segments WHERE isProtected = 0 AND isComplete = 1 " +
            "ORDER BY startedAtEpochMs ASC LIMIT :limit",
    )
    suspend fun oldestUnprotected(limit: Int): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE isComplete = 0")
    suspend fun incomplete(): List<SegmentEntity>

    /**
     * Segments overlapping a time window, used to select pre- and post-event footage.
     *
     * Overlap rather than containment is deliberate: an impact at a segment boundary must
     * protect both the segment that was closing and the one that had just opened.
     */
    @Query(
        "SELECT * FROM segments WHERE startedAtEpochMs < :toEpochMs " +
            "AND (startedAtEpochMs + durationMs) > :fromEpochMs " +
            "ORDER BY startedAtEpochMs ASC",
    )
    suspend fun overlapping(fromEpochMs: Long, toEpochMs: Long): List<SegmentEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segments WHERE isProtected = 0")
    fun observeLoopBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segments WHERE isProtected = 1")
    fun observeProtectedBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segments WHERE isProtected = 0")
    suspend fun loopBytes(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segments WHERE isProtected = 1")
    suspend fun protectedBytes(): Long

    @Query("SELECT COUNT(*) FROM segments")
    suspend fun count(): Int

    @Query("SELECT fileName FROM segments")
    suspend fun allFileNames(): List<String>

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE segments SET isProtected = 1, protectionReason = :reason, eventId = :eventId WHERE id IN (:ids)")
    suspend fun protect(ids: List<Long>, reason: String, eventId: Long?)

    @Query("UPDATE segments SET isProtected = 0, protectionReason = NULL, eventId = NULL WHERE id = :id")
    suspend fun unprotect(id: Long)

    @Query("SELECT * FROM segments WHERE eventId = :eventId ORDER BY startedAtEpochMs ASC")
    suspend fun forEvent(eventId: Long): List<SegmentEntity>

    // ── Trips ─────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM segments WHERE tripId IS NULL ORDER BY startedAtEpochMs ASC")
    suspend fun unassigned(): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE tripId = :tripId ORDER BY startedAtEpochMs ASC")
    suspend fun forTrip(tripId: Long): List<SegmentEntity>

    @Query("SELECT COUNT(*) FROM segments WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: Long): Int

    @Query("UPDATE segments SET tripId = :tripId WHERE id IN (:ids)")
    suspend fun assignTrip(ids: List<Long>, tripId: Long)

    @Query("UPDATE segments SET endLatitude = :latitude, endLongitude = :longitude WHERE id = :id")
    suspend fun setEndLocation(id: Long, latitude: Double?, longitude: Double?)

    /**
     * Records that a segment's file was finalised: its length, size and end position.
     *
     * A targeted UPDATE rather than an [Update] of a row read a moment earlier. An impact or a
     * manual Protect can mark this very row protected while the finalise path is running, and
     * writing back the stale copy would silently clear that mark -- after which the loop could
     * delete the footage. This statement touches no protection column at all. A null end position
     * keeps whatever the row already had.
     */
    @Query(
        "UPDATE segments SET durationMs = :durationMs, sizeBytes = :sizeBytes, isComplete = 1, " +
            "endLatitude = COALESCE(:endLatitude, endLatitude), " +
            "endLongitude = COALESCE(:endLongitude, endLongitude) WHERE id = :id",
    )
    suspend fun markComplete(id: Long, durationMs: Long, sizeBytes: Long, endLatitude: Double?, endLongitude: Double?)

    /** Average bytes per second across recent complete segments, for storage estimates. */
    @Query(
        "SELECT CASE WHEN SUM(durationMs) > 0 THEN (SUM(sizeBytes) * 1000.0) / SUM(durationMs) ELSE 0 END " +
            "FROM (SELECT sizeBytes, durationMs FROM segments WHERE isComplete = 1 " +
            "ORDER BY startedAtEpochMs DESC LIMIT :sampleSize)",
    )
    suspend fun measuredBytesPerSecond(sampleSize: Int = 20): Double
}

@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun byId(id: Long): EventEntity?

    @Query("SELECT * FROM events ORDER BY detectedAtEpochMs DESC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE state = :state ORDER BY detectedAtEpochMs ASC")
    suspend fun byState(state: String): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY detectedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Transaction
    suspend fun markProtected(id: Long) {
        byId(id)?.let { update(it.copy(state = EventState.Protected.name)) }
    }
}

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: Long): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startedAtEpochMs DESC")
    suspend fun all(): List<TripEntity>

    /** The most recently started trip, which is the only one a new recording could continue. */
    @Query("SELECT * FROM trips ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun latest(): TripEntity?

    @Query("SELECT * FROM trips WHERE state = :state ORDER BY startedAtEpochMs ASC")
    suspend fun byState(state: String): List<TripEntity>

    @Query("SELECT trackFileName FROM trips WHERE trackFileName IS NOT NULL")
    suspend fun allTrackFileNames(): List<String>

    @Query("UPDATE trips SET endedAtEpochMs = :endedAtEpochMs, endLatitude = :latitude, endLongitude = :longitude WHERE id = :id")
    suspend fun advanceEnd(id: Long, endedAtEpochMs: Long, latitude: Double?, longitude: Double?)

    @Query("UPDATE trips SET trackFileName = :fileName WHERE id = :id")
    suspend fun setTrackFile(id: Long, fileName: String?)

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)
}
