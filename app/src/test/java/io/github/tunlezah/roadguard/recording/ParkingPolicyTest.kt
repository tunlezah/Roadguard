package io.github.tunlezah.roadguard.recording

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The three tiers of a parked car: recording, parked and watching, asleep.
 *
 * Each rule here is a promise to the driver -- recording pauses only after the whole time has
 * passed, comes back the moment the car moves, and does not hold the phone awake for longer than
 * the watch -- so each is pinned rather than left to be discovered in a car park.
 */
class ParkingPolicyTest {

    private fun decide(
        status: RecorderStatus,
        enabled: Boolean = true,
        stillForMs: Long = 0L,
        moving: Boolean = false,
        parkedForMs: Long? = null,
    ): ParkingAction = ParkingPolicy.decide(
        enabled = enabled,
        status = status,
        stillForMs = stillForMs,
        parkAfterMs = PARK_AFTER_MS,
        moving = moving,
        parkedForMs = parkedForMs,
        watchForMs = WATCH_MS,
    )

    @Test
    fun `a recording parks once the vehicle has been still for the whole time, not a second before`() {
        assertThat(decide(RecorderStatus.Recording, stillForMs = PARK_AFTER_MS - 1_000)).isEqualTo(ParkingAction.None)
        assertThat(decide(RecorderStatus.Recording, stillForMs = PARK_AFTER_MS)).isEqualTo(ParkingAction.Park)
    }

    @Test
    fun `only a session that is recording or reconnecting parks`() {
        val expected = mapOf(
            RecorderStatus.Idle to ParkingAction.None,
            // Starting has observed nothing yet; stopping is ending anyway.
            RecorderStatus.Starting to ParkingAction.None,
            RecorderStatus.Recording to ParkingAction.Park,
            RecorderStatus.RollingOver to ParkingAction.Park,
            // A camera that has been lost for ten minutes in a car that has not moved is not
            // worth retrying every minute either.
            RecorderStatus.Recovering to ParkingAction.Park,
            RecorderStatus.Stopping to ParkingAction.None,
            RecorderStatus.Failed to ParkingAction.None,
            RecorderStatus.Parked to ParkingAction.None,
        )
        assertThat(expected.keys).containsExactlyElementsIn(RecorderStatus.entries)
        for ((status, action) in expected) {
            val parkedFor = if (status == RecorderStatus.Parked) 0L else null
            assertWithMessage(status.name)
                .that(decide(status, stillForMs = 60 * 60_000L, parkedForMs = parkedFor))
                .isEqualTo(action)
        }
    }

    @Test
    fun `with pausing switched off a recording never parks`() {
        assertThat(decide(RecorderStatus.Recording, enabled = false, stillForMs = 24 * 60 * 60_000L))
            .isEqualTo(ParkingAction.None)
    }

    @Test
    fun `a parked session resumes the moment the vehicle moves`() {
        assertThat(decide(RecorderStatus.Parked, moving = true, parkedForMs = 1_000)).isEqualTo(ParkingAction.Resume)
    }

    @Test
    fun `a parked session resumes when pausing is switched off`() {
        assertThat(decide(RecorderStatus.Parked, enabled = false, parkedForMs = 1_000)).isEqualTo(ParkingAction.Resume)
    }

    @Test
    fun `a parked session sleeps once the watch is over, not before`() {
        assertThat(decide(RecorderStatus.Parked, parkedForMs = WATCH_MS - 1_000)).isEqualTo(ParkingAction.None)
        assertThat(decide(RecorderStatus.Parked, parkedForMs = WATCH_MS)).isEqualTo(ParkingAction.Sleep)
    }

    @Test
    fun `driving off wins over a watch that ran out in the same second`() {
        assertThat(decide(RecorderStatus.Parked, moving = true, parkedForMs = WATCH_MS)).isEqualTo(ParkingAction.Resume)
    }

    @Test
    fun `a parked session already told to sleep is not told again`() {
        // The recorder forgets the parked time the moment it acts on Sleep.
        assertThat(decide(RecorderStatus.Parked, parkedForMs = null)).isEqualTo(ParkingAction.None)
    }

    private companion object {
        const val PARK_AFTER_MS = 10 * 60_000L
        const val WATCH_MS = 30 * 60_000L
    }
}
