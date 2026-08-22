package io.github.tunlezah.roadguard.capability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.settings.CameraFacing
import io.github.tunlezah.roadguard.settings.FrameRateSetting
import io.github.tunlezah.roadguard.settings.QualitySetting
import io.github.tunlezah.roadguard.settings.Settings
import io.github.tunlezah.roadguard.settings.TriState
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.thermal.ThermalPolicy
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.planFor
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Pins down [RecordingProfileSelector], the decision table that turns "what the user asked
 * for" plus "what this phone can actually do" plus "how hot it is" into the exact
 * configuration handed to CameraX.
 *
 * The contracts asserted here are the ones a dashcam cannot get wrong:
 *
 *  * **Auto is not "maximum".** Auto means the best quality the device can sustain for hours
 *    in a hot windscreen, so it tops out at 1080p30 even on a [DeviceTier.Capable] phone that
 *    advertises 4K. A slightly lower-quality recording that runs all day beats a better one
 *    that thermally collapses ten minutes into a trip -- and the reason is written into the
 *    rationale so a user can see it in Diagnostics rather than assuming Roadguard is broken.
 *  * **Never request what cannot be delivered.** Anything the camera or a hardware encoder does
 *    not report is walked down the ladder, and the step-down is explained.
 *  * **Heat is paid for in the documented order.** At [ThermalLevel.Elevated] recording quality
 *    is untouched -- identical resolution, frame rate and bitrate -- and only optional extras are
 *    shed. Resolution is only surrendered at [ThermalLevel.High] and beyond.
 *  * **Expensive extras are gated three ways.** Dual camera needs platform concurrency support,
 *    a Capable tier *and* thermal headroom; every refusal says which gate closed.
 *  * **Compatibility over file size.** H.264 is predicted wherever a hardware H.264 encoder
 *    exists, because evidence footage has to open in whatever a court, insurer or police
 *    officer happens to use.
 *
 * Plain JVM tests: the selector, [Settings] and the thermal plans are all Android-free, and no
 * clock or random source is involved.
 */
class RecordingProfileSelectorTest {

    private companion object {
        val UHD = Resolution(3840, 2160)
        val FHD = Resolution(1920, 1080)
        val HD = Resolution(1280, 720)
        val SD = Resolution(720, 480)

        val NORMAL = planFor(ThermalLevel.Normal)
        val ELEVATED = planFor(ThermalLevel.Elevated)
        val HIGH = planFor(ThermalLevel.High)
        val CRITICAL = planFor(ThermalLevel.Critical)
    }

    // ------------------------------------------------------------------ factories

    private fun camera(
        cameraId: String = "0",
        lensFacing: LensFacing = LensFacing.Back,
        hardwareLevel: CameraHardwareLevel = CameraHardwareLevel.Full,
        supportedQualities: List<String> = listOf("UHD", "FHD", "HD", "SD"),
        supportedResolutions: List<Resolution> = listOf(UHD, FHD, HD, SD),
        maxFrameRate: Int? = 60,
        focalLengthsMm: List<Float> = listOf(4.7f),
        supportsVideoStabilisation: Boolean = true,
    ) = CameraCapability(
        cameraId = cameraId,
        lensFacing = lensFacing,
        hardwareLevel = hardwareLevel,
        supportedQualities = supportedQualities,
        supportedResolutions = supportedResolutions,
        maxFrameRate = maxFrameRate,
        supportedFrameRateRanges = listOf(IntRange(15, maxFrameRate ?: 30)),
        focalLengthsMm = focalLengthsMm,
        sensorAspectRatio = 1.333f,
        supportsVideoStabilisation = supportsVideoStabilisation,
        supportedDynamicRanges = listOf("SDR"),
        isLogicalMultiCamera = false,
        physicalCameraIds = emptyList(),
    )

    private fun encoder(
        mimeType: String = VideoMimeTypes.H264,
        hardwareAccelerated: Boolean = true,
        maxWidth: Int? = UHD.width,
        maxHeight: Int? = UHD.height,
        achievableFrameRates: Map<Resolution, Int> = emptyMap(),
        maxInstances: Int? = 4,
    ) = EncoderCapability(
        name = if (hardwareAccelerated) "c2.hw.$mimeType" else "c2.android.sw.$mimeType",
        mimeType = mimeType,
        hardwareAccelerated = hardwareAccelerated,
        softwareOnly = !hardwareAccelerated,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        bitrateRange = null,
        maxInstances = maxInstances,
        achievableFrameRates = achievableFrameRates,
    )

