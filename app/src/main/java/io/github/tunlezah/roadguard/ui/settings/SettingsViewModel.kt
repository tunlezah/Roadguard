package io.github.tunlezah.roadguard.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.tunlezah.roadguard.capability.CameraCapability
import io.github.tunlezah.roadguard.capability.DeviceCapabilities
import io.github.tunlezah.roadguard.capability.DeviceTier
import io.github.tunlezah.roadguard.capability.DeviceTierAssessment
import io.github.tunlezah.roadguard.capability.DeviceTierScorer
import io.github.tunlezah.roadguard.capability.RecordingProfile
import io.github.tunlezah.roadguard.capability.RecordingProfileSelector
import io.github.tunlezah.roadguard.core.RoadguardContainer
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.settings.CameraFacing
import io.github.tunlezah.roadguard.settings.FrameRateSetting
import io.github.tunlezah.roadguard.settings.QualitySetting
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.storage.StorageVolumeOption
import io.github.tunlezah.roadguard.thermal.ThermalPlan
import io.github.tunlezah.roadguard.thermal.ThermalPolicy
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.weather.WeatherUnavailableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State and commands for the Settings screen.
 *
 * Like [io.github.tunlezah.roadguard.ui.main.MainViewModel] this owns nothing: settings live in
 * the repository, capability facts live in the recorder, volumes live in the storage manager. Its
 * one real job is to answer the question every row on the screen has to ask -- *may the user
 * change this, and if not, why not* -- from a single consistent snapshot, so the screen cannot
 * offer dual camera against a stale capability report.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = RoadguardContainer.from(application)

    /**
     * Capability facts probed by this screen because the recorder had none yet.
     *
     * The recorder probes when it starts, so on a device that has recorded even once these stay
     * null and the recorder's own values are used. When they are needed, no recording is in
     * progress by definition -- which is the condition
     * [io.github.tunlezah.roadguard.capability.PerformanceProbe] requires.
     */
    private val probedCapabilities = MutableStateFlow<DeviceCapabilities?>(null)
    private val probedTier = MutableStateFlow<DeviceTierAssessment?>(null)

    private val storageVolumes = MutableStateFlow<List<StorageVolumeOption>>(emptyList())

    val settings: StateFlow<Settings> = container.settings

    private val device: Flow<DeviceSnapshot> = combine(
        combine(container.recordingController.capabilities, probedCapabilities) { live, probed -> live ?: probed },
        combine(container.recordingController.tier, probedTier) { live, probed -> live ?: probed },
        container.recordingController.state,
        container.recordingController.thermalPlan,
    ) { capabilities, tier, recording, thermalPlan ->
        DeviceSnapshot(capabilities, tier, recording.profile, thermalPlan)
    }

    val state: StateFlow<SettingsUiState> = combine(
        container.settings,
        device,
        container.mapRepository.installState,
        storageVolumes,
    ) { settings, device, mapInstall, volumes ->
        val liveProfile = device.profile
        SettingsUiState(
            settings = settings,
            capabilities = device.capabilities,
            tier = device.tier,
            profile = liveProfile ?: predictProfile(device, settings),
            profileIsPredicted = liveProfile == null,
            mapInstall = mapInstall,
            storageVolumes = volumes,
            weatherSupported = container.weatherRepository.isSupported,
            weatherSourceName = container.weatherRepository.sourceName,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(),
    )

    init {
        viewModelScope.launch { loadStorageVolumes() }
        viewModelScope.launch { probeIfRecorderHasNot() }
    }

    /**
     * The single write path.
     *
     * Everything goes through [io.github.tunlezah.roadguard.settings.SettingsRepository.update] so
     * that validation and clamping happen once, in the place that persists the value, rather than
     * being re-implemented per row.
     */
    fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }

    /** Re-reads volume sizes, which change as recordings accumulate or a card is swapped. */
    fun refreshStorageVolumes() {
        viewModelScope.launch { loadStorageVolumes() }
    }

    private suspend fun loadStorageVolumes() {
        val options = withContext(Dispatchers.IO) {
            runCatching { container.storageManager.volumeOptions() }.getOrDefault(emptyList())
        }
        storageVolumes.value = options
    }

    private suspend fun probeIfRecorderHasNot() {
        if (container.recordingController.capabilities.value != null) return
        val capabilities = runCatching { container.capabilityProbe.probe() }.getOrNull() ?: return
        probedCapabilities.value = capabilities
        probedTier.value = DeviceTierScorer.score(capabilities)
    }

    /**
     * What the recorder *would* choose right now.
     *
     * Shown, clearly labelled as a prediction, when nothing has been recorded yet. It uses the
     * same pure selector the recorder does, so "Why Auto chose this" is useful the first time a
     * user opens Settings rather than only after a drive.
     */
    private fun predictProfile(device: DeviceSnapshot, settings: Settings): RecordingProfile? {
        val capabilities = device.capabilities ?: return null
        val tier = device.tier ?: return null
        return runCatching {
            RecordingProfileSelector.select(capabilities, tier, settings, device.thermalPlan)
        }.getOrNull()
    }

    private data class DeviceSnapshot(
        val capabilities: DeviceCapabilities?,
        val tier: DeviceTierAssessment?,
        val profile: RecordingProfile?,
        val thermalPlan: ThermalPlan,
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return SettingsViewModel(application) as T
            }
        }
    }
}

