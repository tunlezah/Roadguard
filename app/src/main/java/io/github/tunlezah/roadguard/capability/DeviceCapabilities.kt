package io.github.tunlezah.roadguard.capability

/**
 * Everything Roadguard learned about this device at runtime.
 *
 * This is the *only* input to profile selection. Roadguard never keys behaviour off
 * `Build.MODEL`: the specification is explicit that a conservative profile must be earned by
 * measured or probed capability, not assigned by name. Model and SoC strings are carried
 * here purely so the diagnostics report is useful when a user sends one in.
 *
 * Fields that could not be determined are null rather than guessed, and the diagnostics
 * screen renders them as "not reported" so a reader can tell a real answer from a missing
 * one.
 */
data class DeviceCapabilities(
    val apiLevel: Int,
    val releaseName: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val socModel: String?,
    val socManufacturer: String?,

    val cpuCoreCount: Int,
    /** Highest per-core maximum frequency reported by `cpufreq`, in GHz. Null when unreadable. */
    val maxCpuFrequencyGHz: Float?,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val isLowRamDevice: Boolean,

    val cameras: List<CameraCapability>,
    val encoders: List<EncoderCapability>,
    /** Camera-id pairs the platform says may run concurrently. Empty means unsupported. */
    val concurrentCameraPairs: List<Pair<String, String>>,

    val thermal: ThermalApiSupport,
    val sensors: SensorSupport,
    val display: DisplayCapability,

    /**
     * Result of the deterministic start-up CPU probe, in arbitrary "work units per
     * millisecond"; higher is faster. Null when the probe has not run.
     *
     * This is a genuine on-device measurement and is labelled as such in diagnostics, but it
     * measures only single-thread integer/float throughput -- it is a tiebreaker, not a
     * substitute for real recording benchmarks.
     */
    val cpuProbeScore: Float?,
) {
    val rearCameras: List<CameraCapability> get() = cameras.filter { it.lensFacing == LensFacing.Back }
    val frontCameras: List<CameraCapability> get() = cameras.filter { it.lensFacing == LensFacing.Front }

    /** True when at least one hardware-accelerated encoder exists for [mimeType]. */
    fun hasHardwareEncoder(mimeType: String): Boolean =
        encoders.any { it.mimeType == mimeType && it.hardwareAccelerated }

    fun encoderFor(mimeType: String): EncoderCapability? =
        encoders.filter { it.mimeType == mimeType }
            .sortedByDescending { it.hardwareAccelerated }
            .firstOrNull()

    val supportsConcurrentCameras: Boolean get() = concurrentCameraPairs.isNotEmpty()

    companion object {
        /** Used before probing completes, so the UI always has something coherent to show. */
        fun unknown(): DeviceCapabilities = DeviceCapabilities(
            apiLevel = 0,
            releaseName = "",
            manufacturer = "",
            model = "",
            device = "",
            socModel = null,
            socManufacturer = null,
            cpuCoreCount = 1,
            maxCpuFrequencyGHz = null,
            totalRamBytes = 0,
            availableRamBytes = 0,
            isLowRamDevice = true,
            cameras = emptyList(),
            encoders = emptyList(),
            concurrentCameraPairs = emptyList(),
            thermal = ThermalApiSupport(false, false, false),
            sensors = SensorSupport(false, false, false, null),
            display = DisplayCapability(0, 0, 0, 60f, false),
            cpuProbeScore = null,
        )
    }
}

enum class LensFacing { Back, Front, External, Unknown }

/** Camera2 hardware level, in increasing capability order. */
enum class CameraHardwareLevel { Legacy, Limited, Full, Level3, External, Unknown }

/**
 * One camera as CameraX and Camera2 describe it.
 *
 * @param supportedQualities CameraX `Quality` names actually supported for recording.
 * @param focalLengthsMm used to tell a main camera from an ultrawide or macro.
 * @param sensorAspectRatio active-array aspect ratio, e.g. 1.333 for a 4:3 sensor.
 */
data class CameraCapability(
    val cameraId: String,
    val lensFacing: LensFacing,
    val hardwareLevel: CameraHardwareLevel,
    val supportedQualities: List<String>,
    val supportedResolutions: List<Resolution>,
    val maxFrameRate: Int?,
    val supportedFrameRateRanges: List<IntRange>,
    val focalLengthsMm: List<Float>,
    val sensorAspectRatio: Float?,
    val supportsVideoStabilisation: Boolean,
    val supportedDynamicRanges: List<String>,
    val isLogicalMultiCamera: Boolean,
    val physicalCameraIds: List<String>,
) {
    /** Longest focal length, the usual signal that this is the main (not ultrawide) lens. */
    val primaryFocalLengthMm: Float? get() = focalLengthsMm.maxOrNull()
}

data class Resolution(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val aspectRatio: Float get() = if (height == 0) 0f else width.toFloat() / height
    override fun toString(): String = "${width}x$height"
}

/**
 * A video encoder from `MediaCodecList`.
 *
 * @param hardwareAccelerated `MediaCodecInfo.isHardwareAccelerated()`. Roadguard will not
 *   select a software-only video encoder for continuous recording at any resolution: on the
 *   baseline device that is a guaranteed thermal and frame-drop failure.
 * @param maxInstances `CodecCapabilities.getMaxSupportedInstances()`, which bounds dual
 *   camera recording.
 */
data class EncoderCapability(
    val name: String,
    val mimeType: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val bitrateRange: LongRange?,
    val maxInstances: Int?,
    /** Sizes the encoder reports as supported together with their maximum frame rate. */
    val achievableFrameRates: Map<Resolution, Int>,
) {
    fun supports(resolution: Resolution, frameRate: Int): Boolean {
        val fits = (maxWidth == null || resolution.width <= maxWidth) &&
            (maxHeight == null || resolution.height <= maxHeight)
        val rate = achievableFrameRates[resolution]
        return fits && (rate == null || frameRate <= rate)
    }
}

/** Which thermal APIs this device actually answers. */
data class ThermalApiSupport(
    val hasThermalStatus: Boolean,
    val hasThermalHeadroom: Boolean,
    val hasHeadroomThresholds: Boolean,
)

/**
 * Sensors relevant to event detection.
 *
 * [hasGyroscope] matters a great deal: many budget phones ship without one, and Roadguard's
 * impact detector must degrade to an accelerometer-only mode rather than silently not work.
 */
data class SensorSupport(
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasRotationVector: Boolean,
    /** Fastest accelerometer rate the platform reports, in Hz. Null when unknown. */
    val maxAccelerometerRateHz: Int?,
)

data class DisplayCapability(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val isHdrCapable: Boolean,
)

/** Well-known video MIME types, so string literals do not spread through the codebase. */
object VideoMimeTypes {
    const val H264 = "video/avc"
    const val HEVC = "video/hevc"
    const val AV1 = "video/av01"
    const val VP9 = "video/x-vnd.on2.vp9"
}
