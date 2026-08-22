package io.github.tunlezah.roadguard.capability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Pins down [DeviceTierScorer]: how Roadguard decides how much sustained work a phone can do.
 *
 * The tier is the single gate in front of every expensive recording decision (resolution
 * ceiling, dual camera, stabilisation), so for a dashcam three properties of this scorer are
 * load-bearing:
 *
 *  1. **A tier is earned from probed capability, never from a model name.** Every case below
 *     is described purely by RAM, clock, camera hardware level and encoder facts. If tiering
 *     ever grew a `Build.MODEL` branch, these tests would still pass -- which is exactly why
 *     they are written as a capability table: a reviewer can see that nothing here needs a
 *     device allow-list.
 *  2. **Never promise a resolution the device cannot encode in hardware.** Without a hardware
 *     encoder confirmed at 1080p30 the answer is [DeviceTier.Baseline] no matter how much RAM
 *     or clock the device has. Continuous recording on a software encoder is a guaranteed
 *     thermal and frame-drop failure, i.e. lost evidence.
 *  3. **Monotonicity.** Improving any single input must never lower the tier. A scoring
 *     function that is not monotonic makes the diagnostics report indefensible ("my newer
 *     phone scored worse") and makes threshold tuning unsafe.
 *
 * Plain JVM tests: [DeviceTierScorer] is pure Kotlin, so nothing here needs Robolectric, a
 * clock or a random source.
 */
class DeviceTierScorerTest {

    private companion object {
        val FULL_HD = Resolution(1920, 1080)
        val UHD = Resolution(3840, 2160)

        fun gib(count: Double): Long = (count * 1024 * 1024 * 1024).toLong()

        /** The scorer formats numbers with the platform default locale; so does this test. */
        fun oneDecimal(value: Double): String = "%.1f".format(value)

        fun noDecimal(value: Float): String = "%.0f".format(value)
    }

    // ------------------------------------------------------------------ factories

    /**
     * A hardware encoder. Defaults to one that can do 1080p30, because that is the single
     * capability that unlocks anything above [DeviceTier.Baseline].
     */
    private fun encoder(
        mimeType: String = VideoMimeTypes.H264,
        hardwareAccelerated: Boolean = true,
        maxWidth: Int? = 1920,
        maxHeight: Int? = 1080,
        achievableFrameRates: Map<Resolution, Int> = emptyMap(),
    ) = EncoderCapability(
        name = if (hardwareAccelerated) "c2.hw.encoder" else "c2.android.sw.encoder",
        mimeType = mimeType,
        hardwareAccelerated = hardwareAccelerated,
        softwareOnly = !hardwareAccelerated,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        bitrateRange = null,
        maxInstances = 4,
        achievableFrameRates = achievableFrameRates,
    )

    private fun rearCamera(
        hardwareLevel: CameraHardwareLevel = CameraHardwareLevel.Full,
        cameraId: String = "0",
    ) = CameraCapability(
        cameraId = cameraId,
        lensFacing = LensFacing.Back,
        hardwareLevel = hardwareLevel,
        supportedQualities = listOf("FHD", "HD", "SD"),
        supportedResolutions = listOf(FULL_HD, Resolution(1280, 720)),
        maxFrameRate = 30,
        supportedFrameRateRanges = listOf(IntRange(15, 30)),
        focalLengthsMm = listOf(4.7f),
        sensorAspectRatio = 1.333f,
        supportsVideoStabilisation = false,
        supportedDynamicRanges = listOf("SDR"),
        isLogicalMultiCamera = false,
        physicalCameraIds = emptyList(),
    )

    /**
     * A device described only by the facts the scorer is allowed to look at. Built from
     * [DeviceCapabilities.unknown] so a new field added to the data class shows up here as a
     * conservative default rather than a compile error in every case.
     */
    private fun device(
        isLowRamDevice: Boolean = false,
        totalRamBytes: Long = gib(8.0),
        maxCpuFrequencyGHz: Float? = 2.5f,
        cameraHardwareLevel: CameraHardwareLevel? = CameraHardwareLevel.Full,
        encoders: List<EncoderCapability> = listOf(encoder()),
        cpuProbeScore: Float? = null,
    ) = DeviceCapabilities.unknown().copy(
        isLowRamDevice = isLowRamDevice,
        totalRamBytes = totalRamBytes,
        availableRamBytes = totalRamBytes / 2,
        cpuCoreCount = 8,
        maxCpuFrequencyGHz = maxCpuFrequencyGHz,
        cameras = cameraHardwareLevel?.let { listOf(rearCamera(it)) } ?: emptyList(),
        encoders = encoders,
        cpuProbeScore = cpuProbeScore,
    )

    // ------------------------------------------------- 1. low RAM overrides everything

    @Test
    fun `isLowRamDevice forces Baseline whatever else the device reports`() {
        val flagship = device(
            isLowRamDevice = true,
            totalRamBytes = gib(16.0),
            maxCpuFrequencyGHz = 3.2f,
            cameraHardwareLevel = CameraHardwareLevel.Level3,
            encoders = listOf(encoder(maxWidth = 3840, maxHeight = 2160)),
            cpuProbeScore = 5_000f,
        )

        val assessment = DeviceTierScorer.score(flagship)

        assertThat(assessment.tier).isEqualTo(DeviceTier.Baseline)
        assertThat(assessment.points).isEqualTo(0)
        // The short-circuit is the whole point: no other signal is even examined.
        assertThat(assessment.reasons).containsExactly("platform reports a low-RAM device")
    }

    // ------------------------------------- 2. safety property: no 1080p we cannot encode

    @Test
    fun `a device with no hardware encoder confirmed at 1080p30 is Baseline however strong it is`() {
        val cases = mapOf(
            "software-only encoder, even a 4K one" to
                listOf(encoder(hardwareAccelerated = false, maxWidth = 3840, maxHeight = 2160)),
            "hardware encoder capped at 720p" to
                listOf(encoder(maxWidth = 1280, maxHeight = 720)),
            "hardware encoder that only reaches 24 fps at 1080p" to
                listOf(encoder(achievableFrameRates = mapOf(FULL_HD to 24))),
            "no encoders reported at all" to emptyList(),
        )

        for ((name, encoders) in cases) {
            val strongEverywhereElse = device(
                totalRamBytes = gib(12.0),
                maxCpuFrequencyGHz = 3.0f,
                cameraHardwareLevel = CameraHardwareLevel.Level3,
                encoders = encoders,
                cpuProbeScore = 4_000f,
            )

            val assessment = DeviceTierScorer.score(strongEverywhereElse)

            assertWithMessage(name).that(assessment.tier).isEqualTo(DeviceTier.Baseline)
            assertWithMessage(name).that(assessment.reasons)
                .contains("no hardware encoder confirmed at 1080p30")
            // The veto is applied on top of a genuinely high score, not by scoring zero.
            assertWithMessage(name).that(assessment.points).isAtLeast(5)
        }
    }

    @Test
    fun `an encoder that reports no per-size frame rates is trusted within its size limits`() {
        // achievableFrameRates is frequently empty on real devices; treating "unreported" as
        // "unsupported" would demote every such phone to Baseline.
        val assessment = DeviceTierScorer.score(
            device(encoders = listOf(encoder(achievableFrameRates = emptyMap()))),
        )

        assertThat(assessment.reasons).contains("hardware encoder handles 1080p30")
        assertThat(assessment.tier).isNotEqualTo(DeviceTier.Baseline)
    }

    // ---------------------------------------------------- 3 & 4. the two anchor devices

    /** Low clock, 4 GiB, LIMITED camera, but a genuine 1080p hardware encoder. */
    private fun budgetClassDevice() = device(
        totalRamBytes = gib(4.0),
        maxCpuFrequencyGHz = 1.6f,
        cameraHardwareLevel = CameraHardwareLevel.Limited,
        encoders = listOf(encoder()),
    )

    /** 8 GiB, 2.5 GHz, FULL camera, 1080p hardware encoder. */
    private fun mainstreamDevice() = device(
        totalRamBytes = gib(8.0),
        maxCpuFrequencyGHz = 2.5f,
        cameraHardwareLevel = CameraHardwareLevel.Full,
        encoders = listOf(encoder()),
    )

    @Test
    fun `a low clock 4 GiB LIMITED device with a 1080p encoder lands in Baseline`() {
        val assessment = DeviceTierScorer.score(budgetClassDevice())

        assertThat(assessment.tier).isEqualTo(DeviceTier.Baseline)
        // Only the encoder earned a point; RAM, clock and camera level all earned nothing.
        assertThat(assessment.points).isEqualTo(1)
        assertThat(assessment.reasons).contains("hardware encoder handles 1080p30")
        assertThat(assessment.reasons)
            .contains("RAM ${oneDecimal(4.0)} GiB is tight for map plus encoder")
        assertThat(assessment.reasons).contains("CPU tops out at ${oneDecimal(1.6)} GHz")
        assertThat(assessment.reasons).contains("camera hardware level Limited")
    }

    @Test
    fun `a 8 GiB 2point5 GHz FULL device reaches at least Standard and outscores the budget class`() {
        val mainstream = DeviceTierScorer.score(mainstreamDevice())
        val budget = DeviceTierScorer.score(budgetClassDevice())

        assertThat(mainstream.tier).isAnyOf(DeviceTier.Standard, DeviceTier.Capable)
        assertThat(mainstream.tier.ordinal).isGreaterThan(budget.tier.ordinal)
        assertThat(mainstream.points).isGreaterThan(budget.points)
        assertThat(mainstream.reasons).contains("RAM ${oneDecimal(8.0)} GiB")
        assertThat(mainstream.reasons).contains("CPU up to ${oneDecimal(2.5)} GHz")
        assertThat(mainstream.reasons).contains("camera hardware level Full")
    }

    // ------------------------------------------------------- 5. unreadable CPU clock

    @Test
    fun `an unreadable CPU clock does not crash and awards no points`() {
        val unreadable = DeviceTierScorer.score(device(maxCpuFrequencyGHz = null))
        val slowest = DeviceTierScorer.score(device(maxCpuFrequencyGHz = 0.8f))
        val standardClock = DeviceTierScorer.score(device(maxCpuFrequencyGHz = 1.9f))

        assertThat(unreadable.points).isEqualTo(slowest.points)
        assertThat(unreadable.points).isLessThan(standardClock.points)
        assertThat(unreadable.reasons).contains("CPU clock ceiling not readable")
    }

    @Test
    fun `a device with nothing probed yet is Baseline and still explains itself`() {
        val assessment = DeviceTierScorer.score(
            DeviceCapabilities.unknown().copy(isLowRamDevice = false),
        )

        assertThat(assessment.tier).isEqualTo(DeviceTier.Baseline)
        assertThat(assessment.reasons).contains("no rear camera reported yet")
        assertThat(assessment.reasons).contains("CPU clock ceiling not readable")
        assertThat(assessment.reasons).contains("no hardware encoder confirmed at 1080p30")
    }

    // ------------------------------------------------- 6. every case explains itself

    @Test
    fun `every assessment carries a non-empty reason list naming the decisive factor`() {
        data class Case(val name: String, val caps: DeviceCapabilities, val decisive: String)

        val cases = listOf(
            Case(
                "low RAM flag",
                device(isLowRamDevice = true),
                "platform reports a low-RAM device",
            ),
            Case(
                "no hardware 1080p encoder",
                device(encoders = listOf(encoder(hardwareAccelerated = false))),
                "no hardware encoder confirmed at 1080p30",
            ),
            Case(
                "tight RAM",
                budgetClassDevice(),
                "RAM ${oneDecimal(4.0)} GiB is tight for map plus encoder",
            ),
            Case("strong clock", mainstreamDevice(), "CPU up to ${oneDecimal(2.5)} GHz"),
            Case(
                "fast CPU probe",
                device(cpuProbeScore = DeviceTierScorer.CPU_PROBE_STRONG + 1f),
                "CPU probe ${noDecimal(1_201f)} units/ms",
            ),
            Case("missing rear camera", device(cameraHardwareLevel = null), "no rear camera reported yet"),
        )

        for (case in cases) {
            val assessment = DeviceTierScorer.score(case.caps)
            assertWithMessage(case.name).that(assessment.reasons).isNotEmpty()
            assertWithMessage(case.name).that(assessment.reasons).contains(case.decisive)
        }
    }

    @Test
    fun `the CPU probe only breaks ties and never overturns the encoder veto`() {
        val strongProbe = DeviceTierScorer.score(
            device(cpuProbeScore = DeviceTierScorer.CPU_PROBE_STRONG),
        )
        val weakProbe = DeviceTierScorer.score(
            device(cpuProbeScore = DeviceTierScorer.CPU_PROBE_STRONG - 1f),
        )
        assertThat(strongProbe.points).isEqualTo(weakProbe.points + 1)

        val noEncoder = DeviceTierScorer.score(
            device(encoders = emptyList(), cpuProbeScore = 100_000f),
        )
        assertThat(noEncoder.tier).isEqualTo(DeviceTier.Baseline)
    }

    // -------------------------------------------------------- tier score boundaries

    @Test
    fun `tier boundaries sit exactly on the documented point thresholds`() {
        data class Case(
            val name: String,
            val ram: Long,
            val clock: Float,
            val camera: CameraHardwareLevel,
            val expectedPoints: Int,
            val expectedTier: DeviceTier,
        )

        val cases = listOf(
            // encoder alone earns 1 point; everything else is at its threshold or just below.
            Case("2 points", gib(5.0), 1.8f, CameraHardwareLevel.Limited, 2, DeviceTier.Baseline),
            Case("3 points", gib(5.0), 1.9f, CameraHardwareLevel.Limited, 3, DeviceTier.Standard),
            Case("5 points", gib(7.0), 1.9f, CameraHardwareLevel.Full, 5, DeviceTier.Standard),
            Case("6 points", gib(7.0), 2.3f, CameraHardwareLevel.Full, 6, DeviceTier.Capable),
        )

        for (case in cases) {
            val assessment = DeviceTierScorer.score(
                device(
                    totalRamBytes = case.ram,
                    maxCpuFrequencyGHz = case.clock,
                    cameraHardwareLevel = case.camera,
                ),
            )
            assertWithMessage(case.name).that(assessment.points).isEqualTo(case.expectedPoints)
            assertWithMessage(case.name).that(assessment.tier).isEqualTo(case.expectedTier)
        }
    }

    @Test
    fun `the documented thresholds are inclusive lower bounds`() {
        val atRamStandard = DeviceTierScorer.score(
            device(totalRamBytes = DeviceTierScorer.RAM_STANDARD_BYTES, maxCpuFrequencyGHz = 1.0f),
        )
        val justUnderRamStandard = DeviceTierScorer.score(
            device(totalRamBytes = DeviceTierScorer.RAM_STANDARD_BYTES - 1, maxCpuFrequencyGHz = 1.0f),
        )
        assertThat(atRamStandard.points).isEqualTo(justUnderRamStandard.points + 1)

        val atClockCapable = DeviceTierScorer.score(
            device(maxCpuFrequencyGHz = DeviceTierScorer.CLOCK_CAPABLE_GHZ, totalRamBytes = gib(1.0)),
        )
        val justUnderClockCapable = DeviceTierScorer.score(
            device(maxCpuFrequencyGHz = DeviceTierScorer.CLOCK_CAPABLE_GHZ - 0.1f, totalRamBytes = gib(1.0)),
        )
        assertThat(atClockCapable.points).isEqualTo(justUnderClockCapable.points + 1)
    }

    // ------------------------------------------------------------- 7. monotonicity

    @Test
    fun `raising RAM never lowers the score or the tier`() {
        val ramSweep = listOf(
            0L, gib(1.0), gib(2.0), gib(3.0), gib(4.0),
            DeviceTierScorer.RAM_STANDARD_BYTES - 1,
            DeviceTierScorer.RAM_STANDARD_BYTES,
            gib(6.0),
            DeviceTierScorer.RAM_CAPABLE_BYTES - 1,
            DeviceTierScorer.RAM_CAPABLE_BYTES,
            gib(8.0), gib(12.0), gib(24.0),
        )

        assertNonDecreasing(ramSweep.map { ram -> "$ram bytes" to device(totalRamBytes = ram) })
    }

    @Test
    fun `raising the CPU clock never lowers the score or the tier`() {
        val clockSweep = listOf(0.5f, 1.0f, 1.4f, 1.8f, 1.9f, 2.0f, 2.2f, 2.3f, 2.6f, 3.2f)

        assertNonDecreasing(
            clockSweep.map { clock -> "$clock GHz" to device(maxCpuFrequencyGHz = clock) },
        )
        // A null clock must sit at the bottom of that sweep, not above it.
        val unreadable = DeviceTierScorer.score(device(maxCpuFrequencyGHz = null))
        val slowest = DeviceTierScorer.score(device(maxCpuFrequencyGHz = clockSweep.first()))
        assertThat(unreadable.points).isEqualTo(slowest.points)
    }

    @Test
    fun `improving the camera hardware level never lowers the score or the tier`() {
        val levels = listOf(
            CameraHardwareLevel.Legacy,
            CameraHardwareLevel.Limited,
            CameraHardwareLevel.Full,
            CameraHardwareLevel.Level3,
        )

        assertNonDecreasing(levels.map { it.name to device(cameraHardwareLevel = it) })
    }

    @Test
    fun `raising the CPU probe score never lowers the score or the tier`() {
        val probes = listOf(0f, 100f, 900f, 1_199f, 1_200f, 5_000f)

        assertNonDecreasing(probes.map { "probe $it" to device(cpuProbeScore = it) })
    }

    @Test
    fun `gaining a hardware 1080p encoder never lowers the score or the tier`() {
        assertNonDecreasing(
            listOf(
                "software only" to device(encoders = listOf(encoder(hardwareAccelerated = false))),
                "hardware 1080p" to device(encoders = listOf(encoder())),
                "hardware 4K" to device(encoders = listOf(encoder(maxWidth = UHD.width, maxHeight = UHD.height))),
            ),
        )
    }

    /** Asserts points and tier are non-decreasing along an ordered sweep of one input. */
    private fun assertNonDecreasing(sweep: List<Pair<String, DeviceCapabilities>>) {
        val scored = sweep.map { (name, caps) -> name to DeviceTierScorer.score(caps) }
        for (index in 1 until scored.size) {
            val (previousName, previous) = scored[index - 1]
            val (name, current) = scored[index]
            assertWithMessage("points: $previousName -> $name")
                .that(current.points).isAtLeast(previous.points)
            assertWithMessage("tier: $previousName -> $name")
                .that(current.tier.ordinal).isAtLeast(previous.tier.ordinal)
        }
        // A sweep that never changes anything would satisfy the above vacuously.
        assertThat(scored.last().second.points).isGreaterThan(scored.first().second.points)
    }
}
