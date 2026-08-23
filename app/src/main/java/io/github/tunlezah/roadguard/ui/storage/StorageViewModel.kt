package io.github.tunlezah.roadguard.ui.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.map.MapPackage
import io.github.tunlezah.roadguard.settings.LoopBudget
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.SettingsRepository
import io.github.tunlezah.roadguard.storage.CleanupOutcome
import io.github.tunlezah.roadguard.storage.StorageAssessment
import io.github.tunlezah.roadguard.storage.StorageVolumeOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State and commands for the storage screen.
 *
 * Storage figures come from two very different places, and keeping them apart is what makes this
 * screen trustworthy. The index sums (loop bytes, protected bytes, the protected clip list) are
 * *observed*, so they are live and never stale. The volume figures -- total, free, map directory
 * size, the quarantine directory, the per-volume `StatFs` -- are *measured*, which means walking
 * directories and hitting the filesystem, so they are taken on entry and on explicit demand
 * rather than continuously. A dashcam screen that re-walked the maps directory on every
 * recomposition would burn battery and thermal headroom the recorder needs.
 *
 * Nothing here estimates. Where a measurement does not exist yet -- most importantly the recorded
 * bytes per second, which needs at least one finished segment -- the state carries null and the
 * screen says so.
 */
class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val container = RoadguardContainer.from(application)
    private val segments = container.database.segments()
    private val storage = container.storageManager

    private val measured = MutableStateFlow(MeasuredStorage())

    val state: StateFlow<StorageUiState> = combine(
        container.settings,
        storage.assessment,
        segments.observeProtected(),
        segments.observeProtectedBytes(),
        measured,
    ) { settings, assessment, protectedSegments, protectedBytes, filesystem ->
        StorageUiState(
            settings = settings,
            assessment = assessment,
            protectedSegments = protectedSegments,
            protectedBytes = protectedBytes,
            mapBytes = filesystem.mapBytes,
            volumes = filesystem.volumes,
            quarantineFileCount = filesystem.quarantineFileCount,
            quarantineBytes = filesystem.quarantineBytes,
            isBusy = filesystem.isBusy,
            action = filesystem.action,
        )
    }.combine(container.mapRepository.installState) { snapshot, mapInstall ->
        snapshot.copy(
            mapInstall = mapInstall,
            mapPackages = container.mapRepository.packages,
            mapPackage = container.mapRepository.selectedPackage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = StorageUiState(),
    )

    init {
        // The map install state is read from disk, not held in memory, so the screen has to ask.
        container.mapRepository.refresh(container.settings.value.mapPackageId)
        refresh()
    }

    /** Re-measures the volume. Safe to call as often as the user likes; it is not automatic. */
    fun refresh() = viewModelScope.launch {
        measureAll(container.settings.value.loopBudgetBytes)
    }

    fun setLoopBudget(bytes: Long) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(loopBudgetBytes = bytes) }
        // The settings flow is fed by DataStore and lags this write by a hop, so the budget the
        // assessment is recomputed against is derived here rather than read back.
        measureAll(validatedBudget(bytes))
    }

    /**
     * Points future recordings at another volume.
     *
     * The layout is switched immediately so the refreshed assessment describes the volume the
     * recorder will actually use next, rather than the one it used a moment ago.
     */
    fun chooseVolume(volumeId: String?) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(storageVolumeId = volumeId) }
        val label = measured.value.volumes.firstOrNull { it.id == volumeId }?.label
        withContext(Dispatchers.IO) { storage.useVolume(volumeId) }
        measureAll(container.settings.value.loopBudgetBytes)
        post(StorageAction.VolumeChanged(label))
    }

    /**
     * Runs the loop cleanup now.
     *
     * The assessment is re-measured first: cleanup deletes exactly what the assessment says needs
     * deleting, so acting on a stale one could delete footage that is no longer over budget.
     */
    fun freeSpaceNow() = viewModelScope.launch {
        val budget = container.settings.value.loopBudgetBytes
        measured.update { it.copy(isBusy = true, action = null) }
        val assessment = runCatching { storage.refresh(budget) }.getOrNull()
        val outcome = assessment?.let { runCatching { storage.runCleanup(it) }.getOrNull() }
        runCatching { storage.refresh(budget) }
        remeasure()
        measured.update {
            it.copy(
                isBusy = false,
                action = when {
                    outcome == null -> StorageAction.MeasurementFailed
                    outcome.filesDeleted == 0 -> StorageAction.NothingToClean
                    else -> StorageAction.Cleaned(outcome)
                },
            )
        }
    }

    /** Returns one clip to the loop, at explicit user request. The file is not deleted. */
    fun unprotect(segmentId: Long) = viewModelScope.launch {
        container.protectionCoordinator.unprotect(segmentId)
        measureAll(container.settings.value.loopBudgetBytes)
        post(StorageAction.Unprotected)
    }

    /**
     * Deletes a protected clip's file and its index row.
     *
     * The row is removed only when the file has actually gone, matching
     * [io.github.tunlezah.roadguard.storage.StorageManager.runCleanup]: an index row for a file
     * still on disk is recoverable, whereas a file with no row is invisible footage taking space.
     */
    fun deleteProtectedSegment(segmentId: Long) = viewModelScope.launch {
        val entity = segments.byId(segmentId) ?: return@launch
        val deleted = withContext(Dispatchers.IO) {
            val file = storage.segmentFile(entity)
            val gone = !file.exists() || file.delete()
            if (gone) storage.removeProtectionSidecar(entity.fileName)
            gone
        }
        if (deleted) segments.deleteById(segmentId)
        measureAll(container.settings.value.loopBudgetBytes)
        post(
            if (deleted) {
                StorageAction.Deleted(entity.sizeBytes)
            } else {
                StorageAction.DeleteFailed
            },
        )
    }

    /** Called once the screen has shown the outcome of a command. */
    /** Starts or resumes the offline map download for the selected region. */
    fun installMap() {
        container.mapRepository.refresh(container.settings.value.mapPackageId)
        container.mapRepository.install()
    }

    fun pauseMapInstall() = container.mapRepository.pause()

    /**
     * Switches region, and remembers the choice.
     *
     * The previously installed archive is removed, because the alternative -- keeping several
     * hundred megabytes of a map the user has just replaced -- is exactly the kind of silent
     * storage consumption the rest of this screen exists to prevent.
     */
    fun selectMapPackage(pack: MapPackage) = viewModelScope.launch {
        val previous = container.mapRepository.selectedPackage
        if (previous != null && previous.id != pack.id) {
            container.mapRepository.uninstall()
        }
        if (!container.mapRepository.select(pack)) return@launch
        container.settingsRepository.update { it.copy(mapPackageId = pack.id) }
        remeasure()
    }

    fun removeMap() = viewModelScope.launch {
        container.mapRepository.uninstall()
        remeasure()
        post(StorageAction.Message("Offline map removed"))
    }

    fun clearAction() = measured.update { it.copy(action = null) }

    private suspend fun measureAll(requestedBudgetBytes: Long) {
        measured.update { it.copy(isBusy = true) }
        runCatching { storage.refresh(requestedBudgetBytes) }
        remeasure()
        measured.update { it.copy(isBusy = false) }
    }

    private suspend fun remeasure() {
        val fresh = withContext(Dispatchers.IO) {
            val quarantined = storage.layout.quarantine.listFiles()?.filter { it.isFile }.orEmpty()
            MeasuredStorage(
                mapBytes = storage.mapBytes(),
                volumes = storage.volumeOptions(),
                quarantineFileCount = quarantined.size,
                quarantineBytes = quarantined.sumOf { it.length() },
            )
        }
        measured.update { current -> fresh.copy(isBusy = current.isBusy, action = current.action) }
    }

    private fun post(action: StorageAction) = measured.update { it.copy(action = action) }

    /** Applies the repository's own clamping so the screen and the recorder agree on the budget. */
    private fun validatedBudget(bytes: Long): Long =
        SettingsRepository.validate(container.settings.value.copy(loopBudgetBytes = bytes)).loopBudgetBytes

    /** The filesystem figures, which are sampled rather than observed. */
    private data class MeasuredStorage(
        val mapBytes: Long = 0L,
        val volumes: List<StorageVolumeOption> = emptyList(),
        val quarantineFileCount: Int = 0,
        val quarantineBytes: Long = 0L,
        val isBusy: Boolean = false,
        val action: StorageAction? = null,
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return StorageViewModel(application) as T
            }
        }
    }
}

