package io.github.tunlezah.roadguard.ui.gallery

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.data.EventEntity
import io.github.tunlezah.roadguard.data.EventKind
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.data.TripEntity
import io.github.tunlezah.roadguard.location.GpxWriter
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.trip.RouteSketch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.Date
import java.util.Locale

/** What the recordings list is showing. */
enum class GalleryFilter(val label: String) {
    All("All"),
    Protected("Protected"),
    Events("Incidents"),
}

/** One clip in the recordings list, with everything the row needs already resolved. */
data class GalleryItem(
    val segment: SegmentEntity,
    val file: File,
    val event: EventEntity?,
    /**
     * Resolved once, off the main thread, when the list is built. A getter here would stat the
     * disk several times per row per frame, which with thousands of rows is what an ANR is
     * made of.
     */
    val exists: Boolean,
    /** Preformatted start time, so a row never builds a date formatter during composition. */
    val timeLabel: String,
    /**
     * The recorder is writing this clip right now. Its file has no index yet, so it cannot be
     * played, and it must not be described as damaged: it is simply not finished.
     */
    val inProgress: Boolean = false,
) {
    val isProtected: Boolean get() = segment.isProtected
}

/**
 * One trip's card in the recordings list.
 *
 * @param trip the trip row, or null for the card that gathers clips no trip claims yet.
 * @param items the trip's clips, oldest first: the order they would play in.
 * @param trackFile the GPX track, when one exists on disk.
 * @param sketch the route's shape in the unit square, for the thumbnail; empty when there is no
 *   track to draw from.
 * @param namesHint why the trip has no place names, when it has none and the reason is worth a
 *   line; null while a lookup is pending or once names exist.
 */
data class GalleryTrip(
    val key: String,
    val trip: TripEntity?,
    val title: String,
    val meta: String,
    val items: List<GalleryItem>,
    val incidentCount: Int,
    val protectedCount: Int,
    val trackFile: File?,
    val sketch: List<Pair<Float, Float>>,
    val expanded: Boolean,
    val isRecording: Boolean,
    val namesHint: String?,
) {
    val tripId: Long? get() = trip?.id
    val unprotectedCount: Int get() = items.count { !it.isProtected }
}

data class GalleryDay(val label: String, val trips: List<GalleryTrip>)

data class GalleryUiState(
    val filter: GalleryFilter = GalleryFilter.All,
    val days: List<GalleryDay> = emptyList(),
    val totalCount: Int = 0,
    val message: String? = null,
    /**
     * False until the index has been read once. Until then an empty [days] means "not read yet",
     * not "no recordings": telling someone with a folder full of footage that there is nothing
     * there, while the list is still being built, is how the recordings looked lost.
     */
    val loaded: Boolean = true,
) {
    /** Every card in display order, for the player's neighbours lookup. */
    val allTrips: List<GalleryTrip> get() = days.flatMap { it.trips }
}

/** Where a clip sits in its trip, for the player's title and its previous/next controls. */
data class ClipPosition(
    val trip: GalleryTrip,
    val index: Int,
    val previousSegmentId: Long?,
    val nextSegmentId: Long?,
) {
    val count: Int get() = trip.items.size
}

