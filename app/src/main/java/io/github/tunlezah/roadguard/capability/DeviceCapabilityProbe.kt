// Camera2 interop is how a camera's hardware level, focal lengths and active-array size are read;
// there is no non-interop path to them, and CameraX marks the bridge experimental rather than
// unstable. ExperimentalLensFacing covers the external-camera constant. Both opt-ins are
// deliberate and confined to this probe, which is the only place Roadguard reaches below CameraX.
@file:OptIn(
    androidx.camera.camera2.interop.ExperimentalCamera2Interop::class,
    androidx.camera.core.ExperimentalLensFacing::class,
)

package io.github.tunlezah.roadguard.capability

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import io.github.tunlezah.roadguard.camera.CameraSession
import io.github.tunlezah.roadguard.event.EventSensorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Discovers what this device can actually do.
 *
 * Everything here is read from the platform at runtime. Nothing is keyed off `Build.MODEL`: the
 * specification is explicit that a device must earn its recording profile from probed capability,
 * and a model allow-list would be wrong the moment a variant or a firmware update appeared.
 *
 * Every probe is individually wrapped so that one unavailable API cannot deny Roadguard the
 * others -- on a device where, say, `getThermalHeadroom` throws, the profile is still chosen from
 * the camera and encoder facts.
 */
