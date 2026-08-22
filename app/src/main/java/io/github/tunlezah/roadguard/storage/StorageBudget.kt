package io.github.tunlezah.roadguard.storage

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Decides how much space the recording loop may use, and when it must delete.
 *
 * Pure arithmetic with no Android dependencies so the policy can be unit tested exhaustively
 * -- this is the code that stands between a dashcam and a phone with no free space left.
 *
 * ### The reserve
 *
 * Roadguard never spends the last of the device's storage. The reserve it keeps free must
 * cover Android itself, other apps, the app's own database and map data, temporary files and
 * general filesystem headroom. Two hard facts set the floor:
 *
 *  * CameraX's `Recorder` aborts a recording with `ERROR_INSUFFICIENT_STORAGE` once free
 *    space falls below **50 MiB**, so anything near that is already too late
 *    (`docs/research/camera-pipeline.md` §13); and
 *  * Android's own low-storage warning fires in the same neighbourhood as a few percent of
 *    the volume, and a device in that state behaves badly in ways unrelated to Roadguard.
 *
 * So the reserve is `max(1 GiB, 4% of volume)`, capped at 4 GiB so a large card is not
 * wasted. See `docs/storage.md`.
 */
object StorageBudget {

    /** Absolute minimum free space Roadguard will leave on the volume. */
    const val MIN_RESERVE_BYTES: Long = 1L * 1024 * 1024 * 1024

    /** Upper bound on the reserve, so a 512 GB card does not lose 20 GB to headroom. */
    const val MAX_RESERVE_BYTES: Long = 4L * 1024 * 1024 * 1024

    /** Fraction of the volume kept free, between the two bounds above. */
    const val RESERVE_FRACTION: Double = 0.04

    /** Below this much usable loop space, recording cannot be sustained meaningfully. */
    const val MIN_VIABLE_LOOP_BYTES: Long = 256L * 1024 * 1024

    /** Loop usage above this fraction of the effective budget starts trimming. */
    const val TRIM_TRIGGER_FRACTION: Double = 0.97

    /** Trimming frees down to this fraction, so it runs in batches instead of every segment. */
    const val TRIM_TARGET_FRACTION: Double = 0.90

    fun reserveFor(volumeTotalBytes: Long): Long =
        min(
            MAX_RESERVE_BYTES,
            max(MIN_RESERVE_BYTES, (volumeTotalBytes * RESERVE_FRACTION).roundToLong()),
        )

    /**
     * @param requestedBudgetBytes the user's chosen loop size.
     * @param loopUsedBytes bytes currently held by unprotected loop segments.
     * @param protectedBytes bytes held by protected segments (never auto-deleted).
     * @param mapBytes bytes held by offline map data.
     * @param freeBytes free space on the volume right now.
     * @param volumeTotalBytes total size of the volume.
     * @param measuredBytesPerSecond observed recording rate; 0 when nothing measured yet.
     */
    fun evaluate(
        requestedBudgetBytes: Long,
        loopUsedBytes: Long,
        protectedBytes: Long,
        mapBytes: Long,
        freeBytes: Long,
        volumeTotalBytes: Long,
        measuredBytesPerSecond: Double,
    ): StorageAssessment {
        val reserve = reserveFor(volumeTotalBytes)

        // Space the loop could occupy: what it already holds, plus what is free, less the
        // reserve. Protected footage and map data are already accounted for -- they are not
        // free, so they are excluded automatically.
        val ceiling = (loopUsedBytes + freeBytes - reserve).coerceAtLeast(0L)
        val effectiveBudget = min(requestedBudgetBytes, ceiling)
        val budgetLimitedByDevice = effectiveBudget < requestedBudgetBytes

        val trimTrigger = (effectiveBudget * TRIM_TRIGGER_FRACTION).roundToLong()
        val trimTarget = (effectiveBudget * TRIM_TARGET_FRACTION).roundToLong()
        val bytesToFree = if (loopUsedBytes > trimTrigger) {
            (loopUsedBytes - trimTarget).coerceAtLeast(0L)
        } else {
            0L
        }

        val state = when {
            effectiveBudget < MIN_VIABLE_LOOP_BYTES -> StorageState.Critical
            freeBytes < reserve -> StorageState.Critical
            budgetLimitedByDevice || freeBytes < reserve * 2 -> StorageState.Warning
            else -> StorageState.Ok
        }

        val rate = measuredBytesPerSecond.takeIf { it > 0.0 }
        return StorageAssessment(
            state = state,
            reserveBytes = reserve,
            effectiveBudgetBytes = effectiveBudget,
            requestedBudgetBytes = requestedBudgetBytes,
            budgetLimitedByDevice = budgetLimitedByDevice,
            loopUsedBytes = loopUsedBytes,
            protectedBytes = protectedBytes,
            mapBytes = mapBytes,
            freeBytes = freeBytes,
            volumeTotalBytes = volumeTotalBytes,
            bytesToFree = bytesToFree,
            measuredBytesPerSecond = measuredBytesPerSecond,
            // How much history the loop retains once it is full.
            loopCoverageSeconds = rate?.let { (effectiveBudget / it).roundToLong() },
            // How long until the loop starts deleting, from where it is now.
            headroomSeconds = rate?.let { ((effectiveBudget - loopUsedBytes).coerceAtLeast(0L) / it).roundToLong() },
        )
    }