/**
 * The recordings browser.
 *
 * Clips are shown inside the trip they belong to, because that is how somebody looks for footage:
 * "the drive to Braddon on Saturday", not "the 08:18 file". Trips within a day are newest first,
 * like the days themselves, and the clips inside a trip run oldest first, which is the order they
 * play in. A clip that no trip has claimed yet (recorded before trips existed and not yet grouped
 * by start-up reconciliation) still appears, under a card of its own, rather than being hidden.
 *
 * Two rules the UI depends on:
 *
 *  * a **protected** segment cannot be deleted while it is protected. That is surfaced as a state
 *    ("unprotect it first") rather than a silent refusal, because the whole value of protection is
 *    that it is not easy to undo by accident; and
 *  * a **missing or unplayable** file is shown as such rather than left to fail when tapped. An
 *    interrupted session can leave a quarantined segment behind, and that is worth seeing.
 *
 * Place names are filled in lazily: a trip recorded before the offline map was installed is named
 * the first time the list is built with a map present.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = RoadguardContainer.from(application)
    private val segments = container.database.segments()
    private val events = container.database.events()
    private val trips = container.database.trips()
    private val tripRepository = container.tripRepository
    private val storage = container.storageManager

    private val filter = MutableStateFlow(GalleryFilter.All)
    private val message = MutableStateFlow<String?>(null)
    private val expandedTrips = MutableStateFlow<Set<Long>>(emptySet())

    /** Bumped when route thumbnails computed in the background are ready to be shown. */
    private val sketchGeneration = MutableStateFlow(0)
    private val pendingSketches: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val sketchMutex = Mutex()

    /** Trips whose names are being resolved right now, so a rebuild does not start a second lookup. */
    private val resolving: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    private data class Index(val segments: List<SegmentEntity>, val events: List<EventEntity>, val trips: List<TripEntity>)
    private data class Chrome(val message: String?, val expanded: Set<Long>, val filter: GalleryFilter, val sketches: Int)

    val state: StateFlow<GalleryUiState> = combine(
        combine(segments.observeAll(), events.observeAll(), trips.observeAll()) { s, e, t -> Index(s, e, t) },
        // Re-resolve every path when the storage volume changes: rows mapped before the
        // persisted volume was applied at start-up would otherwise stay "missing" forever.
        container.storageManager.layoutGeneration,
        container.mapRepository.installState,
        combine(message, expandedTrips, filter, sketchGeneration) { m, x, f, g -> Chrome(m, x, f, g) },
    ) { index, _, mapInstall, chrome ->
        build(index, mapInstall is MapInstallState.Installed, chrome)
    }
        // Thousands of rows mean thousands of File.exists() calls per rebuild; that work, and
        // the grouping behind it, must never run on the main thread.
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = GalleryUiState(loaded = false),
        )

    init {
        // Name trips that were recorded without a map, once a map exists. One attempt per trip per
        // process; the repository marks the trip resolved whatever the lookup finds.
        viewModelScope.launch {
            combine(trips.observeAll(), container.mapRepository.installState) { all, install ->
                if (install is MapInstallState.Installed) {
                    all.filter { !it.namesResolved && !it.isRecording && (it.startLatitude != null || it.endLatitude != null) }
                } else {
                    emptyList()
                }
            }.collect { pending ->
                for (trip in pending) {
                    if (!resolving.add(trip.id)) continue
                    launch(Dispatchers.IO) {
                        runCatching { tripRepository.resolveNames(trip) }
                        resolving.remove(trip.id)
                    }
                }
            }
        }
    }

    private fun build(index: Index, lookupAvailable: Boolean, chrome: Chrome): GalleryUiState {
        val eventsById = index.events.associateBy { it.id }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFormat = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())

        val items = index.segments
            .map { segment ->
                val file = storage.segmentFile(segment)
                GalleryItem(
                    segment = segment,
                    file = file,
                    event = segment.eventId?.let { eventsById[it] },
                    exists = file.exists(),
                    timeLabel = timeFormat.format(Date(segment.startedAtEpochMs)),
                    inProgress = !segment.isComplete && storage.isBeingWritten(segment.fileName),
                )
            }
            .filter { item ->
                when (chrome.filter) {
                    GalleryFilter.All -> true
                    GalleryFilter.Protected -> item.isProtected
                    GalleryFilter.Events -> item.event != null
                }
            }
        val itemsByTrip = items.groupBy { it.segment.tripId }
        val knownTripIds = index.trips.mapTo(HashSet()) { it.id }

        // A card per trip that still has clips under the current filter, newest trip first.
        val cards = index.trips.mapNotNull { trip ->
            val tripItems = itemsByTrip[trip.id]?.sortedBy { it.segment.startedAtEpochMs } ?: return@mapNotNull null
            card(trip, tripItems, clockFormat, chrome.expanded, lookupAvailable)
        }
        val dayOf: (Long) -> String = { epochMs -> dayFormat.format(Date(epochMs)) }
        val cardsByDay = cards.groupBy { dayOf(it.trip!!.startedAtEpochMs) }
        // A clip with no trip -- or one whose trip row has gone missing -- goes under a card of its
        // own rather than being dropped. Dropping a clip whose tripId points at a trip that no
        // longer exists is exactly how a recordings folder full of footage shows as an empty list,
        // and it breaks the promise in SegmentEntity.tripId that such a clip is still shown.
        val looseItems = items.filter { isLoose(it.segment.tripId, knownTripIds) }
        val looseByDay = looseItems
            .groupBy { dayOf(it.segment.startedAtEpochMs) }
            .mapValues { (label, loose) -> looseCard(label, loose.sortedBy { it.segment.startedAtEpochMs }) }

        val dayOrder = (cards.map { it.trip!!.startedAtEpochMs } + looseItems.map { it.segment.startedAtEpochMs })
            .sortedDescending()
            .map(dayOf)
            .distinct()
        val days = dayOrder.map { label ->
            GalleryDay(label, cardsByDay[label].orEmpty() + listOfNotNull(looseByDay[label]))
        }

        return GalleryUiState(
            filter = chrome.filter,
            days = days,
            totalCount = index.segments.size,
            message = chrome.message,
        ).also { requestSketches() }
    }

    private fun card(
        trip: TripEntity,
        items: List<GalleryItem>,
        clock: SimpleDateFormat,
        expanded: Set<Long>,
        lookupAvailable: Boolean,
    ): GalleryTrip {
        val range = "${clock.format(Date(trip.startedAtEpochMs))} – ${clock.format(Date(trip.endedAtEpochMs))}"
        val label = tripRepository.labelFor(trip, fallbackTitle = "Trip · $range")
        val trackFile = trip.trackFileName?.let { storage.trackFile(it) }?.takeIf { it.isFile }
        val meta = listOfNotNull(
            label.subtitle,
            range.takeIf { !label.title.contains(range) },
            GalleryFormat.tripDuration(trip.durationMs),
            GalleryFormat.clipCount(items.size),
            GalleryFormat.distance(trip.distanceMetres),
        ).joinToString("  ·  ")
        return GalleryTrip(
            key = "trip-${trip.id}",
            trip = trip,
            title = if (trip.isRecording && label.subtitle == null && label.title.startsWith("Trip")) "Recording now" else label.title,
            meta = meta,
            items = items,
            incidentCount = items.count { it.event != null },
            protectedCount = items.count { it.isProtected },
            trackFile = trackFile,
            sketch = trackFile?.let { sketchFor(it) } ?: emptyList(),
            expanded = trip.id in expanded,
            isRecording = trip.isRecording,
            namesHint = when {
                trip.namesResolved -> null
                trip.isRecording -> null
                trip.startLatitude == null && trip.endLatitude == null -> "No location was recorded, so this trip is named by its time."
                lookupAvailable -> null
                else -> "Place names appear once the offline map is installed."
            },
        )
    }

    private fun looseCard(dayLabel: String, items: List<GalleryItem>): GalleryTrip = GalleryTrip(
        key = "loose-$dayLabel",
        trip = null,
        title = "Clips not yet in a trip",
        meta = GalleryFormat.clipCount(items.size) + "  ·  grouped into trips on the next start",
        items = items,
        incidentCount = items.count { it.event != null },
        protectedCount = items.count { it.isProtected },
        trackFile = null,
        sketch = emptyList(),
        expanded = true,
        isRecording = false,
        namesHint = null,
    )

    /**
     * The route thumbnail for [file] as far as it is known right now. Never reads the track.
     *
     * Reading every trip's GPX file used to happen inside the list build, so the first build after
     * the screen opened read every track ever recorded -- megabytes per long drive -- before a
     * single clip was shown, and the list said "No recordings yet" the whole time. That grew with
     * every drive. Now a missing or out-of-date thumbnail is queued for [requestSketches], the list
     * is shown straight away, and the thumbnail appears when it is ready.
     */
    private fun sketchFor(file: File): List<Pair<Float, Float>> {
        val cached = sketchCache[file.name]
        if (cached == null || SketchEntry.isStale(cached, file.length(), SystemClock.elapsedRealtime())) {
            pendingSketches += file.path
        }
        return cached?.points ?: emptyList()
    }

    /**
     * Computes the thumbnails [sketchFor] queued, one pass at a time, revealing them in waves.
     *
     * The pass used to bump the list only after the *last* track had been read, so on a long history
     * every route thumbnail waited on the slowest one and the list showed placeholders until the
     * whole queue drained. It now rebuilds every [SKETCH_BUMP_BATCH] tracks, so the first routes
     * appear promptly and the rest fill in behind them -- the minimum shown first, the rest in slow
     * time. The player does not wait on this queue at all; it reads its own trip's track directly
     * through [routeSketch].
     */
    private fun requestSketches() {
        if (pendingSketches.isEmpty() || sketchMutex.isLocked) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!sketchMutex.tryLock()) return@launch
            try {
                var sinceBump = 0
                while (true) {
                    val path = synchronized(pendingSketches) {
                        pendingSketches.firstOrNull()?.also { pendingSketches.remove(it) }
                    } ?: break
                    val file = File(path)
                    cacheSketch(file, file.length(), computeSketch(file))
                    if (++sinceBump >= SKETCH_BUMP_BATCH) {
                        sketchGeneration.update { it + 1 }
                        sinceBump = 0
                    }
                }
                // Show whatever the last, partial wave computed. Nothing computed means no bump,
                // so an empty queue drained by a racing pass does not churn the list.
                if (sinceBump > 0) sketchGeneration.update { it + 1 }
            } finally {
                sketchMutex.unlock()
            }
        }
    }

    /** Reads one track (streaming) and turns it into route points. IO-bound; callers dispatch it. */
    private fun computeSketch(file: File): List<Pair<Float, Float>> =
        runCatching { RouteSketch.normalise(GpxWriter.readPoints(file, SKETCH_POINTS)) }.getOrDefault(emptyList())

    private fun cacheSketch(file: File, sizeBytes: Long, points: List<Pair<Float, Float>>) {
        if (sketchCache.size > SKETCH_CACHE_LIMIT) sketchCache.clear()
        sketchCache[file.name] = SketchEntry(sizeBytes, SystemClock.elapsedRealtime(), points)
    }

    /**
     * The route for one trip's track, for the player, which needs exactly one and should not wait
     * on the gallery's whole-list queue.
     *
     * Returns the shared cache when it already holds a fresh sketch -- so returning to a clip whose
     * route the list has drawn is instant -- and otherwise reads just this one track off the disk
     * and caches it for the list to reuse. This is the "load the minimum as required" path.
     */
    suspend fun routeSketch(trackFile: File): List<Pair<Float, Float>> {
        sketchCache[trackFile.name]?.let { cached ->
            val size = withContext(Dispatchers.IO) { trackFile.length() }
            if (!SketchEntry.isStale(cached, size, SystemClock.elapsedRealtime())) return cached.points
        }
        return withContext(Dispatchers.IO) {
            val size = trackFile.length()
            computeSketch(trackFile).also { cacheSketch(trackFile, size, it) }
        }
    }

    fun setFilter(value: GalleryFilter) {
        filter.value = value
    }

    fun toggleTrip(tripId: Long) {
        expandedTrips.update { current -> if (tripId in current) current - tripId else current + tripId }
    }

    fun clearMessage() {
        message.value = null
    }

    fun itemFor(segmentId: Long): GalleryItem? =
        state.value.allTrips.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.segment.id == segmentId }

    /** Where [segmentId] sits in its trip, or null when it is not in the list. */
    fun positionOf(segmentId: Long): ClipPosition? {
        val trip = state.value.allTrips.firstOrNull { card -> card.items.any { it.segment.id == segmentId } } ?: return null
        val index = trip.items.indexOfFirst { it.segment.id == segmentId }
        return ClipPosition(
            trip = trip,
            index = index,
            previousSegmentId = trip.items.getOrNull(index - 1)?.segment?.id,
            nextSegmentId = trip.items.getOrNull(index + 1)?.segment?.id,
        )
    }

    fun protect(segmentId: Long) = viewModelScope.launch {
        val segment = segments.byId(segmentId) ?: return@launch
        container.protectionCoordinator.protect(
            kind = EventKind.Manual,
            atEpochMs = segment.startedAtEpochMs + segment.durationMs / 2,
            preSeconds = 0,
            postSeconds = 0,
            confidence = 1f,
            detection = null,
            inProgress = null,
        )
        message.value = "Protected. The loop will not delete it."
    }

    fun unprotect(segmentId: Long) = viewModelScope.launch {
        container.protectionCoordinator.unprotect(segmentId)
        message.value = "No longer protected. The loop may delete it when space is needed."
    }

    /** Protects every clip in a trip at once, without inventing an incident for each. */
    fun protectTrip(tripId: Long) = viewModelScope.launch {
        val ids = segments.forTrip(tripId).map { it.id }
        val count = container.protectionCoordinator.protectSegments(ids, "trip $tripId protected by user")
        message.value = when (count) {
            0 -> "Every clip in this trip was already protected."
            1 -> "Protected 1 clip. The loop will not delete it."
            else -> "Protected $count clips. The loop will not delete them."
        }
    }

    /**
     * Deletes a segment and its file.
     *
     * Refuses while the segment is protected: unprotecting is a separate, deliberate action.
     */
    fun delete(segmentId: Long) = viewModelScope.launch {
        val segment = segments.byId(segmentId) ?: return@launch
        if (segment.isProtected) {
            message.value = "This clip is protected. Unprotect it first if you really want it gone."
            return@launch
        }
        val file = storage.segmentFile(segment)
        val removed = withContext(Dispatchers.IO) { !file.exists() || file.delete() }
        if (removed) {
            segments.deleteById(segmentId)
            segment.tripId?.let { storage.pruneEmptyTrips(listOf(it)) }
            // Recompute the storage assessment now, so the Storage screen and Diagnostics do not
            // keep showing a loop total that still counts the clip just deleted.
            runCatching { storage.refresh(container.settings.value.loopBudgetBytes) }
            message.value = "Deleted"
        } else {
            message.value = "That file could not be deleted"
        }
    }

    /**
     * Deletes every unprotected clip in a trip. Protected clips stay, and so does the trip and its
     * track while any clip remains; once the last clip goes the track goes with it.
     */
    fun deleteUnprotected(tripId: Long) = viewModelScope.launch {
        val candidates = segments.forTrip(tripId).filter { !it.isProtected }
        var deleted = 0
        var failed = 0
        withContext(Dispatchers.IO) {
            for (segment in candidates) {
                val file = storage.segmentFile(segment)
                if (!file.exists() || file.delete()) {
                    segments.deleteById(segment.id)
                    deleted++
                } else {
                    failed++
                }
            }
            storage.pruneEmptyTrips(listOf(tripId))
        }
        // Keep the storage figures honest after a bulk delete, as the single-clip path does.
        runCatching { storage.refresh(container.settings.value.loopBudgetBytes) }
        message.value = when {
            failed > 0 -> "Deleted $deleted clip(s); $failed could not be deleted"
            deleted == 0 -> "Nothing to delete: every clip in this trip is protected"
            deleted == 1 -> "Deleted 1 clip"
            else -> "Deleted $deleted clips"
        }
    }

    fun post(text: String) {
        message.value = text
    }

    /** A route thumbnail, with the track size it was drawn from and when. */
    internal data class SketchEntry(val sizeBytes: Long, val computedAtElapsedMs: Long, val points: List<Pair<Float, Float>>) {
        companion object {
            /**
             * Whether a thumbnail should be redrawn. Only when the track has changed, and for a
             * track still being written -- whose size changes with every fix -- no more often than
             * [SKETCH_REFRESH_MS], so recording does not cause a track read on every list rebuild.
             */
            fun isStale(entry: SketchEntry, currentSizeBytes: Long, nowElapsedMs: Long): Boolean =
                entry.sizeBytes != currentSizeBytes && nowElapsedMs - entry.computedAtElapsedMs >= SKETCH_REFRESH_MS
        }
    }

    companion object {
        /** Points in a route thumbnail. Enough for the shape, few enough to draw per frame. */
        const val SKETCH_POINTS = 48
        private const val SKETCH_CACHE_LIMIT = 2_000

        /** How many tracks a background pass reads before it reveals that wave of thumbnails. */
        const val SKETCH_BUMP_BATCH = 6

        /** Shortest interval between redraws of a thumbnail whose track is still growing. */
        const val SKETCH_REFRESH_MS = 60_000L

        /**
         * Route thumbnails by track file name, shared by every gallery and player screen for the
         * life of the process, so opening the list again does not read every track again.
         */
        private val sketchCache = ConcurrentHashMap<String, SketchEntry>()

        /**
         * Whether a clip belongs under the "not in a trip" card rather than a trip's card.
         *
         * True for a clip with no trip, and -- the case that matters -- for one whose [tripId]
         * points at a trip row that no longer exists. Such a clip must still be listed: footage on
         * disk is never hidden because its trip label went missing. Pure, so this is unit tested.
         */
        fun isLoose(tripId: Long?, knownTripIds: Set<Long>): Boolean =
            tripId == null || tripId !in knownTripIds

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return GalleryViewModel(application) as T
            }
        }
    }
}
