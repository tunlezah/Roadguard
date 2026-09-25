package io.github.tunlezah.roadguard.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.video.AudioStats
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.RecordingStats
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.github.tunlezah.roadguard.camera.CameraOrientationTracker
import io.github.tunlezah.roadguard.camera.CameraSession
import io.github.tunlezah.roadguard.capability.DeviceCapabilities
import io.github.tunlezah.roadguard.capability.DeviceCapabilityProbe
import io.github.tunlezah.roadguard.capability.DeviceTierAssessment
import io.github.tunlezah.roadguard.capability.DeviceTierScorer
import io.github.tunlezah.roadguard.capability.RecordingProfile
import io.github.tunlezah.roadguard.capability.RecordingProfileSelector
import io.github.tunlezah.roadguard.data.EventKind
import io.github.tunlezah.roadguard.data.SegmentDao
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.data.TripEntity
import io.github.tunlezah.roadguard.event.BrakeDetector
import io.github.tunlezah.roadguard.event.BrakeLevel
import io.github.tunlezah.roadguard.event.EventSensorSource
import io.github.tunlezah.roadguard.event.ImpactDetector
import io.github.tunlezah.roadguard.event.MotionContext
import io.github.tunlezah.roadguard.event.ProtectionCoordinator
import io.github.tunlezah.roadguard.event.SegmentTiming
import io.github.tunlezah.roadguard.event.SensorSample
import io.github.tunlezah.roadguard.location.GpxWriter
import io.github.tunlezah.roadguard.location.LocationEngine
import io.github.tunlezah.roadguard.location.LocationState
import io.github.tunlezah.roadguard.location.TrackRecorder
import io.github.tunlezah.roadguard.overlay.OverlayComposer
import io.github.tunlezah.roadguard.overlay.VideoOverlayEffect
import io.github.tunlezah.roadguard.power.BatterySafeGate
import io.github.tunlezah.roadguard.power.PowerAction
import io.github.tunlezah.roadguard.power.PowerMonitor
import io.github.tunlezah.roadguard.power.PowerPolicy
import io.github.tunlezah.roadguard.power.PowerState
import io.github.tunlezah.roadguard.power.PowerTransition
import io.github.tunlezah.roadguard.settings.CameraFacing
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.SettingsRepository
import io.github.tunlezah.roadguard.storage.Mp4Inspector
import io.github.tunlezah.roadguard.storage.StorageAssessment
import io.github.tunlezah.roadguard.storage.StorageBucket
import io.github.tunlezah.roadguard.storage.StorageManager
import io.github.tunlezah.roadguard.storage.StorageState
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.thermal.ThermalPlan
import io.github.tunlezah.roadguard.thermal.ThermalPolicy
import io.github.tunlezah.roadguard.thermal.ThermalReading
import io.github.tunlezah.roadguard.thermal.ThermalSource
import io.github.tunlezah.roadguard.trip.TripRepository
import io.github.tunlezah.roadguard.weather.WeatherState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the recording loop.
 *
 * ### Where it lives, and why
 *
 * The controller is a process singleton and the CameraX use cases are bound to the recording
 * *service's* lifecycle, never an Activity's. Rotating the phone, opening Settings or turning the
 * screen off therefore cannot interrupt a recording -- the UI is a viewer of state the recorder
 * publishes, not the owner of the camera.
 *
 * ### The segment loop
 *
 * A recording is stopped and the next one started immediately. CameraX's `Recorder` explicitly
 * queues a start issued while it is stopping and services it when the previous recording
 * finalises, which is the smallest gap the stable API offers. A small gap is unavoidable -- the
 * video encoder is stopped and the next segment needs a fresh keyframe -- and
 * `docs/benchmarking.md` records how to measure it on real hardware.
 *
 * ### Reconfiguration only at boundaries
 *
 * Resolution, frame rate, bitrate, effects and the second camera are all baked into the camera
 * session at bind time, so changing any of them means rebinding, which stops the recording. The
 * controller therefore never reconfigures mid-segment: a request from the thermal engine, the
 * power policy or the settings screen is *queued* and applied at the next segment boundary. That
 * single rule is what lets Roadguard respond to heat without ever cutting a recording short.
 *
 * ### Threading
 *
 * All controller state is owned by one serial dispatcher. Recorder events, sensor samples,
 * location fixes, the tick, settings, thermal and power changes are handled there one at a time,
 * so none of them can see another half-done. The recorder's per-frame status callback is the one
 * exception: it runs on the recorder's own thread, touches only volatile fields and the atomic
 * state, and posts real work to the serial dispatcher. CameraX calls hop to the main thread.
 *
 * Suspension points still let other work run in between, so every operation that can start,
 * stop or rebind the camera -- start, stop, rollover, recovery, the watchdog -- holds
 * [stateMutex], and each carries the [session] token it began under. Stop, detach and shutdown
 * bump the token *before* anything else, so a start, rollover or recovery that was in flight
 * notices at its next step and abandons cleanly. Without that, a rollover landing during a stop
 * could start a new segment after the teardown, and the state would say "recording" while
 * nothing was.
 *
 * ### Failure
 *
 * A session ends only when the user, the power policy or a nearly flat battery ends it.
 * Anything else that stops the frames -- the camera lost to another app, an encoder error, a full
 * or missing volume, frames that silently stop arriving -- moves the recorder to
 * [RecorderStatus.Recovering], and [RecoveryPolicy] brings it back: quick retries first, then a
 * slow cadence for as long as it takes, resuming at once when the camera reopens. The watchdog
 * exists for the failure that reports nothing at all, a recording whose frames just stop. A
 * camera configuration the device refuses falls back to a safe one instead of ending the session.
 *
 * ### Trips and tracks
 *
 * Every recording session belongs to a trip (see [TripRepository]): one is opened or continued
 * when recording starts, each clip is indexed against it, its end advances as clips finalise, and
 * it is closed and named when recording stops. While it is open, and the user has the GPX switch
 * on, every usable fix is offered to the [TrackRecorder], which writes the trip's track file.
 * Neither can touch the camera or the encoder: the trip is index rows and the track is a small
 * side file, and both are guarded so a failure in either leaves recording untouched.
 */