/**
 * One consistent snapshot of where the space has gone.
 *
 * Live index sums and sampled filesystem figures are combined here so the screen cannot render a
 * loop total from now beside a free-space figure from a minute ago -- which, on the one screen
 * whose job is to explain a full volume, would be worse than showing nothing.
 */
data class StorageUiState(
    val settings: Settings = Settings(),
    val assessment: StorageAssessment? = null,
    val protectedSegments: List<SegmentEntity> = emptyList(),
    val protectedBytes: Long = 0L,
    val mapInstall: MapInstallState = MapInstallState.NotInstalled,
    val mapBytes: Long = 0L,
    val mapPackages: List<MapPackage> = emptyList(),
    val mapPackage: MapPackage? = null,
    val volumes: List<StorageVolumeOption> = emptyList(),
    val quarantineFileCount: Int = 0,
    val quarantineBytes: Long = 0L,
    val isBusy: Boolean = false,
    val action: StorageAction? = null,
) {
    /** Recorded bytes per second, or null until at least one segment has been measured. */
    val measuredBytesPerSecond: Double?
        get() = assessment?.measuredBytesPerSecond?.takeIf { it > 0.0 }

    val protectedOverWarning: Boolean
        get() = settings.protectedWarningBytes > 0L && protectedBytes > settings.protectedWarningBytes

    /** The presets plus whatever is currently set, so a value from elsewhere stays selectable. */
    val budgetOptions: List<Long>
        get() = (LoopBudget.presets + settings.loopBudgetBytes).distinct().sorted()

    /** Space on the volume held by anything that is not Roadguard's and is not free. */
    val otherBytes: Long
        get() {
            val assessment = assessment ?: return 0L
            return (
                assessment.volumeTotalBytes - assessment.loopUsedBytes - protectedBytes -
                    mapBytes - assessment.freeBytes
                ).coerceAtLeast(0L)
        }

    /** How long [bytes] of footage would last at the measured rate, or null with no measurement. */
    fun secondsOfFootageFor(bytes: Long): Long? =
        measuredBytesPerSecond?.let { rate -> (bytes / rate).toLong() }
}

/**
 * The outcome of a command, for the screen's confirmation message.
 *
 * Modelled rather than pre-formatted so the wording and the byte formatting stay in the UI layer,
 * and so a test can assert that deleting a clip that could not be deleted reports the failure.
 */
sealed interface StorageAction {
    data class Cleaned(val outcome: CleanupOutcome) : StorageAction
    data object NothingToClean : StorageAction
    data class VolumeChanged(val label: String?) : StorageAction
    data object Unprotected : StorageAction
    data class Deleted(val bytes: Long) : StorageAction
    data object DeleteFailed : StorageAction
    data object MeasurementFailed : StorageAction

    /** A one-off confirmation with nothing further to say about it. */
    data class Message(val text: String) : StorageAction
}
