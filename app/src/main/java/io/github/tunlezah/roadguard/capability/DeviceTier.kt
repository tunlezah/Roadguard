package io.github.tunlezah.roadguard.capability

/**
 * How much sustained work Roadguard believes this device can do.
 *
 * Tiers exist so that optional, expensive features (dual camera, stabilisation, higher
 * resolutions, HDR) have a single defensible gate instead of being switched on ad hoc.
 *
 * A tier is **earned from probed capability**, never assigned by model name: see
 * [DeviceTierScorer]. The Moto G04 baseline is expected to land in [Baseline] because of its
 * RAM, core mix and clock ceiling, not because it is a Moto G04.
 */
enum class DeviceTier(val label: String) {
    /**
     * Low-end. Conservative recording, map rendering allowed but shed early, no dual camera,
     * no stabilisation, no HDR.
     */
    Baseline("Baseline"),

    /** Mainstream. 1080p30 sustained is expected to be safe; dual camera stays off. */
    Standard("Standard"),

    /** Strong. Higher resolutions and optional features may be offered. */
    Capable("Capable"),
}

/**
 * Scores a device into a [DeviceTier] from runtime facts only.
 *
 * The signals, in rough order of how much they say about *sustained* video encoding:
 *
 *  1. `ActivityManager.isLowRamDevice()` -- an explicit platform statement that the device is
 *     resource constrained. On its own it forces [DeviceTier.Baseline].
 *  2. Total RAM. Continuous encoding plus a vector map is memory hungry; under 4 GiB there is
 *     no headroom for the map at all.
 *  3. CPU clock ceiling from `cpufreq`. This separates an 8x A55-class cluster at 1.6 GHz
 *     from a 4x A78 + 4x A55 at 2.5 GHz far better than core *count* does, and core count
 *     alone is actively misleading (both are "octa-core").
 *  4. Camera2 hardware level. `LEGACY`/`LIMITED` devices are more likely to force stream
 *     sharing and extra GPU copies.
 *  5. A hardware video encoder that can actually do 1080p. Without one, 1080p is off the table
 *     regardless of everything else.
 *  6. The start-up CPU probe, used only to break ties.
 *
 * Every threshold below is a documented starting point, not a measurement of these devices.
 * `docs/device-profiles.md` records which are verified and which still need on-road data.
 */
object DeviceTierScorer {

    const val RAM_STANDARD_BYTES: Long = 5L * 1024 * 1024 * 1024
    const val RAM_CAPABLE_BYTES: Long = 7L * 1024 * 1024 * 1024
    const val CLOCK_STANDARD_GHZ: Float = 1.9f
    const val CLOCK_CAPABLE_GHZ: Float = 2.3f

    fun score(capabilities: DeviceCapabilities): DeviceTierAssessment {
        val reasons = mutableListOf<String>()

        if (capabilities.isLowRamDevice) {
            reasons += "platform reports a low-RAM device"
            return DeviceTierAssessment(DeviceTier.Baseline, reasons, points = 0)
        }

        var points = 0

        when {
            capabilities.totalRamBytes >= RAM_CAPABLE_BYTES -> {
                points += 2
                reasons += "RAM ${formatGib(capabilities.totalRamBytes)} GiB"
            }

            capabilities.totalRamBytes >= RAM_STANDARD_BYTES -> {
                points += 1
                reasons += "RAM ${formatGib(capabilities.totalRamBytes)} GiB"
            }

            else -> reasons += "RAM ${formatGib(capabilities.totalRamBytes)} GiB is tight for map plus encoder"
        }

        val clock = capabilities.maxCpuFrequencyGHz
        when {
            clock == null -> reasons += "CPU clock ceiling not readable"
            clock >= CLOCK_CAPABLE_GHZ -> {
                points += 2
                reasons += "CPU up to ${"%.1f".format(clock)} GHz"
            }

            clock >= CLOCK_STANDARD_GHZ -> {
                points += 1
                reasons += "CPU up to ${"%.1f".format(clock)} GHz"
            }

            else -> reasons += "CPU tops out at ${"%.1f".format(clock)} GHz"
        }

        val bestCamera = capabilities.rearCameras.maxByOrNull { it.hardwareLevel.ordinal }
        when (bestCamera?.hardwareLevel) {
            CameraHardwareLevel.Level3, CameraHardwareLevel.Full -> {
                points += 1
                reasons += "camera hardware level ${bestCamera.hardwareLevel.name}"
            }

            null -> reasons += "no rear camera reported yet"
            else -> reasons += "camera hardware level ${bestCamera.hardwareLevel.name}"
        }

        val fullHd = Resolution(1920, 1080)
        val canEncodeFullHd = capabilities.encoders.any {
            it.hardwareAccelerated && it.supports(fullHd, 30)
        }
        if (canEncodeFullHd) {
            points += 1
            reasons += "hardware encoder handles 1080p30"
        } else {
            reasons += "no hardware encoder confirmed at 1080p30"
        }

        capabilities.cpuProbeScore?.let { probe ->
            if (probe >= CPU_PROBE_STRONG) {
                points += 1
                reasons += "CPU probe ${"%.0f".format(probe)} units/ms"
            } else {
                reasons += "CPU probe ${"%.0f".format(probe)} units/ms"
            }
        }

        val tier = when {
            !canEncodeFullHd -> DeviceTier.Baseline
            points >= 6 -> DeviceTier.Capable
            points >= 3 -> DeviceTier.Standard
            else -> DeviceTier.Baseline
        }
        return DeviceTierAssessment(tier, reasons, points)
    }

    /**
     * Threshold on the start-up probe above which a device is treated as fast.
     *
     * Calibrated only against the development machine, so it is a weak tiebreaker by design;
     * `docs/device-profiles.md` lists the on-device figures needed to set it properly.
     */
    const val CPU_PROBE_STRONG: Float = 1200f

    private fun formatGib(bytes: Long): String =
        "%.1f".format(bytes.toDouble() / (1024.0 * 1024 * 1024))
}

/** A tier plus the human-readable reasons behind it, shown verbatim in diagnostics. */
data class DeviceTierAssessment(
    val tier: DeviceTier,
    val reasons: List<String>,
    val points: Int,
)
