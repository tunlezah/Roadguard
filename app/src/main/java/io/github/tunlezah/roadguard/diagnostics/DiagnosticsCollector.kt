package io.github.tunlezah.roadguard.diagnostics

import android.content.Context
import android.os.Build
import io.github.tunlezah.roadguard.camera.CameraOrientationTracker
import io.github.tunlezah.roadguard.capability.CameraCapability
import io.github.tunlezah.roadguard.data.EventDao
import io.github.tunlezah.roadguard.data.SegmentDao
import io.github.tunlezah.roadguard.event.EventSensorSource
import io.github.tunlezah.roadguard.location.LocationEngine
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.map.MapRepository
import io.github.tunlezah.roadguard.power.PowerMonitor
import io.github.tunlezah.roadguard.recording.RecordingController
import io.github.tunlezah.roadguard.storage.StorageManager
import io.github.tunlezah.roadguard.storage.StorageState
import io.github.tunlezah.roadguard.thermal.ThermalSignalSource
import io.github.tunlezah.roadguard.thermal.ThermalSource
import io.github.tunlezah.roadguard.weather.WeatherRepository
import io.github.tunlezah.roadguard.weather.WeatherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the diagnostics report.
 *
 * This screen exists because Roadguard's device profiles are honest about being unvalidated: the
 * only way they improve is if real users on real hardware can see, and export, exactly what the
 * app probed and measured. So the report is deliberately complete on the technical facts and
 * deliberately empty of anything personal.
 */
