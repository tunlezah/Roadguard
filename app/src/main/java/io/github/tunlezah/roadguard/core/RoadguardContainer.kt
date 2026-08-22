package io.github.tunlezah.roadguard.core

import android.content.Context
import io.github.tunlezah.roadguard.RoadguardApplication
import io.github.tunlezah.roadguard.camera.CameraOrientationTracker
import io.github.tunlezah.roadguard.camera.CameraSession
import io.github.tunlezah.roadguard.capability.DeviceCapabilityProbe
import io.github.tunlezah.roadguard.data.RoadguardDatabase
import io.github.tunlezah.roadguard.diagnostics.DiagnosticsCollector
import io.github.tunlezah.roadguard.event.EventSensorSource
import io.github.tunlezah.roadguard.event.ProtectionCoordinator
import io.github.tunlezah.roadguard.location.LocationEngine
import io.github.tunlezah.roadguard.map.MapRepository
import io.github.tunlezah.roadguard.overlay.OverlayComposer
import io.github.tunlezah.roadguard.power.PowerMonitor
import io.github.tunlezah.roadguard.recording.RecordingController
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.SettingsRepository
import io.github.tunlezah.roadguard.storage.StorageManager
import io.github.tunlezah.roadguard.storage.StorageReconciler
import io.github.tunlezah.roadguard.thermal.AndroidThermalSource
import io.github.tunlezah.roadguard.thermal.SimulatedThermalSource
import io.github.tunlezah.roadguard.thermal.ThermalSource
import io.github.tunlezah.roadguard.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Roadguard's dependency graph.
 *
 * A hand-written container rather than a DI framework. The reasons are specific, not ideological:
 * the app is a single Gradle module with about a dozen long-lived singletons and no runtime
 * scoping beyond "the process"; an annotation processor would add build time and APK size for a
 * graph this shape; and, most importantly, the *order* in which the recorder's collaborators come
 * up matters (storage must be reconciled before the recorder can index a segment), which is
 * clearer as explicit code than as a set of generated bindings.
 *
 * Everything here is lazy, so opening the app to look at the map does not construct the camera
 * probe, and constructing the container has no side effects beyond the coroutine scope.
 */
class RoadguardContainer(private val appContext: Context) {

    /**
     * Application-lifetime scope.
     *
     * `SupervisorJob` so one failing subsystem -- say weather -- cannot cancel the recorder, which
     * is the whole point of the app.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val locationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "roadguard-location")
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    /**
     * The current settings, hot, so synchronous callers (the foreground-service type bitmask, the
     * notification) do not have to suspend.
     */
    val settings: StateFlow<Settings> by lazy {
        settingsRepository.settings.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = Settings(),
        )
    }

    fun settingsSnapshot(): Settings = settings.value

    val database: RoadguardDatabase by lazy { RoadguardDatabase.create(appContext) }

    val storageManager: StorageManager by lazy {
        StorageManager(appContext, database.segments()).also {
            it.useVolume(settings.value.storageVolumeId)
        }
    }

    val storageReconciler: StorageReconciler by lazy {
        StorageReconciler(storageManager, database.segments(), database.events())
    }

    val protectionCoordinator: ProtectionCoordinator by lazy {
        ProtectionCoordinator(database.segments(), database.events(), storageManager)
    }

    val cameraSession: CameraSession by lazy { CameraSession(appContext) }

    val sensorSource: EventSensorSource by lazy { EventSensorSource(appContext) }

    val capabilityProbe: DeviceCapabilityProbe by lazy {
        DeviceCapabilityProbe(appContext, cameraSession, sensorSource)
    }

    val locationEngine: LocationEngine by lazy { LocationEngine(appContext, locationExecutor) }

    val powerMonitor: PowerMonitor by lazy { PowerMonitor(appContext) }

    val orientationTracker: CameraOrientationTracker by lazy { CameraOrientationTracker(appContext) }

    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(appContext, applicationScope, settings, locationEngine.state)
    }

    val mapRepository: MapRepository by lazy {
        MapRepository(appContext, applicationScope, storageManager)
    }

    /**
     * The thermal source.
     *
     * Swapped for [SimulatedThermalSource] by the developer thermal harness. The simulated source
     * is never wired in a release build, and anything it produces is labelled SIMULATED wherever
     * it surfaces, so a simulated reading can never be mistaken for a measurement.
     */
    var thermalSource: ThermalSource = AndroidThermalSource(appContext, applicationScope)
        private set

    /** Used only by the debug thermal harness. */
    fun useSimulatedThermalSource(source: SimulatedThermalSource) {
        thermalSource.stop()
        thermalSource = source
    }

    val recordingController: RecordingController by lazy {
        RecordingController(
            context = appContext,
            scope = applicationScope,
            settingsRepository = settingsRepository,
            cameraSession = cameraSession,
            capabilityProbe = capabilityProbe,
            storage = storageManager,
            segments = database.segments(),
            protection = protectionCoordinator,
            locationEngine = locationEngine,
            sensorSource = sensorSource,
            thermalSource = thermalSource,
            powerMonitor = powerMonitor,
            orientationTracker = orientationTracker,
            overlayComposer = OverlayComposer(),
            weatherState = weatherRepository.state,
        )
    }

    val diagnosticsCollector: DiagnosticsCollector by lazy {
        DiagnosticsCollector(
            context = appContext,
            recordingController = recordingController,
            storageManager = storageManager,
            locationEngine = locationEngine,
            powerMonitor = powerMonitor,
            sensorSource = sensorSource,
            thermalSource = { thermalSource },
            mapRepository = mapRepository,
            weatherRepository = weatherRepository,
            segments = database.segments(),
            events = database.events(),
        )
    }

    /**
     * Start-up work that must happen before recording, run once per process.
     *
     * Reconciliation comes first and deliberately blocks nothing else: the map, the location fix
     * and the UI all proceed in parallel. The one ordering that matters is that the index is
     * consistent before the recorder writes to it.
     */
    fun onApplicationCreate() {
        applicationScope.launch {
            storageManager.useVolume(settings.value.storageVolumeId)
            runCatching { storageReconciler.reconcile() }
        }
        applicationScope.launch {
            runCatching { storageManager.refresh(settings.value.loopBudgetBytes) }
        }
    }

    companion object {
        fun from(context: Context): RoadguardContainer =
            (context.applicationContext as RoadguardApplication).container
    }
}
