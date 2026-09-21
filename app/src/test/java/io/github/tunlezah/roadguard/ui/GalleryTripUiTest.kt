package io.github.tunlezah.roadguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.data.TripEntity
import io.github.tunlezah.roadguard.data.TripState
import io.github.tunlezah.roadguard.ui.gallery.GalleryContent
import io.github.tunlezah.roadguard.ui.gallery.GalleryDay
import io.github.tunlezah.roadguard.ui.gallery.GalleryFilter
import io.github.tunlezah.roadguard.ui.gallery.GalleryItem
import io.github.tunlezah.roadguard.ui.gallery.GalleryTrip
import io.github.tunlezah.roadguard.ui.gallery.GalleryUiState
import io.github.tunlezah.roadguard.ui.theme.RoadguardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The recordings list, grouped into trips, composed on the JVM.
 *
 * What is held in place: a trip card says where the drive went and what it holds, its clips are
 * hidden until asked for, the track buttons only offer a track that exists, and every control
 * carries a content description -- the same rule the driving screen's chrome is held to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class GalleryTripUiTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private val start = 1_789_978_324_000L

    private fun segment(id: Long, offsetMinutes: Int, protected: Boolean = false, tripId: Long? = 1L) = SegmentEntity(
        id = id,
        fileName = "RG_$id.mp4",
        bucket = "recordings",
        startedAtEpochMs = start + offsetMinutes * 60_000L,
        durationMs = 180_000L,
        sizeBytes = 212L * 1024 * 1024,
        widthPx = 1920,
        heightPx = 1080,
        rotationDegrees = 0,
        codec = "video/avc",
        bitrateBps = 12_000_000,
        frameRate = 30,
        hasAudio = false,
        cameraFacing = "Rear",
        profileLabel = "FHD 1080p30",
        isProtected = protected,
        isComplete = true,
        tripId = tripId,
    )

    // The clip's File is never touched by the screen (existence is precomputed in the item), so a
    // relative path is enough and nothing depends on the temporary folder before a test runs.
    private fun item(segment: SegmentEntity, time: String) =
        GalleryItem(segment, File(segment.fileName), event = null, exists = true, timeLabel = time)

    private val clips = listOf(item(segment(1, 0), "08:12:04"), item(segment(2, 3, protected = true), "08:15:04"))

    private fun trip(expanded: Boolean = false, trackFile: File? = null) = GalleryTrip(
        key = "trip-1",
        trip = TripEntity(id = 1, startedAtEpochMs = start, endedAtEpochMs = start + 29 * 60_000L, state = TripState.Closed.name),
        title = "Harrison → Braddon",
        meta = "Canberra  ·  08:12 – 08:41  ·  29 min  ·  2 clips  ·  11 km",
        items = clips,
        incidentCount = 1,
        protectedCount = 1,
        trackFile = trackFile,
        sketch = listOf(0f to 1f, 1f to 0f),
        expanded = expanded,
        isRecording = false,
        namesHint = null,
    )

    private fun state(vararg cards: GalleryTrip) = GalleryUiState(
        filter = GalleryFilter.All,
        days = listOf(GalleryDay("Saturday 19 September 2026", cards.toList())),
        totalCount = cards.sumOf { it.items.size },
    )

    private class Recorded {
        var toggled: Long? = null
        var opened: File? = null
        var shared: File? = null
        var protectedTrip: Long? = null
        var openedSegment: Long? = null
    }

    private fun show(state: GalleryUiState): Recorded {
        val recorded = Recorded()
        compose.setContent {
            RoadguardTheme {
                GalleryContent(
                    state = state,
                    onBack = {},
                    onOpenSegment = { recorded.openedSegment = it },
                    onSetFilter = {},
                    onToggleTrip = { recorded.toggled = it },
                    onProtect = {},
                    onUnprotect = {},
                    onDelete = {},
                    onShareClip = {},
                    onOpenTrack = { recorded.opened = it },
                    onShareTrack = { recorded.shared = it },
                    onProtectTrip = { recorded.protectedTrip = it },
                    onDeleteUnprotected = {},
                    onMessageShown = {},
                )
            }
        }
        return recorded
    }

    @Test
    fun `a trip card names the drive and says what it holds`() {
        show(state(trip()))

        compose.onNodeWithText("Saturday 19 September 2026").assertIsDisplayed()
        compose.onNodeWithText("Harrison → Braddon").assertIsDisplayed()
        compose.onNodeWithText("Canberra  ·  08:12 – 08:41  ·  29 min  ·  2 clips  ·  11 km").assertIsDisplayed()
        compose.onNodeWithText("1 incident").assertIsDisplayed()
        compose.onNodeWithText("1 protected").assertIsDisplayed()
    }

    @Test
    fun `a collapsed trip keeps its clips out of the way and offers to show them`() {
        show(state(trip(expanded = false)))

        compose.onNodeWithText("08:12:04").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show the clips in this trip").assertIsDisplayed()
    }

    @Test
    fun `an expanded trip lists its clips and opens one on tap`() {
        val recorded = show(state(trip(expanded = true)))

        compose.onNodeWithContentDescription("Hide the clips in this trip").assertIsDisplayed()
        compose.onNodeWithText("08:12:04").assertIsDisplayed()
        compose.onNodeWithText("08:15:04").assertIsDisplayed().performClick()

        assertThat(recorded.openedSegment).isEqualTo(2L)
    }

    @Test
    fun `tapping the card header asks to toggle that trip`() {
        val recorded = show(state(trip()))

        compose.onNodeWithContentDescription(
            "Trip Harrison → Braddon, Canberra  ·  08:12 – 08:41  ·  29 min  ·  2 clips  ·  11 km",
        ).performClick()

        assertThat(recorded.toggled).isEqualTo(1L)
    }

    @Test
    fun `the track buttons are disabled when no track exists and say so`() {
        show(state(trip(trackFile = null)))

        compose.onNodeWithContentDescription("Open GPX track in a map app").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share GPX track").assertIsNotEnabled()
        compose.onNodeWithText("No GPX track was saved for this trip.").assertIsDisplayed()
    }

    @Test
    fun `the track buttons hand the track file over`() {
        val track = folder.newFile("RG_20260919-081204.gpx")
        val recorded = show(state(trip(trackFile = track)))

        compose.onNodeWithContentDescription("Open GPX track in a map app").assertIsEnabled().performClick()
        assertThat(recorded.opened).isEqualTo(track)

        compose.onNodeWithContentDescription("Share GPX track").assertIsEnabled().performClick()
        assertThat(recorded.shared).isEqualTo(track)
    }

    @Test
    fun `the trip menu can protect every clip at once`() {
        val recorded = show(state(trip()))

        compose.onNodeWithContentDescription("More actions for this trip").performClick()
        compose.onNodeWithText("Protect all 2 clips").assertIsDisplayed().performClick()

        assertThat(recorded.protectedTrip).isEqualTo(1L)
    }

    @Test
    fun `clips that no trip has claimed are still shown, under their own card`() {
        val loose = GalleryTrip(
            key = "loose-day",
            trip = null,
            title = "Clips not yet in a trip",
            meta = "1 clip  ·  grouped into trips on the next start",
            items = listOf(item(segment(9, 40, tripId = null), "08:52:04")),
            incidentCount = 0,
            protectedCount = 0,
            trackFile = null,
            sketch = emptyList(),
            expanded = true,
            isRecording = false,
            namesHint = null,
        )
        show(state(loose))

        compose.onNodeWithText("Clips not yet in a trip").assertIsDisplayed()
        compose.onNodeWithText("08:52:04").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open GPX track in a map app").assertDoesNotExist()
    }

    @Test
    fun `a hint explains a trip without place names`() {
        show(state(trip().copy(title = "Trip · 08:12 – 08:41", namesHint = "Place names appear once the offline map is installed.")))

        compose.onNodeWithText("Place names appear once the offline map is installed.").assertIsDisplayed()
    }

    @Test
    fun `an empty list explains where recordings come from`() {
        show(GalleryUiState())

        compose.onNodeWithText("No recordings yet").assertIsDisplayed()
    }
}
