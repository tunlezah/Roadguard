package io.github.tunlezah.roadguard.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins down [StorageBudget], the policy that stands between a dashcam and a phone with no
 * free space left.
 *
 * Three contracts matter here, and all three are safety contracts rather than cosmetic ones:
 *
 *  1. **The reserve is never spent.** For every possible combination of requested budget,
 *     current loop usage, free space and volume size, the effective budget must fit inside
 *     `loopUsed + free - reserve`. If that arithmetic ever slips, Roadguard fills the volume,
 *     CameraX aborts the recording with `ERROR_INSUFFICIENT_STORAGE`, and the driver loses the
 *     footage of the crash that just happened.
 *  2. **Trimming is batched and bounded.** Nothing is deleted below the trigger fraction, and
 *     once the trigger is crossed, usage lands exactly on the target fraction -- so cleanup runs
 *     occasionally rather than on every segment, and never over-deletes.
 *  3. **Cleanup never eats the newest footage.** `planCleanup` deletes oldest-first and must
 *     hold back the newest `keepNewest` segments even when asked to free more than exists,
 *     because the newest segments are the incident.
 *
 * All arithmetic is asserted against literal expected values computed by hand, so a change in
 * the policy constants or the rounding shows up as a failure rather than a silently different
 * retention window.
 */
class StorageBudgetTest {

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * 1024
        const val GIB = 1024L * 1024 * 1024

        /** 4% of 25 GiB is exactly 1 GiB, so this is the MIN_RESERVE -> fraction crossover. */
        const val RESERVE_FLOOR_CROSSOVER = 25 * GIB

