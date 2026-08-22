package io.github.tunlezah.roadguard.recording

/**
 * Decides when the recorder should close the current segment and open the next one.
 *
 * Rolling over is the only moment at which Roadguard is allowed to change anything the camera
 * session bakes in -- resolution, frame rate, codec, effects, second camera. Doing it here
 * rather than mid-segment is what lets the thermal engine reduce quality without ever cutting
 * a recording short: the change is queued and applied at the next natural boundary.
 *
 * Pure, so every reason to roll over is unit tested.
 */
object SegmentPlanner {

    /**
     * How early a pending reconfiguration may cut a segment short.
     *
     * If the thermal engine asks for a lower profile 20 seconds into a 3-minute segment,
     * waiting 160 more seconds is too slow to help. But rolling over every few seconds would
     * shred the footage into unusable fragments, so a segment always gets at least
     * [MIN_SEGMENT_MS] before an early rollover is allowed.
     */
    const val MIN_SEGMENT_MS = 20_000L

    fun decide(
        elapsedMs: Long,
        targetSegmentMs: Long,
        reconfigurationPending: Boolean,
        storageCleanupRequired: Boolean,
        recorderErrorPending: Boolean,
    ): RolloverDecision = when {
        recorderErrorPending -> RolloverDecision(true, RolloverReason.RecorderError)
        elapsedMs >= targetSegmentMs -> RolloverDecision(true, RolloverReason.SegmentComplete)
        reconfigurationPending && elapsedMs >= MIN_SEGMENT_MS ->
            RolloverDecision(true, RolloverReason.Reconfiguration)

        storageCleanupRequired && elapsedMs >= MIN_SEGMENT_MS ->
            RolloverDecision(true, RolloverReason.StorageCleanup)

        else -> RolloverDecision(false, null)
    }
}

data class RolloverDecision(val shouldRoll: Boolean, val reason: RolloverReason?)

enum class RolloverReason(val label: String) {
    SegmentComplete("segment length reached"),
    Reconfiguration("applying a new recording profile"),
    StorageCleanup("making room in the loop"),
    RecorderError("recovering from a recorder error"),
}