    private fun device(
        cameras: List<CameraCapability> = listOf(camera()),
        encoders: List<EncoderCapability> = listOf(encoder()),
        concurrentCameraPairs: List<Pair<String, String>> = emptyList(),
    ) = DeviceCapabilities.unknown().copy(
        isLowRamDevice = false,
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        maxCpuFrequencyGHz = 2.5f,
        cameras = cameras,
        encoders = encoders,
        concurrentCameraPairs = concurrentCameraPairs,
    )

    private fun assessment(tier: DeviceTier, points: Int = 6) =
        DeviceTierAssessment(tier, listOf("test fixture for ${tier.label}"), points)

    /** Overlays default to on in [Settings], which matters for the burn-in assertions. */
    private fun settings() = Settings()

    private fun rationaleOf(profile: RecordingProfile) = profile.rationale.joinToString(" | ")

    private fun assertRationaleMentions(profile: RecordingProfile, fragment: String) {
        assertWithMessage("rationale should mention \"$fragment\": ${rationaleOf(profile)}")
            .that(profile.rationale.any { it.contains(fragment) })
            .isTrue()
    }

    // ------------------------------------------------------- Auto is not "maximum"

    @Test
    fun `Auto on a Capable 4K-capable device still selects 1080p30`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(
                cameras = listOf(camera(supportedQualities = listOf("UHD", "FHD", "HD", "SD"))),
                encoders = listOf(encoder(maxWidth = UHD.width, maxHeight = UHD.height)),
            ),
            assessment = assessment(DeviceTier.Capable),
            settings = settings(),
            thermalPlan = NORMAL,
        )

        assertThat(profile.cameraXQuality).isEqualTo("FHD")
        assertThat(profile.resolution).isEqualTo(FHD)
        assertThat(profile.frameRate).isEqualTo(30)
        assertThat(profile.isAuto).isTrue()
        assertRationaleMentions(profile, "Auto caps this tier at FHD for sustained recording")
    }

    @Test
    fun `Auto on a Baseline device selects 720p30`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(),
            assessment = assessment(DeviceTier.Baseline, points = 1),
            settings = settings(),
            thermalPlan = NORMAL,
        )

        assertThat(profile.cameraXQuality).isEqualTo("HD")
        assertThat(profile.resolution).isEqualTo(HD)
        assertThat(profile.frameRate).isEqualTo(30)
        assertRationaleMentions(profile, "Auto caps this tier at HD")
    }

    @Test
    fun `Auto never exceeds 1080p on any tier`() {
        for (tier in DeviceTier.entries) {
            val profile = RecordingProfileSelector.select(
                capabilities = device(),
                assessment = assessment(tier),
                settings = settings(),
                thermalPlan = NORMAL,
            )
            assertWithMessage(tier.label).that(profile.resolution!!.pixels)
                .isAtMost(FHD.pixels)
        }
    }

    // ---------------------------------------------------- manual selection is honoured

    @Test
    fun `manual 2160p is honoured when the camera and encoder support it and warns about heat`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(),
            assessment = assessment(DeviceTier.Capable),
            settings = settings().copy(quality = QualitySetting.Uhd2160p),
            thermalPlan = NORMAL,
        )

        assertThat(profile.cameraXQuality).isEqualTo("UHD")
        assertThat(profile.resolution).isEqualTo(UHD)
        assertThat(profile.isAuto).isFalse()
        assertRationaleMentions(profile, "expect extra heat and storage use")
    }

    @Test
    fun `manual 2160p is honoured even on a Baseline device because the user asked for it`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(),
            assessment = assessment(DeviceTier.Baseline, points = 1),
            settings = settings().copy(quality = QualitySetting.Uhd2160p),
            thermalPlan = NORMAL,
        )

        assertThat(profile.resolution).isEqualTo(UHD)
        assertRationaleMentions(profile, "2160p selected manually")
    }

    @Test
    fun `each manual quality maps onto its ladder rung`() {
        val expected = mapOf(
            QualitySetting.Uhd2160p to UHD,
            QualitySetting.FullHd1080p to FHD,
            QualitySetting.Hd720p to HD,
            QualitySetting.Sd480p to SD,
        )

        for ((quality, resolution) in expected) {
            val profile = RecordingProfileSelector.select(
                capabilities = device(),
                assessment = assessment(DeviceTier.Capable),
                settings = settings().copy(quality = quality),
                thermalPlan = NORMAL,
            )
            assertWithMessage(quality.label).that(profile.resolution).isEqualTo(resolution)
        }
    }

    // ------------------------------------------------------------ ladder fallback

    @Test
    fun `an unsupported quality falls back down the ladder and says so`() {
        data class Case(
            val name: String,
            val requested: QualitySetting,
            val cameraQualities: List<String>,
            val expectedQuality: String,
            val expectedResolution: Resolution,
            val note: String,
        )

        val cases = listOf(
            Case(
                name = "camera does not list UHD",
                requested = QualitySetting.Uhd2160p,
                cameraQualities = listOf("FHD", "HD", "SD"),
                expectedQuality = "FHD",
                expectedResolution = FHD,
                note = "UHD is not supported here; using FHD",
            ),
            Case(
                name = "camera lists only HD and below",
                requested = QualitySetting.Uhd2160p,
                cameraQualities = listOf("HD", "SD"),
                expectedQuality = "HD",
                expectedResolution = HD,
                note = "UHD is not supported here; using HD",
            ),
            Case(
                name = "camera does not list FHD",
                requested = QualitySetting.FullHd1080p,
                cameraQualities = listOf("UHD", "HD", "SD"),
                expectedQuality = "HD",
                expectedResolution = HD,
                note = "FHD is not supported here; using HD",
            ),
            Case(
                name = "camera lists only SD",
                requested = QualitySetting.Hd720p,
                cameraQualities = listOf("SD"),
                expectedQuality = "SD",
                expectedResolution = SD,
                note = "HD is not supported here; using SD",
            ),
        )

        for (case in cases) {
            val profile = RecordingProfileSelector.select(
                capabilities = device(cameras = listOf(camera(supportedQualities = case.cameraQualities))),
                assessment = assessment(DeviceTier.Capable),
                settings = settings().copy(quality = case.requested),
                thermalPlan = NORMAL,
            )

            assertWithMessage(case.name).that(profile.cameraXQuality).isEqualTo(case.expectedQuality)
            assertWithMessage(case.name).that(profile.resolution).isEqualTo(case.expectedResolution)
            assertWithMessage("${case.name}: ${rationaleOf(profile)}")
                .that(profile.rationale.any { it.contains(case.note) })
                .isTrue()
        }
    }

    @Test
    fun `a resolution the hardware encoder cannot reach is stepped down even when the camera lists it`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(
                cameras = listOf(camera(supportedQualities = listOf("UHD", "FHD", "HD", "SD"))),
                encoders = listOf(encoder(maxWidth = FHD.width, maxHeight = FHD.height)),
            ),
            assessment = assessment(DeviceTier.Capable),
            settings = settings().copy(quality = QualitySetting.Uhd2160p),
            thermalPlan = NORMAL,
        )

        assertThat(profile.resolution).isEqualTo(FHD)
        assertRationaleMentions(profile, "UHD is not supported here; using FHD")
    }

    @Test
    fun `a camera that has not reported its qualities yet gets a conservative default and a note`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(cameras = listOf(camera(supportedQualities = emptyList()))),
            assessment = assessment(DeviceTier.Capable),
            settings = settings(),
            thermalPlan = NORMAL,
        )

        assertThat(profile.resolution).isEqualTo(FHD)
        assertRationaleMentions(profile, "has not reported its supported qualities yet")
    }

    // --------------------------------------------------------------- codec policy

    @Test
    fun `hardware H264 wins over hardware HEVC for playback compatibility`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(
                encoders = listOf(
                    encoder(mimeType = VideoMimeTypes.HEVC),
                    encoder(mimeType = VideoMimeTypes.H264),
                ),
            ),
            assessment = assessment(DeviceTier.Capable),
            settings = settings(),
            thermalPlan = NORMAL,
        )

        assertThat(profile.codecMimeType).isEqualTo(VideoMimeTypes.H264)
        assertRationaleMentions(profile, "widest playback compatibility for evidence footage")
        assertThat(profile.label).contains("H.264")
    }

    @Test
    fun `hardware HEVC is predicted only when no hardware H264 encoder exists`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(
                encoders = listOf(
                    encoder(mimeType = VideoMimeTypes.H264, hardwareAccelerated = false),
                    encoder(mimeType = VideoMimeTypes.HEVC),
                ),
            ),
            assessment = assessment(DeviceTier.Capable),
            settings = settings(),
            thermalPlan = NORMAL,
        )

        assertThat(profile.codecMimeType).isEqualTo(VideoMimeTypes.HEVC)
        assertRationaleMentions(profile, "no hardware H.264 encoder was reported")
        assertThat(profile.label).contains("HEVC")
    }

    @Test
    fun `a device with no hardware encoder at all is predicted as H264 with a diagnostics caveat`() {
        // Software encoders are never predicted; Roadguard says it does not know instead.
        val rationale = mutableListOf<String>()
        val codec = RecordingProfileSelector.predictCodec(
            capabilities = device(
                encoders = listOf(encoder(mimeType = VideoMimeTypes.HEVC, hardwareAccelerated = false)),
            ),
            rationale = rationale,
        )

        assertThat(codec).isEqualTo(VideoMimeTypes.H264)
        assertThat(rationale.single()).contains("No hardware video encoder was reported")
        assertThat(rationale.single()).contains("Diagnostics")
    }

    @Test
    fun `the predicted codec does not depend on the user's settings`() {
        // There is deliberately no codec setting: CameraX 1.6.x gives an app no override, so
        // the codec is predicted from hardware alone and reported, never "selected".
        val capabilities = device(
            encoders = listOf(
                encoder(mimeType = VideoMimeTypes.HEVC),
                encoder(mimeType = VideoMimeTypes.H264),
            ),
        )
        val variants = listOf(
            settings(),
            settings().copy(quality = QualitySetting.Uhd2160p, frameRate = FrameRateSetting.Fps60),
            settings().copy(quality = QualitySetting.Sd480p, microphoneEnabled = true),
        )

        for (variant in variants) {
            val profile = RecordingProfileSelector.select(
                capabilities = capabilities,
                assessment = assessment(DeviceTier.Capable),
                settings = variant,
                thermalPlan = NORMAL,
            )
            assertWithMessage(variant.quality.label).that(profile.codecMimeType)
                .isEqualTo(VideoMimeTypes.H264)
        }
    }

    // --------------------------------------------------------- thermal interaction

    private fun capableDevice() = device(concurrentCameraPairs = listOf("0" to "1"))

    private fun selectAt(
        plan: io.github.tunlezah.roadguard.thermal.ThermalPlan,
        settings: Settings = settings(),
        tier: DeviceTier = DeviceTier.Capable,
    ) = RecordingProfileSelector.select(
        capabilities = capableDevice(),
        assessment = assessment(tier),
        settings = settings,
        thermalPlan = plan,
    )

    @Test
    fun `Elevated sheds optional work only and leaves recording quality untouched`() {
        val normal = selectAt(NORMAL)
        val elevated = selectAt(ELEVATED)

        assertThat(elevated.resolution).isEqualTo(normal.resolution)
        assertThat(elevated.frameRate).isEqualTo(normal.frameRate)
        assertThat(elevated.targetBitrateBps).isEqualTo(normal.targetBitrateBps)
        assertThat(elevated.cameraXQuality).isEqualTo(normal.cameraXQuality)
        assertThat(elevated.codecMimeType).isEqualTo(normal.codecMimeType)
        // Something must actually have been given up, or the plan would be a no-op.
        assertThat(normal.stabilisation).isTrue()
        assertThat(elevated.stabilisation).isFalse()
    }

    @Test
    fun `High steps resolution down one rung and starts constraining bitrate`() {
        val normal = selectAt(NORMAL)
        val high = selectAt(HIGH)

        assertThat(normal.resolution).isEqualTo(FHD)
        assertThat(high.resolution).isEqualTo(HD)
        assertThat(high.cameraXQuality).isEqualTo("HD")
        assertThat(high.targetBitrateBps).isGreaterThan(0)
        assertRationaleMentions(high, "stepped resolution down to HD")

        val nominal = RecordingProfileSelector.nominalBitrate(HD, high.frameRate, high.codecMimeType)
        assertThat(high.targetBitrateBps).isEqualTo((nominal * HIGH.bitrateScale).roundToInt())
        assertThat(high.targetBitrateBps).isLessThan(nominal)
    }

    @Test
    fun `Critical steps resolution down two rungs and drops burnt-in overlays`() {
        val overlaysOn = settings().copy(overlayDateTime = true, overlaySpeed = true)
        assertThat(overlaysOn.anyVideoOverlayEnabled).isTrue()

        val normal = selectAt(NORMAL, overlaysOn)
        val critical = selectAt(CRITICAL, overlaysOn)

        assertThat(normal.resolution).isEqualTo(FHD)
        assertThat(normal.burnInOverlays).isTrue()

        assertThat(critical.resolution).isEqualTo(SD)
        assertThat(critical.cameraXQuality).isEqualTo("SD")
        assertThat(critical.burnInOverlays).isFalse()
        assertThat(critical.targetBitrateBps).isGreaterThan(0)
        assertRationaleMentions(critical, "Video overlays suspended at thermal level Critical")
    }

    @Test
    fun `the thermal step-down never falls off the bottom of the ladder`() {
        val profile = selectAt(CRITICAL, settings().copy(quality = QualitySetting.Sd480p))

        assertThat(profile.resolution).isEqualTo(SD)
        assertThat(profile.cameraXQuality).isEqualTo("SD")
    }

    @Test
    fun `the thermal frame rate cap only ever reduces the requested rate`() {
        data class Case(
            val name: String,
            val plan: io.github.tunlezah.roadguard.thermal.ThermalPlan,
            val requested: FrameRateSetting,
            val expected: Int,
        )

        val cases = listOf(
            Case("Normal, 60 fps requested", NORMAL, FrameRateSetting.Fps60, 60),
            Case("High caps 60 fps at 30", HIGH, FrameRateSetting.Fps60, 30),
            Case("Critical caps 60 fps at 24", CRITICAL, FrameRateSetting.Fps60, 24),
            Case("Critical leaves 24 fps alone", CRITICAL, FrameRateSetting.Fps24, 24),
            Case("Elevated leaves 30 fps alone", ELEVATED, FrameRateSetting.Fps30, 30),
        )

        for (case in cases) {
            val profile = selectAt(case.plan, settings().copy(frameRate = case.requested))
            assertWithMessage(case.name).that(profile.frameRate).isEqualTo(case.expected)
        }
    }

    @Test
    fun `a camera that cannot reach the requested frame rate caps it and says so`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(cameras = listOf(camera(maxFrameRate = 24))),
            assessment = assessment(DeviceTier.Capable),
            settings = settings().copy(frameRate = FrameRateSetting.Fps30),
            thermalPlan = NORMAL,
        )

        assertThat(profile.frameRate).isEqualTo(24)
        assertRationaleMentions(profile, "Camera reports a maximum of 24 fps")
    }

    // ------------------------------------------------------------------- bitrate

    @Test
    fun `targetBitrateBps is zero exactly when the thermal plan is not scaling bitrate`() {
        for (level in ThermalLevel.entries) {
            val plan = planFor(level)
            val profile = selectAt(plan)
            if (plan.bitrateScale == 1.0f) {
                assertWithMessage("${level.label} lets the device decide")
                    .that(profile.targetBitrateBps).isEqualTo(0)
            } else {
                assertWithMessage("${level.label} deliberately reduces bitrate")
                    .that(profile.targetBitrateBps).isGreaterThan(0)
                assertRationaleMentions(profile, "Bitrate scaled to")
            }
        }
        // Guards the test above against a future plan table where nothing scales bitrate.
        assertThat(NORMAL.bitrateScale).isEqualTo(1.0f)
        assertThat(HIGH.bitrateScale).isLessThan(1.0f)
    }

    @Test
    fun `nominalBitrate scales with pixels and frame rate`() {
        val hdAt30 = RecordingProfileSelector.nominalBitrate(HD, 30, VideoMimeTypes.H264)
        val hdAt60 = RecordingProfileSelector.nominalBitrate(HD, 60, VideoMimeTypes.H264)
        val fhdAt30 = RecordingProfileSelector.nominalBitrate(FHD, 30, VideoMimeTypes.H264)
        val uhdAt30 = RecordingProfileSelector.nominalBitrate(UHD, 30, VideoMimeTypes.H264)

        assertThat(hdAt60).isEqualTo(hdAt30 * 2)
        // FHD is exactly 2.25x the pixels of HD; UHD is exactly 4x FHD.
        assertThat(fhdAt30).isEqualTo((hdAt30 * 2.25).roundToInt())
        assertThat(uhdAt30).isEqualTo(fhdAt30 * 4)
    }

    @Test
    fun `nominalBitrate is lower for HEVC than for H264 at the same resolution`() {
        for (resolution in listOf(UHD, FHD, HD)) {
            val h264 = RecordingProfileSelector.nominalBitrate(resolution, 30, VideoMimeTypes.H264)
            val hevc = RecordingProfileSelector.nominalBitrate(resolution, 30, VideoMimeTypes.HEVC)
            assertWithMessage(resolution.toString()).that(hevc).isLessThan(h264)
        }
    }

    @Test
    fun `nominalBitrate never drops below 1 Mbps and treats a missing resolution as 720p`() {
        assertThat(RecordingProfileSelector.nominalBitrate(SD, 24, VideoMimeTypes.HEVC))
            .isEqualTo(1_000_000)
        assertThat(RecordingProfileSelector.nominalBitrate(Resolution(160, 120), 1, VideoMimeTypes.H264))
            .isEqualTo(1_000_000)
        assertThat(RecordingProfileSelector.nominalBitrate(null, 30, VideoMimeTypes.H264))
            .isEqualTo(RecordingProfileSelector.nominalBitrate(HD, 30, VideoMimeTypes.H264))
    }

    // --------------------------------------------------------------- dual camera

    @Test
    fun `dual camera is granted only when concurrency, tier and thermal headroom all agree`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(
                cameras = listOf(camera(cameraId = "0"), camera(cameraId = "1", lensFacing = LensFacing.Front)),
                concurrentCameraPairs = listOf("0" to "1"),
            ),
            assessment = assessment(DeviceTier.Capable),
            settings = settings().copy(dualCameraEnabled = true),
            thermalPlan = NORMAL,
        )

        assertThat(profile.dualCamera).isTrue()
        assertThat(profile.rationale.none { it.contains("Dual camera requested but not enabled") }).isTrue()
    }

    @Test
    fun `dual camera is refused when the platform reports no concurrent pairs`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(concurrentCameraPairs = emptyList()),
            assessment = assessment(DeviceTier.Capable),
            settings = settings().copy(dualCameraEnabled = true),
            thermalPlan = NORMAL,
        )

        assertThat(profile.dualCamera).isFalse()
        assertRationaleMentions(profile, "the platform reports no concurrent camera pairs")
    }

    @Test
    fun `dual camera is refused at Elevated and above even on a Capable device`() {
        for (plan in listOf(ELEVATED, HIGH, CRITICAL)) {
            val profile = RecordingProfileSelector.select(
                capabilities = device(concurrentCameraPairs = listOf("0" to "1")),
                assessment = assessment(DeviceTier.Capable),
                settings = settings().copy(dualCameraEnabled = true),
                thermalPlan = plan,
            )

            assertWithMessage(plan.level.label).that(profile.dualCamera).isFalse()
            assertWithMessage("${plan.level.label}: ${rationaleOf(profile)}")
                .that(profile.rationale.any { it.contains("thermal level ${plan.level.label}") })
                .isTrue()
        }
    }

    @Test
    fun `dual camera is refused below the Capable tier`() {
        for (tier in listOf(DeviceTier.Baseline, DeviceTier.Standard)) {
            val profile = RecordingProfileSelector.select(
                capabilities = device(concurrentCameraPairs = listOf("0" to "1")),
                assessment = assessment(tier),
                settings = settings().copy(dualCameraEnabled = true),
                thermalPlan = NORMAL,
            )

            assertWithMessage(tier.label).that(profile.dualCamera).isFalse()
            assertWithMessage("${tier.label}: ${rationaleOf(profile)}")
                .that(
                    profile.rationale.any {
                        it.contains("device tier ${tier.label} is not fast enough")
                    },
                )
                .isTrue()
        }
    }

    @Test
    fun `no dual camera complaint is recorded when the user never asked for it`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(concurrentCameraPairs = emptyList()),
            assessment = assessment(DeviceTier.Baseline, points = 1),
            settings = settings().copy(dualCameraEnabled = false),
            thermalPlan = CRITICAL,
        )

        assertThat(profile.dualCamera).isFalse()
        assertThat(profile.rationale.none { it.contains("Dual camera") }).isTrue()
    }

    // ------------------------------------------------------- optional feature gates

    @Test
    fun `Auto stabilisation is offered only on a Capable device that reports support`() {
        data class Case(val name: String, val tier: DeviceTier, val supported: Boolean, val expected: Boolean)

        val cases = listOf(
            Case("Capable and supported", DeviceTier.Capable, true, true),
            Case("Capable but unsupported", DeviceTier.Capable, false, false),
            Case("Standard and supported", DeviceTier.Standard, true, false),
            Case("Baseline and supported", DeviceTier.Baseline, true, false),
        )

        for (case in cases) {
            val profile = RecordingProfileSelector.select(
                capabilities = device(
                    cameras = listOf(camera(supportsVideoStabilisation = case.supported)),
                ),
                assessment = assessment(case.tier),
                settings = settings().copy(videoStabilisation = TriState.Auto),
                thermalPlan = NORMAL,
            )
            assertWithMessage(case.name).that(profile.stabilisation).isEqualTo(case.expected)
        }
    }

    @Test
    fun `stabilisation requested on a camera without support is refused with an explanation`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(cameras = listOf(camera(supportsVideoStabilisation = false))),
            assessment = assessment(DeviceTier.Capable),
            settings = settings().copy(videoStabilisation = TriState.On),
            thermalPlan = NORMAL,
        )

        assertThat(profile.stabilisation).isFalse()
        assertRationaleMentions(profile, "the camera does not report support")
    }

    @Test
    fun `HDR is never selected automatically`() {
        for (tier in DeviceTier.entries) {
            for (level in ThermalLevel.entries) {
                val profile = RecordingProfileSelector.select(
                    capabilities = device(),
                    assessment = assessment(tier),
                    settings = settings(),
                    thermalPlan = planFor(level),
                )
                assertWithMessage("${tier.label}/${level.label}").that(profile.hdr).isFalse()
            }
        }
    }

    // ------------------------------------------------------------------ pickCamera

    @Test
    fun `pickCamera prefers the rear lens with the longest focal length`() {
        val ultrawide = camera(cameraId = "2", focalLengthsMm = listOf(2.2f))
        val main = camera(cameraId = "0", focalLengthsMm = listOf(4.7f))
        val macro = camera(cameraId = "3", focalLengthsMm = listOf(1.9f))

        val picked = RecordingProfileSelector.pickCamera(
            capabilities = device(cameras = listOf(ultrawide, macro, main)),
            settings = settings().copy(cameraFacing = CameraFacing.Rear),
        )

        assertThat(picked?.cameraId).isEqualTo("0")
    }

    @Test
    fun `pickCamera breaks a focal length tie on the largest reported resolution`() {
        val small = camera(cameraId = "a", supportedResolutions = listOf(HD, SD))
        val large = camera(cameraId = "b", supportedResolutions = listOf(UHD, FHD))

        val picked = RecordingProfileSelector.pickCamera(
            capabilities = device(cameras = listOf(small, large)),
            settings = settings(),
        )

        assertThat(picked?.cameraId).isEqualTo("b")
    }

    @Test
    fun `pickCamera falls back to any camera when the requested facing is unavailable`() {
        val onlyRear = camera(cameraId = "0", lensFacing = LensFacing.Back)

        val picked = RecordingProfileSelector.pickCamera(
            capabilities = device(cameras = listOf(onlyRear)),
            settings = settings().copy(cameraFacing = CameraFacing.Front),
        )

        assertThat(picked?.cameraId).isEqualTo("0")
    }

    @Test
    fun `pickCamera honours a front facing request when a front camera exists`() {
        val rear = camera(cameraId = "0", lensFacing = LensFacing.Back, focalLengthsMm = listOf(6.0f))
        val front = camera(cameraId = "1", lensFacing = LensFacing.Front, focalLengthsMm = listOf(2.7f))

        val picked = RecordingProfileSelector.pickCamera(
            capabilities = device(cameras = listOf(rear, front)),
            settings = settings().copy(cameraFacing = CameraFacing.Front),
        )

        assertThat(picked?.cameraId).isEqualTo("1")
    }

    @Test
    fun `pickCamera returns null only when the device reports no cameras at all`() {
        assertThat(
            RecordingProfileSelector.pickCamera(
                capabilities = device(cameras = emptyList()),
                settings = settings(),
            ),
        ).isNull()
    }

    // -------------------------------------------------------- requiresRebindFrom

    private fun baseProfile() = RecordingProfile(
        cameraXQuality = "FHD",
        resolution = FHD,
        frameRate = 30,
        codecMimeType = VideoMimeTypes.H264,
        targetBitrateBps = 0,
        stabilisation = false,
        nightAssist = false,
        hdr = false,
        dualCamera = false,
        burnInOverlays = true,
        tier = DeviceTier.Capable,
        isAuto = true,
        rationale = listOf("fixture"),
    )

    @Test
    fun `requiresRebindFrom is true against null and false against an identical profile`() {
        assertThat(baseProfile().requiresRebindFrom(null)).isTrue()
        assertThat(baseProfile().requiresRebindFrom(baseProfile())).isFalse()
    }

    @Test
    fun `requiresRebindFrom is true when any session-baked field differs`() {
        val mutations = mapOf<String, (RecordingProfile) -> RecordingProfile>(
            "cameraXQuality" to { it.copy(cameraXQuality = "HD") },
            "frameRate" to { it.copy(frameRate = 24) },
            "codecMimeType" to { it.copy(codecMimeType = VideoMimeTypes.HEVC) },
            "targetBitrateBps" to { it.copy(targetBitrateBps = 2_000_000) },
            "stabilisation" to { it.copy(stabilisation = true) },
            "hdr" to { it.copy(hdr = true) },
            "dualCamera" to { it.copy(dualCamera = true) },
            "burnInOverlays" to { it.copy(burnInOverlays = false) },
        )

        for ((field, mutate) in mutations) {
            val changed = mutate(baseProfile())
            assertWithMessage("$field must force a rebind")
                .that(changed.requiresRebindFrom(baseProfile())).isTrue()
            assertWithMessage("$field must force a rebind symmetrically")
                .that(baseProfile().requiresRebindFrom(changed)).isTrue()
        }
    }

    @Test
    fun `requiresRebindFrom ignores fields the camera session does not bake in`() {
        // Rebinding cuts the current segment short, so it must not happen for a changed
        // explanation string or a re-scored tier that changed nothing observable.
        val cosmetic = baseProfile().copy(
            rationale = listOf("a different explanation"),
            tier = DeviceTier.Standard,
        )

        assertThat(cosmetic.requiresRebindFrom(baseProfile())).isFalse()
    }

    // ------------------------------------------------------- end-to-end coherence

    @Test
    fun `a thermal escalation from Normal to Elevated does not force a camera rebind`() {
        // The whole point of the Elevated rung: mitigate without cutting a segment.
        val normal = selectAt(NORMAL)
        val elevatedWithoutStabilisation = selectAt(
            ELEVATED,
            settings().copy(videoStabilisation = TriState.Off),
        )
        val normalWithoutStabilisation = selectAt(
            NORMAL,
            settings().copy(videoStabilisation = TriState.Off),
        )

        assertThat(elevatedWithoutStabilisation.requiresRebindFrom(normalWithoutStabilisation)).isFalse()
        // ... whereas High genuinely changes the encoded stream and therefore must rebind.
        assertThat(selectAt(HIGH).requiresRebindFrom(normal)).isTrue()
    }

    @Test
    fun `the rationale always starts by naming the tier and its reasons`() {
        val profile = RecordingProfileSelector.select(
            capabilities = device(),
            assessment = DeviceTierAssessment(
                DeviceTier.Standard,
                listOf("RAM 6.0 GiB", "CPU up to 2.0 GHz"),
                points = 4,
            ),
            settings = settings(),
            thermalPlan = NORMAL,
        )

        assertThat(profile.rationale).isNotEmpty()
        assertThat(profile.rationale.first())
            .isEqualTo("Device tier Standard (RAM 6.0 GiB; CPU up to 2.0 GHz)")
    }

    @Test
    fun `isAuto is true only when both quality and frame rate are Auto`() {
        data class Case(val quality: QualitySetting, val frameRate: FrameRateSetting, val expected: Boolean)

        val cases = listOf(
            Case(QualitySetting.Auto, FrameRateSetting.Auto, true),
            Case(QualitySetting.Auto, FrameRateSetting.Fps30, false),
            Case(QualitySetting.FullHd1080p, FrameRateSetting.Auto, false),
            Case(QualitySetting.FullHd1080p, FrameRateSetting.Fps30, false),
        )

        for (case in cases) {
            val profile = RecordingProfileSelector.select(
                capabilities = device(),
                assessment = assessment(DeviceTier.Capable),
                settings = settings().copy(quality = case.quality, frameRate = case.frameRate),
                thermalPlan = NORMAL,
            )
            assertWithMessage("${case.quality}/${case.frameRate}").that(profile.isAuto)
                .isEqualTo(case.expected)
        }
    }

    @Test
    fun `night assist is never enabled on a Baseline device or above Elevated heat`() {
        data class Case(val name: String, val tier: DeviceTier, val level: ThermalLevel, val expected: Boolean)

        val cases = listOf(
            Case("Capable, Normal", DeviceTier.Capable, ThermalLevel.Normal, true),
            Case("Capable, Elevated", DeviceTier.Capable, ThermalLevel.Elevated, true),
            Case("Capable, High", DeviceTier.Capable, ThermalLevel.High, false),
            Case("Baseline, Normal", DeviceTier.Baseline, ThermalLevel.Normal, false),
        )

        for (case in cases) {
            val profile = RecordingProfileSelector.select(
                capabilities = device(),
                assessment = assessment(case.tier),
                settings = settings().copy(nightAssist = TriState.Auto),
                thermalPlan = planFor(case.level),
            )
            assertWithMessage(case.name).that(profile.nightAssist).isEqualTo(case.expected)
        }
    }

    @Test
    fun `the thermal policy plan table is the one these expectations were written against`() {
        // A cheap tripwire: if the plan table moves, the assertions above stop meaning what
        // they say, so fail here with a clear message rather than mysteriously elsewhere.
        assertThat(ELEVATED.qualityStepDown).isEqualTo(0)
        assertThat(HIGH.qualityStepDown).isEqualTo(1)
        assertThat(CRITICAL.qualityStepDown).isEqualTo(2)
        assertThat(CRITICAL.allowVideoOverlay).isFalse()
        assertThat(ELEVATED.allowVideoOverlay).isTrue()
        assertThat(ThermalPolicy.HEADROOM_ELEVATED).isLessThan(ThermalPolicy.HEADROOM_HIGH)
    }
}
