package io.github.tunlezah.roadguard.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
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
import io.github.tunlezah.roadguard.event.EventSensorSource
import io.github.tunlezah.roadguard.event.ImpactDetector
import io.github.tunlezah.roadguard.event.MotionContext
import io.github.tunlezah.roadguard.event.ProtectionCoordinator
import io.github.tunlezah.roadguard.event.SegmentTiming
import io.github.tunlezah.roadguard.location.LocationEngine
import io.github.tunlezah.roadguard.overlay.OverlayComposer
import io.github.tunlezah.roadguard.overlay.VideoOverlayEffect
import io.github.tunlezah.roadguard.power.PowerAction
import io.github.tunlezah.roadguard.power.PowerMonitor
import io.github.tunlezah.roadguard.power.PowerPolicy
import io.github.tunlezah.roadguard.power.PowerTransition
import io.github.tunlezah.roadguard.settings.CameraFacing
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.SettingsRepository
import io.github.tunlezah.roadguard.storage.StorageAssessment
import io.github.tunlezah.roadguard.storage.StorageBucket
import io.github.tunlezah.roadguard.storage.StorageManager
import io.github.tunlezah.roadguard.storage.StorageState
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.thermal.ThermalPlan
import io.github.tunlezah.roadguard.thermal.ThermalPolicy
import io.github.tunlezah.roadguard.thermal.ThermalSource
import io.github.tunlezah.roadguard.weather.WeatherState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
 * A recording is stopped and the next one started immediately, on the same thread. CameraX's
 * `Recorder` explicitly queues a start issued while it is stopping and services it when the
 * previous recording finalises, which is the smallest gap the stable API offers. A small gap is
 * unavoidable -- the video encoder is stopped and the next segment needs a fresh keyframe -- and
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
 * ### Failure
 *
 * Every recorder error is classified. Recoverable ones (storage, encoder, source inactive) start
 * a backoff-limited restart; unrecoverable ones stop recording, say why, and leave the last valid
 * segment intact. There is no unbounded restart loop: [MAX_CONSECUTIVE_FAILURES] consecutive
 * failures stop the loop and surface a blocker instead.
 */
