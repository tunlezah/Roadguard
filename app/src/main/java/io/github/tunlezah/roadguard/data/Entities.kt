package io.github.tunlezah.roadguard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recorded video segment.
 *
 * The index is the authority for loop deletion ordering and for storage accounting, but it
 * is never trusted blindly: [io.github.tunlezah.roadguard.storage.StorageReconciler]
 * re-reconciles it against the filesystem on every start, because files can vanish (user
 * deletion, SD card removal) or appear (a crash between muxer finalise and index insert)
 * behind its back.
 */
@Entity(
    tableName = "segments",
    indices = [
        Index("startedAtEpochMs"),
        Index("isProtected"),
        Index("eventId"),
        Index(value = ["fileName"], unique = true),
    ],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** File name within [bucket]; unique so a double insert cannot duplicate a segment. */
    val fileName: String,

    /** Which directory the file lives in; see [io.github.tunlezah.roadguard.storage.StorageBucket]. */
    val bucket: String,

    val startedAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,

    /** Encoded frame size, before the container rotation hint is applied. */
    val widthPx: Int,
    val heightPx: Int,

    /** MP4 rotation hint in degrees: how a player must rotate the frames to display them. */
    val rotationDegrees: Int,

    val codec: String,
    val bitrateBps: Int,
    val frameRate: Int,
    val hasAudio: Boolean,
    val cameraFacing: String,

    /** Label of the recording profile in force, for diagnostics and benchmarking. */
    val profileLabel: String,

    val isProtected: Boolean = false,
    val protectionReason: String? = null,
    val eventId: Long? = null,

    /**
     * False until the recorder reported a successful finalise. An incomplete segment is a
     * crash/kill candidate and is quarantined and inspected on the next start.
     */
    val isComplete: Boolean = false,

    /** First fix seen while this segment was recording, if location was available. */
    @ColumnInfo(name = "startLatitude") val startLatitude: Double? = null,
    @ColumnInfo(name = "startLongitude") val startLongitude: Double? = null,
)

/** A detected or manually requested protection event. */
@Entity(
    tableName = "events",
    indices = [Index("detectedAtEpochMs"), Index("state")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val detectedAtEpochMs: Long,

    /** [EventKind] name. */
    val kind: String,

    /** 0..1 detector confidence; 1.0 for a manual protect. */
    val confidence: Float,

    /** Peak resultant acceleration excluding gravity, in g. Null for a manual protect. */
    val peakG: Float? = null,

    /** Estimated speed change across the impact window, km/h. Null when GNSS was unavailable. */
    val deltaSpeedKmh: Float? = null,

    val speedBeforeKmh: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,

    val preEventSeconds: Int,
    val postEventSeconds: Int,

    /** [EventState] name. */
    val state: String,

    val note: String? = null,
)

/** How an event came to exist. */
enum class EventKind { Impact, HardBraking, Manual }

/**
 * Lifecycle of an event's protection work.
 *
 * `AwaitingPostRoll` matters for crash resilience: if the process dies between detecting an
 * event and protecting its post-event footage, the next start finds the event in this state
 * and finishes the job with whatever segments exist.
 */
enum class EventState { AwaitingPostRoll, Protected, Incomplete }
