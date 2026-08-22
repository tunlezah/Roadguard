package io.github.tunlezah.roadguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The recording index.
 *
 * Room is used rather than a scan of the filesystem because loop deletion, storage
 * accounting and event protection all need ordered queries with a "protected" predicate
 * many times a minute, and a directory scan of thousands of files would be both slow and
 * thermally wasteful. The database lives in internal storage so it is never lost when a
 * removable volume is unmounted.
 *
 * `fallbackToDestructiveMigration` is deliberately **not** used: losing the index would
 * orphan protected footage. Schema changes ship with explicit migrations.
 */
@Database(
    entities = [SegmentEntity::class, EventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RoadguardDatabase : RoomDatabase() {

    abstract fun segments(): SegmentDao

    abstract fun events(): EventDao

    companion object {
        private const val NAME = "roadguard-index.db"

        fun create(context: Context): RoadguardDatabase =
            Room.databaseBuilder(context.applicationContext, RoadguardDatabase::class.java, NAME)
                .build()

        fun createInMemory(context: Context): RoadguardDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, RoadguardDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