class RecordingController(
    private val context: Context,
    scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val cameraSession: CameraSession,
    private val capabilityProbe: DeviceCapabilityProbe,
    private val storage: StorageManager,
    private val segments: SegmentDao,
    private val protection: ProtectionCoordinator,
    private val locationEngine: LocationEngine,
    private val trips: TripRepository,
    private val trackRecorder: TrackRecorder,
    private val sensorSource: EventSensorSource,
    private val thermalSource: ThermalSource,
    private val powerMonitor: PowerMonitor,
    private val orientationTracker: CameraOrientationTracker,
    private val overlayComposer: OverlayComposer,
    private val weatherState: StateFlow<WeatherState>,
    /** Remembers whether a session was running, so a kill can be followed by a resume prompt. */
    private val journal: SessionJournal? = null,
    /** Suspends until start-up reconciliation has finished with the index (bounded by the caller). */
    private val awaitStartupRepair: suspend () -> Unit = {},
    /** The serial dispatcher that owns all controller state. Injected so tests can drive it. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val scope = CoroutineScope(scope.coroutineContext + dispatcher)

    private val recorderExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "roadguard-recorder").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val stateMutex = Mutex()
    private val thermalPolicy = ThermalPolicy()
    private val batterySafeGate = BatterySafeGate()

    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    val surfaceRequest = cameraSession.surfaceRequest

    /**
     * The physical-orientation-derived target rotation, in `Surface.ROTATION_*` form.
     *
     * Published so the viewfinder can work out the *displayed* aspect ratio of the camera image,
     * which is what the preview-fit calculation needs. It has no effect on the recording beyond
     * the rotation already applied to the use cases.
     */
    val surfaceRotation: StateFlow<Int> = orientationTracker.surfaceRotation

    private val _capabilities = MutableStateFlow<DeviceCapabilities?>(null)
    val capabilities: StateFlow<DeviceCapabilities?> = _capabilities.asStateFlow()

    private val _tier = MutableStateFlow<DeviceTierAssessment?>(null)
    val tier: StateFlow<DeviceTierAssessment?> = _tier.asStateFlow()

    private val _thermalPlan = MutableStateFlow(ThermalPolicy.planFor(ThermalLevel.Normal))
    val thermalPlan: StateFlow<ThermalPlan> = _thermalPlan.asStateFlow()

    /**
     * Identifies the current recording session. Bumped -- from any thread -- by everything that
     * ends a session; work belonging to an older value abandons at its next step.
     */
    private val session = AtomicLong(0L)

    // ── Confined to the serial dispatcher ─────────────────────────────────────────────────────

    private var lifecycleOwner: LifecycleOwner? = null
    private var overlayEffect: VideoOverlayEffect? = null
    private var activeRecording: Recording? = null
    private var sequence: Long = 0L
    private var boundProfile: RecordingProfile? = null

    /** Camera configurations this session has seen refused, so they are not retried every segment. */
    private val unbindableProfiles = mutableSetOf<RecordingProfile.BindKey>()

    /** Burn-in failed once this session; later profiles leave it out rather than failing again. */
    private var overlayBroken = false

    /** Recordings started and not yet finalised. See [RecordingUiState.unfinalizedSegments]. */
    private val liveSegments = mutableSetOf<SegmentHandle>()
    private val unfinalized = MutableStateFlow(0)

    private var impactDetector = ImpactDetector()
    private val brakeDetector = BrakeDetector()
    private var brakeLevel: BrakeLevel? = null
    private var lastBrakeFixEpochMs: Long? = null
    private var peripheralsRunning = false
    private var sessionJournaled = false
    private var overlayJob: Job? = null
    private var sensorJob: Job? = null
    private var supervisionJob: Job? = null
    private var scheduledStopJob: Job? = null
    private var lastSettings: Settings = Settings()

    private var recoveryJob: Job? = null
    private var recoveryAttempt = 0
    private var recoveryStartedAtMs: Long? = null
    private var recoveryFailure: RecordingFailure? = null
    private var rebindOnRecovery = false
    private var cameraBlocker: RecordingBlocker? = null

    // ── Also read from the recorder's thread ──────────────────────────────────────────────────

    @Volatile
    private var activeSegment: SegmentHandle? = null

    @Volatile
    private var pendingProfile: RecordingProfile? = null

    @Volatile
    private var storageCleanupRequired = false

    /** The trip this recording session belongs to, or null between sessions. */
    @Volatile
    private var activeTripId: Long? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────

    @Volatile
    private var attached = false

    /** True while the recording service is running and owns the camera. */
    val isAttached: Boolean get() = attached

    /** Called by [RecordingService] once it is a foreground service and can own the camera. */
    fun attach(owner: LifecycleOwner) {
        attached = true
        powerMonitor.start()
        thermalSource.start()
        orientationTracker.start()
        scope.launch {
            lifecycleOwner = owner
            startSupervision()
        }
    }

    /**
     * Called when the recording service is destroyed. Ends whatever session was running -- the
     * service is what owned the camera -- and closes its current file.
     */
    fun detach() {
        attached = false
        session.incrementAndGet()
        orientationTracker.stop()
        thermalSource.stop()
        powerMonitor.stop()
        scope.launch { detachInternal() }
    }

    private suspend fun detachInternal() {
        // Captured before the first suspension: a new service can attach while this one is still
        // being torn down, and its owner must not be the one cleared at the end.
        val detaching = lifecycleOwner
        supervisionJob?.cancel()
        supervisionJob = null
        scheduledStopJob?.cancel()
        scheduledStopJob = null
        cancelRecoveryJob()
        val wasActive = _state.value.isSessionActive
        closeActiveRecording()
        stateMutex.withLock {
            stopPeripherals()
            closeTrip()
            unbindCamera()
            if (lifecycleOwner === detaching) lifecycleOwner = null
            batterySafeGate.reset(false)
            update {
                if (wasActive) {
                    it.copy(
                        status = RecorderStatus.Failed,
                        lastErrorMessage = "The recording service stopped",
                        segmentStartedAtEpochMs = null,
                        startupCountdownSeconds = null,
                        batterySafe = false,
                    )
                } else {
                    it.copy(batterySafe = false)
                }
            }
        }
    }

    private fun startSupervision() {
        supervisionJob?.cancel()
        supervisionJob = scope.launch {
            launch { settingsRepository.settings.collect { onSettings(it) } }
            launch { thermalSource.reading.collect { onThermalReading(it) } }
            launch { powerMonitor.transitions.collect { onPowerTransition(it) } }
            launch { powerMonitor.state.collect { onBatteryState(it) } }
            launch { cameraSession.cameraError.collect { onCameraError(it) } }
            launch { cameraSession.cameraOpen.collect { open -> if (open) onCameraReopened() } }
            launch { locationEngine.state.collect { onLocationState(it) } }
            launch { orientationTracker.surfaceRotation.collect { applyPreviewRotation(it) } }
            launch { tickLoop() }
            launch { watchdogLoop() }
        }
    }

    // ── Public commands ───────────────────────────────────────────────────────────────────────

    /**
     * Starts recording.
     *
     * @param delaySeconds start-up delay, so the camera's exposure has settled and the phone is
     *   in its cradle before the first segment begins. Passed in rather than read here so the
     *   power policy can start immediately when the ignition supplies power.
     */
    fun start(delaySeconds: Int? = null) {
        // The token is read now, not when the start runs, so a Stop pressed after this call wins.
        val token = session.get()
        scope.launch { startInternal(token, delaySeconds) }
    }

    fun stop() = requestStop(RecorderStatus.Idle)

    /**
     * Ends the session. The token is bumped here, synchronously, so anything already in flight is
     * abandoned even before the stop itself runs; the stop is then its own coroutine, so a caller
     * that is itself a cancellable job (the power policy's delayed stop, say) can never cancel the
     * stop half-way by cancelling itself.
     */
    private fun requestStop(status: RecorderStatus, message: String? = null, blocker: RecordingBlocker? = null) {
        session.incrementAndGet()
        scope.launch { stopInternal(status, message, blocker) }
    }

    /** Protects the current and preceding footage at the user's request. */
    fun protectNow() {
        scope.launch {
            val settings = lastSettings
            val result = protection.protect(
                kind = EventKind.Manual,
                atEpochMs = System.currentTimeMillis(),
                preSeconds = settings.preEventSeconds,
                postSeconds = settings.postEventSeconds,
                confidence = 1f,
                detection = null,
                inProgress = currentSegmentTiming(),
            )
            update { it.copy(lastProtectionMessage = result.message()) }
            // Recording continues untouched: protection is metadata plus a sidecar file.
            scope.launch {
                delay(PROTECT_MESSAGE_MS)
                update { it.copy(lastProtectionMessage = null) }
            }
        }
    }

    /** Detaches or reattaches the preview surface, for screen-off and thermal relief. Main thread. */
    fun setPreviewEnabled(enabled: Boolean) = cameraSession.setPreviewEnabled(enabled)

    /**
     * The phone is powering off: close the current file now, while there is still time.
     *
     * An MP4 is only playable once its index is written at the end, so a clip that is still open
     * when the power goes is lost. Android gives receivers of the shutdown broadcast a few
     * seconds; this spends them finalising rather than on an orderly teardown nobody will see.
     * Returns once the recorder has closed its files or [timeoutMs] has passed.
     */
    suspend fun finalizeForShutdown(timeoutMs: Long) {
        session.incrementAndGet()
        withContext(dispatcher) {
            scheduledStopJob?.cancel()
            cancelRecoveryJob()
            val hadRecording = closeActiveRecording()
            journal?.markStopped()
            if (hadRecording || liveSegments.isNotEmpty()) {
                Log.i(TAG, "phone is shutting down; closing the current clip")
                if (!awaitFinalised(timeoutMs)) Log.w(TAG, "the clip was not closed before shutdown")
            }
            update {
                if (it.isSessionActive) {
                    it.copy(
                        status = RecorderStatus.Idle,
                        lastErrorMessage = "Recording stopped because the phone is shutting down",
                        segmentStartedAtEpochMs = null,
                        startupCountdownSeconds = null,
                    )
                } else {
                    it
                }
            }
        }
    }

    // ── Start / stop ──────────────────────────────────────────────────────────────────────────

    private suspend fun startInternal(token: Long, delaySecondsOverride: Int?) = stateMutex.withLock {
        if (!isCurrent(token)) return@withLock
        if (_state.value.isSessionActive) return@withLock
        if (lifecycleOwner == null) {
            update { it.copy(status = RecorderStatus.Failed, lastErrorMessage = "Recording service is not running") }
            return@withLock
        }

        val settings = settingsRepository.settings.first().also { lastSettings = it }
        if (!isCurrent(token)) return@withLock
        val blockers = evaluateBlockers(settings)
        if (blockers.any { it.blocksRecording }) {
            update { it.copy(status = RecorderStatus.Idle, blockers = blockers.map { blocker -> blocker.blocker }) }
            return@withLock
        }
        // The battery monitor only acts on a *change* while a session is running. A session
        // started on a battery that is already at the floor would otherwise record until the
        // phone died mid-clip, which is the one ending that loses footage.
        if (PowerPolicy.evaluateBattery(powerMonitor.state.value, settings) is PowerAction.StopForLowBattery) {
            update {
                it.copy(
                    status = RecorderStatus.Idle,
                    blockers = listOf(RecordingBlocker.LowBattery),
                    lastErrorMessage = RecordingBlocker.LowBattery.message,
                )
            }
            return@withLock
        }
        update {
            it.copy(
                status = RecorderStatus.Starting,
                blockers = emptyList(),
                lastErrorMessage = null,
                sessionDurationMs = 0,
                sessionSegmentCount = 0,
            )
        }
        resetRecovery()
        unbindableProfiles.clear()
        overlayBroken = false
        sessionJournaled = false
        cameraBlocker = null

        // Never write into an index that start-up reconciliation is still repairing: it would
        // judge this session's in-progress file as a truncated one from the last run.
        awaitStartupRepair()
        if (!isCurrent(token)) return@withLock abandonStart()

        val volumeReady = runCatchingNonCancellation {
            withContext(Dispatchers.IO) { storage.useVolume(settings.storageVolumeId) }
            !storage.requestedVolumeMissing
        } ?: false
        if (!volumeReady) {
            // Recording onto whatever volume is left would scatter one drive's footage across two
            // devices, and the next start-up would compare the index against the wrong one.
            Log.w(TAG, "the chosen recording volume is not mounted; not starting")
            update {
                it.copy(
                    status = RecorderStatus.Idle,
                    blockers = listOf(RecordingBlocker.StorageUnavailable),
                    lastErrorMessage = RecordingBlocker.StorageUnavailable.message,
                )
            }
            return@withLock
        }
        // The loop may be holding space other apps have since eaten into; delete old loop
        // footage before refusing to record for want of room.
        val roomToRecord = runCatchingNonCancellation { makeRoom() }
        if (roomToRecord != true) {
            update {
                it.copy(
                    status = RecorderStatus.Idle,
                    blockers = listOf(if (roomToRecord == null) RecordingBlocker.StorageUnavailable else RecordingBlocker.StorageFull),
                )
            }
            return@withLock
        }

        // Warm the camera stack and the capability facts during the countdown rather than after
        // it. Failures here are not final: establish() below retries and reports them.
        runCatchingNonCancellation {
            if (cameraSession.initialise().isSuccess) ensureCapabilities()
        }

        // The start-up delay lets the camera settle and gives the driver a moment to seat the
        // phone. It is counted down visibly so a user never wonders whether Roadguard is stuck.
        val delaySeconds = delaySecondsOverride ?: settings.startupDelaySeconds
        for (remaining in delaySeconds downTo 1) {
            update { it.copy(startupCountdownSeconds = remaining) }
            delay(1_000)
            if (!isCurrent(token)) return@withLock abandonStart()
        }
        update { it.copy(startupCountdownSeconds = null) }

        // A session starts from the power and thermal state as they are now, not after a debounce.
        batterySafeGate.reset(PowerPolicy.batterySafe(powerMonitor.state.value, settings))
        update { it.copy(batterySafe = batterySafeGate.applied) }
        onThermalReading(thermalSource.reading.value)

        establish(token, rebind = true)
    }

    /** A start that was overtaken by a stop: leave the state as the stop will expect to find it. */
    private fun abandonStart() {
        update {
            if (it.status == RecorderStatus.Starting) {
                it.copy(status = RecorderStatus.Idle, startupCountdownSeconds = null)
            } else {
                it
            }
        }
    }

    private suspend fun stopInternal(status: RecorderStatus, message: String?, blocker: RecordingBlocker?) {
        stateMutex.withLock {
            scheduledStopJob?.cancel()
            scheduledStopJob = null
            resetRecovery()
            update { it.copy(status = RecorderStatus.Stopping, startupCountdownSeconds = null) }
            closeActiveRecording()
            // Let the recorder write the closing file's index while the camera is still bound; an
            // unbind first would cut the encoder off mid-drain. Bounded, because a wedged recorder
            // must not be able to hold the stop forever.
            if (!awaitFinalised(STOP_FINALIZE_TIMEOUT_MS)) {
                Log.w(TAG, "recorder did not finalise within $STOP_FINALIZE_TIMEOUT_MS ms; unbinding anyway")
            }
            stopPeripherals()
            closeTrip()
            unbindCamera()
            // Unbinding ends anything still open. Whatever has not reported by now never will, and
            // must not keep the wake lock held after the session is over.
            if (!awaitFinalised(UNBIND_FINALIZE_WAIT_MS)) {
                liveSegments.clear()
                publishUnfinalized()
            }
            journal?.markStopped()
            sessionJournaled = false
            batterySafeGate.reset(false)
            update {
                it.copy(
                    status = status,
                    lastErrorMessage = message,
                    blockers = listOfNotNull(blocker),
                    segmentStartedAtEpochMs = null,
                    segmentBytes = 0,
                    batterySafe = false,
                )
            }
        }
    }

    /**
     * Brings the recording pipeline up and starts a segment: the camera stack, capabilities, the
     * binding (with safe fallbacks), the peripherals, the trip. Every step that already holds is
     * skipped, so the same function serves the first start and every recovery. Caller holds
     * [stateMutex]. Anything that fails schedules the next recovery attempt.
     *
     * @return true when a segment was started.
     */
    private suspend fun establish(token: Long, rebind: Boolean, preferred: RecordingProfile? = null): Boolean {
        if (!isCurrent(token)) return false
        val owner = lifecycleOwner ?: return false
        if (cameraSession.initialise().isFailure) {
            scheduleRecovery(token, RecordingFailure.CameraUnavailable)
            return false
        }
        val probed = runCatchingNonCancellation { ensureCapabilities() } ?: run {
            scheduleRecovery(token, RecordingFailure.CameraUnavailable)
            return false
        }
        if (!isCurrent(token)) return false

        if (rebind || boundProfile == null || !cameraSession.isBound) {
            val profile = preferred ?: selectProfile(probed)
            if (!bindCamera(token, owner, profile)) {
                scheduleRecovery(token, RecordingFailure.BindFailed)
                return false
            }
            if (!isCurrent(token)) return false
        }

        startPeripherals()
        if (activeTripId == null) openTrip(lastSettings)
        if (!isCurrent(token)) return false
        return startSegment(token)
    }

    private suspend fun ensureCapabilities(): DeviceCapabilities {
        val probed = _capabilities.value ?: capabilityProbe.probe().also { _capabilities.value = it }
        if (_tier.value == null) _tier.value = DeviceTierScorer.score(probed)
        return probed
    }

    private fun selectProfile(probed: DeviceCapabilities): RecordingProfile {
        val assessed = _tier.value ?: DeviceTierScorer.score(probed).also { _tier.value = it }
        val selected = RecordingProfileSelector.select(probed, assessed, lastSettings, effectivePlan())
        return if (overlayBroken && selected.burnInOverlays) selected.copy(burnInOverlays = false) else selected
    }

    /** The thermal plan with battery-safe restraints applied on top. */
    private fun effectivePlan(): ThermalPlan = PowerPolicy.restrain(_thermalPlan.value, batterySafeGate.applied)

    /**
     * Binds [requested], or the first safe fallback the camera accepts.
     *
     * A configuration refused while another one then bound is remembered for the session, so a
     * profile the camera cannot run is not queued again at every segment boundary. When nothing
     * binds at all, nothing is remembered: that is the camera service failing, not a
     * configuration, and the next recovery attempt must be free to try everything again.
     */
    private suspend fun bindCamera(token: Long, owner: LifecycleOwner, requested: RecordingProfile): Boolean {
        val candidates = buildList {
            add(requested)
            addAll(RecordingProfileSelector.safeFallbacks(requested))
        }.filter { it.bindKey !in unbindableProfiles }
        val refused = mutableListOf<RecordingProfile.BindKey>()
        for (profile in candidates) {
            // A session that has ended since the last attempt needs no more of them.
            if (!isCurrent(token)) return false
            if (bindOnce(owner, profile)) {
                unbindableProfiles += refused
                if (profile !== requested) {
                    Log.w(TAG, "camera refused ${requested.label}; recording at ${profile.label}")
                    update {
                        it.copy(lastErrorMessage = "The camera refused the preferred settings; recording at ${profile.label}")
                    }
                }
                return true
            }
            refused += profile.bindKey
        }
        return false
    }

    private suspend fun bindOnce(owner: LifecycleOwner, profile: RecordingProfile): Boolean {
        val selector = when (lastSettings.cameraFacing) {
            CameraFacing.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraFacing.Rear -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val rotation = orientationTracker.surfaceRotation.value
        val previousEffect = overlayEffect
        val (effect, result) = withContext(Dispatchers.Main) {
            val effect = if (profile.burnInOverlays) {
                previousEffect ?: VideoOverlayEffect(onError = { onOverlayFailure() })
            } else {
                null
            }
            effect to cameraSession.bind(
                lifecycleOwner = owner,
                selector = selector,
                profile = profile,
                surfaceRotation = rotation,
                overlayEffect = effect,
            )
        }
        if (result.isFailure) {
            // bind() leaves nothing bound when it fails; keep whichever effect exists for a retry.
            overlayEffect = effect ?: previousEffect
            return false
        }
        if (effect == null) previousEffect?.close()
        overlayEffect = effect
        boundProfile = profile
        if (lastSettings.recordingZoom > 1f) cameraSession.setRecordingZoom(lastSettings.recordingZoom)
        update { it.copy(profile = profile) }
        // Whatever was queued was measured against the previous binding.
        pendingProfile = null
        requeueProfileIfNeeded()
        return true
    }

    private suspend fun unbindCamera() {
        withContext(NonCancellable + Dispatchers.Main) { cameraSession.unbind() }
        overlayEffect?.close()
        overlayEffect = null
        boundProfile = null
        pendingProfile = null
    }

    /**
     * Stops the recording that is currently carrying the session, if any. Its Finalize event still
     * arrives and is indexed as usual; it simply no longer counts as the active segment.
     *
     * @return true when there was one.
     */
    private fun closeActiveRecording(): Boolean {
        val recording = activeRecording
        activeSegment?.stopRequestedAtMs = SystemClock.elapsedRealtime()
        activeRecording = null
        activeSegment = null
        recording?.let { runCatching { it.stop() }.onFailure { error -> Log.w(TAG, "stop failed", error) } }
        return recording != null
    }

    private suspend fun awaitFinalised(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { unfinalized.first { it == 0 } } != null

    private fun publishUnfinalized() {
        val count = liveSegments.size
        unfinalized.value = count
        update { it.copy(unfinalizedSegments = count) }
    }

    // ── Peripherals ───────────────────────────────────────────────────────────────────────────

    private fun startPeripherals() {
        if (peripheralsRunning) return
        peripheralsRunning = true
        val settings = lastSettings
        if (settings.locationEnabled) {
            locationEngine.request(LocationEngine.Client.Recorder, effectivePlan().locationIntervalMs)
        }
        if (settings.eventDetectionEnabled) startSensors()
        overlayJob?.cancel()
        overlayJob = scope.launch {
            while (isActive) {
                publishOverlay()
                delay(OVERLAY_UPDATE_MS)
            }
        }
    }

    private fun startSensors() {
        impactDetector = ImpactDetector(
            sensitivity = lastSettings.eventSensitivity,
            hasGyroscope = _capabilities.value?.sensors?.hasGyroscope ?: false,
        )
        sensorSource.start()
        sensorJob?.cancel()
        sensorJob = scope.launch { sensorSource.samples.collect { sample -> onSensorSample(sample) } }
    }

    private fun stopSensors() {
        sensorJob?.cancel()
        sensorJob = null
        sensorSource.stop()
    }

    private fun stopPeripherals() {
        overlayJob?.cancel()
        overlayJob = null
        stopSensors()
        locationEngine.release(LocationEngine.Client.Recorder)
        brakeDetector.reset()
        brakeLevel = null
        lastBrakeFixEpochMs = null
        peripheralsRunning = false
    }

    // ── Segments ──────────────────────────────────────────────────────────────────────────────

    /**
     * Starts the next segment. Caller holds [stateMutex].
     *
     * The index row is written first so a crash leaves a row the reconciler can repair. The last
     * session check and the recorder start then happen with no suspension in between, so a stop
     * can never slip in after the check and find a recording it did not know about.
     *
     * @return true when the recorder accepted the segment.
     */
    @SuppressLint("MissingPermission")
    private suspend fun startSegment(token: Long): Boolean {
        if (!isCurrent(token)) return false
        val recorder = cameraSession.recorder
        if (boundProfile == null || recorder == null) {
            scheduleRecovery(token, RecordingFailure.StartRejected)
            return false
        }
        val settings = lastSettings
        val startedAt = System.currentTimeMillis()
        val index = ++sequence
        val file = try {
            withContext(Dispatchers.IO) { storage.createSegmentFile(startedAt, index) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "could not prepare a segment file", error)
            scheduleRecovery(token, RecordingFailure.StorageUnavailable)
            return false
        }

        val rotation = orientationTracker.surfaceRotation.value
        val audio = settings.microphoneEnabled && hasMicrophonePermission()
        val tripId = activeTripId
        val profile = boundProfile ?: run {
            scheduleRecovery(token, RecordingFailure.StartRejected)
            return false
        }
        val location = locationEngine.state.value
        val segmentId = withContext(Dispatchers.IO) {
            runCatching {
                segments.insert(
                    SegmentEntity(
                        fileName = file.name,
                        bucket = StorageBucket.Recordings.dirName,
                        startedAtEpochMs = startedAt,
                        durationMs = 0,
                        sizeBytes = 0,
                        widthPx = profile.resolution?.width ?: 0,
                        heightPx = profile.resolution?.height ?: 0,
                        rotationDegrees = CameraOrientationTracker.degreesFor(rotation),
                        codec = profile.codecMimeType,
                        bitrateBps = profile.targetBitrateBps,
                        frameRate = profile.frameRate,
                        hasAudio = audio,
                        cameraFacing = settings.cameraFacing.name,
                        profileLabel = profile.label,
                        isComplete = false,
                        startLatitude = location.latitude,
                        startLongitude = location.longitude,
                        tripId = tripId,
                    ),
                )
            }.onFailure {
                // Recording matters more than its index: start-up reconciliation adopts a playable
                // file with no row, so the footage is recovered on the next launch.
                Log.w(TAG, "could not index segment $index", it)
            }.getOrNull()
        }

        // CameraX latches a recording's orientation hint when it starts, so this is the moment to
        // apply a rotation that settled during the previous segment.
        withContext(Dispatchers.Main) { cameraSession.updateVideoRotation(rotation) }
        if (!isCurrent(token)) {
            segmentId?.let(::dropRow)
            return false
        }

        // ---- No suspension from here to the end. ----
        val output = FileOutputOptions.Builder(file)
            // A duration limit is a backstop only: the controller rolls over on its own timer.
            // Without it, a stuck timer would produce a single enormous unmanageable file.
            .setDurationLimitMillis(settings.segmentLength.seconds * 1_000L + SEGMENT_LIMIT_GRACE_MS)
            .apply { if (settings.gpsStorage.metadata) locationForMetadata()?.let { setLocation(it) } }
            .build()

        // Everything an event handler needs to know about this recording is captured here and
        // carried by the callback closure. CameraX events do not identify their recording, and
        // the next segment is started while the previous one is still finalising, so state read
        // back from shared fields inside a handler can belong to the wrong segment.
        val handle = SegmentHandle(
            token = token,
            index = index,
            segmentId = segmentId,
            startedAtEpochMs = startedAt,
            file = file,
            tripId = tripId,
            targetMs = settings.segmentLength.seconds * 1_000L,
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        val recording = try {
            val pending = recorder.prepareRecording(context, output)
            if (audio) {
                // A recorder that cannot capture audio must not cost the video.
                runCatching { pending.withAudioEnabled() }
                    .onFailure { Log.w(TAG, "recording segment $index without audio", it) }
            }
            activeSegment = handle
            pending.start(recorderExecutor) { event -> onRecordEvent(handle, event) }
        } catch (error: Throwable) {
            Log.e(TAG, "could not start segment $index", error)
            if (activeSegment === handle) activeSegment = null
            // The row was indexed for a file that will now never be written; without this it
            // sits in the gallery forever as a missing file.
            segmentId?.let(::dropRow)
            scheduleRecovery(token, RecordingFailure.StartRejected)
            return false
        }
        activeRecording = recording
        liveSegments += handle
        publishUnfinalized()
        if (!sessionJournaled) {
            sessionJournaled = true
            journal?.markRecording(startedAt)
        }
        update {
            it.copy(
                status = RecorderStatus.Recording,
                segmentStartedAtEpochMs = startedAt,
                segmentTargetMs = handle.targetMs,
                segmentBytes = 0,
                segmentIndex = index,
                audioEnabled = audio,
                audioMuted = false,
                blockers = emptyList(),
                lastErrorMessage = if (it.status == RecorderStatus.Recovering) null else it.lastErrorMessage,
            )
        }
        return true
    }

    private fun dropRow(segmentId: Long) {
        scope.launch(Dispatchers.IO) { runCatching { segments.deleteById(segmentId) } }
    }

    private fun onRecordEvent(handle: SegmentHandle, event: VideoRecordEvent) {
        when (event) {
            // The recording has actually begun: a queued start waits for the previous file to
            // finalise, so the watchdog measures from here.
            is VideoRecordEvent.Start -> handle.lastProgressAtMs = SystemClock.elapsedRealtime()
            is VideoRecordEvent.Status -> onStatus(handle, event.recordingStats)
            is VideoRecordEvent.Finalize -> {
                handle.finalized.complete(Unit)
                scope.launch { onFinalize(handle, event) }
            }

            else -> Unit
        }
    }

    /**
     * Per-frame progress, on the recorder's thread.
     *
     * CameraX calls this for every encoded frame -- thirty times a second, more with audio -- so
     * it does as little as possible: note the time for the watchdog, publish progress to the UI
     * only every [STATUS_PUBLISH_INTERVAL_MS], and hand a due rollover to the serial dispatcher.
     * Publishing on every frame used to rebuild and re-post the foreground notification thirty
     * times a second for the whole drive, which Android throttles to five a second and drops the
     * rest -- including, sometimes, the final "stopped" update.
     */
    private fun onStatus(handle: SegmentHandle, stats: RecordingStats) {
        // A recording that has been told to stop can still deliver a Status or two while it
        // drains. Those must not update the UI for its successor, and above all must not trip
        // the rollover check again.
        if (handle !== activeSegment) return
        val now = SystemClock.elapsedRealtime()
        handle.lastProgressAtMs = now
        val elapsedMs = stats.recordedDurationNanos / 1_000_000
        handle.recordedMs = elapsedMs

        val muted = stats.audioStats.audioState == AudioStats.AUDIO_STATE_MUTED
        if (muted != handle.publishedMuted || now - handle.publishedAtMs >= STATUS_PUBLISH_INTERVAL_MS) {
            handle.publishedMuted = muted
            handle.publishedAtMs = now
            val bytes = stats.numBytesRecorded
            update { state ->
                if (state.segmentIndex == handle.index) state.copy(segmentBytes = bytes, audioMuted = muted) else state
            }
        }

        if (!handle.healthyReported && elapsedMs >= RecoveryPolicy.HEALTHY_AFTER_MS) {
            handle.healthyReported = true
            scope.launch { onRecordingHealthy(handle) }
        }

        if (handle.rolloverRequested) return
        // Nothing can make a segment roll before MIN_SEGMENT_MS except reaching a shorter target.
        if (elapsedMs < SegmentPlanner.MIN_SEGMENT_MS && elapsedMs < handle.targetMs) return
        val decision = SegmentPlanner.decide(
            elapsedMs = elapsedMs,
            targetSegmentMs = handle.targetMs,
            reconfigurationPending = pendingProfile != null,
            storageCleanupRequired = storageCleanupRequired,
            recorderErrorPending = false,
        )
        if (decision.shouldRoll) {
            handle.rolloverRequested = true
            scope.launch { rollOver(handle) }
        }
    }

    /**
     * Rolls the segment over.
     *
     * With nothing to reconfigure, the next recording is queued straight behind the one being
     * stopped, which the recorder services the instant the previous file finalises. With a
     * profile change pending, the closing file is given a moment to finish before the rebind,
     * because a rebind stops everything bound to the camera.
     */
    private suspend fun rollOver(handle: SegmentHandle) {
        stateMutex.withLock {
            val token = handle.token
            if (!isCurrent(token) || handle !== activeSegment || _state.value.status != RecorderStatus.Recording) {
                return@withLock
            }
            // Re-check with the flags as they are now: a pending change may have been withdrawn.
            val decision = SegmentPlanner.decide(
                elapsedMs = handle.recordedMs,
                targetSegmentMs = handle.targetMs,
                reconfigurationPending = pendingProfile != null,
                storageCleanupRequired = storageCleanupRequired,
                recorderErrorPending = false,
            )
            if (!decision.shouldRoll) {
                handle.rolloverRequested = false
                return@withLock
            }
            Log.i(TAG, "rolling over: ${decision.reason?.label}")
            val next = pendingProfile
            update { it.copy(status = RecorderStatus.RollingOver) }
            closeActiveRecording()
            if (next == null) {
                startSegment(token)
            } else {
                pendingProfile = null
                withTimeoutOrNull(REBIND_FINALIZE_WAIT_MS) { handle.finalized.await() }
                Log.i(TAG, "applying queued profile ${next.label}")
                establish(token, rebind = true, preferred = next)
            }
        }
    }

    /** A restarted recording has run cleanly long enough: the recovery episode is over. */
    private fun onRecordingHealthy(handle: SegmentHandle) {
        if (handle !== activeSegment || !isCurrent(handle.token)) return
        if (recoveryAttempt > 0 || recoveryStartedAtMs != null) {
            Log.i(TAG, "recording is healthy again after $recoveryAttempt recovery attempt(s)")
            resetRecovery()
        }
    }

    private suspend fun onFinalize(handle: SegmentHandle, event: VideoRecordEvent.Finalize) {
        liveSegments.remove(handle)
        val wasActive = handle === activeSegment
        if (wasActive) {
            // Nothing was queued behind this recording: it ended without our stop (the duration
            // backstop, or an error). The session continues below.
            activeSegment = null
            activeRecording = null
        }
        if (event.hasError()) Log.w(TAG, "segment ${handle.index} finalised with error ${event.error}", event.cause)

        // Bookkeeping first, and never allowed to throw: a database or file error here used to
        // escape, skip the code below that keeps the loop going, and leave the state saying
        // "recording" with nothing recording.
        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
        val kept = runCatchingNonCancellation { indexFinalisedSegment(handle, event, durationMs) } ?: false
        // Only now does the clip stop holding the wake lock: it is on the disk and in the index.
        publishUnfinalized()
        update {
            it.copy(
                sessionDurationMs = it.sessionDurationMs + if (kept) durationMs else 0L,
                sessionSegmentCount = it.sessionSegmentCount + if (kept) 1 else 0,
            )
        }
        runCatchingNonCancellation { maintainStorage() }

        if (!wasActive) {
            // A rollover or stop already moved on; a successor reports its own problems.
            if (event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) storageCleanupRequired = true
            return
        }
        val token = handle.token
        if (!isCurrent(token)) return
        stateMutex.withLock {
            if (!isCurrent(token) || activeSegment != null) return@withLock
            val status = _state.value.status
            if (status != RecorderStatus.Recording && status != RecorderStatus.RollingOver) return@withLock
            when (event.error) {
                VideoRecordEvent.Finalize.ERROR_NONE,
                VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
                VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                -> if (durationMs >= SHORT_UNPROMPTED_SEGMENT_MS) {
                    startSegment(token)
                } else {
                    Log.w(TAG, "segment ${handle.index} ended by itself after $durationMs ms")
                    scheduleRecovery(token, RecordingFailure.EncoderFailed)
                }

                else -> handleFinalizeError(token, event.error)
            }
        }
    }

    /**
     * Indexes what a finished recording produced.
     *
     * A file the recorder finalised normally, or cut at one of its own limits, is trusted as it
     * is. After any other error the file is *inspected* rather than written off: CameraX's own
     * documentation says a recording that ran out of storage part-way still produces a valid
     * file, and discarding a playable file on the strength of an error code is exactly the loss
     * this app exists to prevent. Only a file that genuinely cannot be played is quarantined.
     *
     * @return true when the file was kept as a segment.
     */
    private suspend fun indexFinalisedSegment(
        handle: SegmentHandle,
        event: VideoRecordEvent.Finalize,
        durationMs: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val file = handle.file
        val exists = file.exists() && file.length() > 0
        val trusted = !event.hasError() || event.error in TRUSTED_FINALIZE_ERRORS
        val usable = exists && (trusted || Mp4Inspector.inspect(file).isUsable)
        // Onto the medium before the row says "complete": the muxer leaves the clip's end, index
        // included, in the write cache, and a flat battery within the next half minute would
        // otherwise take it. See StorageManager.flushToDisk.
        if (usable) storage.flushToDisk(file)
        val segmentId = handle.segmentId ?: return@withContext usable
        if (usable) {
            val location = locationEngine.state.value
            segments.markComplete(
                id = segmentId,
                durationMs = durationMs,
                sizeBytes = file.length(),
                endLatitude = location.latitude?.takeIf { location.hasPosition },
                endLongitude = location.longitude?.takeIf { location.hasPosition },
            )
            (handle.tripId ?: activeTripId)?.let { tripId ->
                runCatching { trips.onSegmentFinalised(tripId, handle.startedAtEpochMs + durationMs, location) }
                    .onFailure { Log.w(TAG, "could not advance trip $tripId", it) }
            }
            runCatching {
                protection.onSegmentFinalised(
                    SegmentTiming(segmentId, handle.startedAtEpochMs, durationMs),
                    file.name,
                )
            }.onFailure { Log.w(TAG, "could not offer segment $segmentId to open events", it) }
            true
        } else {
            // Nothing usable: quarantine rather than delete, and drop the row.
            if (file.exists()) storage.quarantine(file)
            segments.deleteById(segmentId)
            false
        }
    }

    /** Classifies a finalise error on the active recording. Caller holds [stateMutex]. */
    private suspend fun handleFinalizeError(token: Long, error: Int) {
        when (error) {
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> {
                storageCleanupRequired = true
                runCatchingNonCancellation { makeRoom() }
                scheduleRecovery(token, RecordingFailure.StorageFull)
            }

            // The camera closed under the recording: another app, or an error CameraX is
            // recovering from. Wait for it rather than rebinding over CameraX's own reopen.
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> scheduleRecovery(token, RecordingFailure.CameraLost)

            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ->
                scheduleRecovery(token, RecordingFailure.StorageUnavailable)

            // ERROR_ENCODING_FAILED, ERROR_RECORDER_ERROR, ERROR_NO_VALID_DATA, ERROR_UNKNOWN and
            // anything newer: rebuild the camera session and try again.
            else -> scheduleRecovery(token, RecordingFailure.EncoderFailed)
        }
    }

    // ── Recovery ──────────────────────────────────────────────────────────────────────────────

    /**
     * Moves the session to [RecorderStatus.Recovering] and schedules the next attempt.
     *
     * Never ends the session: see [RecoveryPolicy] for why, and for the schedule.
     */
    private fun scheduleRecovery(token: Long, failure: RecordingFailure) {
        if (!isCurrent(token)) return
        recoveryAttempt++
        val now = SystemClock.elapsedRealtime()
        val since = recoveryStartedAtMs ?: now.also { recoveryStartedAtMs = it }
        recoveryFailure = failure
        if (failure.needsRebind || RecoveryPolicy.forcesRebind(recoveryAttempt)) rebindOnRecovery = true
        val delayMs = RecoveryPolicy.delayFor(recoveryAttempt)
        Log.w(TAG, "recording interrupted (${failure.name}); attempt $recoveryAttempt in $delayMs ms")
        update {
            it.copy(
                status = RecorderStatus.Recovering,
                lastErrorMessage = failure.message,
                blockers = listOfNotNull(cameraBlocker, failure.blocker).distinct(),
                recoveringSinceElapsedMs = since,
                segmentStartedAtEpochMs = null,
            )
        }
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(delayMs)
            recover(token)
        }
    }

    private suspend fun recover(token: Long) {
        if (!isCurrent(token)) return
        stateMutex.withLock {
            // This attempt has begun, and from here it runs to completion. Cancelling it part-way
            // -- the camera reopening while it binds, say -- would leave the camera bound to a
            // configuration the controller has no record of, and leak that binding's overlay
            // thread. A newer request now queues behind it instead of replacing it.
            if (recoveryJob === currentCoroutineContext()[Job]) recoveryJob = null
            if (!isCurrent(token) || _state.value.status != RecorderStatus.Recovering) return@withLock
            // Keep the CPU awake for the attempt itself, even once recovery has gone quiet; the
            // next tick lets it sleep again if the attempt fails.
            if (_state.value.recoveryIdle) update { it.copy(recoveryIdle = false) }
            val failure = recoveryFailure
            if (failure?.isStorage == true) {
                val ready = runCatchingNonCancellation {
                    makeRoom() && withContext(Dispatchers.IO) { storage.layout.isWritable }
                } ?: false
                if (!ready) {
                    scheduleRecovery(token, failure)
                    return@withLock
                }
            }
            val rebind = rebindOnRecovery || boundProfile == null || !cameraSession.isBound
            if (!rebind && !cameraSession.isCameraOpen) {
                // Still bound, still closed: another app has the camera, or CameraX is reopening
                // it. Starting now would finalise at once with nothing written. The camera
                // reopening triggers the next attempt straight away (onCameraReopened).
                scheduleRecovery(token, RecordingFailure.CameraLost)
                return@withLock
            }
            rebindOnRecovery = false
            Log.i(TAG, "recovery attempt $recoveryAttempt (rebind=$rebind)")
            establish(token, rebind)
        }
    }

    /**
     * The camera opened again while waiting for it: resume now rather than at the next retry.
     *
     * This replaces a retry that is still waiting out its delay. An attempt already under way is
     * never cancelled (see [recover]); this one queues behind it and finds nothing to do if that
     * attempt succeeded, or is replaced by the retry it schedules if it failed.
     */
    private fun onCameraReopened() {
        if (_state.value.status != RecorderStatus.Recovering) return
        // Only while waiting for the camera itself. After a failure that needs a rebind, the
        // camera opening is the rebind's own doing, and following it would skip the backoff.
        if (recoveryFailure != RecordingFailure.CameraLost || rebindOnRecovery) return
        val token = session.get()
        Log.i(TAG, "camera is available again; resuming")
        recoveryJob?.cancel()
        recoveryJob = scope.launch { recover(token) }
    }

    private fun resetRecovery() {
        cancelRecoveryJob()
        recoveryAttempt = 0
        recoveryStartedAtMs = null
        recoveryFailure = null
        rebindOnRecovery = false
        update { it.copy(recoveringSinceElapsedMs = null, recoveryOverdue = false, recoveryIdle = false) }
    }

    private fun cancelRecoveryJob() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    /**
     * Called every tick: publishes when recovery is overdue, and when it may let the CPU sleep.
     *
     * Both apply only while actually reconnecting. Once a segment is running again the episode
     * stays open until the recording proves healthy, but nothing is interrupted any more, so the
     * driver must not be told it is.
     */
    private fun reviewRecovery() {
        val since = recoveryStartedAtMs ?: return
        val recoveringFor = SystemClock.elapsedRealtime() - since
        val reconnecting = _state.value.status == RecorderStatus.Recovering
        val overdue = reconnecting && RecoveryPolicy.isOverdue(recoveringFor)
        val idle = reconnecting && !RecoveryPolicy.holdsWakeLock(recoveringFor)
        val current = _state.value
        if (current.recoveryOverdue != overdue || current.recoveryIdle != idle) {
            update { it.copy(recoveryOverdue = overdue, recoveryIdle = idle) }
        }
    }

    /**
     * Forgets recordings that were told to stop long ago and never reported back. Their Finalize
     * is not coming -- the camera stack that owed it has been rebuilt or has died -- and waiting
     * for it would keep the wake lock held with nothing being recorded. A Finalize that does turn
     * up later is still indexed as usual.
     */
    private fun pruneAbandonedSegments() {
        if (liveSegments.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val abandoned = liveSegments.filter { handle ->
            val stoppedAt = handle.stopRequestedAtMs
            stoppedAt != null && now - stoppedAt >= FINALIZE_ABANDON_MS
        }
        if (abandoned.isEmpty()) return
        Log.w(TAG, "${abandoned.size} recording(s) never finalised; no longer waiting for them")
        liveSegments.removeAll(abandoned.toSet())
        publishUnfinalized()
    }

    /**
     * Catches the failure that reports nothing: a recording whose frames simply stop.
     *
     * CameraX reports progress with every encoded frame. If none has arrived for
     * [STALL_TIMEOUT_MS] while the state says recording -- a wedged camera HAL, a queued start
     * whose predecessor never finalised -- the recorder is stopped and rebuilt. Without this the
     * notification would say "recording" for the rest of the drive while nothing was written.
     */
    private suspend fun watchdogLoop() {
        while (currentCoroutineContext().isActive) {
            delay(WATCHDOG_INTERVAL_MS)
            val handle = activeSegment ?: continue
            if (_state.value.status != RecorderStatus.Recording) continue
            val silentForMs = SystemClock.elapsedRealtime() - handle.lastProgressAtMs
            if (silentForMs >= STALL_TIMEOUT_MS) onStall(handle, silentForMs)
        }
    }

    private suspend fun onStall(handle: SegmentHandle, silentForMs: Long) {
        stateMutex.withLock {
            if (handle !== activeSegment || !isCurrent(handle.token)) return@withLock
            if (_state.value.status != RecorderStatus.Recording) return@withLock
            Log.w(TAG, "no frames for $silentForMs ms; restarting the recorder")
            closeActiveRecording()
            scheduleRecovery(handle.token, RecordingFailure.Stalled)
        }
    }

    // ── Reactions ─────────────────────────────────────────────────────────────────────────────

    private suspend fun onSettings(settings: Settings) {
        val previous = lastSettings
        lastSettings = settings
        if (settings.eventSensitivity != previous.eventSensitivity) {
            impactDetector = ImpactDetector(
                sensitivity = settings.eventSensitivity,
                hasGyroscope = _capabilities.value?.sensors?.hasGyroscope ?: false,
            )
        }
        // Only a running session holds these. Claiming GNSS or the sensors here while idle would
        // leave them running until some later recording happened to release them.
        if (peripheralsRunning) {
            if (settings.locationEnabled != previous.locationEnabled) {
                if (settings.locationEnabled) {
                    locationEngine.request(LocationEngine.Client.Recorder, effectivePlan().locationIntervalMs)
                } else {
                    locationEngine.release(LocationEngine.Client.Recorder)
                }
            }
            if (settings.eventDetectionEnabled != previous.eventDetectionEnabled) {
                if (settings.eventDetectionEnabled) startSensors() else stopSensors()
            }
        }
        if (settings.recordingZoom != previous.recordingZoom && cameraSession.isBound) {
            cameraSession.setRecordingZoom(settings.recordingZoom)
        }
        if (settings.saveGpxTrack != previous.saveGpxTrack || settings.locationEnabled != previous.locationEnabled) {
            applyTrackSetting(settings)
        }
        requeueProfileIfNeeded()
    }

    // ── Trips and tracks ──────────────────────────────────────────────────────────────────────

    /**
     * Opens or continues the trip this session records into, and its track when the user wants one.
     *
     * Everything here is best effort: a failure is logged and recording proceeds without a trip,
     * because the footage matters more than its label.
     */
    private suspend fun openTrip(settings: Settings) {
        val trip = runCatchingNonCancellation {
            trips.openOrContinue(System.currentTimeMillis(), locationEngine.state.value)
        } ?: return
        activeTripId = trip.id
        if (settings.saveGpxTrack && settings.locationEnabled) openTrack(trip)
    }

    private suspend fun openTrack(trip: TripEntity) {
        val file = runCatchingNonCancellation {
            withContext(Dispatchers.IO) {
                trip.trackFileName?.let { storage.trackFile(it) } ?: storage.createTrackFile(trip.startedAtEpochMs)
            }
        } ?: return
        val opened = trackRecorder.open(
            target = file,
            trackName = provisionalTrackName(trip.startedAtEpochMs),
            existingPoints = trip.trackPointCount,
            existingDistanceMetres = trip.distanceMetres,
        )
        if (opened) {
            runCatching { trips.setTrackFile(trip.id, file.name) }
                .onFailure { Log.w(TAG, "could not record the track file for trip ${trip.id}", it) }
        }
    }

    /** Reacts to the GPX switch, or location, changing while a recording is running. */
    private suspend fun applyTrackSetting(settings: Settings) {
        val tripId = activeTripId ?: return
        val wanted = settings.saveGpxTrack && settings.locationEnabled
        if (wanted && !trackRecorder.isOpen) {
            val trip = runCatching { trips.byId(tripId) }
                .onFailure { Log.w(TAG, "could not read trip $tripId", it) }
                .getOrNull() ?: return
            openTrack(trip)
        } else if (!wanted && trackRecorder.isOpen) {
            val summary = trackRecorder.close()
            runCatching { trips.recordTrack(tripId, summary) }
                .onFailure { Log.w(TAG, "could not record the track for trip $tripId", it) }
        }
    }

    /**
     * Closes the session's trip: the track is finished, the row closed and named, and the track
     * file renamed after the trip so a map app shows "Harrison → Braddon" rather than a timestamp.
     */
    private suspend fun closeTrip() {
        val tripId = activeTripId ?: return
        activeTripId = null
        runCatchingNonCancellation {
            val summary = trackRecorder.close()
            val closed = trips.close(tripId, summary, locationEngine.state.value, System.currentTimeMillis())
                ?: return@runCatchingNonCancellation
            val trackName = closed.trackFileName ?: return@runCatchingNonCancellation
            val label = trips.labelFor(closed, provisionalTrackName(closed.startedAtEpochMs))
            withContext(Dispatchers.IO) {
                GpxWriter.rename(storage.trackFile(trackName), "${label.title} · ${trackDate(closed.startedAtEpochMs)}")
            }
        }
    }

    private fun provisionalTrackName(startedAtEpochMs: Long): String = "Roadguard trip ${trackDate(startedAtEpochMs)}"

    private fun trackDate(epochMs: Long): String =
        SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

    private fun onThermalReading(reading: ThermalReading) {
        val level = thermalPolicy.accept(reading)
        val plan = ThermalPolicy.planFor(level)
        if (plan == _thermalPlan.value) return
        _thermalPlan.value = plan
        update { it.copy(thermalLevel = level) }
        locationEngine.setRecorderInterval(effectivePlan().locationIntervalMs)
        requeueProfileIfNeeded()
    }

    /**
     * Recomputes the profile and queues it if it differs from the bound one.
     *
     * Queued, not applied: see the class documentation on why reconfiguration only ever happens
     * at a segment boundary. A profile the camera has already refused this session is not queued
     * again.
     */
    private fun requeueProfileIfNeeded() {
        val probed = _capabilities.value ?: return
        val bound = boundProfile ?: return
        val next = selectProfile(probed)
        pendingProfile = if (next.requiresRebindFrom(bound) && next.bindKey !in unbindableProfiles) {
            Log.i(TAG, "queued profile change ${bound.label} -> ${next.label}")
            next
        } else {
            null
        }
    }

    private fun onPowerTransition(transition: PowerTransition?) {
        transition ?: return
        powerMonitor.consumeTransition()
        val settings = lastSettings
        val active = _state.value.isSessionActive
        when (transition) {
            is PowerTransition.Connected -> {
                // Power is back: a delayed stop scheduled for the disconnect no longer applies.
                scheduledStopJob?.cancel()
                scheduledStopJob = null
                if (PowerPolicy.onPowerConnected(settings) == PowerAction.StartRecording && !active) start()
            }

            is PowerTransition.Disconnected -> when (val action = PowerPolicy.onPowerDisconnected(settings)) {
                PowerAction.StopRecording -> if (active) stop()
                is PowerAction.StopAfter -> if (active) scheduleStop(action.seconds)
                // Battery-safe mode follows the power state through reviewBatterySafe().
                else -> Unit
            }
        }
    }

    private fun onBatteryState(state: PowerState) {
        if (!_state.value.isSessionActive) return
        when (val action = PowerPolicy.evaluateBattery(state, lastSettings)) {
            is PowerAction.StopForLowBattery -> requestStop(
                RecorderStatus.Failed,
                "Battery is at ${action.batteryPercent}%. Recording stopped so the last clip is saved cleanly.",
                RecordingBlocker.LowBattery,
            )

            else -> Unit
        }
    }

    /** Called every tick: follows battery-safe mode through its debounce. */
    private fun reviewBatterySafe() {
        if (!_state.value.isSessionActive) return
        val wanted = PowerPolicy.batterySafe(powerMonitor.state.value, lastSettings)
        if (!batterySafeGate.accept(wanted, SystemClock.elapsedRealtime())) return
        val on = batterySafeGate.applied
        Log.i(TAG, if (on) "battery-safe mode on" else "battery-safe mode off")
        update { it.copy(batterySafe = on) }
        locationEngine.setRecorderInterval(effectivePlan().locationIntervalMs)
        requeueProfileIfNeeded()
    }

    private fun scheduleStop(seconds: Int) {
        scheduledStopJob?.cancel()
        scheduledStopJob = scope.launch {
            delay(seconds * 1_000L)
            // stop() runs the stop in a coroutine of its own: this job is cancelled by the stop,
            // and must not take the stop down with it.
            if (!powerMonitor.state.value.isOnExternalPower && _state.value.isSessionActive) stop()
        }
    }

    private fun onCameraError(error: CameraState.StateError?) {
        val blocker = if (error == null) null else when (error.code) {
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                RecordingBlocker.CameraInUse

            CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
                RecordingBlocker.CameraDisabled

            CameraState.ERROR_CAMERA_FATAL_ERROR, CameraState.ERROR_CAMERA_REMOVED ->
                RecordingBlocker.CameraFatal

            else -> null
        }
        // CameraX does not retry these by itself; the next recovery attempt must rebuild the session.
        if (error != null && error.code in REBIND_CAMERA_ERRORS) rebindOnRecovery = true
        if (blocker == cameraBlocker) return
        val previous = cameraBlocker
        cameraBlocker = blocker
        val state = _state.value
        when {
            state.status == RecorderStatus.Recovering ->
                update { it.copy(blockers = listOfNotNull(blocker, recoveryFailure?.blocker).distinct()) }

            blocker != null && state.isSessionActive ->
                update { it.copy(blockers = listOf(blocker), lastErrorMessage = blocker.message) }

            // The camera recovered by itself: stop telling the driver about a problem that has gone.
            blocker == null && previous != null ->
                update {
                    it.copy(
                        blockers = it.blockers - previous,
                        lastErrorMessage = it.lastErrorMessage.takeUnless { message -> message == previous.message },
                    )
                }
        }
        // Recovery itself is driven by what the camera error does to the recording -- a
        // finalise with no source, or the watchdog -- so it cannot be triggered twice.
    }

    private fun onOverlayFailure() {
        // Burn-in failed. Drop the effect at the next boundary and keep recording; the on-screen
        // overlay still works, and the recording is what matters.
        Log.w(TAG, "disabling overlay burn-in after an effect error")
        scope.launch {
            if (overlayBroken) return@launch
            overlayBroken = true
            requeueProfileIfNeeded()
            update { it.copy(lastErrorMessage = "Overlays could not be added to the video; recording continues") }
        }
    }

    private suspend fun applyPreviewRotation(rotation: Int) {
        if (!cameraSession.isBound) return
        withContext(Dispatchers.Main) { cameraSession.updatePreviewRotation(rotation) }
    }

    private fun onSensorSample(sample: SensorSample) {
        if (!lastSettings.eventDetectionEnabled) return
        brakeDetector.onSample(sample)
        val detected = impactDetector.onSample(sample) { motionContext() } ?: return
        if (!detected.accepted) return
        scope.launch {
            val result = protection.protect(
                kind = detected.kind,
                atEpochMs = System.currentTimeMillis(),
                preSeconds = lastSettings.preEventSeconds,
                postSeconds = lastSettings.postEventSeconds,
                confidence = detected.confidence,
                detection = detected,
                inProgress = currentSegmentTiming(),
            )
            update { it.copy(lastProtectionMessage = result.message()) }
            delay(PROTECT_MESSAGE_MS)
            update { it.copy(lastProtectionMessage = null) }
        }
    }

    /**
     * Feeds the brake detector one filtered speed per fix.
     *
     * [LocationEngine.state] re-emits on ticks and satellite counts; deduplicating on the fix
     * timestamp keeps the detector's slope window honest. A held speed expiring to null is fed
     * through so the indicator goes out rather than freezing on.
     */
    private suspend fun onLocationState(location: LocationState) {
        // The track recorder filters and deduplicates for itself; this is a cheap volatile read
        // when no track is open, which is the case between sessions.
        if (trackRecorder.isOpen) trackRecorder.accept(location)
        val speed = location.speedMetresPerSecond
        if (speed == null) {
            if (lastBrakeFixEpochMs != null) {
                lastBrakeFixEpochMs = null
                brakeDetector.onSpeed(null, SystemClock.elapsedRealtime())
            }
        } else {
            val fixEpochMs = location.fixEpochMs ?: return
            if (fixEpochMs == lastBrakeFixEpochMs) return
            lastBrakeFixEpochMs = fixEpochMs
            brakeDetector.onSpeed(speed, SystemClock.elapsedRealtime())
        }
        refreshBrakeLevel()
    }

    /**
     * Recomputes the brake level; a change republishes the overlay immediately rather than
     * waiting out the once-a-second refresh, because braking is over in a couple of seconds.
     * Publishing is an [java.util.concurrent.atomic.AtomicReference] set -- rasterisation still
     * only happens when the content changed.
     */
    private fun refreshBrakeLevel() {
        val next = brakeDetector.level(SystemClock.elapsedRealtime())
        if (next != brakeLevel) {
            brakeLevel = next
            publishOverlay()
        }
    }

    private fun motionContext(): MotionContext {
        val location = locationEngine.state.value
        val speedKmh = location.speedMetresPerSecond?.times(3.6f)
        return MotionContext(
            speedBeforeKmh = speedKmh,
            speedAfterKmh = speedKmh,
            latitude = location.latitude,
            longitude = location.longitude,
        )
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────────────────────

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            locationEngine.tick()
            // The tick is also what lets the brake light go out after a hold or a GNSS loss,
            // when no new fix arrives to trigger the recomputation.
            refreshBrakeLevel()
            reviewRecovery()
            reviewBatterySafe()
            pruneAbandonedSegments()
            delay(TICK_MS)
        }
    }

    /**
     * Deletes loop footage when the loop is over its budget.
     *
     * @return whether the volume has room to record afterwards.
     */
    private suspend fun makeRoom(): Boolean {
        var assessment = storage.refresh(lastSettings.loopBudgetBytes)
        if (assessment.needsCleanup) {
            val outcome = storage.runCleanup(assessment)
            Log.i(TAG, "loop cleanup freed ${outcome.bytesFreed} bytes in ${outcome.filesDeleted} files")
            assessment = storage.refresh(lastSettings.loopBudgetBytes)
        }
        storageCleanupRequired = assessment.needsCleanup
        return assessment.canRecord
    }

    private suspend fun maintainStorage() {
        val canRecord = makeRoom()
        val assessment = storage.assessment.value
        if (!canRecord || assessment?.state == StorageState.Critical) {
            update { it.copy(blockers = (it.blockers + RecordingBlocker.StorageFull).distinct()) }
        }
    }

    private fun publishOverlay() {
        val effect = overlayEffect ?: return
        val content = overlayComposer.compose(
            settings = lastSettings,
            location = locationEngine.state.value,
            weather = (weatherState.value as? WeatherState.Available)?.snapshot,
            nowEpochMs = System.currentTimeMillis(),
            protectedLabel = _state.value.lastProtectionMessage?.let { "PROTECTED" },
            brake = brakeLevel,
        )
        effect.update(content)
    }

    private fun currentSegmentTiming(): SegmentTiming? {
        val segment = activeSegment ?: return null
        val id = segment.segmentId ?: return null
        return SegmentTiming(
            id = id,
            startedAtEpochMs = segment.startedAtEpochMs,
            durationMs = (System.currentTimeMillis() - segment.startedAtEpochMs).coerceAtLeast(0),
            isInProgress = true,
        )
    }

    private fun locationForMetadata(): Location? {
        val state = locationEngine.state.value
        val latitude = state.latitude ?: return null
        val longitude = state.longitude ?: return null
        return Location("roadguard").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
    }

    private fun evaluateBlockers(settings: Settings): List<BlockerCheck> = buildList {
        if (!hasCameraPermission()) add(BlockerCheck(RecordingBlocker.CameraPermission, true))
        if (settings.microphoneEnabled && !hasMicrophonePermission()) {
            add(BlockerCheck(RecordingBlocker.MicrophonePermission, false))
        }
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun isCurrent(token: Long): Boolean = session.get() == token

    /** Atomic: the recorder's thread publishes progress while the serial dispatcher changes status. */
    private fun update(transform: (RecordingUiState) -> RecordingUiState) {
        _state.update(transform)
    }

    /** Like [runCatching], but never swallows cancellation. Returns null on failure. */
    private suspend inline fun <T> runCatchingNonCancellation(crossinline block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.e(TAG, "recoverable failure in the recording loop", error)
        null
    }

    /** A blocker plus whether it actually prevents recording, as opposed to degrading it. */
    private data class BlockerCheck(val blocker: RecordingBlocker, val blocksRecording: Boolean)

    /**
     * The identity of one recording, captured by that recording's event callback closure.
     *
     * The rollover path stops a recording and starts the next one back to back, so events from
     * the two overlap: the old recording's Finalize (and sometimes a last Status) arrives after
     * the new segment's row has been indexed. Each callback carrying its own handle is what lets
     * a finalise update the row it actually recorded into, rather than whichever row happens to
     * be newest -- misattributing it deleted the new row, left the finished one marked
     * incomplete, and sent perfectly good footage to quarantine on the next start.
     */
    private class SegmentHandle(
        val token: Long,
        val index: Long,
        val segmentId: Long?,
        val startedAtEpochMs: Long,
        val file: File,
        val tripId: Long?,
        val targetMs: Long,
        startedAtElapsedMs: Long,
    ) {
        /** Completed on the recorder's thread when the file has been finalised. */
        val finalized = CompletableDeferred<Unit>()

        /** Last sign of life from the recorder, for the watchdog. */
        @Volatile
        var lastProgressAtMs: Long = startedAtElapsedMs

        @Volatile
        var recordedMs: Long = 0L

        @Volatile
        var rolloverRequested: Boolean = false

        /** When the controller told this recording to stop; see [pruneAbandonedSegments]. */
        @Volatile
        var stopRequestedAtMs: Long? = null

        // Recorder-thread only.
        var publishedAtMs: Long = 0L
        var publishedMuted: Boolean = false
        var healthyReported: Boolean = false
    }

    /** Storage assessment, republished for the UI. */
    val storageAssessment: StateFlow<StorageAssessment?> get() = storage.assessment

    companion object {
        private const val TAG = "RoadguardRecorder"

        /** Overlay content is regenerated once a second; the clock is the fastest field. */
        const val OVERLAY_UPDATE_MS = 1_000L

        /** Cadence for staleness housekeeping (GNSS age, held speed expiry, recovery review). */
        const val TICK_MS = 1_000L

        /** How long a protection confirmation stays on screen. */
        const val PROTECT_MESSAGE_MS = 4_000L

        /** Extra time allowed before the recorder's own duration backstop fires. */
        const val SEGMENT_LIMIT_GRACE_MS = 15_000L

        /** How often per-segment progress reaches the UI; see [onStatus]. */
        const val STATUS_PUBLISH_INTERVAL_MS = 5_000L

        /** How often the watchdog looks for a stalled recording. */
        const val WATCHDOG_INTERVAL_MS = 5_000L

        /**
         * How long a recording may go without a single frame before it counts as stalled.
         * Frames normally arrive thirty times a second; the longest legitimate pause is a
         * segment's start waiting for its predecessor to finalise, well under this.
         */
        const val STALL_TIMEOUT_MS = 15_000L

        /** How long a stop waits for the closing file before unbinding the camera. */
        const val STOP_FINALIZE_TIMEOUT_MS = 4_000L

        /** After unbinding, how long to wait for recordings the unbind itself ended. */
        const val UNBIND_FINALIZE_WAIT_MS = 2_000L

        /** How long a rollover that rebinds waits for the closing file first. */
        const val REBIND_FINALIZE_WAIT_MS = 3_000L

        /**
         * How long after being told to stop a recording may still hold the wake lock waiting for
         * its Finalize. Finalising takes well under a second; this is only for one that never
         * reports at all.
         */
        const val FINALIZE_ABANDON_MS = 60_000L

        /**
         * A recording that ends by itself -- not stopped by the controller -- sooner than this is
         * treated as a failure, so a recorder that finalises every start at once goes through
         * the recovery backoff instead of spinning the segment loop.
         */
        const val SHORT_UNPROMPTED_SEGMENT_MS = 3_000L

        /** Finalise errors whose file is complete and playable by CameraX's own contract. */
        private val TRUSTED_FINALIZE_ERRORS = setOf(
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        )

        /** Camera errors CameraX does not recover from by itself without a rebind. */
        private val REBIND_CAMERA_ERRORS = setOf(
            CameraState.ERROR_STREAM_CONFIG,
            CameraState.ERROR_CAMERA_FATAL_ERROR,
        )
    }
}
