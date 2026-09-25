package io.github.tunlezah.roadguard.core

import android.content.Context
import android.util.Log
import io.github.tunlezah.roadguard.RoadguardApplication
import io.github.tunlezah.roadguard.camera.CameraOrientationTracker
import io.github.tunlezah.roadguard.camera.CameraSession
import io.github.tunlezah.roadguard.capability.DeviceCapabilityProbe
import io.github.tunlezah.roadguard.data.RoadguardDatabase
import io.github.tunlezah.roadguard.diagnostics.DiagnosticsCollector
import io.github.tunlezah.roadguard.event.EventSensorSource
import io.github.tunlezah.roadguard.event.ProtectionCoordinator
import io.github.tunlezah.roadguard.location.LocationEngine
import io.github.tunlezah.roadguard.location.TrackRecorder
import io.github.tunlezah.roadguard.map.MapRepository
import io.github.tunlezah.roadguard.map.OfflinePlaceLookup
import io.github.tunlezah.roadguard.map.PlaceLookup
import io.github.tunlezah.roadguard.overlay.OverlayComposer
import io.github.tunlezah.roadguard.power.PowerMonitor
import io.github.tunlezah.roadguard.recording.RecordingController
import io.github.tunlezah.roadguard.recording.SessionJournal
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.SettingsRepository
import io.github.tunlezah.roadguard.storage.StorageManager
import io.github.tunlezah.roadguard.storage.ReconcileReport
import io.github.tunlezah.roadguard.storage.StorageReconciler
import io.github.tunlezah.roadguard.thermal.AndroidThermalSource
import io.github.tunlezah.roadguard.thermal.SimulatedThermalSource
import io.github.tunlezah.roadguard.thermal.ThermalSource
import io.github.tunlezah.roadguard.trip.TripRepository
import io.github.tunlezah.roadguard.weather.OpenMeteoWeatherSource
import io.github.tunlezah.roadguard.weather.WeatherRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /** When this process started, so start-up repair can tell this run's work from the last one's. */
    private val processStartedAtEpochMs: Long = System.currentTimeMillis()

    /**
     * Application-lifetime scope.
     *
     * `SupervisorJob` so one failing subsystem -- say weather -- cannot cancel the recorder, which
     * is the whole point of the app. And an exception handler, because without one an exception
     * escaping *any* coroutine here goes to the thread's default handler, which on Android kills
     * the process -- mid-recording, leaving the file being written without its index. A logged
     * failure in one subsystem is always better than a dead dashcam.
     */
    val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "uncaught failure in a background task; the app keeps running", error)
        },
    )

    /**
     * Completed once start-up reconciliation has finished (or failed). The recorder waits for it,
     * bounded, before writing its first segment: see [RecordingController].
     */
    private val startupRepair = CompletableDeferred<Unit>()

    /** Remembers whether a recording was running, across process death. */
    val sessionJournal: SessionJournal by lazy { SessionJournal(appContext) }

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

    /**
     * Uses the primary volume until [onApplicationCreate] has loaded the persisted choice.
     * Deliberately not initialised from [settings] here: that snapshot is the compiled-in
     * default until DataStore's first read completes, so it cannot be trusted for the volume.
     */
    val storageManager: StorageManager by lazy {
        StorageManager(appContext, database.segments(), database.trips())
    }

    val storageReconciler: StorageReconciler by lazy {
        StorageReconciler(storageManager, database.segments(), database.events(), tripRepository)
    }

    /**
     * The most recent start-up reconciliation result, and when it ran, exposed so Diagnostics
     * can show exactly what the last start repaired, dropped or quarantined. It is the single
     * most useful signal when footage appears to be missing: it distinguishes "nothing was ever
     * written" from "clips were written and then quarantined or dropped". Null until
     * [onApplicationCreate] has run the reconcile once this process.
     */
    @Volatile
    var lastReconcileReport: io.github.tunlezah.roadguard.storage.ReconcileReport? = null
        private set

    @Volatile
    var lastReconcileAtEpochMs: Long? = null
        private set

    /**
     * Place names from the installed offline map. Reads the same archive the map renders from, so
     * naming a trip needs no network and no extra download.
     */
    val placeLookup: PlaceLookup by lazy { OfflinePlaceLookup(mapRepository) }

    val tripRepository: TripRepository by lazy {
        TripRepository(database.trips(), database.segments(), storageManager, placeLookup)
    }

    val trackRecorder: TrackRecorder by lazy { TrackRecorder() }

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

    /**
     * Optional weather.
     *
     * Open-Meteo is the source because it is the only one that met every constraint: free, no API
     * key, no registration, no account, and covering Australia. The Bureau of Meteorology, which the
     * specification would have preferred, explicitly blocks automated access and directs
     * programmatic users to a charged registered-user service -- see
     * docs/research/weather-australia.md.
     */
    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(
            context = appContext,
            scope = applicationScope,
            settings = settings,
            location = locationEngine.state,
            source = OpenMeteoWeatherSource(),
        )
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
            trips = tripRepository,
            trackRecorder = trackRecorder,
            sensorSource = sensorSource,
            thermalSource = thermalSource,
            powerMonitor = powerMonitor,
            orientationTracker = orientationTracker,
            overlayComposer = OverlayComposer(),
            weatherState = weatherRepository.state,
            journal = sessionJournal,
            awaitStartupRepair = { withTimeoutOrNull(STARTUP_REPAIR_WAIT_MS) { startupRepair.await() } },
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
            reconcileReport = { lastReconcileReport },
            reconcileAtEpochMs = { lastReconcileAtEpochMs },
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
            try {
                // Wait for the settings actually persisted on disk. The hot [settings] StateFlow
                // reports the compiled-in defaults until DataStore's first read lands, and those
                // defaults say "internal storage": reconciling against the wrong volume would treat
                // every file on the chosen one as gone and drop its index rows.
                val loaded = settingsRepository.settings.first()
                storageManager.useVolume(loaded.storageVolumeId)
                runCatching { storageReconciler.reconcile(sessionStartedAtEpochMs = processStartedAtEpochMs) }
                    .onSuccess { report ->
                        lastReconcileReport = report
                        lastReconcileAtEpochMs = System.currentTimeMillis()
                    }
                    .onFailure { failure ->
                        // Diagnostics must show that the pass failed, not "not run yet": a
                        // failed pass is the difference between footage that was never written
                        // and footage that is on the disk waiting to be re-indexed.
                        Log.e(TAG, "start-up reconciliation failed", failure)
                        lastReconcileReport = ReconcileReport.skipped(
                            "the pass failed before it finished: ${failure.message ?: failure.javaClass.simpleName}",
                        )
                        lastReconcileAtEpochMs = System.currentTimeMillis()
                    }
                runCatching { storageManager.refresh(loaded.loopBudgetBytes) }
            } finally {
                // Whatever happened, the recorder must not wait for this any longer.
                startupRepair.complete(Unit)
            }
        }
    }

    companion object {
        private const val TAG = "RoadguardContainer"

        /**
         * The longest the recorder waits for start-up reconciliation before its first segment.
         * Reconciliation normally takes well under a second; the bound is there so a pathological
         * volume can delay recording, never prevent it. If it is ever hit, the reconciler still
         * leaves this run's files alone ([StorageManager.isFromThisProcess]).
         */
        const val STARTUP_REPAIR_WAIT_MS = 10_000L

        fun from(context: Context): RoadguardContainer =
            (context.applicationContext as RoadguardApplication).container
    }
}
