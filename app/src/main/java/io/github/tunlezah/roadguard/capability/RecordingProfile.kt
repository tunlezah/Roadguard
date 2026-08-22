package io.github.tunlezah.roadguard.capability

import io.github.tunlezah.roadguard.settings.FrameRateSetting
import io.github.tunlezah.roadguard.settings.QualitySetting
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.TriState
import io.github.tunlezah.roadguard.thermal.ThermalPlan
import kotlin.math.roundToInt

/**
 * A concrete recording configuration: exactly what the recorder will ask CameraX for.
 *
 * @param cameraXQuality the CameraX `Quality` constant name (`SD`, `HD`, `FHD`, `UHD`).
 * @param targetBitrateBps 0 means "let the device decide", which is the safe default; a
 *   non-zero value is only set when Roadguard is deliberately reducing bitrate.
 * @param rationale plain-English reasons this profile was chosen, surfaced in Diagnostics so
 *   a user can see *why* Auto picked what it picked.
 */
data class RecordingProfile(
    val cameraXQuality: String,
    val resolution: Resolution?,
    val frameRate: Int,
    val codecMimeType: String,
    val targetBitrateBps: Int,
    val stabilisation: Boolean,
    val nightAssist: Boolean,
    val hdr: Boolean,
    val dualCamera: Boolean,
    val burnInOverlays: Boolean,
    val tier: DeviceTier,
    val isAuto: Boolean,
    val rationale: List<String>,
) {
    val label: String
        get() = buildString {
            append(resolution?.toString() ?: cameraXQuality)
            append(" @ ")
            append(frameRate)
            append(" fps ")
            append(if (codecMimeType == VideoMimeTypes.HEVC) "HEVC" else "H.264")
            if (isAuto) append(" (Auto)")
        }

    /** True when [other] differs in a way that requires the camera use cases to be rebound. */
    fun requiresRebindFrom(other: RecordingProfile?): Boolean = other == null ||
        other.cameraXQuality != cameraXQuality ||
        other.frameRate != frameRate ||
        other.codecMimeType != codecMimeType ||
        other.targetBitrateBps != targetBitrateBps ||
        other.stabilisation != stabilisation ||
        other.hdr != hdr ||
        other.dualCamera != dualCamera ||
        other.burnInOverlays != burnInOverlays
}

/**
 * Chooses the recording profile.
 *
 * ### What "Auto" means here
 *
 * Auto is **not** "the highest thing the hardware admits to". It is the highest quality that
 * a device of this tier can be expected to sustain for hours without dropping frames or
 * cooking itself. That is why the ladder tops out at 1080p30 even on a [DeviceTier.Capable]
 * device: 4K recording on a phone is a well-known thermal cliff, and the specification is
 * explicit that a slightly lower-quality reliable recording beats a theoretically better one
 * that overheats. A user who wants more can select it manually, and is warned.
 *
 * Pure and Android-free, so the whole decision table is unit tested.
 */
object RecordingProfileSelector {

    /** Resolution ladder, richest first. Manual settings may exceed it; Auto may not. */
    private val LADDER: List<Rung> = listOf(
        Rung("UHD", Resolution(3840, 2160)),
        Rung("FHD", Resolution(1920, 1080)),
        Rung("HD", Resolution(1280, 720)),
        Rung("SD", Resolution(720, 480)),
    )

    /** The best rung Auto will choose per tier. Everything above must be selected by hand. */
    private val AUTO_CEILING: Map<DeviceTier, String> = mapOf(
        DeviceTier.Baseline to "HD",
        DeviceTier.Standard to "FHD",
        DeviceTier.Capable to "FHD",
    )

    private val AUTO_FRAME_RATE: Map<DeviceTier, Int> = mapOf(
        DeviceTier.Baseline to 30,
        DeviceTier.Standard to 30,
        DeviceTier.Capable to 30,
    )