/**
 * Whether a setting may be changed, and what to tell the user either way.
 *
 * A reason on an [available] setting is an advisory -- 2160p works, and it will cost heat. A
 * reason on an unavailable one is the explanation shown in place of the setting silently doing
 * nothing, which is the failure mode this type exists to prevent.
 */
data class SettingAvailability(val available: Boolean, val reason: String? = null) {
    companion object {
        val Available = SettingAvailability(available = true)
        fun unavailable(reason: String): SettingAvailability = SettingAvailability(false, reason)
        fun advisory(reason: String): SettingAvailability = SettingAvailability(true, reason)
    }
}

/**
 * One consistent snapshot for the Settings screen, including every "you cannot have this" verdict.
 *
 * The verdicts are computed here rather than in the composables so they are pure functions of
 * probed capability and can be reasoned about (and, if it ever matters, tested) without a
 * composition.
 */
data class SettingsUiState(
    val settings: Settings = Settings(),
    val capabilities: DeviceCapabilities? = null,
    val tier: DeviceTierAssessment? = null,
    val profile: RecordingProfile? = null,
    val profileIsPredicted: Boolean = false,
    val mapInstall: MapInstallState = MapInstallState.NotInstalled,
    val storageVolumes: List<StorageVolumeOption> = emptyList(),
    val weatherSupported: Boolean = false,
    val weatherSourceName: String = "",
) {
    /** The camera the recorder would actually use for the selected facing. */
    val selectedCamera: CameraCapability?
        get() = capabilities?.let { RecordingProfileSelector.pickCamera(it, settings) }

    private val facingLabel: String
        get() = if (settings.cameraFacing == CameraFacing.Front) "front" else "rear"

    private val supportedQualityNames: Set<String>
        get() = selectedCamera?.supportedQualities?.toSet().orEmpty()

    val selectedVolume: StorageVolumeOption?
        get() = storageVolumes.firstOrNull { it.isSelected }

    /**
     * Dual camera is gated twice: by the platform's own concurrent-camera list and by device
     * tier, because a second encoder on a slow device threatens the primary recording -- which is
     * the one thing Roadguard must not lose.
     */
    val dualCamera: SettingAvailability
        get() {
            val capabilities = capabilities ?: return SettingAvailability.unavailable(STILL_CHECKING)
            if (!capabilities.supportsConcurrentCameras) {
                return SettingAvailability.unavailable(
                    "This device reports no pair of cameras that can record at the same time.",
                )
            }
            val assessment = tier ?: return SettingAvailability.unavailable(STILL_CHECKING)
            if (assessment.tier != DeviceTier.Capable) {
                return SettingAvailability.unavailable(
                    "Dual camera needs a Capable device so the main recording stays safe; " +
                        "this one is rated ${assessment.tier.label}.",
                )
            }
            return SettingAvailability.advisory(
                "Two encoders run at once: expect more heat and roughly twice the storage use.",
            )
        }

    /**
     * Stabilisation follows the camera, not the tier: if no camera reports support there is
     * nothing to turn on, and if only the other camera supports it the user should be told which.
     */
    val stabilisation: SettingAvailability
        get() {
            val capabilities = capabilities ?: return SettingAvailability.unavailable(STILL_CHECKING)
            if (capabilities.cameras.none { it.supportsVideoStabilisation }) {
                return SettingAvailability.unavailable(
                    "No camera on this device reports video stabilisation.",
                )
            }
            if (selectedCamera?.supportsVideoStabilisation == false) {
                return SettingAvailability.advisory(
                    "The $facingLabel camera does not report stabilisation; another camera here does.",
                )
            }
            return SettingAvailability.Available
        }

    /** Weather is optional and stays visible when unsupported, with the reason. */
    val weather: SettingAvailability
        get() = if (weatherSupported) {
            SettingAvailability.Available
        } else {
            SettingAvailability.unavailable(WeatherUnavailableReason.NotSupported.message + ".")
        }

    /** Night assist is a tier decision in the selector, so Settings explains rather than blocks. */
    val nightAssist: SettingAvailability
        get() = if (tier?.tier == DeviceTier.Baseline) {
            SettingAvailability.advisory(
                "Auto leaves night assist off on a Baseline device; On still applies it.",
            )
        } else {
            SettingAvailability.Available
        }

    fun quality(option: QualitySetting): SettingAvailability {
        val cameraXName = cameraXQualityNameOf(option) ?: return SettingAvailability.Available
        if (capabilities == null) return SettingAvailability.advisory(STILL_CHECKING)
        val supported = supportedQualityNames
        if (supported.isEmpty()) {
            return SettingAvailability.advisory("This camera has not reported its recording modes yet.")
        }
        if (cameraXName !in supported) {
            return SettingAvailability.unavailable(
                "Not offered by the $facingLabel camera. It supports $supportedQualitySummary.",
            )
        }
        return if (option == QualitySetting.Uhd2160p) {
            SettingAvailability.advisory("Much more heat, battery and storage than 1080p.")
        } else {
            SettingAvailability.Available
        }
    }

    fun frameRate(option: FrameRateSetting): SettingAvailability {
        val fps = option.fps ?: return SettingAvailability.Available
        val maximum = selectedCamera?.maxFrameRate
            ?: return if (capabilities == null) {
                SettingAvailability.advisory(STILL_CHECKING)
            } else {
                SettingAvailability.Available
            }
        if (fps > maximum) {
            return SettingAvailability.unavailable("The $facingLabel camera reports up to $maximum fps.")
        }
        return if (fps >= 60) {
            SettingAvailability.advisory("Doubles the data rate and the heat; Auto stays at 30 fps.")
        } else {
            SettingAvailability.Available
        }
    }

    /** Human-readable list of the qualities this camera does offer, richest first. */
    val supportedQualitySummary: String
        get() {
            val supported = supportedQualityNames
            val labels = QUALITY_LADDER
                .filter { cameraXQualityNameOf(it) in supported }
                .map { it.label }
            return if (labels.isEmpty()) "nothing yet" else labels.joinToString(", ")
        }

    private companion object {
        const val STILL_CHECKING = "Roadguard is still checking what this device supports."

        val QUALITY_LADDER = listOf(
            QualitySetting.Uhd2160p,
            QualitySetting.FullHd1080p,
            QualitySetting.Hd720p,
            QualitySetting.Sd480p,
        )
    }
}

/**
 * Maps a user-facing quality onto the CameraX `Quality` name the capability probe recorded.
 *
 * Null for `Auto`, which is not a resolution and is therefore always selectable.
 */
private fun cameraXQualityNameOf(quality: QualitySetting): String? = when (quality) {
    QualitySetting.Auto -> null
    QualitySetting.Sd480p -> "SD"
    QualitySetting.Hd720p -> "HD"
    QualitySetting.FullHd1080p -> "FHD"
    QualitySetting.Uhd2160p -> "UHD"
}

/** The plan a screen with no recorder attached should assume: no thermal pressure. */
internal val NormalThermalPlan: ThermalPlan = ThermalPolicy.planFor(ThermalLevel.Normal)