class RecordingController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val cameraSession: CameraSession,
    private val capabilityProbe: DeviceCapabilityProbe,
    private val storage: StorageManager,
    private val segments: SegmentDao,
    private val protection: ProtectionCoordinator,
    private val locationEngine: LocationEngine,
    private val sensorSource: EventSensorSource,
    private val thermalSource: ThermalSource,
    private val powerMonitor: PowerMonitor,
    private val orientationTracker: CameraOrientationTracker,
    private val overlayComposer: OverlayComposer,
    private val weatherState: StateFlow<WeatherState>,
) {
    private val recorderExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "roadguard-recorder").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val stateMutex = Mutex()
    private val thermalPolicy = ThermalPolicy()

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

    private var lifecycleOwner: LifecycleOwner? = null
    private var overlayEffect: VideoOverlayEffect? = null
    private var activeRecording: Recording? = null
    private var activeSegmentId: Long? = null
    private var activeSegmentStartedAtEpochMs: Long = 0L
    private var activeSegmentFileName: String? = null
    private var sequence: Long = 0L
    private var boundProfile: RecordingProfile? = null
    private var pendingProfile: RecordingProfile? = null
    private var rebindPending = false
    private var stopRequested = false
    private var consecutiveFailures = 0
    private var storageCleanupRequired = false
    private var impactDetector = ImpactDetector()
    private var overlayJob: Job? = null
    private var sensorJob: Job? = null
    private var supervisionJob: Job? = null
    private var scheduledStopJob: Job? = null
    private var lastSettings: Settings = Settings()

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────

    /** Called by [RecordingService] once it is a foreground service and can own the camera. */
    fun attach(owner: LifecycleOwner) {
        lifecycleOwner = owner
        powerMonitor.start()
        thermalSource.start()
        orientationTracker.start()
        startSupervision()
    }

    fun detach() {
        supervisionJob?.cancel()
        overlayJob?.cancel()
        sensorJob?.cancel()
        scheduledStopJob?.cancel()
        orientationTracker.stop()
        thermalSource.stop()
        powerMonitor.stop()
        lifecycleOwner = null
    }

    private fun startSupervision() {
        supervisionJob?.cancel()
        supervisionJob = scope.launch {
            launch { settingsRepository.settings.collect { onSettings(it) } }
            launch { thermalSource.reading.collect { onThermalReading(it) } }
            launch { powerMonitor.transitions.collect { onPowerTransition(it) } }
            launch { powerMonitor.state.collect { onBatteryState(it) } }
            launch { cameraSession.cameraError.collect { onCameraError(it) } }
            launch { tickLoop() }
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
        scope.launch { startInternal(delaySeconds) }
    }

    fun stop() {
        scope.launch { stopInternal(RecorderStatus.Stopping) }
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

    /** Detaches or reattaches the preview surface, for screen-off and thermal relief. */
    fun setPreviewEnabled(enabled: Boolean) = cameraSession.setPreviewEnabled(enabled)

    // ── Start / stop ──────────────────────────────────────────────────────────────────────────

    private suspend fun startInternal(delaySecondsOverride: Int?) = stateMutex.withLock {
        if (_state.value.isRecording || _state.value.status == RecorderStatus.Starting) return
        val owner = lifecycleOwner ?: run {
            update { it.copy(status = RecorderStatus.Failed, lastErrorMessage = "Recording service is not running") }
            return
        }

        val settings = settingsRepository.settings.first().also { lastSettings = it }
        val blockers = evaluateBlockers(settings)
        if (blockers.any { it.blocksRecording }) {
            update { it.copy(status = RecorderStatus.Idle, blockers = blockers.map { blocker -> blocker.blocker }) }
            return
        }
        update { it.copy(status = RecorderStatus.Starting, blockers = emptyList(), lastErrorMessage = null) }

        storage.useVolume(settings.storageVolumeId)
        val assessment = storage.refresh(settings.loopBudgetBytes)
        if (!assessment.canRecord) {
            update {
                it.copy(
                    status = RecorderStatus.Idle,
                    blockers = listOf(RecordingBlocker.StorageFull),
                )
            }
            return
        }

        if (cameraSession.initialise().isFailure) {
            update { it.copy(status = RecorderStatus.Failed, lastErrorMessage = "The camera could not be opened") }
            return
        }

        val probed = _capabilities.value ?: capabilityProbe.probe().also { probed ->
            _capabilities.value = probed
            _tier.value = DeviceTierScorer.score(probed)
        }
        val assessed = _tier.value ?: DeviceTierScorer.score(probed).also { _tier.value = it }

        val profile = RecordingProfileSelector.select(probed, assessed, settings, _thermalPlan.value)
        impactDetector = ImpactDetector(
            sensitivity = settings.eventSensitivity,
            hasGyroscope = probed.sensors.hasGyroscope,
        )

        // The start-up delay lets the camera settle and gives the driver a moment to seat the
        // phone. It is counted down visibly so a user never wonders whether Roadguard is stuck.
        val delaySeconds = delaySecondsOverride ?: settings.startupDelaySeconds
        for (remaining in delaySeconds downTo 1) {
            update { it.copy(startupCountdownSeconds = remaining) }
            delay(1_000)
            if (stopRequested) {
                stopRequested = false
                update { it.copy(status = RecorderStatus.Idle, startupCountdownSeconds = null) }
                return
            }
        }
        update { it.copy(startupCountdownSeconds = null) }

        if (!bindCamera(owner, settings, profile)) return

        startPeripherals(settings)
        startSegment(settings, profile)
    }

    private suspend fun bindCamera(
        owner: LifecycleOwner,
        settings: Settings,
        profile: RecordingProfile,
    ): Boolean = withContext(Dispatchers.Main) {
        val selector = when (settings.cameraFacing) {
            CameraFacing.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraFacing.Rear -> CameraSelector.DEFAULT_BACK_CAMERA
        }

        if (profile.burnInOverlays && overlayEffect == null) {
            overlayEffect = VideoOverlayEffect(onError = { onOverlayFailure() })
        } else if (!profile.burnInOverlays) {
            overlayEffect?.close()
            overlayEffect = null
        }

        val result = cameraSession.bind(
            lifecycleOwner = owner,
            selector = selector,
            profile = profile,
            surfaceRotation = orientationTracker.surfaceRotation.value,
            overlayEffect = overlayEffect,
        )
        if (result.isFailure) {
            update {
                it.copy(
                    status = RecorderStatus.Failed,
                    lastErrorMessage = "The camera could not be configured for ${profile.label}",
                )
            }
            return@withContext false
        }
        boundProfile = profile
        if (settings.recordingZoom > 1f) cameraSession.setRecordingZoom(settings.recordingZoom)
        update { it.copy(profile = profile) }
        true
    }

    private fun startPeripherals(settings: Settings) {
        if (settings.locationEnabled) {
            locationEngine.start(_thermalPlan.value.locationIntervalMs)
        }
        if (settings.eventDetectionEnabled) {
            sensorSource.start()
            sensorJob?.cancel()
            sensorJob = scope.launch {
                sensorSource.samples.collect { sample -> onSensorSample(sample) }
            }
        }
        overlayJob?.cancel()
        overlayJob = scope.launch {
            while (isActive) {
                publishOverlay()
                delay(OVERLAY_UPDATE_MS)
            }
        }
    }

    private suspend fun stopInternal(status: RecorderStatus, message: String? = null) {
        stopRequested = true
        scheduledStopJob?.cancel()
        update { it.copy(status = RecorderStatus.Stopping) }
        withContext(Dispatchers.Main) {
            activeRecording?.stop()
            activeRecording = null
        }
        // Peripherals stop after the recorder so a final overlay update or GNSS fix cannot be
        // lost from the closing segment.
        sensorJob?.cancel()
        overlayJob?.cancel()
        locationEngine.stop()
        sensorSource.stop()
        withContext(Dispatchers.Main) { cameraSession.unbind() }
        overlayEffect?.close()
        overlayEffect = null
        boundProfile = null
        update {
            it.copy(
                status = status,
                lastErrorMessage = message,
                segmentStartedAtEpochMs = null,
                segmentBytes = 0,
            )
        }
        stopRequested = false
    }

    // ── Segments ──────────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun startSegment(settings: Settings, profile: RecordingProfile) {
        val recorder = cameraSession.recorder ?: run {
            update { it.copy(status = RecorderStatus.Failed, lastErrorMessage = "The recorder is not available") }
            return
        }
        val startedAt = System.currentTimeMillis()
        sequence++
        val file = storage.createSegmentFile(startedAt, sequence)

        val rotation = CameraOrientationTracker.degreesFor(orientationTracker.surfaceRotation.value)
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
                        rotationDegrees = rotation,
                        codec = profile.codecMimeType,
                        bitrateBps = profile.targetBitrateBps,
                        frameRate = profile.frameRate,
                        hasAudio = settings.microphoneEnabled && hasMicrophonePermission(),
                        cameraFacing = settings.cameraFacing.name,
                        profileLabel = profile.label,
                        isComplete = false,
                        startLatitude = locationEngine.state.value.latitude,
                        startLongitude = locationEngine.state.value.longitude,
                    ),
                )
            }.getOrNull()
        }

        val outputBuilder = FileOutputOptions.Builder(file)
            // A duration limit is a backstop only: the controller rolls over on its own timer.
            // Without it, a stuck timer would produce a single enormous unmanageable file.
            .setDurationLimitMillis(settings.segmentLength.seconds * 1_000L + SEGMENT_LIMIT_GRACE_MS)
        if (settings.gpsStorage.metadata) {
            locationForMetadata()?.let { outputBuilder.setLocation(it) }
        }

        val pending = recorder.prepareRecording(context, outputBuilder.build())
        if (settings.microphoneEnabled && hasMicrophonePermission()) pending.withAudioEnabled()

        activeSegmentId = segmentId
        activeSegmentStartedAtEpochMs = startedAt
        activeSegmentFileName = file.name

        val recording = runCatching {
            pending.start(recorderExecutor) { event -> onRecordEvent(event) }
        }.getOrElse { throwable ->
            Log.e(TAG, "could not start a segment", throwable)
            update {
                it.copy(status = RecorderStatus.Failed, lastErrorMessage = "Recording could not be started")
            }
            return
        }
        activeRecording = recording
        update {
            it.copy(
                status = RecorderStatus.Recording,
                segmentStartedAtEpochMs = startedAt,
                segmentTargetMs = settings.segmentLength.seconds * 1_000L,
                segmentBytes = 0,
                segmentIndex = sequence,
                audioEnabled = settings.microphoneEnabled && hasMicrophonePermission(),
                blockers = emptyList(),
            )
        }
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> consecutiveFailures = 0

            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                val elapsedMs = stats.recordedDurationNanos / 1_000_000
                update {
                    it.copy(
                        segmentBytes = stats.numBytesRecorded,
                        audioMuted = stats.audioStats.audioState ==
                            androidx.camera.video.AudioStats.AUDIO_STATE_MUTED,
                    )
                }
                maybeRollOver(elapsedMs)
            }

            is VideoRecordEvent.Finalize -> scope.launch { onFinalize(event) }

            else -> Unit
        }
    }

    /**
     * Rolls the segment over.
     *
     * `stop()` and the next `prepareRecording(...).start(...)` are issued back to back so the
     * recorder queues the new recording and services it the instant the previous one finalises.
     * When a rebind is pending the new recording is *not* queued here -- it has to wait for the
     * camera to be reconfigured in [onFinalize].
     */
    private fun maybeRollOver(elapsedMs: Long) {
        if (_state.value.status != RecorderStatus.Recording) return
        val settings = lastSettings
        val decision = SegmentPlanner.decide(
            elapsedMs = elapsedMs,
            targetSegmentMs = settings.segmentLength.seconds * 1_000L,
            reconfigurationPending = pendingProfile != null,
            storageCleanupRequired = storageCleanupRequired,
            recorderErrorPending = false,
        )
        if (!decision.shouldRoll) return

        rebindPending = pendingProfile != null
        update { it.copy(status = RecorderStatus.RollingOver) }
        Log.i(TAG, "rolling over: ${decision.reason?.label}")

        val recording = activeRecording ?: return
        recording.stop()
        activeRecording = null
        if (!rebindPending && !stopRequested) {
            // Queue the next segment immediately; the recorder services it on finalise.
            scope.launch { startSegment(settings, boundProfile ?: return@launch) }
        }
    }

    private suspend fun onFinalize(event: VideoRecordEvent.Finalize) {
        val segmentId = activeSegmentId
        val fileName = activeSegmentFileName
        val startedAt = activeSegmentStartedAtEpochMs
        val stats = event.recordingStats
        val durationMs = stats.recordedDurationNanos / 1_000_000
        activeSegmentId = null
        activeSegmentFileName = null

        if (segmentId != null && fileName != null) {
            withContext(Dispatchers.IO) {
                segments.byId(segmentId)?.let { entity ->
                    val file = storage.segmentFile(entity)
                    val usable = !event.hasError() ||
                        event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                        event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
                        event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                    if (usable && file.exists() && file.length() > 0) {
                        segments.update(
                            entity.copy(
                                durationMs = durationMs,
                                sizeBytes = file.length(),
                                isComplete = true,
                            ),
                        )
                        protection.onSegmentFinalised(
                            SegmentTiming(entity.id, startedAt, durationMs),
                            entity.fileName,
                        )
                    } else {
                        // Nothing usable: quarantine rather than delete, and drop the row.
                        storage.quarantine(file)
                        segments.deleteById(entity.id)
                    }
                }
            }
        }

        update {
            it.copy(
                sessionDurationMs = it.sessionDurationMs + durationMs,
                sessionSegmentCount = it.sessionSegmentCount + 1,
            )
        }

        maintainStorage()

        if (event.hasError()) {
            handleFinalizeError(event)
            return
        }

        if (stopRequested || _state.value.status == RecorderStatus.Stopping) return

        if (rebindPending) {
            rebindPending = false
            val profile = pendingProfile
            pendingProfile = null
            val owner = lifecycleOwner
            if (profile != null && owner != null) {
                Log.i(TAG, "applying queued profile ${profile.label}")
                if (bindCamera(owner, lastSettings, profile)) {
                    startSegment(lastSettings, profile)
                    return
                }
            }
        }

        // The queued start from maybeRollOver normally covers this; if it did not (for example the
        // recorder rejected the queued start), start one now so the loop cannot silently stall.
        if (activeRecording == null && _state.value.status != RecorderStatus.Idle) {
            boundProfile?.let { startSegment(lastSettings, it) }
        }
    }

    /**
     * Classifies a finalise error and decides whether to keep going.
     *
     * The split is between conditions Roadguard can plausibly recover from by restarting the
     * loop, and conditions where restarting would just fail again in a hot loop.
     */
    private suspend fun handleFinalizeError(event: VideoRecordEvent.Finalize) {
        val error = event.error
        Log.w(TAG, "segment finalised with error $error", event.cause)

        when (error) {
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            -> {
                // Expected: the backstop limit fired. Just continue the loop.
                if (!stopRequested) boundProfile?.let { startSegment(lastSettings, it) }
            }

            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> {
                storageCleanupRequired = true
                val assessment = storage.refresh(lastSettings.loopBudgetBytes)
                storage.runCleanup(assessment)
                val after = storage.refresh(lastSettings.loopBudgetBytes)
                if (after.canRecord && consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
                    consecutiveFailures++
                    boundProfile?.let { startSegment(lastSettings, it) }
                } else {
                    stopInternal(RecorderStatus.Failed, "Storage is full and could not be freed")
                    update { it.copy(blockers = listOf(RecordingBlocker.StorageFull)) }
                }
            }

            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR,
            VideoRecordEvent.Finalize.ERROR_UNKNOWN,
            -> restartWithBackoff("The recorder failed and is restarting")

            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                restartWithBackoff("The camera stopped supplying frames and is restarting")

            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
                restartWithBackoff("A segment contained no usable video")

            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> {
                stopInternal(RecorderStatus.Failed, "The recording location is not writable")
                update { it.copy(blockers = listOf(RecordingBlocker.StorageUnavailable)) }
            }

            else -> restartWithBackoff("Recording restarted after an error")
        }
    }

    /**
     * Restarts the loop with a growing delay, and gives up rather than spinning.
     *
     * An unbounded restart loop on a device with a broken encoder would drain the battery and
     * fill logs while never recording anything; stopping with an explicit message is more useful
     * to the user and to a diagnostics report.
     */
    private suspend fun restartWithBackoff(message: String) {
        consecutiveFailures++
        if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
            stopInternal(RecorderStatus.Failed, "$message, but it kept failing. Recording has stopped.")
            update { it.copy(blockers = listOf(RecordingBlocker.EncoderFailed)) }
            return
        }
        update { it.copy(lastErrorMessage = message) }
        delay(RESTART_BACKOFF_MS * consecutiveFailures)
        if (stopRequested) return

        val owner = lifecycleOwner ?: return
        val profile = boundProfile ?: return
        // Rebind before retrying: an encoder failure usually needs a fresh capture session.
        if (bindCamera(owner, lastSettings, profile)) startSegment(lastSettings, profile)
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
        if (settings.locationEnabled != previous.locationEnabled) {
            if (settings.locationEnabled) locationEngine.start(_thermalPlan.value.locationIntervalMs)
            else locationEngine.stop()
        }
        if (settings.recordingZoom != previous.recordingZoom) {
            cameraSession.setRecordingZoom(settings.recordingZoom)
        }
        requeueProfileIfNeeded()
    }

    private fun onThermalReading(reading: io.github.tunlezah.roadguard.thermal.ThermalReading) {
        val level = thermalPolicy.accept(reading)
        val plan = ThermalPolicy.planFor(level)
        if (plan == _thermalPlan.value) return
        _thermalPlan.value = plan
        update { it.copy(thermalLevel = level) }
        locationEngine.setInterval(plan.locationIntervalMs)
        scope.launch { requeueProfileIfNeeded() }
    }

    /**
     * Recomputes the profile and queues it if it differs from the bound one.
     *
     * Queued, not applied: see the class documentation on why reconfiguration only ever happens
     * at a segment boundary.
     */
    private suspend fun requeueProfileIfNeeded() {
        val probed = _capabilities.value ?: return
        val assessed = _tier.value ?: return
        val next = RecordingProfileSelector.select(probed, assessed, lastSettings, _thermalPlan.value)
        val bound = boundProfile ?: return
        if (next.requiresRebindFrom(bound)) {
            pendingProfile = next
            Log.i(TAG, "queued profile change ${bound.label} -> ${next.label}")
        } else {
            pendingProfile = null
        }
    }

    private fun onPowerTransition(transition: PowerTransition?) {
        transition ?: return
        powerMonitor.consumeTransition()
        val settings = lastSettings
        when (transition) {
            is PowerTransition.Connected -> when (PowerPolicy.onPowerConnected(settings)) {
                PowerAction.StartRecording -> if (!_state.value.isRecording) start()
                else -> Unit
            }

            is PowerTransition.Disconnected -> when (val action = PowerPolicy.onPowerDisconnected(settings)) {
                PowerAction.StopRecording -> stop()
                is PowerAction.StopAfter -> scheduleStop(action.seconds)
                PowerAction.BatterySafeProfile -> scope.launch { requeueProfileIfNeeded() }
                else -> Unit
            }
        }
    }

    private fun onBatteryState(state: io.github.tunlezah.roadguard.power.PowerState) {
        if (!_state.value.isRecording) return
        when (val action = PowerPolicy.evaluateBattery(state, lastSettings)) {
            is PowerAction.StopForLowBattery -> scope.launch {
                stopInternal(
                    RecorderStatus.Failed,
                    "Battery is at ${action.batteryPercent}%. Recording stopped so the last clip is saved cleanly.",
                )
                update { it.copy(blockers = listOf(RecordingBlocker.LowBattery)) }
            }

            else -> Unit
        }
    }

    private fun scheduleStop(seconds: Int) {
        scheduledStopJob?.cancel()
        scheduledStopJob = scope.launch {
            delay(seconds * 1_000L)
            if (!powerMonitor.state.value.isOnExternalPower) stopInternal(RecorderStatus.Idle)
        }
    }

    private fun onCameraError(error: CameraState.StateError?) {
        error ?: return
        val blocker = when (error.code) {
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                RecordingBlocker.CameraInUse

            CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
                RecordingBlocker.CameraDisabled

            CameraState.ERROR_CAMERA_FATAL_ERROR, CameraState.ERROR_CAMERA_REMOVED ->
                RecordingBlocker.CameraFatal

            else -> null
        }
        if (blocker != null) {
            update { it.copy(blockers = listOf(blocker), lastErrorMessage = blocker.message) }
        }
        if (error.code == CameraState.ERROR_OTHER_RECOVERABLE_ERROR ||
            error.code == CameraState.ERROR_STREAM_CONFIG
        ) {
            scope.launch { restartWithBackoff("The camera reported a recoverable error") }
        }
    }

    private fun onOverlayFailure() {
        // Burn-in failed. Drop the effect at the next boundary and keep recording; the on-screen
        // overlay still works, and the recording is what matters.
        Log.w(TAG, "disabling overlay burn-in after an effect error")
        scope.launch {
            val probed = _capabilities.value ?: return@launch
            val assessed = _tier.value ?: return@launch
            pendingProfile = RecordingProfileSelector
                .select(probed, assessed, lastSettings, _thermalPlan.value)
                .copy(burnInOverlays = false)
            update { it.copy(lastErrorMessage = "Overlays could not be added to the video; recording continues") }
        }
    }

    private fun onSensorSample(sample: io.github.tunlezah.roadguard.event.SensorSample) {
        if (!lastSettings.eventDetectionEnabled) return
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
        while (scope.isActive) {
            locationEngine.tick()
            delay(TICK_MS)
        }
    }

    private suspend fun maintainStorage() {
        val assessment = storage.refresh(lastSettings.loopBudgetBytes)
        storageCleanupRequired = assessment.needsCleanup
        if (assessment.needsCleanup) {
            val outcome = storage.runCleanup(assessment)
            Log.i(TAG, "loop cleanup freed ${outcome.bytesFreed} bytes in ${outcome.filesDeleted} files")
            storageCleanupRequired = false
        }
        if (assessment.state == StorageState.Critical) {
            update { it.copy(blockers = listOf(RecordingBlocker.StorageFull)) }
        }
    }

    private suspend fun publishOverlay() {
        val effect = overlayEffect ?: return
        val content = overlayComposer.compose(
            settings = lastSettings,
            location = locationEngine.state.value,
            weather = (weatherState.value as? WeatherState.Available)?.snapshot,
            nowEpochMs = System.currentTimeMillis(),
            protectedLabel = _state.value.lastProtectionMessage?.let { "PROTECTED" },
        )
        effect.update(content)
    }

    private fun currentSegmentTiming(): SegmentTiming? {
        val id = activeSegmentId ?: return null
        return SegmentTiming(
            id = id,
            startedAtEpochMs = activeSegmentStartedAtEpochMs,
            durationMs = (System.currentTimeMillis() - activeSegmentStartedAtEpochMs).coerceAtLeast(0),
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

    private fun update(transform: (RecordingUiState) -> RecordingUiState) {
        _state.value = transform(_state.value)
    }

    /** A blocker plus whether it actually prevents recording, as opposed to degrading it. */
    private data class BlockerCheck(val blocker: RecordingBlocker, val blocksRecording: Boolean)

    /** Storage assessment, republished for the UI. */
    val storageAssessment: StateFlow<StorageAssessment?> get() = storage.assessment

    companion object {
        private const val TAG = "RoadguardRecorder"

        /** Overlay content is regenerated once a second; the clock is the fastest field. */
        const val OVERLAY_UPDATE_MS = 1_000L

        /** Cadence for staleness housekeeping (GNSS age, held speed expiry). */
        const val TICK_MS = 1_000L

        /** How long a protection confirmation stays on screen. */
        const val PROTECT_MESSAGE_MS = 4_000L

        /** Extra time allowed before the recorder's own duration backstop fires. */
        const val SEGMENT_LIMIT_GRACE_MS = 15_000L

        /** Base delay between restart attempts; multiplied by the failure count. */
        const val RESTART_BACKOFF_MS = 2_000L

        /** Consecutive failures after which Roadguard stops rather than spinning. */
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
