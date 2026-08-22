package io.github.tunlezah.roadguard.ui.main

import android.app.Application
import androidx.camera.core.SurfaceRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.recording.RecordingUiState
import io.github.tunlezah.roadguard.settings.PreviewZoom
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.storage.StorageAssessment
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.weather.WeatherState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State and commands for the main driving screen.
 *
 * Everything here is a *view* of state owned elsewhere: the recorder owns recording, the service
 * owns the camera, the storage manager owns the budget. The view model exists to combine them into
 * one snapshot the screen can render without tearing -- so the UI can never show "recording" beside
 * a storage figure from thirty seconds ago -- and to forward user intents.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = RoadguardContainer.from(application)

    val settings: StateFlow<Settings> = container.settings

    val surfaceRequest: StateFlow<SurfaceRequest?> = container.recordingController.surfaceRequest

    val state: StateFlow<MainUiState> = combine(
        container.settings,
        container.recordingController.state,
        container.locationEngine.state,
        container.mapRepository.installState,
        container.weatherRepository.state,
    ) { settings, recording, location, map, weather ->
        MainUiState(
            settings = settings,
            recording = recording,
            location = location,
            mapInstall = map,
            weather = weather,
            storage = container.storageManager.assessment.value,
            thermalLevel = recording.thermalLevel,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = MainUiState(),
    )

    fun setPreviewZoom(zoom: PreviewZoom) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(previewZoom = zoom) }
    }

    fun setMapVisible(visible: Boolean) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(mapVisible = visible) }
    }

    fun setMicrophoneEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(microphoneEnabled = enabled) }
    }

    fun protectNow() = container.recordingController.protectNow()

    fun setPreviewSurfaceEnabled(enabled: Boolean) =
        container.recordingController.setPreviewEnabled(enabled)

    /** Re-centres the map on the vehicle and re-enables follow mode. */
    fun recentreMap() = viewModelScope.launch {
        container.settingsRepository.update { it.copy(mapFollowsVehicle = true) }
    }

    fun retryMapInstall() = container.mapRepository.install()

    fun pauseMapInstall() = container.mapRepository.pause()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return MainViewModel(application) as T
            }
        }
    }
}

/** One consistent snapshot for the main screen. */
data class MainUiState(
    val settings: Settings = Settings(),
    val recording: RecordingUiState = RecordingUiState(),
    val location: LocationState = LocationState(),
    val mapInstall: MapInstallState = MapInstallState.NotInstalled,
    val weather: WeatherState = WeatherState.Unavailable(
        io.github.tunlezah.roadguard.weather.WeatherUnavailableReason.Disabled,
    ),
    val storage: StorageAssessment? = null,
    val thermalLevel: ThermalLevel = ThermalLevel.Normal,
) {
    /** True when the map pane should be shown at all. */
    val showMap: Boolean get() = settings.mapVisible

    val hasStorageWarning: Boolean
        get() = storage?.state != null && storage.state != io.github.tunlezah.roadguard.storage.StorageState.Ok

    val hasThermalWarning: Boolean get() = thermalLevel != ThermalLevel.Normal
}
