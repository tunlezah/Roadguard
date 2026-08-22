package io.github.tunlezah.roadguard.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.diagnostics.DiagnosticsEntry
import io.github.tunlezah.roadguard.diagnostics.DiagnosticsSection
import io.github.tunlezah.roadguard.diagnostics.EntrySeverity
import io.github.tunlezah.roadguard.diagnostics.Provenance
import io.github.tunlezah.roadguard.thermal.ThermalScenario
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors

/**
 * The diagnostics screen.
 *
 * Roadguard's device profiles are honest about being derived from runtime probing rather than
 * validated on every phone, and the only way that improves is if a real user on real hardware can
 * see and export exactly what the app probed. So this screen is deliberately complete on technical
 * facts and deliberately empty of anything personal: no coordinates, no file names, no device
 * serial, no account, and nothing is uploaded.
 *
 * Provenance is rendered, not implied. A value the thermal harness produced is badged SIMULATED in
 * the app and in the exported text, because a simulated figure quoted as a measurement would be
 * worse than no figure at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.Factory),
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = {
                            viewModel.export { file -> shareReport(context, file) }
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Export the report")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Preamble() }

                if (viewModel.harnessAvailable) {
                    item { ThermalHarness(onSimulate = viewModel::simulate) }
                }

                items(snapshot?.sections.orEmpty()) { section ->
                    SectionCard(
                        section = section,
                        onCopy = {
                            clipboard.setText(AnnotatedString(sectionText(section)))
                        },
                    )
                }

                snapshot?.let { document ->
                    item {
                        AssistChip(
                            onClick = { clipboard.setText(AnnotatedString(document.toPlainText())) },
                            label = { Text("Copy the whole report") },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_content_copy),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Preamble() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What this report contains", style = MaterialTheme.typography.titleSmall)
            Text(
                "The device's camera, encoder, sensor, thermal and storage capabilities as Roadguard " +
                    "probed them, the recording profile it chose and why, and what it has measured " +
                    "while running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "It contains no coordinates, no file names, and no account or device identifiers. " +
                    "Nothing is uploaded: the report only leaves the device if you share it yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The thermal test harness.
 *
 * Debug builds only. It injects a thermal reading so the mitigation ladder can be watched end to
 * end -- profile step-down, map shutdown, overlay removal -- on a cool desk. Every value it drives
 * is badged SIMULATED below, which is the whole point: the harness exists so behaviour can be
 * verified without ever being able to pass a simulation off as a measurement.
 */
@Composable
private fun ThermalHarness(onSimulate: (ThermalScenario) -> Unit) {
    val status = LocalRoadguardStatusColors.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Thermal test harness (debug build)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Injects a thermal reading so the mitigation ladder can be exercised without a hot " +
                    "device. Anything it produces is marked SIMULATED and is not a measurement.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThermalScenario.entries.forEach { scenario ->
                    AssistChip(
                        onClick = { onSimulate(scenario) },
                        label = { Text(scenario.label, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = status.warning),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(section: DiagnosticsSection, onCopy: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_content_copy),
                        contentDescription = "Copy ${section.title}",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            section.entries.forEach { entry -> EntryRow(entry) }
        }
    }
}

@Composable
private fun EntryRow(entry: DiagnosticsEntry) {
    val status = LocalRoadguardStatusColors.current
    val valueColour = when (entry.severity) {
        EntrySeverity.Normal -> MaterialTheme.colorScheme.onSurface
        EntrySeverity.Warning -> status.warning
        EntrySeverity.Error -> status.critical
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            entry.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = valueColour,
                modifier = Modifier.weight(1f, fill = false),
            )
            ProvenanceBadge(entry.provenance)
        }
    }
}

/**
 * The provenance badge.
 *
 * `Simulated` is loud on purpose. The others are quiet, because a reader should be able to tell a
 * platform-reported value from a derived one at a glance without the screen shouting at them.
 */
@Composable
private fun ProvenanceBadge(provenance: Provenance) {
    val status = LocalRoadguardStatusColors.current
    val (text, colour) = when (provenance) {
        Provenance.PlatformReported -> return
        Provenance.Measured -> "measured" to MaterialTheme.colorScheme.primary
        Provenance.Inferred -> "inferred" to MaterialTheme.colorScheme.onSurfaceVariant
        Provenance.Unavailable -> "not reported" to MaterialTheme.colorScheme.onSurfaceVariant
        Provenance.Simulated -> "SIMULATED" to status.critical
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colour as Color,
    )
}

private fun sectionText(section: DiagnosticsSection): String = buildString {
    appendLine("== ${section.title} ==")
    section.entries.forEach { entry ->
        appendLine("  ${entry.label}: ${entry.value}${entry.provenance.suffix}")
    }
}

/**
 * Shares the exported report.
 *
 * Goes through Roadguard's own FileProvider, which exposes nothing outside the app's directories,
 * and grants read permission for this one share only.
 */
private fun shareReport(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Roadguard diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share the diagnostics report"))
    }
}
