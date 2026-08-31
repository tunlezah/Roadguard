package io.github.tunlezah.roadguard.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.data.EventEntity
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.data.EventKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the recordings list is showing. */
enum class GalleryFilter(val label: String) {
    All("All"),
    Protected("Protected"),
    Events("Incidents"),
}

/** One row in the recordings list, with everything the row needs already resolved. */
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
) {
    val isProtected: Boolean get() = segment.isProtected
}

data class GalleryUiState(
    val filter: GalleryFilter = GalleryFilter.All,
    val days: List<GalleryDay> = emptyList(),
    val totalCount: Int = 0,
    val message: String? = null,
)

data class GalleryDay(val label: String, val items: List<GalleryItem>)

/**
 * The recordings browser.
 *
 * Two rules the UI depends on:
 *
 *  * a **protected** segment cannot be deleted while it is protected. That is surfaced as a state
 *    ("unprotect it first") rather than a silent refusal, because the whole value of protection is
 *    that it is not easy to undo by accident; and
 *  * a **missing or unplayable** file is shown as such rather than left to fail when tapped. An
 *    interrupted session can leave a quarantined segment behind, and that is worth seeing.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = RoadguardContainer.from(application)
    private val segments = container.database.segments()
    private val events = container.database.events()

    private val filter = MutableStateFlow(GalleryFilter.All)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<GalleryUiState> = combine(
        segments.observeAll(),
        events.observeAll(),
        // Re-resolve every path when the storage volume changes: rows mapped before the
        // persisted volume was applied at start-up would otherwise stay "missing" forever.
        container.storageManager.layoutGeneration,
        filter,
        message,
    ) { allSegments, allEvents, _, activeFilter, activeMessage ->
        val eventsById = allEvents.associateBy { it.id }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val items = allSegments
            .map { segment ->
                val file = container.storageManager.segmentFile(segment)
                GalleryItem(
                    segment = segment,
                    file = file,
                    event = segment.eventId?.let { eventsById[it] },
                    exists = file.exists(),
                    timeLabel = timeFormat.format(Date(segment.startedAtEpochMs)),
                )
            }
            .filter { item ->
                when (activeFilter) {
                    GalleryFilter.All -> true
                    GalleryFilter.Protected -> item.isProtected
                    GalleryFilter.Events -> item.event != null
                }
            }
        GalleryUiState(
            filter = activeFilter,
            days = groupByDay(items),
            totalCount = allSegments.size,
            message = activeMessage,
        )
    }
        // Thousands of rows mean thousands of File.exists() calls per rebuild; that work, and
        // the grouping behind it, must never run on the main thread.
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = GalleryUiState(),
        )

    fun setFilter(value: GalleryFilter) {
        filter.value = value
    }

    fun clearMessage() {
        message.value = null
    }

    fun itemFor(segmentId: Long): GalleryItem? =
        state.value.days.flatMap { it.items }.firstOrNull { it.segment.id == segmentId }

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
        val file = container.storageManager.segmentFile(segment)
        val removed = withContext(Dispatchers.IO) { !file.exists() || file.delete() }
        if (removed) {
            segments.deleteById(segmentId)
            message.value = "Deleted"
        } else {
            message.value = "That file could not be deleted"
        }
    }

    /**
     * Date formatters are built once per list rebuild rather than held in a field or created
     * per item. Per field, a formatter that captures `Locale.getDefault()` once would keep
     * formatting in the old locale for the life of the process if the user changes their
     * language; per item, a list of thousands of segments would allocate thousands of them
     * on every update.
     */
    private fun groupByDay(items: List<GalleryItem>): List<GalleryDay> {
        val dayFormat = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())
        return items.groupBy { dayFormat.format(Date(it.segment.startedAtEpochMs)) }
            .map { (label, dayItems) -> GalleryDay(label, dayItems) }
    }

    companion object {
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
