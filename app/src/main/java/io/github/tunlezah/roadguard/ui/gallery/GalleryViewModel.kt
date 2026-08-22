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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
) {
    val exists: Boolean get() = file.exists()
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
        filter,
        message,
    ) { allSegments, allEvents, activeFilter, activeMessage ->
        val eventsById = allEvents.associateBy { it.id }
        val items = allSegments
            .map { segment ->
                GalleryItem(
                    segment = segment,
                    file = container.storageManager.segmentFile(segment),
                    event = segment.eventId?.let { eventsById[it] },
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
    }.stateIn(
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
        val removed = !file.exists() || file.delete()
        if (removed) {
            segments.deleteById(segmentId)
            message.value = "Deleted"
        } else {
            message.value = "That file could not be deleted"
        }
    }

    private fun groupByDay(items: List<GalleryItem>): List<GalleryDay> =
        items.groupBy { dayLabel(it.segment.startedAtEpochMs) }
            .map { (label, dayItems) -> GalleryDay(label, dayItems) }

    companion object {
        /**
         * Date formatters are built per call rather than held in a field.
         *
         * A formatter that captures `Locale.getDefault()` once keeps formatting in the old locale
         * for the life of the process if the user changes their language, which on a long-lived
         * foreground app is a real possibility rather than a theoretical one.
         */
        fun dayLabel(epochMs: Long): String =
            SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()).format(Date(epochMs))

        fun timeLabel(epochMs: Long): String =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

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
