package io.github.tunlezah.roadguard.ui.firstrun

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.map.MapPackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The steps of first-run setup, in order. */
enum class SetupStep { Welcome, Permissions, Location, Map, Ready }

data class FirstRunUiState(
    val step: SetupStep = SetupStep.Welcome,
    val cameraGranted: Boolean = false,
    val locationGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val microphoneRequested: Boolean = false,
    val microphoneGranted: Boolean = false,
    val location: LocationState = LocationState(),
    val mapInstall: MapInstallState = MapInstallState.NotInstalled,
    val mapPackage: MapPackage? = null,
    val mapPackages: List<MapPackage> = emptyList(),
    val autoStartRecording: Boolean = true,
    val startupDelaySeconds: Int = 3,
) {
    val canLeavePermissions: Boolean get() = cameraGranted
    val hasFix: Boolean get() = location.quality.hasFix
    val mapInstalled: Boolean get() = mapInstall is MapInstallState.Installed
}

/**
 * Drives first-run setup.
 *
 * The order is deliberate and is the order things can actually be done in: explain, then ask for
 * permissions, then start the GNSS receiver (which takes time and can be doing so while the user
 * reads), then install the map (which needs a network the user may not have), then finish.
 *
 * Microphone is not part of the flow. It is an explicit opt-in with its own consequence stated,
 * because a dashcam that quietly records cabin conversation because the user tapped "allow all" on
 * setup day is not acceptable.
 */
class FirstRunViewModel(application: Application) : AndroidViewModel(application) {

    private val container = RoadguardContainer.from(application)
    private val step = MutableStateFlow(SetupStep.Welcome)
    private val permissionTick = MutableStateFlow(0)
    private val microphoneRequested = MutableStateFlow(false)

    val state: StateFlow<FirstRunUiState> = combine(
        step,
        permissionTick,
        container.locationEngine.state,
        container.mapRepository.installState,
        container.settings,
    ) { currentStep, _, location, mapInstall, settings ->
        FirstRunUiState(
            step = currentStep,
            cameraGranted = granted(Manifest.permission.CAMERA),
            locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS),
            microphoneRequested = microphoneRequested.value,
            microphoneGranted = granted(Manifest.permission.RECORD_AUDIO),
            location = location,
            mapInstall = mapInstall,
            mapPackage = container.mapRepository.selectedPackage,
            mapPackages = container.mapRepository.packages,
            autoStartRecording = settings.autoStartRecording,
            startupDelaySeconds = settings.startupDelaySeconds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FirstRunUiState(),
    )

    /** The permissions Roadguard asks for during setup. Microphone is deliberately absent. */
    val corePermissions: Array<String> = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        // No API-level guard: minSdk is 34, so POST_NOTIFICATIONS is always a runtime permission.
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun next() {
        step.value = when (step.value) {
            SetupStep.Welcome -> SetupStep.Permissions
            SetupStep.Permissions -> SetupStep.Location.also { startLocation() }
            SetupStep.Location -> SetupStep.Map.also { installMap() }
            SetupStep.Map -> SetupStep.Ready
            SetupStep.Ready -> SetupStep.Ready
        }
    }

    fun back() {
        step.value = when (step.value) {
            SetupStep.Welcome, SetupStep.Permissions -> SetupStep.Welcome
            SetupStep.Location -> SetupStep.Permissions
            SetupStep.Map -> SetupStep.Location
            SetupStep.Ready -> SetupStep.Map
        }
    }

    /** Re-reads permission state; called after a permission dialog closes. */
    fun onPermissionResult() {
        permissionTick.value += 1
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) startLocation()
    }

    fun onMicrophoneRequested() {
        microphoneRequested.value = true
        permissionTick.value += 1
    }

    fun setMicrophoneEnabled(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(microphoneEnabled = enabled) }
    }

    fun setAutoStart(enabled: Boolean) = viewModelScope.launch {
        container.settingsRepository.update { it.copy(autoStartRecording = enabled) }
    }

    fun startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return
        }
        container.locationEngine.start()
    }

    /**
     * Picks the region to install, and remembers it.
     *
     * The choice is persisted before the download starts, so a user who selects their state and
     * then loses the app mid-download does not come back to the default region.
     */
    fun selectMapPackage(pack: MapPackage) {
        if (!container.mapRepository.select(pack)) return
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(mapPackageId = pack.id) }
        }
    }

    fun installMap() {
        container.mapRepository.refresh(container.settings.value.mapPackageId)
        container.mapRepository.install()
    }

    fun pauseMapInstall() = container.mapRepository.pause()

    fun skipMap() {
        step.value = SetupStep.Ready
    }

    /** Marks setup done. The caller starts recording, because only a visible activity may. */
    fun complete() = viewModelScope.launch {
        container.settingsRepository.update {
            it.copy(setupComplete = true, acceptedRecordingDisclaimer = true)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return FirstRunViewModel(application) as T
            }
        }
    }
}