class DeviceCapabilityProbe(
    private val context: Context,
    private val cameraSession: CameraSession,
    private val sensors: EventSensorSource,
) {

    suspend fun probe(runCpuProbe: Boolean = true): DeviceCapabilities = withContext(Dispatchers.Default) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also {
            runCatching { activityManager?.getMemoryInfo(it) }
        }

        val cameras = runCatching { probeCameras() }.getOrElse {
            Log.w(TAG, "camera probe failed", it)
            emptyList()
        }
        val concurrent = runCatching { probeConcurrentCameras() }.getOrElse { emptyList() }
        val encoders = runCatching { probeEncoders() }.getOrElse {
            Log.w(TAG, "encoder probe failed", it)
            emptyList()
        }

        DeviceCapabilities(
            apiLevel = Build.VERSION.SDK_INT,
            releaseName = Build.VERSION.RELEASE ?: "",
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            socModel = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
            socManufacturer = Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            maxCpuFrequencyGHz = readMaxCpuFrequencyGHz(),
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            isLowRamDevice = activityManager?.isLowRamDevice == true,
            cameras = cameras,
            encoders = encoders,
            concurrentCameraPairs = concurrent,
            thermal = probeThermalSupport(),
            sensors = SensorSupport(
                hasAccelerometer = sensors.hasAccelerometer,
                hasGyroscope = sensors.hasGyroscope,
                hasRotationVector = sensors.available.value.gravity,
                maxAccelerometerRateHz = sensors.available.value.maxRateHz,
            ),
            display = probeDisplay(),
            cpuProbeScore = if (runCpuProbe) runCatching { PerformanceProbe.run().score }.getOrNull() else null,
        )
    }

    private suspend fun probeCameras(): List<CameraCapability> {
        val infos = cameraSession.availableCameraInfos()
        val cameraManager = context.getSystemService(CameraManager::class.java)
        return infos.map { info -> describe(info, cameraManager) }
    }

    private fun describe(info: CameraInfo, cameraManager: CameraManager?): CameraCapability {
        val cameraId = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId
        }.getOrNull()

        val characteristics = cameraId?.let { id ->
            runCatching { cameraManager?.getCameraCharacteristics(id) }.getOrNull()
        }

        val qualities = runCatching {
            Recorder.getVideoCapabilities(info)
                .getSupportedQualities(DynamicRange.SDR)
                .map { CameraSession.nameFor(it) }
        }.getOrDefault(emptyList())

        // QualitySelector.getResolution is the public accessor; VideoCapabilities.getResolution
        // is library-restricted.
        val resolutions = runCatching {
            Recorder.getVideoCapabilities(info)
                .getSupportedQualities(DynamicRange.SDR)
                .mapNotNull { quality ->
                    QualitySelector.getResolution(info, quality)?.let { Resolution(it.width, it.height) }
                }
        }.getOrDefault(emptyList())

        val frameRateRanges = runCatching {
            info.supportedFrameRateRanges.map { it.lower..it.upper }
        }.getOrDefault(emptyList())

        return CameraCapability(
            cameraId = cameraId ?: "unknown",
            lensFacing = when (runCatching { info.lensFacing }.getOrNull()) {
                CameraSelector.LENS_FACING_BACK -> LensFacing.Back
                CameraSelector.LENS_FACING_FRONT -> LensFacing.Front
                CameraSelector.LENS_FACING_EXTERNAL -> LensFacing.External
                else -> LensFacing.Unknown
            },
            hardwareLevel = hardwareLevelOf(characteristics),
            supportedQualities = qualities,
            supportedResolutions = resolutions,
            maxFrameRate = frameRateRanges.maxOfOrNull { it.last },
            supportedFrameRateRanges = frameRateRanges,
            focalLengthsMm = characteristics
                ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList()
                .orEmpty(),
            sensorAspectRatio = characteristics
                ?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { if (it.height() == 0) null else it.width().toFloat() / it.height() },
            supportsVideoStabilisation = runCatching {
                Recorder.getVideoCapabilities(info).isStabilizationSupported
            }.getOrDefault(false),
            supportedDynamicRanges = runCatching {
                // is10BitHdr is library-restricted; the encoding and bit depth are public.
                Recorder.getVideoCapabilities(info).supportedDynamicRanges.map { range ->
                    when {
                        range.bitDepth == DynamicRange.BIT_DEPTH_10_BIT &&
                            range.encoding != DynamicRange.ENCODING_SDR -> "HDR 10-bit"

                        range.encoding == DynamicRange.ENCODING_SDR -> "SDR"
                        else -> "encoding ${range.encoding}, ${range.bitDepth}-bit"
                    }
                }
            }.getOrDefault(listOf("SDR")),
            isLogicalMultiCamera = runCatching { info.isLogicalMultiCameraSupported }.getOrDefault(false),
            physicalCameraIds = runCatching {
                info.physicalCameraInfos.mapNotNull { physical ->
                    runCatching {
                        androidx.camera.camera2.interop.Camera2CameraInfo.from(physical).cameraId
                    }.getOrNull()
                }
            }.getOrDefault(emptyList()),
        )
    }

    private suspend fun probeConcurrentCameras(): List<Pair<String, String>> =
        cameraSession.concurrentCameraInfos().mapNotNull { combination ->
            if (combination.size < 2) return@mapNotNull null
            val ids = combination.mapNotNull { info ->
                runCatching {
                    androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId
                }.getOrNull()
            }
            if (ids.size >= 2) ids[0] to ids[1] else null
        }

    /**
     * Enumerates video encoders.
     *
     * `REGULAR_CODECS` excludes the specialised secure/tunnelled entries an app cannot use.
     * `isHardwareAccelerated` and `isSoftwareOnly` are recorded rather than inferred from the
     * codec name, which is a common and unreliable shortcut.
     */
    private fun probeEncoders(): List<EncoderCapability> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val interesting = setOf(
            VideoMimeTypes.H264,
            VideoMimeTypes.HEVC,
            VideoMimeTypes.AV1,
            VideoMimeTypes.VP9,
        )
        return list.codecInfos
            .filter { it.isEncoder }
            .flatMap { codec ->
                codec.supportedTypes
                    .filter { it in interesting }
                    .mapNotNull { mime -> describeEncoder(codec, mime) }
            }
    }

    private fun describeEncoder(codec: MediaCodecInfo, mime: String): EncoderCapability? = runCatching {
        val capabilities = codec.getCapabilitiesForType(mime)
        val video = capabilities.videoCapabilities ?: return@runCatching null
        val probeSizes = listOf(
            Resolution(720, 480),
            Resolution(1280, 720),
            Resolution(1920, 1080),
            Resolution(2560, 1440),
            Resolution(3840, 2160),
        )
        val achievable = probeSizes.mapNotNull { size ->
            runCatching {
                if (!video.isSizeSupported(size.width, size.height)) return@runCatching null
                val rate = video.getSupportedFrameRatesFor(size.width, size.height).upper.toInt()
                size to rate
            }.getOrNull()
        }.toMap()

        EncoderCapability(
            name = codec.name,
            mimeType = mime,
            hardwareAccelerated = codec.isHardwareAccelerated,
            softwareOnly = codec.isSoftwareOnly,
            maxWidth = runCatching { video.supportedWidths.upper }.getOrNull(),
            maxHeight = runCatching { video.supportedHeights.upper }.getOrNull(),
            bitrateRange = runCatching {
                video.bitrateRange.lower.toLong()..video.bitrateRange.upper.toLong()
            }.getOrNull(),
            maxInstances = runCatching { capabilities.maxSupportedInstances }.getOrNull(),
            achievableFrameRates = achievable,
        )
    }.getOrNull()

    private fun probeThermalSupport(): ThermalApiSupport {
        val powerManager = context.getSystemService(android.os.PowerManager::class.java)
        val hasStatus = runCatching { powerManager?.currentThermalStatus != null }.getOrDefault(false)
        val headroom = runCatching { powerManager?.getThermalHeadroom(30) }.getOrNull()
        return ThermalApiSupport(
            hasThermalStatus = hasStatus,
            hasThermalHeadroom = headroom != null && !headroom.isNaN(),
            hasHeadroomThresholds = Build.VERSION.SDK_INT >= 35,
        )
    }

    private fun probeDisplay(): DisplayCapability = runCatching {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = windowManager.currentWindowMetrics
        val display = context.display
        DisplayCapability(
            widthPx = metrics.bounds.width(),
            heightPx = metrics.bounds.height(),
            densityDpi = context.resources.configuration.densityDpi,
            refreshRateHz = display?.refreshRate ?: 60f,
            isHdrCapable = display?.isHdr == true,
        )
    }.getOrDefault(DisplayCapability(0, 0, context.resources.configuration.densityDpi, 60f, false))

    /**
     * Highest `cpufreq` ceiling across all cores, in GHz.
     *
     * This is the single best cheap discriminator between a low-end all-efficiency-core SoC and
     * a mid-range one, and it separates them far better than core count. It is also the probe
     * most likely to be unavailable: many devices restrict `/sys` access, in which case the
     * scorer simply awards no points for clock speed rather than assuming a value.
     */
    fun readMaxCpuFrequencyGHz(): Float? {
        val cores = Runtime.getRuntime().availableProcessors()
        var best = 0L
        for (core in 0 until cores) {
            val path = File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
            val value = runCatching { path.readText().trim().toLong() }.getOrNull() ?: continue
            if (value > best) best = value
        }
        // cpufreq reports kHz.
        return if (best <= 0L) null else best / 1_000_000f
    }

    private fun hardwareLevelOf(characteristics: CameraCharacteristics?): CameraHardwareLevel =
        when (characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> CameraHardwareLevel.Legacy
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> CameraHardwareLevel.Limited
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> CameraHardwareLevel.Full
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> CameraHardwareLevel.Level3
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> CameraHardwareLevel.External
            else -> CameraHardwareLevel.Unknown
        }

    private companion object {
        const val TAG = "RoadguardCapability"
    }
}
