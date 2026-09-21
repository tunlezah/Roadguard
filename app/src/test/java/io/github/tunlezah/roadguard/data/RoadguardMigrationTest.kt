package io.github.tunlezah.roadguard.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every hand-written migration, run against the schema Room exported for each version.
 *
 * `runMigrationsAndValidate` opens a database at the old version from `schemas/…/1.json`, applies
 * the migration, and compares the result with `2.json` column by column, index by index. A
 * statement that drifts from what the entities declare fails here, on the JVM, instead of as a
 * crash on first launch after an update -- and losing the index is exactly the failure Roadguard
 * refuses to recover from destructively, because it would orphan protected footage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoadguardMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RoadguardDatabase::class.java,
    )

    @Test
    fun `migrating from 1 to 2 keeps every clip and adds the trip columns`() {
        helper.createDatabase(NAME, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO segments (fileName, bucket, startedAtEpochMs, durationMs, sizeBytes, widthPx, heightPx, " +
                    "rotationDegrees, codec, bitrateBps, frameRate, hasAudio, cameraFacing, profileLabel, isProtected, " +
                    "protectionReason, eventId, isComplete, startLatitude, startLongitude) VALUES " +
                    "('RG_20260919-081204_000001.mp4', 'recordings', 1789978324000, 180000, 212000000, 1920, 1080, " +
                    "90, 'video/avc', 12000000, 30, 0, 'Rear', 'FHD 1080p30', 1, 'event 41', 41, 1, -35.1991, 149.1561)",
            )
        }

        val v2 = helper.runMigrationsAndValidate(NAME, 2, true, RoadguardMigrations.MIGRATION_1_2)

        v2.query("SELECT fileName, isProtected, tripId, endLatitude, endLongitude FROM segments").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("RG_20260919-081204_000001.mp4")
            assertThat(cursor.getInt(1)).isEqualTo(1)
            // The new columns are null until start-up reconciliation groups the clip into a trip.
            assertThat(cursor.isNull(2)).isTrue()
            assertThat(cursor.isNull(3)).isTrue()
            assertThat(cursor.isNull(4)).isTrue()
        }
        v2.query("SELECT COUNT(*) FROM trips").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        v2.close()
    }

    @Test
    fun `the migration list covers every version step`() {
        assertThat(RoadguardMigrations.ALL.map { it.startVersion to it.endVersion }).containsExactly(1 to 2)
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
