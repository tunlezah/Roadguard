package io.github.tunlezah.roadguard.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.data.RoadguardDatabase
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.map.PlaceLookup
import io.github.tunlezah.roadguard.trip.PlaceNames
import io.github.tunlezah.roadguard.trip.TripRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Start-up reconciliation against a real (in-memory) Room database and a real temporary
 * filesystem, under Robolectric.
 *
 * The case that matters most is the one a flat battery produced in the field: the index was
 * compared with a recordings folder that held nothing -- the volume was not the one the rows were
 * written to, or was not ready yet -- and every row was dropped, taking the trips, the tracks and
 * the protection marks with it while the footage sat on the disk. The first tests pin that a
 * folder with no earlier recording never empties the index, and that an unreadable folder stops
 * the pass altogether. The rest pin the repairs that are meant to happen: an interrupted clip is
 * recovered or quarantined by what its file contains, a finished clip whose end never reached the
 * disk is quarantined rather than listed as playable, and a file the index lost is adopted.
 *
 * `MediaMetadataRetriever` is a shadow here, so a file is declared playable by registering a
 * duration for its path; the box scan that decides "index present or not" runs on real bytes.
 * Each test records into a temporary folder of its own rather than Robolectric's shared
 * external-storage stand-in, so nothing another test wrote or removed can reach it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageReconcilerTest {

    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: RoadguardDatabase
    private lateinit var storage: StorageManager
    private lateinit var reconciler: StorageReconciler

    @Before
    fun setUp() {
        database = RoadguardDatabase.createInMemory(context)
        storage = StorageManager(
            context,
            database.segments(),
            database.trips(),
            initialLayout = StorageLayout(context, folder.root),
        )
        val places = object : PlaceLookup {
            override val isAvailable: Boolean = false
            override suspend fun resolve(latitude: Double, longitude: Double): PlaceNames? = null
        }
        val trips = TripRepository(database.trips(), database.segments(), storage, places)
        reconciler = StorageReconciler(storage, database.segments(), database.events(), trips)
    }

    @After
    fun tearDown() {
        database.close()
        ShadowMediaMetadataRetriever.reset()
    }

    // ── The index survives an empty or unreadable folder ────────────────────────────────

    @Test
    fun `rows whose files are missing are kept while the folder holds no earlier recording`() = runTest {
        insert(row("RG_1.mp4", complete = true, startedAt = 1_000L))
        insert(row("RG_2.mp4", complete = true, startedAt = 2_000L))
        insert(row("RG_3.mp4", complete = false, startedAt = 3_000L))

        val report = reconciler.reconcile()

        assertThat(database.segments().count()).isEqualTo(3)
        assertWithMessage(report.toString()).that(report.droppedRows).isEqualTo(0)
        assertWithMessage(report.toString()).that(report.keptMissing).isEqualTo(3)
        assertThat(report.summary()).contains("kept")
    }

    @Test
    fun `nothing is checked when the recordings folder cannot be read`() = runTest {
        insert(row("RG_1.mp4", complete = true, startedAt = 1_000L))
        // A file where the directory should be: listing it fails, as it does on a volume that
        // is not ready or a card that came back read-only.
        val recordings = storage.layout.recordings
        recordings.deleteRecursively()
        recordings.writeText("not a directory")
        try {
            val report = reconciler.reconcile()

            assertThat(database.segments().count()).isEqualTo(1)
            assertWithMessage(report.toString()).that(report.changedAnything).isFalse()
            assertThat(report.notes.single()).contains("could not be read")
        } finally {
            recordings.delete()
        }
    }

    @Test
    fun `nothing is checked while the chosen volume is not mounted`() = runTest {
        insert(row("RG_1.mp4", complete = true, startedAt = 1_000L))
        storage.useVolume("/definitely/not/a/mounted/volume")

        val report = reconciler.reconcile()

        assertThat(database.segments().count()).isEqualTo(1)
        assertWithMessage(report.toString()).that(report.changedAnything).isFalse()
        assertThat(report.notes.single()).contains("not mounted")
    }

    @Test
    fun `a missing file is dropped once other recordings are present`() = runTest {
        insert(row("RG_1.mp4", complete = true, startedAt = 1_000L))
        insert(row("RG_2.mp4", complete = true, startedAt = 2_000L))
        writePlayable("RG_2.mp4")

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.droppedRows).isEqualTo(1)
        assertWithMessage(report.toString()).that(report.keptMissing).isEqualTo(0)
        assertThat(database.segments().allFileNames()).containsExactly("RG_2.mp4")
    }

    // ── Interrupted and truncated clips ─────────────────────────────────────────────────

    @Test
    fun `an interrupted clip whose file is whole is recovered into the index`() = runTest {
        insert(row("RG_1.mp4", complete = false, startedAt = 1_000L))
        writePlayable("RG_1.mp4")

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.repairedIncomplete).isEqualTo(1)
        assertWithMessage(report.toString()).that(report.quarantined).isEqualTo(0)
        val repaired = database.segments().byFileName("RG_1.mp4")!!
        assertThat(repaired.isComplete).isTrue()
        assertThat(repaired.durationMs).isEqualTo(180_000L)
    }

    @Test
    fun `an interrupted clip with no index is quarantined, never deleted`() = runTest {
        insert(row("RG_1.mp4", complete = false, startedAt = 1_000L))
        val file = writeTruncated("RG_1.mp4")

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.quarantined).isEqualTo(1)
        assertThat(database.segments().count()).isEqualTo(0)
        assertThat(file.exists()).isFalse()
        assertThat(File(storage.layout.quarantine, "RG_1.mp4").length()).isGreaterThan(0L)
    }

    @Test
    fun `a finished clip whose end never reached the disk is quarantined, and the one before it kept`() = runTest {
        // The last clip of a drive that ended with the power: its row says complete, its file
        // stops before the index. Listing it as playable would only fail in the player.
        insert(row("RG_1.mp4", complete = true, startedAt = 1_000L))
        insert(row("RG_2.mp4", complete = true, startedAt = 2_000L))
        writePlayable("RG_1.mp4")
        writeTruncated("RG_2.mp4")

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.quarantined).isEqualTo(1)
        assertThat(report.notes.single()).contains("lost with the power")
        assertThat(database.segments().allFileNames()).containsExactly("RG_1.mp4")
        assertThat(File(storage.layout.quarantine, "RG_2.mp4").exists()).isTrue()
    }

    @Test
    fun `a finished clip with an index is left alone even when its metadata cannot be read`() = runTest {
        // No duration registered for this file: the retriever fails, as it might for a moment
        // on a real device. The structure is whole, so the clip stays where it is.
        insert(row("RG_1.mp4", complete = true, startedAt = 1_000L))
        val file = File(storage.layout.recordings, "RG_1.mp4").apply { writeBytes(mp4(withIndex = true)) }

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.quarantined).isEqualTo(0)
        assertThat(file.exists()).isTrue()
        assertThat(database.segments().count()).isEqualTo(1)
    }

    @Test
    fun `an interrupted clip that is whole but unreadable right now is left exactly as it is`() = runTest {
        // Index and media present, metadata read failing: nothing is moved and nothing dropped.
        insert(row("RG_1.mp4", complete = false, startedAt = 1_000L))
        val file = File(storage.layout.recordings, "RG_1.mp4").apply { writeBytes(mp4(withIndex = true)) }

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.quarantined).isEqualTo(0)
        assertWithMessage(report.toString()).that(report.repairedIncomplete).isEqualTo(0)
        assertThat(file.exists()).isTrue()
        assertThat(database.segments().byFileName("RG_1.mp4")).isNotNull()
        assertThat(report.notes.single()).contains("left for the next start")
    }

    @Test
    fun `a finished clip whose index was cut short is quarantined`() = runTest {
        // The power went during the last write: the index box is there, but not all of it.
        insert(row("RG_1.mp4", complete = true, startedAt = 1_000L))
        insert(row("RG_2.mp4", complete = true, startedAt = 2_000L))
        writePlayable("RG_1.mp4")
        val whole = mp4(withIndex = true)
        File(storage.layout.recordings, "RG_2.mp4").writeBytes(whole.copyOf(whole.size - 4))

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.quarantined).isEqualTo(1)
        assertThat(database.segments().allFileNames()).containsExactly("RG_1.mp4")
    }

    // ── Files the index lost ────────────────────────────────────────────────────────────

    @Test
    fun `a playable file the index does not know about is adopted`() = runTest {
        writePlayable("RG_lost.mp4")

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.adoptedFiles).isEqualTo(1)
        val adopted = database.segments().byFileName("RG_lost.mp4")!!
        assertThat(adopted.isComplete).isTrue()
        assertThat(adopted.profileLabel).isEqualTo("recovered")
    }

    @Test
    fun `an unindexed file that is whole but unreadable right now is left in place`() = runTest {
        val file = File(storage.layout.recordings, "RG_lost.mp4").apply { writeBytes(mp4(withIndex = true)) }

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.quarantined).isEqualTo(0)
        assertWithMessage(report.toString()).that(report.adoptedFiles).isEqualTo(0)
        assertThat(file.exists()).isTrue()
        assertThat(database.segments().count()).isEqualTo(0)
    }

    @Test
    fun `a truncated file the index does not know about is quarantined`() = runTest {
        writeTruncated("RG_lost.mp4")

        val report = reconciler.reconcile()

        assertWithMessage(report.toString()).that(report.quarantined).isEqualTo(1)
        assertWithMessage(report.toString()).that(report.adoptedFiles).isEqualTo(0)
        assertThat(File(storage.layout.quarantine, "RG_lost.mp4").exists()).isTrue()
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private suspend fun insert(segment: SegmentEntity): Long = database.segments().insert(segment)

    private fun row(name: String, complete: Boolean, startedAt: Long) = SegmentEntity(
        fileName = name,
        bucket = StorageBucket.Recordings.dirName,
        startedAtEpochMs = startedAt,
        durationMs = if (complete) 180_000L else 0L,
        sizeBytes = if (complete) 40L * 1024 else 0L,
        widthPx = 1920,
        heightPx = 1080,
        rotationDegrees = 0,
        codec = "video/avc",
        bitrateBps = 12_000_000,
        frameRate = 30,
        hasAudio = false,
        cameraFacing = "Rear",
        profileLabel = "test",
        isComplete = complete,
    )

    private fun writePlayable(name: String): File {
        val file = File(storage.layout.recordings, name)
        file.writeBytes(mp4(withIndex = true))
        ShadowMediaMetadataRetriever.addMetadata(file.absolutePath, MediaMetadataRetriever.METADATA_KEY_DURATION, "180000")
        return file
    }

    private fun writeTruncated(name: String): File =
        File(storage.layout.recordings, name).apply { writeBytes(mp4(withIndex = false)) }

    /** The top-level box chain of an MP4: `ftyp`, then media, then -- only for a finished file -- the index. */
    private fun mp4(withIndex: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun box(type: String, payload: ByteArray) {
            val size = 8 + payload.size
            out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(payload)
        }
        box("ftyp", "isom".toByteArray(Charsets.US_ASCII) + ByteArray(4))
        box("mdat", ByteArray(40 * 1024))
        if (withIndex) box("moov", ByteArray(8))
        return out.toByteArray()
    }
}
