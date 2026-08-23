package io.github.tunlezah.roadguard.ui.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.data.SegmentEntity
import io.github.tunlezah.roadguard.map.MapPackage
import androidx.compose.material3.LinearProgressIndicator
import io.github.tunlezah.roadguard.map.MapInstallState
import io.github.tunlezah.roadguard.storage.StorageState
import io.github.tunlezah.roadguard.storage.StorageVolumeOption
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The storage screen: it exists to answer one question, "where has my space gone", without the
 * user having to do arithmetic.
 *
 * The answer is given three times over, in decreasing order of how quickly it can be read: a
 * stacked bar of the whole volume, a legend with the byte figures behind each band of that bar,
 * and then the individual figures that explain Roadguard's own share of it. Everything below that
 * is a lever for changing the answer -- the loop budget, the volume, the protected clips that the
 * loop is not allowed to touch.
 *
 * ### Why the bar is hand-built
 *
 * There is no charting library in this app and there should not be one for a single stacked bar.
 * It is a [Row] of weighted boxes; the legend beneath it carries every figure as text, so the
 * bar is decoration for people who can see it and the screen loses nothing for people who
 * cannot. Colour is never the only signal: each band is named in the legend, and the wide bands
 * repeat their name inside the bar.
 *
 * ### Why nothing is estimated
 *
 * The loop coverage and headroom figures depend on the recorded bytes per second, which does not
 * exist until a segment has finished. Before then the screen says "not measured yet". A dashcam
 * that guessed how many hours it was keeping would be worse than one that admitted it did not
 * know.
 */
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = viewModel(factory = StorageViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageContent(
        state = state,
        onBack = onBack,
        onRefresh = { viewModel.refresh() },
        onSetLoopBudget = { viewModel.setLoopBudget(it) },
        onChooseVolume = { viewModel.chooseVolume(it) },
        onFreeSpaceNow = { viewModel.freeSpaceNow() },
        onUnprotect = { viewModel.unprotect(it) },
        onDeleteProtected = { viewModel.deleteProtectedSegment(it) },
        onSelectMapPackage = { viewModel.selectMapPackage(it) },
        onInstallMap = { viewModel.installMap() },
        onPauseMapInstall = { viewModel.pauseMapInstall() },
        onRemoveMap = { viewModel.removeMap() },
        onActionShown = { viewModel.clearAction() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageContent(
    state: StorageUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSetLoopBudget: (Long) -> Unit,
    onChooseVolume: (String?) -> Unit,
    onFreeSpaceNow: () -> Unit,
    onUnprotect: (Long) -> Unit,
    onDeleteProtected: (Long) -> Unit,
    onSelectMapPackage: (MapPackage) -> Unit,
    onInstallMap: () -> Unit,
    onPauseMapInstall: () -> Unit,
    onRemoveMap: () -> Unit,
    onActionShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val clipDateFormat = remember { SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()) }
    var pendingUnprotect by remember { mutableStateOf<SegmentEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<SegmentEntity?>(null) }
    var showAllProtected by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.action) {
        state.action?.let { action ->
            snackbarHostState.showSnackbar(messageFor(action))
            onActionShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isBusy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Measure storage again")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { UsageSection(state) }

            state.assessment?.let { assessment ->
                if (assessment.state == StorageState.Critical) {
                    item {
                        Notice(
                            text = "There is not enough room on this volume to keep recording " +
                                "reliably. Free some space, or choose a volume with more room.",
                            iconRes = R.drawable.ic_error,
                            container = LocalRoadguardStatusColors.current.critical,
                            content = LocalRoadguardStatusColors.current.onCritical,
                        )
                    }
                }
                if (assessment.budgetLimitedByDevice) {
                    item {
                        Notice(
                            text = "This device has less room than the budget you asked for. You " +
                                "chose ${formatBytes(assessment.requestedBudgetBytes)}, and " +
                                "Roadguard is using " +
                                "${formatBytes(assessment.effectiveBudgetBytes)} -- everything " +
                                "else is already taken by protected footage, map data, other " +
                                "apps, and the space Roadguard keeps free for the phone itself.",
                            iconRes = R.drawable.ic_report_problem,
                            container = LocalRoadguardStatusColors.current.warning,
                            content = LocalRoadguardStatusColors.current.onWarning,
                        )
                    }
                }
            }

            item { FiguresSection(state) }
            item { FreeSpaceSection(state, onFreeSpaceNow) }
            item { LoopBudgetSection(state, onSetLoopBudget) }

            if (state.volumes.size > 1) {
                item { VolumeSection(state.volumes, onChooseVolume) }
            }

            item {
                OfflineMapSection(
                    state = state,
                    onSelectMapPackage = onSelectMapPackage,
                    onInstall = onInstallMap,
                    onPause = onPauseMapInstall,
                    onRemove = onRemoveMap,
                )
            }

            item {
                ProtectedHeaderSection(
                    state = state,
                    showingAll = showAllProtected,
                    onShowAll = { showAllProtected = true },
                )
            }

            val visible = if (showAllProtected) {
                state.protectedSegments
            } else {
                state.protectedSegments.take(COLLAPSED_PROTECTED_COUNT)
            }
            items(visible, key = { it.id }) { segment ->
                ProtectedClipRow(
                    segment = segment,
                    formattedDate = clipDateFormat.format(Date(segment.startedAtEpochMs)),
                    onUnprotect = { pendingUnprotect = segment },
                    onDelete = { pendingDelete = segment },
                )
            }

            item { QuarantineSection(state) }
        }
    }

    pendingUnprotect?.let { segment ->
        ConfirmDialog(
            title = "Unprotect this clip?",
            body = "The clip recorded ${clipDateFormat.format(Date(segment.startedAtEpochMs))} " +
                "goes back into the loop, so it will be deleted in its turn as the oldest " +
                "footage. Nothing is deleted right now.",
            confirmLabel = "Unprotect",
            onConfirm = {
                onUnprotect(segment.id)
                pendingUnprotect = null
            },
            onDismiss = { pendingUnprotect = null },
        )
    }

    pendingDelete?.let { segment ->
        ConfirmDialog(
            title = "Delete this clip now?",
            body = "The video recorded ${clipDateFormat.format(Date(segment.startedAtEpochMs))} " +
                "(${formatBytes(segment.sizeBytes)}) is removed from the device. This cannot be " +
                "undone.",
            confirmLabel = "Delete",
            onConfirm = {
                onDeleteProtected(segment.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ── Usage visualisation ────────────────────────────────────────────────────────────────

@Composable
private fun UsageSection(state: StorageUiState) {
    StorageSection(title = "This volume") {
        val assessment = state.assessment
        if (assessment == null || assessment.volumeTotalBytes <= 0L) {
            Text(
                text = "Storage has not been measured yet. Tap the refresh button above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@StorageSection
        }

        val status = LocalRoadguardStatusColors.current
        val scheme = MaterialTheme.colorScheme
        val slices = listOf(
            UsageSlice(
                label = "Loop footage",
                shortLabel = "Loop",
                bytes = assessment.loopUsedBytes,
                colour = scheme.primary,
                onColour = scheme.onPrimary,
            ),
            UsageSlice(
                label = "Protected footage",
                shortLabel = "Protected",
                bytes = state.protectedBytes,
                colour = status.protected,
                onColour = status.onProtected,
            ),
            UsageSlice(
                label = "Map data",
                shortLabel = "Maps",
                bytes = state.mapBytes,
                colour = scheme.tertiary,
                onColour = scheme.onTertiary,
            ),
            UsageSlice(
                label = "Everything else on the volume",
                shortLabel = "Other",
                bytes = state.otherBytes,
                colour = scheme.secondary,
                onColour = scheme.onSecondary,
            ),
            UsageSlice(
                label = "Free",
                shortLabel = "Free",
                bytes = assessment.freeBytes,
                colour = scheme.surfaceContainerHighest,
                onColour = scheme.onSurfaceVariant,
            ),
        )

        Text(
            text = "${formatBytes(assessment.volumeTotalBytes)} total, " +
                "${formatBytes(assessment.freeBytes)} free",
            style = MaterialTheme.typography.bodyLarge,
        )
        UsageBar(slices)
        UsageLegend(slices)
    }
}

/** One band of the stacked bar and its legend entry; the two are always built from one list. */
private data class UsageSlice(
    val label: String,
    val shortLabel: String,
    val bytes: Long,
    val colour: Color,
    val onColour: Color,
)

@Composable
private fun UsageBar(slices: List<UsageSlice>) {
    val total = slices.sumOf { it.bytes }
    if (total <= 0L) return
    val present = slices.filter { it.bytes > 0L }
    val description = "Volume usage: " + present.joinToString(", ") { slice ->
        "${slice.label} ${formatBytes(slice.bytes)}"
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // The bar duplicates the legend, so it is one opaque object to a screen reader
            // rather than a row of unlabelled coloured boxes.
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val barWidth = maxWidth
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            present.forEach { slice ->
                val fraction = (slice.bytes.toDouble() / total).toFloat()
                Box(
                    modifier = Modifier
                        .weight(fraction)
                        .fillMaxHeight()
                        .background(slice.colour),
                    contentAlignment = Alignment.Center,
                ) {
                    // Only a band wide enough to hold its name gets one; the legend names them all.
                    if (barWidth * fraction >= MIN_INLINE_LABEL_WIDTH) {
                        Text(
                            text = slice.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = slice.onColour,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageLegend(slices: List<UsageSlice>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slices.forEach { slice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(14.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(slice.colour),
                )
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatBytes(slice.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ── Headline figures ───────────────────────────────────────────────────────────────────

@Composable
private fun FiguresSection(state: StorageUiState) {
    StorageSection(title = "Roadguard's share") {
        val assessment = state.assessment
        if (assessment == null) {
            Text(
                text = "Not measured yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@StorageSection
        }

        Figure(
            label = "Loop footage",
            value = "${formatBytes(assessment.loopUsedBytes)} of " +
                formatBytes(assessment.effectiveBudgetBytes),
            supporting = if (assessment.budgetLimitedByDevice) {
                "Limited by the device, not by your budget of " +
                    formatBytes(assessment.requestedBudgetBytes)
            } else {
                null
            },
        )
        Figure(
            label = "Protected footage",
            value = formatBytes(state.protectedBytes),
            supporting = "${state.protectedSegments.size} clip(s) the loop may not delete",
        )
        Figure(
            label = "Map data",
            value = formatBytes(state.mapBytes),
            supporting = mapInstallDescription(state.mapInstall),
        )
        Figure(label = "Free space", value = formatBytes(assessment.freeBytes))
        Figure(
            label = "Kept free by Roadguard",
            value = formatBytes(assessment.reserveBytes),
            supporting = "Never spent, so the phone and other apps keep working when the loop is full",
        )
        Figure(
            label = "A full loop holds",
            value = assessment.loopCoverageSeconds?.let { formatDuration(it) } ?: NOT_MEASURED,
            supporting = "At the rate this device is actually recording",
        )
        Figure(
            label = "Room before the loop deletes",
            value = assessment.headroomSeconds?.let { formatDuration(it) } ?: NOT_MEASURED,
        )
    }
}

@Composable
private fun Figure(label: String, value: String, supporting: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Both halves are weighted so a long value wraps instead of squeezing the label away,
        // which is what happens to a fixed two-column row at a 200% font scale.
        Column(modifier = Modifier.weight(0.55f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.45f),
        )
    }
}

// ── Levers ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FreeSpaceSection(state: StorageUiState, onFreeSpaceNow: () -> Unit) {
    StorageSection(title = "Free space now") {
        val pending = state.assessment?.bytesToFree ?: 0L
        Text(
            text = if (pending > 0L) {
                "The loop is over its budget by about ${formatBytes(pending)}. Cleaning up now " +
                    "deletes the oldest unprotected clips until it fits."
            } else {
                "The loop is inside its budget, so there is nothing for a clean-up to delete. " +
                    "Protected clips are never deleted by a clean-up."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onFreeSpaceNow,
            enabled = !state.isBusy,
            modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_delete_sweep),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(text = "Free space now", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun LoopBudgetSection(state: StorageUiState, onSetLoopBudget: (Long) -> Unit) {
    StorageSection(title = "Loop budget") {
        Text(
            text = "How much space the loop may fill before it starts deleting its own oldest " +
                "footage.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.budgetOptions.forEach { bytes ->
            val seconds = state.secondsOfFootageFor(bytes)
            ChoiceRow(
                selected = bytes == state.settings.loopBudgetBytes,
                title = formatBytes(bytes),
                supporting = seconds?.let { "about ${formatDuration(it)} of footage" }
                    ?: "footage estimate appears once something has been recorded",
                onSelect = { onSetLoopBudget(bytes) },
            )
        }
    }
}

@Composable
private fun VolumeSection(volumes: List<StorageVolumeOption>, onChooseVolume: (String?) -> Unit) {
    StorageSection(title = "Where to record") {
        volumes.forEach { volume ->
            ChoiceRow(
                selected = volume.isSelected,
                title = if (volume.isRemovable) "${volume.label} (removable)" else volume.label,
                supporting = "${formatBytes(volume.freeBytes)} free of " +
                    formatBytes(volume.totalBytes),
                onSelect = { onChooseVolume(volume.id) },
            )
        }
        Notice(
            text = "Changing this only affects new recordings. Roadguard never moves a video file " +
                "after it is written, so footage you already have stays on the volume it was " +
                "recorded to.",
            iconRes = R.drawable.ic_help_outline,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Offline map ────────────────────────────────────────────────────────────────────────

/**
 * The offline map: which region, how big, and where it is up to.
 *
 * This lives on the Storage screen rather than in Settings because the only thing about it a user
 * ever needs to reconsider is how much of the phone it is occupying. Switching region replaces the
 * installed archive rather than accumulating a second one -- stated on screen, because several
 * hundred megabytes quietly retained is precisely what this screen exists to prevent.
 */
@Composable
private fun OfflineMapSection(
    state: StorageUiState,
    onSelectMapPackage: (MapPackage) -> Unit,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
) {
    val install = state.mapInstall
    val busy = install is MapInstallState.Downloading || install is MapInstallState.Verifying

    StorageSection(title = "Offline map") {
        Text(
            text = mapInstallDescription(install),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (install) {
            is MapInstallState.Downloading -> {
                install.fraction
                    ?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = buildString {
                        append("${install.bytesDownloaded / (1024 * 1024)} MB")
                        install.totalBytes?.let { append(" of ${it / (1024 * 1024)} MB") }
                        install.etaSeconds?.let {
                            append(", about ${(it / 60).coerceAtLeast(1)} min remaining")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            is MapInstallState.Failed -> Notice(
                text = install.detail?.let { "${install.reason.message} ($it)" } ?: install.reason.message,
                iconRes = R.drawable.ic_error_outline,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )

            else -> Unit
        }

        state.mapPackages.forEach { pack ->
            ChoiceRow(
                selected = pack.id == state.mapPackage?.id,
                title = pack.displayName,
                supporting = buildString {
                    pack.sizeBytes?.let { append("${it / (1024 * 1024)} MB download") }
                    if (isNotEmpty()) append(" · ")
                    append(if (pack.isStreetLevel) "street level" else "main roads only")
                },
                onSelect = { if (!busy) onSelectMapPackage(pack) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                busy -> TextButton(onClick = onPause) { Text("Pause") }
                install is MapInstallState.Installed ->
                    TextButton(onClick = onRemove) { Text("Remove map") }
                else -> TextButton(onClick = onInstall) {
                    Text(if (install is MapInstallState.Paused) "Resume download" else "Download map")
                }
            }
        }

        Notice(
            text = "Downloaded once, then the map works with no SIM, no mobile data and no Wi-Fi. " +
                "Choosing a different region replaces the installed one rather than keeping both.",
            iconRes = R.drawable.ic_help_outline,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Protected footage ──────────────────────────────────────────────────────────────────

@Composable
private fun ProtectedHeaderSection(
    state: StorageUiState,
    showingAll: Boolean,
    onShowAll: () -> Unit,
) {
    StorageSection(title = "Protected footage") {
        Text(
            text = "Protected footage is never deleted by the loop, which is exactly why it needs " +
                "managing: left alone, it is the one thing that can quietly fill the volume.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Figure(
            label = "Protected clips",
            value = "${state.protectedSegments.size}",
        )
        Figure(label = "Total size", value = formatBytes(state.protectedBytes))

        if (state.protectedOverWarning) {
            Notice(
                text = "Protected footage has passed the " +
                    "${formatBytes(state.settings.protectedWarningBytes)} you asked to be warned " +
                    "about. Unprotect or delete the clips you no longer need.",
                iconRes = R.drawable.ic_report_problem,
                container = LocalRoadguardStatusColors.current.warning,
                content = LocalRoadguardStatusColors.current.onWarning,
            )
        }

        if (state.protectedSegments.isEmpty()) {
            Text(
                text = "Nothing is protected at the moment.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (!showingAll && state.protectedSegments.size > COLLAPSED_PROTECTED_COUNT) {
            TextButton(
                onClick = onShowAll,
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET),
            ) {
                Text("Show all ${state.protectedSegments.size} clips")
            }
        }
    }
}

@Composable
private fun ProtectedClipRow(
    segment: SegmentEntity,
    formattedDate: String,
    onUnprotect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Text(text = formattedDate, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${formatClipLength(segment.durationMs)}, " +
                            formatBytes(segment.sizeBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = protectionDescription(segment),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onUnprotect) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock_open),
                        contentDescription = "Unprotect the clip recorded $formattedDate",
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete the clip recorded $formattedDate",
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ── Quarantine ─────────────────────────────────────────────────────────────────────────

@Composable
private fun QuarantineSection(state: StorageUiState) {
    StorageSection(title = "Quarantine") {
        Figure(
            label = "Files held back",
            value = "${state.quarantineFileCount}",
            supporting = if (state.quarantineFileCount > 0) {
                formatBytes(state.quarantineBytes)
            } else {
                null
            },
        )
        Text(
            text = "These are recordings that could not be verified after a session was " +
                "interrupted. Roadguard keeps them instead of deleting them, because an " +
                "unverified clip may still be the one that matters.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Shared pieces ──────────────────────────────────────────────────────────────────────

@Composable
private fun StorageSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * A selectable row.
 *
 * The whole row is the target, not just the radio button, and it is at least 56 dp high: this
 * screen is used while parked, but nothing in a vehicle app should require a precise tap.
 */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    supporting: String?,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = androidx.compose.ui.semantics.Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A block of prose that has to be noticed.
 *
 * The container and content colours are always taken from a contrast-checked pair, because a
 * warning drawn in a tinted-alpha colour is exactly the thing that stops being readable in the
 * OLED theme.
 */
@Composable
private fun Notice(text: String, iconRes: Int, container: Color, content: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = content,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET)) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET)) {
                Text("Cancel")
            }
        },
    )
}

// ── Formatting ─────────────────────────────────────────────────────────────────────────

private const val NOT_MEASURED = "not measured yet"
private const val COLLAPSED_PROTECTED_COUNT = 6
private val BAR_HEIGHT = 28.dp
private val MIN_INLINE_LABEL_WIDTH = 64.dp
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Bytes as the user reads them.
 *
 * Binary divisors with decimal labels, matching the storage chip on the driving screen -- one
 * convention throughout the app matters more here than pedantry about GB versus GiB, and mixing
 * the two would make two screens disagree about the same number.
 */
internal fun formatBytes(bytes: Long): String {
    val locale = Locale.getDefault()
    val gigabytes = bytes / (1024.0 * 1024.0 * 1024.0)
    val megabytes = bytes / (1024.0 * 1024.0)
    return when {
        bytes <= 0L -> "0 MB"
        gigabytes >= 10.0 -> String.format(locale, "%.0f GB", gigabytes)
        gigabytes >= 1.0 -> String.format(locale, "%.1f GB", gigabytes)
        megabytes >= 10.0 -> String.format(locale, "%.0f MB", megabytes)
        megabytes >= 0.1 -> String.format(locale, "%.1f MB", megabytes)
        else -> String.format(locale, "%d kB", bytes / 1024)
    }
}

/** A span of time as hours and minutes; never "0", because that reads as an error. */
internal fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return when {
        hours > 0L && minutes > 0L -> "$hours h $minutes min"
        hours > 0L -> "$hours h"
        minutes > 0L -> "$minutes min"
        else -> "under a minute"
    }
}

private fun formatClipLength(durationMs: Long): String {
    val seconds = (durationMs / 1000).coerceAtLeast(0L)
    val minutes = seconds / 60
    return if (minutes > 0L) "$minutes min ${seconds % 60} s" else "${seconds % 60} s"
}

/** Why a clip is protected, from what the index actually recorded -- never a guess. */
private fun protectionDescription(segment: SegmentEntity): String = when {
    segment.eventId != null -> "Kept for event #${segment.eventId}"
    segment.protectionReason != null -> "Kept: ${segment.protectionReason}"
    else -> "Kept; the reason was not recorded"
}

private fun mapInstallDescription(state: MapInstallState): String = when (state) {
    is MapInstallState.NotInstalled -> "No offline map installed"
    is MapInstallState.Downloading -> "Downloading"
    is MapInstallState.Paused -> "Download paused"
    is MapInstallState.Verifying -> "Verifying the download"
    is MapInstallState.Installed -> "Installed"
    is MapInstallState.Failed -> state.reason.message
}

private fun messageFor(action: StorageAction): String = when (action) {
    is StorageAction.Cleaned ->
        "Freed ${formatBytes(action.outcome.bytesFreed)} by deleting " +
            "${action.outcome.filesDeleted} old clip(s)"

    is StorageAction.NothingToClean -> "Nothing to delete - the loop is inside its budget"

    is StorageAction.VolumeChanged -> action.label
        ?.let { "New recordings will go to $it" }
        ?: "New recordings will go to the volume you chose"

    is StorageAction.Unprotected -> "Clip returned to the loop"
    is StorageAction.Deleted -> "Deleted ${formatBytes(action.bytes)}"
    is StorageAction.DeleteFailed -> "That file could not be deleted; it is still on the device"
    is StorageAction.MeasurementFailed -> "Storage could not be measured just now"
    is StorageAction.Message -> action.text
}
