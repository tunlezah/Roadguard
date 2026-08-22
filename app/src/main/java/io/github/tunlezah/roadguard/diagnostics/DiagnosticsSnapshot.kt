package io.github.tunlezah.roadguard.diagnostics

/**
 * A flat, ordered view of everything Roadguard knows about itself, for the Diagnostics screen and
 * for the exported report.
 *
 * Two design rules:
 *
 *  * **Provenance is explicit.** Every value carries how it was obtained, so a reader can tell a
 *    platform-reported fact from a Roadguard measurement from a simulated value. Nothing that came
 *    from the thermal simulator can be mistaken for a real reading.
 *  * **No unnecessary personal data.** Coordinates are truncated in the exported report, no file
 *    names are listed, and there is no device serial, no advertising id and no account. See
 *    `docs/privacy.md`.
 */
data class DiagnosticsSnapshot(
    val generatedAtEpochMs: Long,
    val sections: List<DiagnosticsSection>,
) {
    fun toPlainText(): String = buildString {
        appendLine("Roadguard diagnostics report")
        appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date(generatedAtEpochMs))}")
        appendLine()
        sections.forEach { section ->
            appendLine("== ${section.title} ==")
            section.entries.forEach { entry ->
                appendLine("  ${entry.label}: ${entry.value}${entry.provenance.suffix}")
            }
            appendLine()
        }
        appendLine("Values marked [simulated] came from the developer thermal harness and are not measurements.")
        appendLine("Values marked [inferred] are derived, not reported by the platform.")
    }
}

data class DiagnosticsSection(val title: String, val entries: List<DiagnosticsEntry>)

data class DiagnosticsEntry(
    val label: String,
    val value: String,
    val provenance: Provenance = Provenance.PlatformReported,
    val severity: EntrySeverity = EntrySeverity.Normal,
)

/** Where a diagnostics value came from. */
enum class Provenance(val suffix: String) {
    /** Read directly from an Android API. */
    PlatformReported(""),

    /** Measured by Roadguard on this device (a benchmark, a byte count, a duration). */
    Measured(" [measured]"),

    /** Derived from other values rather than reported. */
    Inferred(" [inferred]"),

    /** Produced by the thermal test harness. Never a measurement. */
    Simulated(" [simulated]"),

    /** The platform did not answer. */
    Unavailable(" [not reported]"),
}

enum class EntrySeverity { Normal, Warning, Error }