    fun select(
        capabilities: DeviceCapabilities,
        assessment: DeviceTierAssessment,
        settings: Settings,
        thermalPlan: ThermalPlan,
    ): RecordingProfile {
        val rationale = mutableListOf<String>()
        val tier = assessment.tier
        rationale += "Device tier ${tier.label} (${assessment.reasons.joinToString("; ")})"

        val camera = pickCamera(capabilities, settings)
        val supportedQualities = camera?.supportedQualities?.toSet() ?: emptySet()
        if (supportedQualities.isEmpty()) {
            rationale += "Camera has not reported its supported qualities yet; using a conservative default"
        }

        val codec = predictCodec(capabilities, rationale)

        // 1. Start from what the user asked for.
        val requestedRung = when (settings.quality) {
            QualitySetting.Auto -> LADDER.first { it.name == (AUTO_CEILING[tier] ?: "HD") }
                .also { rationale += "Auto caps this tier at ${it.name} for sustained recording" }

            QualitySetting.Uhd2160p -> LADDER.first { it.name == "UHD" }
                .also { rationale += "2160p selected manually; expect extra heat and storage use" }

            QualitySetting.FullHd1080p -> LADDER.first { it.name == "FHD" }
            QualitySetting.Hd720p -> LADDER.first { it.name == "HD" }
            QualitySetting.Sd480p -> LADDER.first { it.name == "SD" }
        }

        // 2. Apply the thermal step-down.
        val afterThermal = stepDown(requestedRung, thermalPlan.qualityStepDown).also {
            if (it != requestedRung) {
                rationale += "Thermal level ${thermalPlan.level.label} stepped resolution down to ${it.name}"
            }
        }

        // 3. Reduce to something the camera and a hardware encoder actually support.
        val achievable = firstAchievable(afterThermal, supportedQualities, capabilities, codec, rationale)

        // 4. Frame rate: requested, capped by thermal, capped by encoder.
        val requestedFps = settings.frameRate.fps ?: (AUTO_FRAME_RATE[tier] ?: 30)
        val cappedFps = listOfNotNull(requestedFps, thermalPlan.frameRateCap).min()
        if (cappedFps != requestedFps) {
            rationale += "Thermal level ${thermalPlan.level.label} capped frame rate at $cappedFps fps"
        }
        val cameraMaxFps = camera?.maxFrameRate
        val frameRate = if (cameraMaxFps != null && cameraMaxFps in 1 until cappedFps) {
            rationale += "Camera reports a maximum of $cameraMaxFps fps"
            cameraMaxFps
        } else {
            cappedFps
        }

        // 5. Bitrate: only overridden when Roadguard is deliberately reducing it.
        val bitrate = if (thermalPlan.bitrateScale < 1.0f) {
            val nominal = nominalBitrate(achievable.resolution, frameRate, codec)
            (nominal * thermalPlan.bitrateScale).roundToInt().also {
                rationale += "Bitrate scaled to ${thermalPlan.bitrateScale} (${it / 1_000_000f} Mbps) for thermal headroom"
            }
        } else {
            0
        }

        val stabilisation = resolveStabilisation(settings, camera, tier, thermalPlan, rationale)
        val nightAssist = settings.nightAssist != TriState.Off && thermalPlan.allowNightAssist &&
            tier != DeviceTier.Baseline
        val dual = settings.dualCameraEnabled &&
            thermalPlan.allowSecondCamera &&
            capabilities.supportsConcurrentCameras &&
            tier == DeviceTier.Capable
        if (settings.dualCameraEnabled && !dual) {
            rationale += "Dual camera requested but not enabled: " + when {
                !capabilities.supportsConcurrentCameras -> "the platform reports no concurrent camera pairs"
                !thermalPlan.allowSecondCamera -> "thermal level ${thermalPlan.level.label}"
                else -> "device tier ${tier.label} is not fast enough to keep the primary recording safe"
            }
        }

        val burnIn = settings.anyVideoOverlayEnabled && thermalPlan.allowVideoOverlay
        if (settings.anyVideoOverlayEnabled && !burnIn) {
            rationale += "Video overlays suspended at thermal level ${thermalPlan.level.label}"
        }

        return RecordingProfile(
            cameraXQuality = achievable.name,
            resolution = achievable.resolution,
            frameRate = frameRate,
            codecMimeType = codec,
            targetBitrateBps = bitrate,
            stabilisation = stabilisation,
            nightAssist = nightAssist,
            // HDR is never chosen automatically: it narrows encoder support, complicates
            // playback of evidence footage and costs power for no evidential benefit.
            hdr = false,
            dualCamera = dual,
            burnInOverlays = burnIn,
            tier = tier,
            isAuto = settings.quality == QualitySetting.Auto &&
                settings.frameRate == FrameRateSetting.Auto,
            rationale = rationale,
        )
    }

    /**
     * Picks the camera to record with.
     *
     * For the rear camera Roadguard prefers the lens with the *longest* focal length among
     * those CameraX exposes, because that is the main camera; an ultrawide or macro would
     * give a distorted, lower-quality image with a much worse low-light response.
     */
    fun pickCamera(capabilities: DeviceCapabilities, settings: Settings): CameraCapability? {
        val pool = when (settings.cameraFacing) {
            io.github.tunlezah.roadguard.settings.CameraFacing.Front -> capabilities.frontCameras
            io.github.tunlezah.roadguard.settings.CameraFacing.Rear -> capabilities.rearCameras
        }
        if (pool.isEmpty()) return capabilities.cameras.firstOrNull()
        return pool.maxWithOrNull(
            compareBy(
                { it.primaryFocalLengthMm ?: 0f },
                { it.supportedResolutions.maxOfOrNull { r -> r.pixels } ?: 0L },
            ),
        )
    }