    /**
     * Chooses which candidate segments to delete to free [bytesToFree].
     *
     * @param candidates unprotected, complete segments **oldest first**.
     * @param keepNewest number of most recent candidates to keep no matter what, so a
     *   pathological budget cannot delete the footage recorded seconds ago.
     * @return ids to delete, oldest first, and the total that would be freed.
     */
    fun planCleanup(
        candidates: List<CleanupCandidate>,
        bytesToFree: Long,
        keepNewest: Int = 2,
    ): CleanupPlan {
        if (bytesToFree <= 0L || candidates.isEmpty()) return CleanupPlan(emptyList(), 0L)
        val deletable = if (candidates.size > keepNewest) {
            candidates.subList(0, candidates.size - keepNewest)
        } else {
            emptyList()
        }
        val chosen = mutableListOf<Long>()
        var freed = 0L
        for (candidate in deletable) {
            if (freed >= bytesToFree) break
            chosen += candidate.id
            freed += candidate.sizeBytes
        }
        return CleanupPlan(chosen, freed)
    }
}

/** A loop segment that may be deleted. */
data class CleanupCandidate(val id: Long, val sizeBytes: Long, val startedAtEpochMs: Long)

data class CleanupPlan(val segmentIds: List<Long>, val bytesFreed: Long) {
    val isEmpty: Boolean get() = segmentIds.isEmpty()
}

enum class StorageState { Ok, Warning, Critical }

/** Everything the UI, the recorder and the diagnostics screen need to know about storage. */
data class StorageAssessment(
    val state: StorageState,
    val reserveBytes: Long,
    val effectiveBudgetBytes: Long,
    val requestedBudgetBytes: Long,
    /** True when the device has less room than the user asked Roadguard to use. */
    val budgetLimitedByDevice: Boolean,
    val loopUsedBytes: Long,
    val protectedBytes: Long,
    val mapBytes: Long,
    val freeBytes: Long,
    val volumeTotalBytes: Long,
    val bytesToFree: Long,
    val measuredBytesPerSecond: Double,
    /** Hours of history the full loop retains, or null before any measurement. */
    val loopCoverageSeconds: Long?,
    /** Seconds until loop deletion begins, or null before any measurement. */
    val headroomSeconds: Long?,
) {
    val loopUsedFraction: Float
        get() = if (effectiveBudgetBytes <= 0L) 1f
        else (loopUsedBytes.toDouble() / effectiveBudgetBytes).coerceIn(0.0, 1.0).toFloat()

    val needsCleanup: Boolean get() = bytesToFree > 0L
    val canRecord: Boolean get() = state != StorageState.Critical
}