        /** 4% of 100 GiB is exactly 4 GiB, so this is the fraction -> MAX_RESERVE crossover. */
        const val RESERVE_CAP_CROSSOVER = 100 * GIB
    }

    private fun evaluate(
        requested: Long,
        loopUsed: Long = 0L,
        protectedBytes: Long = 0L,
        mapBytes: Long = 0L,
        free: Long,
        total: Long,
        rate: Double = 0.0,
    ) = StorageBudget.evaluate(
        requestedBudgetBytes = requested,
        loopUsedBytes = loopUsed,
        protectedBytes = protectedBytes,
        mapBytes = mapBytes,
        freeBytes = free,
        volumeTotalBytes = total,
        measuredBytesPerSecond = rate,
    )

    // ---------------------------------------------------------------- reserveFor

    @Test
    fun `reserve is the floor for volumes at or below the first crossover`() {
        val cases = listOf(
            0L,
            512 * MIB,
            8 * GIB,
            24 * GIB,
            RESERVE_FLOOR_CROSSOVER,
        )
        for (total in cases) {
            assertThat(StorageBudget.reserveFor(total))
                .isEqualTo(StorageBudget.MIN_RESERVE_BYTES)
        }
        // Exactly at the crossover the two candidates coincide: 4% of 25 GiB == 1 GiB.
        assertThat((RESERVE_FLOOR_CROSSOVER * StorageBudget.RESERVE_FRACTION).toLong())
            .isEqualTo(StorageBudget.MIN_RESERVE_BYTES)
    }

    @Test
    fun `reserve is four percent between the crossovers`() {
        val cases = mapOf(
            26 * GIB to 1_116_691_497L,
            32 * GIB to 1_374_389_535L,
            50 * GIB to 2_147_483_648L,
            64 * GIB to 2_748_779_069L,
            99 * GIB to 4_252_017_623L,
        )
        for ((total, expected) in cases) {
            assertThat(StorageBudget.reserveFor(total)).isEqualTo(expected)
            assertThat(StorageBudget.reserveFor(total))
                .isGreaterThan(StorageBudget.MIN_RESERVE_BYTES)
            assertThat(StorageBudget.reserveFor(total))
                .isLessThan(StorageBudget.MAX_RESERVE_BYTES)
        }
    }

    @Test
    fun `reserve is capped at the maximum for very large volumes`() {
        val cases = listOf(
            RESERVE_CAP_CROSSOVER,
            RESERVE_CAP_CROSSOVER + 1,
            128 * GIB,
            512 * GIB,
            2048 * GIB,
        )
        for (total in cases) {
            assertThat(StorageBudget.reserveFor(total))
                .isEqualTo(StorageBudget.MAX_RESERVE_BYTES)
        }
        // Exactly at the cap crossover the two candidates coincide: 4% of 100 GiB == 4 GiB.
        assertThat((RESERVE_CAP_CROSSOVER * StorageBudget.RESERVE_FRACTION).toLong())
            .isEqualTo(StorageBudget.MAX_RESERVE_BYTES)
    }

    @Test
    fun `reserve is monotonically non decreasing in volume size`() {
        var previous = 0L
        var total = 0L
        while (total <= 256 * GIB) {
            val reserve = StorageBudget.reserveFor(total)
            assertThat(reserve).isAtLeast(previous)
            previous = reserve
            total += 1 * GIB
        }
    }

    // ---------------------------------------------------------------- evaluate: healthy

    @Test
    fun `a healthy device gets exactly the budget it asked for`() {
        val result = evaluate(
            requested = 8 * GIB,
            loopUsed = 2 * GIB,
            protectedBytes = 1 * GIB,
            mapBytes = 512 * MIB,
            free = 20 * GIB,
            total = 64 * GIB,
        )

        assertThat(result.state).isEqualTo(StorageState.Ok)
        assertThat(result.effectiveBudgetBytes).isEqualTo(8 * GIB)
        assertThat(result.requestedBudgetBytes).isEqualTo(8 * GIB)
        assertThat(result.budgetLimitedByDevice).isFalse()
        assertThat(result.bytesToFree).isEqualTo(0L)
        assertThat(result.needsCleanup).isFalse()
        assertThat(result.canRecord).isTrue()
        assertThat(result.reserveBytes).isEqualTo(2_748_779_069L)
        // Protected footage and map data are reported back untouched.
        assertThat(result.protectedBytes).isEqualTo(1 * GIB)
        assertThat(result.mapBytes).isEqualTo(512 * MIB)
    }

    // ---------------------------------------------------------------- evaluate: squeezed

    @Test
    fun `a device without room for the requested budget is clamped to the ceiling`() {
        val total = 64 * GIB
        val reserve = 2_748_779_069L
        val loopUsed = 4 * GIB
        val free = 6 * GIB

        val result = evaluate(
            requested = 32 * GIB,
            loopUsed = loopUsed,
            free = free,
            total = total,
        )

        assertThat(result.reserveBytes).isEqualTo(reserve)
        assertThat(result.effectiveBudgetBytes).isEqualTo(loopUsed + free - reserve)
        assertThat(result.effectiveBudgetBytes).isEqualTo(7_988_639_171L)
        assertThat(result.budgetLimitedByDevice).isTrue()
        assertThat(result.state).isAnyOf(StorageState.Warning, StorageState.Critical)
        assertThat(result.state).isNotEqualTo(StorageState.Ok)
        assertThat(result.canRecord).isTrue()
    }

    @Test
    fun `free space near the reserve warns even when the budget fits`() {
        // free is above the reserve but below twice it, and the budget is small enough to fit.
        val result = evaluate(
            requested = 1 * GIB,
            loopUsed = 0,
            free = 4 * GIB,
            total = 64 * GIB,
        )

        assertThat(result.reserveBytes).isEqualTo(2_748_779_069L)
        assertThat(result.effectiveBudgetBytes).isEqualTo(1 * GIB)
        assertThat(result.budgetLimitedByDevice).isFalse()
        assertThat(4 * GIB).isLessThan(2 * result.reserveBytes)
        assertThat(result.state).isEqualTo(StorageState.Warning)
        assertThat(result.canRecord).isTrue()
    }

    // ------------------------------------------------- the invariant: never spend the reserve

    private data class Scenario(
        val name: String,
        val requested: Long,
        val loopUsed: Long,
        val free: Long,
        val total: Long,
    )

    @Test
    fun `the effective budget never plans to consume the reserve`() {
        val scenarios = listOf(
            Scenario("nothing free on a small volume", 4 * GIB, 0, 0, 8 * GIB),
            Scenario("nothing free with a full loop", 32 * GIB, 32 * GIB, 0, 64 * GIB),
            Scenario("free space exactly equal to the reserve", 8 * GIB, 0, 1 * GIB, 8 * GIB),
            Scenario("one byte less than the reserve", 8 * GIB, 0, 1 * GIB - 1, 8 * GIB),
            Scenario("tiny volume, reserve dwarfs it", 256 * MIB, 0, 400 * MIB, 512 * MIB),
            Scenario("zero-size volume", 1 * GIB, 0, 0, 0),
            Scenario("zero requested budget", 0, 4 * GIB, 20 * GIB, 64 * GIB),
            Scenario("enormous request on a big card", Long.MAX_VALUE, 0, 400 * GIB, 512 * GIB),
            Scenario("enormous request on a squeezed card", Long.MAX_VALUE, 2 * GIB, 3 * GIB, 64 * GIB),
            Scenario("healthy mid-size device", 16 * GIB, 8 * GIB, 40 * GIB, 128 * GIB),
            Scenario("loop already larger than the ceiling", 64 * GIB, 60 * GIB, 512 * MIB, 64 * GIB),
            Scenario("single byte of free space", 1 * GIB, 0, 1, 32 * GIB),
            Scenario("single kibibyte volume", 1 * GIB, 0, 1 * KIB, 1 * KIB),
        )

        for (s in scenarios) {
            val result = evaluate(
                requested = s.requested,
                loopUsed = s.loopUsed,
                free = s.free,
                total = s.total,
            )
            val ceiling = (s.loopUsed + s.free - result.reserveBytes).coerceAtLeast(0L)

            assertThat(result.reserveBytes).isAtLeast(StorageBudget.MIN_RESERVE_BYTES)
            assertThat(result.reserveBytes).isAtMost(StorageBudget.MAX_RESERVE_BYTES)
            // The invariant: the loop may never be planned larger than what is available
            // after the reserve is set aside.
            assertThat(result.effectiveBudgetBytes).isAtMost(ceiling)
            assertThat(result.effectiveBudgetBytes).isAtLeast(0L)
            // And never more than the user asked for.
            assertThat(result.effectiveBudgetBytes).isAtMost(s.requested)
            // Trimming can only ever free bytes the loop actually holds.
            assertThat(result.bytesToFree).isAtMost(s.loopUsed)
            assertThat(result.bytesToFree).isAtLeast(0L)
            // Post-trim usage still fits the budget.
            assertThat(s.loopUsed - result.bytesToFree)
                .isAtMost(maxOf(result.effectiveBudgetBytes, 0L))
        }
    }

    @Test
    fun `budgetLimitedByDevice is exactly the disagreement between request and ceiling`() {
        val scenarios = listOf(
            Scenario("fits comfortably", 8 * GIB, 2 * GIB, 20 * GIB, 64 * GIB) to false,
            Scenario("does not fit", 32 * GIB, 4 * GIB, 6 * GIB, 64 * GIB) to true,
            Scenario("nothing free", 1 * GIB, 0, 0, 8 * GIB) to true,
            Scenario("zero request always fits", 0, 0, 0, 8 * GIB) to false,
        )
        for ((s, expected) in scenarios) {
            val result = evaluate(
                requested = s.requested,
                loopUsed = s.loopUsed,
                free = s.free,
                total = s.total,
            )
            assertThat(result.budgetLimitedByDevice).isEqualTo(expected)
            assertThat(result.budgetLimitedByDevice)
                .isEqualTo(result.effectiveBudgetBytes < result.requestedBudgetBytes)
        }
    }

    // ---------------------------------------------------------------- trimming

    @Test
    fun `usage just below the trim trigger frees nothing`() {
        // effectiveBudget == 32 GiB, trigger == round(32 GiB * 0.97) == 33_328_946_217.
        val trigger = 33_328_946_217L
        val result = evaluate(
            requested = 32 * GIB,
            loopUsed = trigger,
            free = 100 * GIB,
            total = 200 * GIB,
        )

        assertThat(result.effectiveBudgetBytes).isEqualTo(32 * GIB)
        assertThat(result.bytesToFree).isEqualTo(0L)
        assertThat(result.needsCleanup).isFalse()
    }

    @Test
    fun `usage one byte above the trim trigger frees down to the target`() {
        val trigger = 33_328_946_217L
        val target = 30_923_764_531L // round(32 GiB * 0.90)
        val result = evaluate(
            requested = 32 * GIB,
            loopUsed = trigger + 1,
            free = 100 * GIB,
            total = 200 * GIB,
        )

        assertThat(result.effectiveBudgetBytes).isEqualTo(32 * GIB)
        assertThat(result.needsCleanup).isTrue()
        assertThat(result.bytesToFree).isEqualTo(trigger + 1 - target)
        assertThat(result.bytesToFree).isEqualTo(2_405_181_687L)
        // What matters is where usage lands, not that some deletion happened.
        assertThat(result.loopUsedBytes - result.bytesToFree).isEqualTo(target)
    }

    @Test
    fun `a badly overfull loop is trimmed to the target in one plan`() {
        val target = 30_923_764_531L
        val loopUsed = 40 * GIB
        val result = evaluate(
            requested = 32 * GIB,
            loopUsed = loopUsed,
            free = 100 * GIB,
            total = 200 * GIB,
        )

        assertThat(result.effectiveBudgetBytes).isEqualTo(32 * GIB)
        assertThat(result.bytesToFree).isEqualTo(loopUsed - target)
        assertThat(result.loopUsedBytes - result.bytesToFree).isEqualTo(target)
    }

    @Test
    fun `lowering the budget below current usage schedules a trim to the new target`() {
        // The user shrank the loop from 4 GiB to 1 GiB; usage must come down to 90% of 1 GiB.
        val result = evaluate(
            requested = 1 * GIB,
            loopUsed = 4 * GIB,
            free = 20 * GIB,
            total = 64 * GIB,
        )

        assertThat(result.effectiveBudgetBytes).isEqualTo(1 * GIB)
        assertThat(result.budgetLimitedByDevice).isFalse()
        assertThat(result.bytesToFree).isEqualTo(3_328_599_654L)
        assertThat(result.loopUsedBytes - result.bytesToFree).isEqualTo(966_367_642L)
    }

    @Test
    fun `an empty loop never triggers a trim`() {
        val result = evaluate(
            requested = 8 * GIB,
            loopUsed = 0,
            free = 20 * GIB,
            total = 64 * GIB,
        )
        assertThat(result.bytesToFree).isEqualTo(0L)
        assertThat(result.needsCleanup).isFalse()
    }

    // ---------------------------------------------------------------- critical states

    @Test
    fun `a budget below the minimum viable loop is critical and blocks recording`() {
        // 8 GiB volume -> 1 GiB reserve. Free is reserve + 100 MiB, so the ceiling is 100 MiB.
        val result = evaluate(
            requested = 32 * GIB,
            loopUsed = 0,
            free = 1 * GIB + 100 * MIB,
            total = 8 * GIB,
        )

        assertThat(result.effectiveBudgetBytes).isEqualTo(100 * MIB)
        assertThat(result.effectiveBudgetBytes).isLessThan(StorageBudget.MIN_VIABLE_LOOP_BYTES)
        assertThat(result.state).isEqualTo(StorageState.Critical)
        assertThat(result.canRecord).isFalse()
    }

    @Test
    fun `the minimum viable loop boundary is inclusive`() {
        val viableFree = 1 * GIB + StorageBudget.MIN_VIABLE_LOOP_BYTES

        val exactlyViable = evaluate(requested = 32 * GIB, free = viableFree, total = 8 * GIB)
        assertThat(exactlyViable.effectiveBudgetBytes)
            .isEqualTo(StorageBudget.MIN_VIABLE_LOOP_BYTES)
        assertThat(exactlyViable.state).isNotEqualTo(StorageState.Critical)
        assertThat(exactlyViable.canRecord).isTrue()

        val oneByteShort = evaluate(requested = 32 * GIB, free = viableFree - 1, total = 8 * GIB)
        assertThat(oneByteShort.effectiveBudgetBytes)
            .isEqualTo(StorageBudget.MIN_VIABLE_LOOP_BYTES - 1)
        assertThat(oneByteShort.state).isEqualTo(StorageState.Critical)
        assertThat(oneByteShort.canRecord).isFalse()
    }

    @Test
    fun `free space below the reserve is critical even when the loop is large`() {
        // 64 GiB volume -> 2_748_779_069 reserve; free is well under it, but the loop already
        // holds 8 GiB so the ceiling is comfortably viable.
        val result = evaluate(
            requested = 4 * GIB,
            loopUsed = 8 * GIB,
            free = 1 * GIB,
            total = 64 * GIB,
        )

        assertThat(result.reserveBytes).isEqualTo(2_748_779_069L)
        assertThat(result.freeBytes).isLessThan(result.reserveBytes)
        assertThat(result.effectiveBudgetBytes).isEqualTo(4 * GIB)
        assertThat(result.effectiveBudgetBytes).isAtLeast(StorageBudget.MIN_VIABLE_LOOP_BYTES)
        assertThat(result.budgetLimitedByDevice).isFalse()
        assertThat(result.state).isEqualTo(StorageState.Critical)
        assertThat(result.canRecord).isFalse()
    }

    @Test
    fun `a tiny volume can never be recorded to`() {
        val result = evaluate(
            requested = 256 * MIB,
            loopUsed = 0,
            free = 400 * MIB,
            total = 512 * MIB,
        )

        assertThat(result.reserveBytes).isEqualTo(StorageBudget.MIN_RESERVE_BYTES)
        assertThat(result.effectiveBudgetBytes).isEqualTo(0L)
        assertThat(result.state).isEqualTo(StorageState.Critical)
        assertThat(result.canRecord).isFalse()
        assertThat(result.bytesToFree).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- rate-derived fields

    @Test
    fun `an unmeasured rate leaves coverage and headroom unknown`() {
        val result = evaluate(
            requested = 8 * GIB,
            loopUsed = 2 * GIB,
            free = 20 * GIB,
            total = 64 * GIB,
            rate = 0.0,
        )

        assertThat(result.measuredBytesPerSecond).isEqualTo(0.0)
        assertThat(result.loopCoverageSeconds).isNull()
        assertThat(result.headroomSeconds).isNull()
    }

    @Test
    fun `a measured rate yields coverage and headroom in seconds`() {
        val result = evaluate(
            requested = 8 * GIB,
            loopUsed = 2 * GIB,
            free = 20 * GIB,
            total = 64 * GIB,
            rate = 2_000_000.0,
        )

        assertThat(result.effectiveBudgetBytes).isEqualTo(8 * GIB)
        // 8 GiB / 2 MB/s == 4294.967296 s
        assertThat(result.loopCoverageSeconds).isEqualTo(4295L)
        // (8 GiB - 2 GiB) / 2 MB/s == 3221.225472 s
        assertThat(result.headroomSeconds).isEqualTo(3221L)
    }

    @Test
    fun `headroom is zero once the loop has outgrown the budget`() {
        val result = evaluate(
            requested = 1 * GIB,
            loopUsed = 4 * GIB,
            free = 20 * GIB,
            total = 64 * GIB,
            rate = 1_000_000.0,
        )

        assertThat(result.loopCoverageSeconds).isEqualTo(1074L) // 1 GiB / 1 MB/s
        assertThat(result.headroomSeconds).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- loopUsedFraction

    @Test
    fun `loop used fraction is clamped to zero through one`() {
        val empty = evaluate(requested = 4 * GIB, loopUsed = 0, free = 20 * GIB, total = 64 * GIB)
        assertThat(empty.loopUsedFraction).isEqualTo(0f)

        val half = evaluate(requested = 4 * GIB, loopUsed = 2 * GIB, free = 20 * GIB, total = 64 * GIB)
        assertThat(half.effectiveBudgetBytes).isEqualTo(4 * GIB)
        assertThat(half.loopUsedFraction).isEqualTo(0.5f)

        // The user shrank the budget: usage overshoots, but the bar cannot exceed full.
        val overfull = evaluate(requested = 1 * GIB, loopUsed = 4 * GIB, free = 20 * GIB, total = 64 * GIB)
        assertThat(overfull.loopUsedFraction).isEqualTo(1f)

        // A zero budget reports full rather than dividing by zero.
        val noBudget = evaluate(requested = 256 * MIB, loopUsed = 0, free = 400 * MIB, total = 512 * MIB)
        assertThat(noBudget.effectiveBudgetBytes).isEqualTo(0L)
        assertThat(noBudget.loopUsedFraction).isEqualTo(1f)
    }

    // ---------------------------------------------------------------- planCleanup

    private fun candidates(vararg sizes: Long): List<CleanupCandidate> =
        sizes.mapIndexed { index, size ->
            // index 0 is the oldest; startedAt increases with index.
            CleanupCandidate(id = index.toLong(), sizeBytes = size, startedAtEpochMs = 1_000L + index * 60_000L)
        }

    @Test
    fun `cleanup is a no-op when nothing needs freeing`() {
        val pool = candidates(100, 100, 100, 100, 100)
        for (bytesToFree in listOf(0L, -1L, Long.MIN_VALUE)) {
            val plan = StorageBudget.planCleanup(pool, bytesToFree)
            assertThat(plan.segmentIds).isEmpty()
            assertThat(plan.bytesFreed).isEqualTo(0L)
            assertThat(plan.isEmpty).isTrue()
        }
    }

    @Test
    fun `cleanup is a no-op when there are no candidates`() {
        val plan = StorageBudget.planCleanup(emptyList(), 10 * GIB)
        assertThat(plan.segmentIds).isEmpty()
        assertThat(plan.bytesFreed).isEqualTo(0L)
        assertThat(plan.isEmpty).isTrue()
    }

    @Test
    fun `cleanup deletes oldest first and stops as soon as enough is freed`() {
        val pool = candidates(100, 100, 100, 100, 100)

        val plan = StorageBudget.planCleanup(pool, bytesToFree = 150)

        assertThat(plan.segmentIds).containsExactly(0L, 1L).inOrder()
        assertThat(plan.bytesFreed).isEqualTo(200L)
        assertThat(plan.bytesFreed).isAtLeast(150L)
    }

    @Test
    fun `cleanup takes exactly one segment when one is enough`() {
        val pool = candidates(500, 100, 100, 100)

        val plan = StorageBudget.planCleanup(pool, bytesToFree = 500)

        assertThat(plan.segmentIds).containsExactly(0L)
        assertThat(plan.bytesFreed).isEqualTo(500L)
    }

    @Test
    fun `cleanup never touches the newest segments even when asked for everything`() {
        val pool = candidates(100, 200, 300, 400, 500)

        val plan = StorageBudget.planCleanup(pool, bytesToFree = Long.MAX_VALUE)

        // keepNewest defaults to 2: ids 3 and 4 are untouchable.
        assertThat(plan.segmentIds).containsExactly(0L, 1L, 2L).inOrder()
        assertThat(plan.segmentIds).doesNotContain(3L)
        assertThat(plan.segmentIds).doesNotContain(4L)
        assertThat(plan.bytesFreed).isEqualTo(600L)
        assertThat(plan.bytesFreed).isLessThan(Long.MAX_VALUE)
    }

    @Test
    fun `cleanup refuses to delete anything when only the protected newest remain`() {
        for (size in 0..2) {
            val pool = candidates(*LongArray(size) { 1 * GIB })
            val plan = StorageBudget.planCleanup(pool, bytesToFree = 10 * GIB)
            assertThat(plan.segmentIds).isEmpty()
            assertThat(plan.bytesFreed).isEqualTo(0L)
        }
    }

    @Test
    fun `keepNewest is honoured for other values`() {
        val pool = candidates(100, 100, 100, 100, 100)
        val cases = mapOf(
            0 to listOf(0L, 1L, 2L, 3L, 4L),
            1 to listOf(0L, 1L, 2L, 3L),
            3 to listOf(0L, 1L),
            5 to emptyList(),
            9 to emptyList(),
        )
        for ((keepNewest, expected) in cases) {
            val plan = StorageBudget.planCleanup(pool, bytesToFree = 10_000, keepNewest = keepNewest)
            assertThat(plan.segmentIds).isEqualTo(expected)
            assertThat(plan.bytesFreed).isEqualTo(expected.size * 100L)
        }
    }

    @Test
    fun `bytesFreed always equals the sum of the chosen segment sizes`() {
        val pool = candidates(37, 1024, 5, 900_000, 1, 42, 7777)
        for (bytesToFree in listOf(1L, 36L, 37L, 38L, 1000L, 1_000_000L, Long.MAX_VALUE)) {
            val plan = StorageBudget.planCleanup(pool, bytesToFree)
            val chosen = pool.filter { it.id in plan.segmentIds }
            assertThat(plan.bytesFreed).isEqualTo(chosen.sumOf { it.sizeBytes })
            assertThat(plan.segmentIds).isEqualTo(chosen.map { it.id })
            // Never reaches into the newest two.
            assertThat(plan.segmentIds).containsNoneOf(5L, 6L)
        }
    }

    @Test
    fun `zero sized candidates cannot make cleanup loop forever`() {
        val pool = candidates(0, 0, 0, 0, 0)

        val plan = StorageBudget.planCleanup(pool, bytesToFree = 100)

        // Everything deletable is consumed and the plan simply falls short, honestly reported.
        assertThat(plan.segmentIds).containsExactly(0L, 1L, 2L).inOrder()
        assertThat(plan.bytesFreed).isEqualTo(0L)
    }

    // ------------------------------------------------- evaluate and planCleanup end to end

    @Test
    fun `an overfull loop is trimmed back under the budget by the resulting plan`() {
        val assessment = evaluate(
            requested = 8 * GIB,
            loopUsed = 8 * GIB,
            free = 20 * GIB,
            total = 64 * GIB,
        )
        assertThat(assessment.needsCleanup).isTrue()

        // Twelve equal segments covering the whole loop, oldest first.
        val segmentSize = 8 * GIB / 12
        val pool = candidates(*LongArray(12) { segmentSize })

        val plan = StorageBudget.planCleanup(pool, assessment.bytesToFree)

        assertThat(plan.bytesFreed).isAtLeast(assessment.bytesToFree)
        assertThat(assessment.loopUsedBytes - plan.bytesFreed)
            .isLessThan(assessment.effectiveBudgetBytes)
        // The two newest segments -- the footage from moments ago -- survive.
        assertThat(plan.segmentIds).containsNoneOf(10L, 11L)
    }
}