    /**
     * Predicts the codec the recorder will produce.
     *
     * Roadguard does not *choose* the codec. CameraX 1.6.x derives it from the device's own
     * encoder profiles and exposes no override, so the honest thing to do is predict it for
     * bitrate arithmetic and diagnostics, and report the real value once a file exists.
     *
     * The prediction is simply "the hardware encoder the device is most likely to use":
     * hardware H.264 where present, because it is what `CamcorderProfile`-derived profiles
     * overwhelmingly specify and what every player, insurer and police system can open;
     * hardware HEVC when no hardware H.264 encoder is reported at all. Software encoders are
     * never predicted, because Roadguard would not sustain continuous recording on one and
     * says so rather than pretending otherwise.
     */
    fun predictCodec(capabilities: DeviceCapabilities, rationale: MutableList<String>): String {
        val hardwareH264 = capabilities.hasHardwareEncoder(VideoMimeTypes.H264)
        val hardwareHevc = capabilities.hasHardwareEncoder(VideoMimeTypes.HEVC)
        return when {
            hardwareH264 -> {
                rationale += "Expecting hardware H.264: widest playback compatibility for evidence footage"
                VideoMimeTypes.H264
            }

            hardwareHevc -> {
                rationale += "Expecting hardware HEVC: no hardware H.264 encoder was reported"
                VideoMimeTypes.HEVC
            }

            else -> {
                rationale += "No hardware video encoder was reported; the recorder's own choice will be shown in Diagnostics"
                VideoMimeTypes.H264
            }
        }
    }

    private fun resolveStabilisation(
        settings: Settings,
        camera: CameraCapability?,
        tier: DeviceTier,
        thermalPlan: ThermalPlan,
        rationale: MutableList<String>,
    ): Boolean {
        if (!thermalPlan.allowStabilisation) return false
        val supported = camera?.supportsVideoStabilisation == true
        return when (settings.videoStabilisation) {
            TriState.On -> supported.also {
                if (!it) rationale += "Stabilisation requested but the camera does not report support"
            }

            TriState.Off -> false
            // A cradle-mounted phone barely benefits from EIS, which crops the frame and costs
            // power, so Auto leaves it off on anything but a fast device.
            TriState.Auto -> supported && tier == DeviceTier.Capable
        }
    }

    private fun stepDown(rung: Rung, steps: Int): Rung {
        if (steps <= 0) return rung
        val index = LADDER.indexOf(rung)
        return LADDER[(index + steps).coerceAtMost(LADDER.lastIndex)]
    }

    private fun firstAchievable(
        preferred: Rung,
        supportedQualities: Set<String>,
        capabilities: DeviceCapabilities,
        codec: String,
        rationale: MutableList<String>,
    ): Rung {
        val start = LADDER.indexOf(preferred)
        val encoder = capabilities.encoderFor(codec)
        for (index in start until LADDER.size) {
            val rung = LADDER[index]
            val cameraOk = supportedQualities.isEmpty() || rung.name in supportedQualities
            val encoderOk = encoder == null || encoder.supports(rung.resolution, 30)
            if (cameraOk && encoderOk) {
                if (index != start) {
                    rationale += "${preferred.name} is not supported here; using ${rung.name}"
                }
                return rung
            }
        }
        rationale += "No ladder entry was reported as supported; requesting ${LADDER.last().name}"
        return LADDER.last()
    }

    /**
     * A nominal bitrate for a resolution/frame rate, used only when Roadguard is reducing
     * bitrate on purpose.
     *
     * Derived from bits per pixel per frame rather than a lookup table so it scales sensibly
     * across the ladder: 0.10 bpp for H.264 and 0.07 bpp for HEVC are mid-range values for
     * "legible detail in a moving scene". `docs/benchmarking.md` records how to replace these
     * with measured figures.
     */
    fun nominalBitrate(resolution: Resolution?, frameRate: Int, codec: String): Int {
        val pixels = resolution?.pixels ?: (1280L * 720)
        val bitsPerPixel = if (codec == VideoMimeTypes.HEVC) 0.07 else 0.10
        return (pixels * frameRate * bitsPerPixel).roundToInt().coerceAtLeast(1_000_000)
    }

    private data class Rung(val name: String, val resolution: Resolution)
}
