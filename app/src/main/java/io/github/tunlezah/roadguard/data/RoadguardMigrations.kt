package io.github.tunlezah.roadguard.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change the index has ever had, as an explicit migration.
 *
 * The database is never allowed to fall back to a destructive migration: losing the index would
 * orphan protected footage. Each step here is checked against the exported schema under
 * `app/schemas/` by `RoadguardMigrationTest`, so a hand-written statement that drifts from what
 * Room expects fails on the JVM rather than on a user's phone.
 */
object RoadguardMigrations {

    /**
     * Version 2 adds trips.
     *
     * Clips gain a nullable `tripId` (start-up reconciliation assigns one to every existing clip)
     * and the fix seen when they finalised, and the new `trips` table holds one row per drive. The
     * new columns are all nullable, so no default is needed for the rows already on disk.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `segments` ADD COLUMN `tripId` INTEGER")
            db.execSQL("ALTER TABLE `segments` ADD COLUMN `endLatitude` REAL")
            db.execSQL("ALTER TABLE `segments` ADD COLUMN `endLongitude` REAL")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_segments_tripId` ON `segments` (`tripId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `trips` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startedAtEpochMs` INTEGER NOT NULL, " +
                    "`endedAtEpochMs` INTEGER NOT NULL, " +
                    "`state` TEXT NOT NULL, " +
                    "`startLatitude` REAL, " +
                    "`startLongitude` REAL, " +
                    "`endLatitude` REAL, " +
                    "`endLongitude` REAL, " +
                    "`startPlace` TEXT, " +
                    "`startArea` TEXT, " +
                    "`endPlace` TEXT, " +
                    "`endArea` TEXT, " +
                    "`namesResolved` INTEGER NOT NULL, " +
                    "`trackFileName` TEXT, " +
                    "`trackPointCount` INTEGER NOT NULL, " +
                    "`distanceMetres` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_startedAtEpochMs` ON `trips` (`startedAtEpochMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_state` ON `trips` (`state`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