class DiagnosticsCollector(
    private val context: Context,
    private val recordingController: RecordingController,
    private val storageManager: StorageManager,
    private val locationEngine: LocationEngine,
    private val powerMonitor: PowerMonitor,
    private val sensorSource: EventSensorSource,
    private val thermalSource: () -> ThermalSource,
    private val mapRepository: MapRepository,
    private val weatherRepository: WeatherRepository,
    private val segments: SegmentDao,
    private val events: EventDao,
) {

    suspend fun collect(): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        DiagnosticsSnapshot(
            generatedAtEpochMs = now,
            sections = listOfNotNull(
                appSection(),
                deviceSection(),
                cameraSection(),
                recordingSection(),
                thermalSection(),
                storageSection(),
                locationSection(),
                sensorSection(),
                mapSection(),
                weatherSection(),
                libraryFactsSection(),
            ),
        )
    }

    /** Writes the report to Roadguard's diagnostics directory and returns the file. */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        val snapshot = collect()
        val name = "roadguard-diagnostics-${FILE_STAMP.format(Date(snapshot.generatedAtEpochMs))}.txt"
        val file = File(storageManager.layout.diagnostics, name)
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toPlainText())
        file
    }

    private fun appSection() = DiagnosticsSection(
        "Roadguard",
        listOf(
            DiagnosticsEntry("Version", "${io.github.tunlezah.roadguard.BuildConfig.VERSION_NAME} (${io.github.tunlezah.roadguard.BuildConfig.VERSION_CODE})"),
            DiagnosticsEntry("Build type", io.github.tunlezah.roadguard.BuildConfig.BUILD_TYPE),
            DiagnosticsEntry("Application id", io.github.tunlezah.roadguard.BuildConfig.APPLICATION_ID),
        ),
    )

    private fun deviceSection(): DiagnosticsSection {
        val capabilities = recordingController.capabilities.value
        val tier = recordingController.tier.value
        return DiagnosticsSection(
            "Device",
            buildList {
                add(DiagnosticsEntry("Model", "${Build.MANUFACTURER} ${Build.MODEL}"))
                add(DiagnosticsEntry("Device", Build.DEVICE ?: "unknown"))
                add(DiagnosticsEntry("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
                add(
                    DiagnosticsEntry(
                        "SoC",
                        capabilities?.socModel?.let { model ->
                            listOfNotNull(capabilities.socManufacturer, model).joinToString(" ")
                        } ?: "not reported",
                        if (capabilities?.socModel == null) Provenance.Unavailable else Provenance.PlatformReported,
                    ),
                )
                add(DiagnosticsEntry("CPU cores", "${capabilities?.cpuCoreCount ?: "?"}"))
                add(
                    DiagnosticsEntry(
                        "CPU maximum clock",
                        capabilities?.maxCpuFrequencyGHz?.let { "%.2f GHz".format(it) } ?: "not readable",
                        if (capabilities?.maxCpuFrequencyGHz == null) {
                            Provenance.Unavailable
                        } else {
                            Provenance.PlatformReported
                        },
                    ),
                )
                add(
                    DiagnosticsEntry(
                        "RAM",
                        capabilities?.let { "%.1f GiB total, %.1f GiB available".format(gib(it.totalRamBytes), gib(it.availableRamBytes)) }
                            ?: "unknown",
                    ),
                )
                add(DiagnosticsEntry("Low-RAM device", yesNo(capabilities?.isLowRamDevice)))
                add(
                    DiagnosticsEntry(
                        "CPU probe",
                        capabilities?.cpuProbeScore?.let { "%.0f units/ms".format(it) } ?: "not run",
                        if (capabilities?.cpuProbeScore == null) Provenance.Unavailable else Provenance.Measured,
                    ),
                )
                add(
                    DiagnosticsEntry(
                        "Device tier",
                        tier?.let { "${it.tier.label} (${it.points} points)" } ?: "not assessed",
                        Provenance.Inferred,
                    ),
                )
                tier?.reasons?.forEachIndexed { index, reason ->
                    add(DiagnosticsEntry("  Reason ${index + 1}", reason, Provenance.Inferred))
                }
                capabilities?.display?.let { display ->
                    add(
                        DiagnosticsEntry(
                            "Display",
                            "${display.widthPx}x${display.heightPx} at ${display.densityDpi} dpi, " +
                                "%.0f Hz%s".format(display.refreshRateHz, if (display.isHdrCapable) ", HDR" else ""),
                        ),
                    )
                }
            },
        )
    }

    private fun cameraSection(): DiagnosticsSection {
        val capabilities = recordingController.capabilities.value
        val cameras = capabilities?.cameras.orEmpty()
        return DiagnosticsSection(
            "Cameras",
            buildList {
                if (cameras.isEmpty()) {
                    add(DiagnosticsEntry("Cameras", "not probed yet", Provenance.Unavailable))
                }
                cameras.forEach { camera -> addAll(describe(camera)) }
                add(
                    DiagnosticsEntry(
                        "Concurrent camera pairs",
                        capabilities?.concurrentCameraPairs
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString { "${it.first}+${it.second}" }
                            ?: "none reported",
                    ),
                )
                capabilities?.encoders?.forEach { encoder ->
                    add(
                        DiagnosticsEntry(
                            "Encoder ${encoder.name}",
                            buildString {
                                append(encoder.mimeType)
                                append(if (encoder.hardwareAccelerated) ", hardware" else ", software")
                                encoder.maxWidth?.let { width ->
                                    encoder.maxHeight?.let { height -> append(", up to ${width}x$height") }
                                }
                                encoder.maxInstances?.let { append(", $it instance(s)") }
                            },
                        ),
                    )
                }
            },
        )
    }

    private fun describe(camera: CameraCapability): List<DiagnosticsEntry> = listOf(
        DiagnosticsEntry(
            "Camera ${camera.cameraId} (${camera.lensFacing.name})",
            "hardware level ${camera.hardwareLevel.name}" +
                (camera.primaryFocalLengthMm?.let { ", %.1f mm".format(it) } ?: ""),
        ),
        DiagnosticsEntry(
            "  Recording qualities",
            camera.supportedQualities.takeIf { it.isNotEmpty() }?.joinToString() ?: "none reported",
        ),
        DiagnosticsEntry(
            "  Frame rates",
            camera.supportedFrameRateRanges.takeIf { it.isNotEmpty() }
                ?.joinToString { "${it.first}-${it.last}" }
                ?: "none reported",
        ),
        DiagnosticsEntry("  Stabilisation", yesNo(camera.supportsVideoStabilisation)),
        DiagnosticsEntry("  Dynamic ranges", camera.supportedDynamicRanges.joinToString()),
    )

    private fun recordingSection(): DiagnosticsSection {
        val state = recordingController.state.value
        val profile = state.profile
        return DiagnosticsSection(
            "Recording",
            buildList {
                add(DiagnosticsEntry("Status", state.status.label))
                add(DiagnosticsEntry("Selected profile", profile?.label ?: "none"))
                add(
                    DiagnosticsEntry(
                        "Resolution",
                        profile?.resolution?.toString() ?: profile?.cameraXQuality ?: "unknown",
                    ),
                )
                add(DiagnosticsEntry("Frame rate", profile?.let { "${it.frameRate} fps" } ?: "unknown"))
                add(
                    DiagnosticsEntry(
                        "Codec",
                        profile?.codecMimeType ?: "unknown",
                        Provenance.Inferred,
                    ),
                )
                add(
                    DiagnosticsEntry(
                        "Bitrate",
                        profile?.targetBitrateBps
                            ?.takeIf { it > 0 }
                            ?.let { "%.1f Mbps (set by Roadguard)".format(it / 1_000_000f) }
                            ?: "chosen by the device",
                    ),
                )
                add(DiagnosticsEntry("Overlay burn-in", yesNo(profile?.burnInOverlays)))
                add(DiagnosticsEntry("Stabilisation", yesNo(profile?.stabilisation)))
                add(DiagnosticsEntry("Dual camera", yesNo(profile?.dualCamera)))
                add(DiagnosticsEntry("Microphone", yesNo(state.audioEnabled)))
                add(
                    DiagnosticsEntry(
                        "Target rotation",
                        CameraOrientationTracker.describe(
                            recordingController.state.value.let { _ -> 0 },
                        ),
                        Provenance.Inferred,
                    ),
                )
                add(
                    DiagnosticsEntry(
                        "Session",
                        "${state.sessionSegmentCount} segment(s), ${state.sessionDurationMs / 1000} s",
                        Provenance.Measured,
                    ),
                )
                profile?.rationale?.forEachIndexed { index, reason ->
                    add(DiagnosticsEntry("  Why ${index + 1}", reason, Provenance.Inferred))
                }
                state.lastErrorMessage?.let {
                    add(DiagnosticsEntry("Last error", it, severity = EntrySeverity.Error))
                }
            },
        )
    }

    private fun thermalSection(): DiagnosticsSection {
        val source = thermalSource()
        val reading = source.reading.value
        val simulated = ThermalSignalSource.Simulated in reading.sources
        val provenance = if (simulated) Provenance.Simulated else Provenance.PlatformReported
        return DiagnosticsSection(
            "Temperature",
            listOf(
                DiagnosticsEntry("Level", recordingController.state.value.thermalLevel.label, Provenance.Inferred),
                DiagnosticsEntry(
                    "Thermal status",
                    reading.status?.toString() ?: "not reported",
                    if (reading.status == null) Provenance.Unavailable else provenance,
                ),
                DiagnosticsEntry(
                    "Thermal headroom",
                    reading.headroom?.let { "%.2f".format(it) } ?: "not reported",
                    if (reading.headroom == null) Provenance.Unavailable else provenance,
                ),
                DiagnosticsEntry(
                    "Battery temperature",
                    reading.batteryTemperatureC?.let { "%.1f C".format(it) } ?: "not reported",
                    if (reading.batteryTemperatureC == null) Provenance.Unavailable else provenance,
                ),
                DiagnosticsEntry("Signals available", source.describeCapability, Provenance.PlatformReported),
                DiagnosticsEntry(
                    "Active plan",
                    recordingController.thermalPlan.value.let { plan ->
                        "quality -${plan.qualityStepDown}, bitrate x${plan.bitrateScale}, " +
                            "map ${plan.mapRenderBudget.label}, overlay ${yesNo(plan.allowVideoOverlay)}"
                    },
                    Provenance.Inferred,
                ),
            ),
        )
    }

    private suspend fun storageSection(): DiagnosticsSection {
        val assessment = storageManager.assessment.value
        return DiagnosticsSection(
            "Storage",
            buildList {
                add(DiagnosticsEntry("Volume", if (storageManager.layout.isRemovable) "removable" else "internal"))
                if (assessment == null) {
                    add(DiagnosticsEntry("Assessment", "not computed yet", Provenance.Unavailable))
                } else {
                    add(
                        DiagnosticsEntry(
                            "State",
                            assessment.state.name,
                            severity = when (assessment.state) {
                                StorageState.Ok -> EntrySeverity.Normal
                                StorageState.Warning -> EntrySeverity.Warning
                                StorageState.Critical -> EntrySeverity.Error
                            },
                        ),
                    )
                    add(DiagnosticsEntry("Loop used", mib(assessment.loopUsedBytes), Provenance.Measured))
                    add(DiagnosticsEntry("Loop budget", mib(assessment.effectiveBudgetBytes), Provenance.Inferred))
                    add(DiagnosticsEntry("Requested budget", mib(assessment.requestedBudgetBytes)))
                    add(DiagnosticsEntry("Protected footage", mib(assessment.protectedBytes), Provenance.Measured))
                    add(DiagnosticsEntry("Map data", mib(assessment.mapBytes), Provenance.Measured))
                    add(DiagnosticsEntry("Free space", mib(assessment.freeBytes), Provenance.PlatformReported))
                    add(DiagnosticsEntry("Reserve kept free", mib(assessment.reserveBytes), Provenance.Inferred))
                    add(
                        DiagnosticsEntry(
                            "Measured bitrate",
                            assessment.measuredBytesPerSecond
                                .takeIf { it > 0 }
                                ?.let { "%.2f Mbps".format(it * 8 / 1_000_000) }
                                ?: "no samples yet",
                            if (assessment.measuredBytesPerSecond > 0) Provenance.Measured else Provenance.Unavailable,
                        ),
                    )
                    add(
                        DiagnosticsEntry(
                            "Loop coverage",
                            assessment.loopCoverageSeconds?.let { formatDuration(it) } ?: "unknown",
                            Provenance.Inferred,
                        ),
                    )
                }
                add(DiagnosticsEntry("Segments indexed", "${segments.count()}", Provenance.Measured))
                add(DiagnosticsEntry("Events recorded", "${events.count()}", Provenance.Measured))
                val quarantined = storageManager.layout.quarantine.listFiles()?.size ?: 0
                add(
                    DiagnosticsEntry(
                        "Quarantined files",
                        "$quarantined",
                        Provenance.Measured,
                        if (quarantined > 0) EntrySeverity.Warning else EntrySeverity.Normal,
                    ),
                )
            },
        )
    }

    private fun locationSection(): DiagnosticsSection {
        val state = locationEngine.state.value
        return DiagnosticsSection(
            "Location",
            listOf(
                DiagnosticsEntry("Permission", yesNo(state.permissionGranted)),
                DiagnosticsEntry("Provider enabled", yesNo(state.providerEnabled)),
                DiagnosticsEntry("Fix quality", state.quality.label, Provenance.Inferred),
                DiagnosticsEntry(
                    "Accuracy",
                    state.accuracyMetres?.let { "%.0f m".format(it) } ?: "no fix",
                    if (state.accuracyMetres == null) Provenance.Unavailable else Provenance.PlatformReported,
                ),
                DiagnosticsEntry("Satellites", "${state.satellitesUsed} used of ${state.satellitesVisible} visible"),
                DiagnosticsEntry(
                    "Speed",
                    state.speedMetresPerSecond?.let { "%.1f km/h".format(it * 3.6f) } ?: "unknown",
                    Provenance.Inferred,
                ),
                DiagnosticsEntry(
                    "Fix age",
                    state.ageMillis?.let { "${it / 1000} s" } ?: "no fix",
                    if (state.ageMillis == null) Provenance.Unavailable else Provenance.Measured,
                ),
                DiagnosticsEntry("Mock location", yesNo(state.isMock)),
                // Coordinates are deliberately omitted from the report; only a coarse indication
                // of whether a position exists is useful for diagnosis.
                DiagnosticsEntry("Position available", yesNo(state.hasPosition), Provenance.Inferred),
            ),
        )
    }

    private fun sensorSection(): DiagnosticsSection {
        val availability = sensorSource.available.value
        return DiagnosticsSection(
            "Motion sensors",
            listOf(
                DiagnosticsEntry("Sensors", availability.describe()),
                DiagnosticsEntry(
                    "Event detection",
                    if (availability.canDetectEvents) "available" else "unavailable",
                    Provenance.Inferred,
                    if (availability.canDetectEvents) EntrySeverity.Normal else EntrySeverity.Warning,
                ),
                DiagnosticsEntry(
                    "Gyroscope",
                    if (availability.gyroscope) {
                        "present"
                    } else {
                        "absent - impact detection runs accelerometer-only and is slightly less confident"
                    },
                    Provenance.PlatformReported,
                    if (availability.gyroscope) EntrySeverity.Normal else EntrySeverity.Warning,
                ),
            ),
        )
    }

    private fun mapSection(): DiagnosticsSection {
        val install = mapRepository.installState.value
        return DiagnosticsSection(
            "Offline map",
            listOf(
                DiagnosticsEntry(
                    "State",
                    when (install) {
                        is MapInstallState.Installed -> "installed, ${mib(install.sizeBytes)}"
                        is MapInstallState.Downloading -> "downloading ${install.fraction?.let { "%.0f%%".format(it * 100) } ?: "..."}"
                        is MapInstallState.Paused -> "paused at ${mib(install.bytesDownloaded)}"
                        is MapInstallState.Verifying -> "verifying"
                        is MapInstallState.Failed -> install.reason.message
                        MapInstallState.NotInstalled -> "not installed"
                    },
                    severity = if (install is MapInstallState.Failed) EntrySeverity.Warning else EntrySeverity.Normal,
                ),
                DiagnosticsEntry("Package", mapRepository.selectedPackage?.displayName ?: "none"),
                DiagnosticsEntry("Data attribution", mapRepository.selectedPackage?.attribution ?: "n/a"),
                DiagnosticsEntry("Map storage", mib(storageManager.mapBytes()), Provenance.Measured),
                DiagnosticsEntry(
                    "Render budget",
                    recordingController.thermalPlan.value.mapRenderBudget.label,
                    Provenance.Inferred,
                ),
            ),
        )
    }

    private fun weatherSection(): DiagnosticsSection {
        val state = weatherRepository.state.value
        return DiagnosticsSection(
            "Weather",
            listOf(
                DiagnosticsEntry("Source", weatherRepository.sourceName),
                DiagnosticsEntry(
                    "State",
                    when (state) {
                        is WeatherState.Available -> "available, updated ${state.snapshot.fetchedAtEpochMs.let { (System.currentTimeMillis() - it) / 60000 }} min ago"
                        WeatherState.Loading -> "loading"
                        is WeatherState.Unavailable -> state.reason.message
                    },
                ),
                DiagnosticsEntry("Attribution", weatherRepository.attribution.ifEmpty { "n/a" }),
            ),
        )
    }

    /**
     * Facts about the libraries Roadguard records with.
     *
     * Included because the single most useful thing in a bug report is knowing which camera stack
     * produced the file, and because the codec decision depends on a CameraX version limitation
     * that a reader deserves to see stated.
     */
    private fun libraryFactsSection() = DiagnosticsSection(
        "Recording stack",
        listOf(
            DiagnosticsEntry("Camera stack", "AndroidX CameraX (Recorder / VideoCapture)"),
            DiagnosticsEntry(
                "Codec selection",
                "chosen by the device; CameraX 1.6 exposes no override",
                Provenance.Inferred,
            ),
            DiagnosticsEntry("Container", "MP4 with a rotation hint (frames are not re-encoded to rotate)"),
            DiagnosticsEntry("Overlay burn-in", "CameraX OverlayEffect, video stream only"),
        ),
    )

    private fun gib(bytes: Long) = bytes.toDouble() / (1024.0 * 1024 * 1024)
    private fun mib(bytes: Long) = "%.1f MB".format(bytes.toDouble() / (1024.0 * 1024))
    private fun yesNo(value: Boolean?) = when (value) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private companion object {
        val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
