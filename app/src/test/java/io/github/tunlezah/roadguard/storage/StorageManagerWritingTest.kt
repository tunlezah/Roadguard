package io.github.tunlezah.roadguard.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.data.RoadguardDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Being written" is what the gallery uses to call a clip "still recording" and keep it from the
 * player. It must end when the recorder is done with a file, not when the process ends: a clip
 * whose recorder never reported back used to be listed as recording, and refused by the player,
 * for as long as the app stayed open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageManagerWritingTest {

    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: RoadguardDatabase
    private lateinit var storage: StorageManager

    @Before
    fun setUp() {
        database = RoadguardDatabase.createInMemory(context)
        storage = StorageManager(context, database.segments(), database.trips(), StorageLayout(context, folder.root))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a new segment file is being written until the recorder is done with it`() {
        val file = storage.createSegmentFile(startedAtEpochMs = 1_000L, sequence = 1)

        assertThat(storage.isBeingWritten(file.name)).isTrue()

        storage.finishedWriting(file.name)

        assertThat(storage.isBeingWritten(file.name)).isFalse()
        // Still this run's file, so start-up style repairs keep their hands off it.
        assertThat(storage.isFromThisProcess(file.name)).isTrue()
    }

    @Test
    fun `resolving a path creates no directory`() {
        val layout = StorageLayout(context, File(folder.root, "fresh"))

        val file = layout.file(StorageBucket.Recordings, "RG_1.mp4")

        assertThat(file.parentFile!!.exists()).isFalse()
    }

    @Test
    fun `the loop total counts the clip being written, and stops counting it once it is done`() = runBlocking {
        // The index records a clip as zero bytes until it finalises. Without the in-flight figure
        // the loop total is short by a whole segment while one is being written, which is one of the
        // ways the storage screen and the on-disk diagnostics figure came to disagree.
        val budget = 5L * 1024 * 1024 * 1024

        storage.setInFlightBytes(2_000_000)
        assertThat(storage.refresh(budget).loopUsedBytes).isEqualTo(2_000_000)

        storage.setInFlightBytes(0)
        assertThat(storage.refresh(budget).loopUsedBytes).isEqualTo(0)
    }
}
