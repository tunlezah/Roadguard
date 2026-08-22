package io.github.tunlezah.roadguard.ui.gallery

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors

/**
 * The recordings list.
 *
 * Grouped by day, because that is how somebody looks for footage ("Tuesday afternoon"), and every
 * row carries the three facts that matter when picking one: when, how long, and whether it is
 * protected. Incident clips also show what triggered them, including the peak g, so a user can tell
 * a real impact from a pothole the detector was unsure about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onOpenSegment: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<GalleryItem?>(null) }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GalleryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = state.filter == option,
                        onClick = { viewModel.setFilter(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            if (state.days.isEmpty()) {
                EmptyState(
                    filter = state.filter,
                    totalCount = state.totalCount,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.days.forEach { day ->
                        item(key = "day-${day.label}") {
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(day.items.size, key = { index -> day.items[index].segment.id }) { index ->
                            val item = day.items[index]
                            SegmentRow(
                                item = item,
                                onOpen = { onOpenSegment(item.segment.id) },
                                onProtect = { viewModel.protect(item.segment.id) },
                                onUnprotect = { viewModel.unprotect(item.segment.id) },
                                onShare = { shareSegment(context, item) },
                                onDelete = { pendingDelete = item },
                            )
                            HorizontalDivider()
                        }
                    }
                    item { androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this clip?") },
            text = {
                Text(
                    "This removes the video file from the device. It cannot be undone, and Roadguard " +
                        "has no copy anywhere else.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(item.segment.id)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun SegmentRow(
    item: GalleryItem,
    onOpen: () -> Unit,
    onProtect: () -> Unit,
    onUnprotect: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = LocalRoadguardStatusColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val segment = item.segment

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.exists, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(
                when {
                    !item.exists -> R.drawable.ic_error_outline
                    item.event != null -> R.drawable.ic_report_problem
                    item.isProtected -> R.drawable.ic_lock
                    else -> R.drawable.ic_movie
                },
            ),
            contentDescription = null,
            tint = when {
                !item.exists -> status.critical
                item.event != null -> status.warning
                item.isProtected -> status.protected
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = GalleryViewModel.timeLabel(segment.startedAtEpochMs),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = buildList {
                    add(formatDuration(segment.durationMs))
                    add(formatSize(segment.sizeBytes))
                    if (segment.widthPx > 0) add("${segment.widthPx}x${segment.heightPx}")
                    if (segment.frameRate > 0) add("${segment.frameRate} fps")
                    if (segment.hasAudio) add("audio")
                }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.event?.let { event ->
                Text(
                    text = buildString {
                        append("Incident: ")
                        append(event.kind.lowercase())
                        event.peakG?.let { append(", %.1f g".format(it)) }
                        append(", ${(event.confidence * 100).toInt()}% confidence")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = status.warning,
                )
            }
            if (!item.exists) {
                Text(
                    text = "The file is missing. It may have been removed or quarantined.",
                    style = MaterialTheme.typography.labelSmall,
                    color = status.critical,
                )
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                Icon(
                    painterResource(R.drawable.ic_more_horiz),
                    contentDescription = "More actions for this clip",
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (item.isProtected) {
                    DropdownMenuItem(
                        text = { Text("Remove protection") },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_lock_open), contentDescription = null)
                        },
                        onClick = {
                            onUnprotect()
                            menuOpen = false
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Protect") },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_lock), contentDescription = null)
                        },
                        onClick = {
                            onProtect()
                            menuOpen = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    enabled = item.exists,
                    onClick = {
                        onShare()
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = null)
                    },
                    enabled = !item.isProtected,
                    onClick = {
                        onDelete()
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(filter: GalleryFilter, totalCount: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_video_library),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = when {
                totalCount == 0 -> "No recordings yet"
                filter == GalleryFilter.Protected -> "Nothing is protected"
                else -> "No incidents recorded"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                totalCount == 0 ->
                    "Tap the record button on the main screen, or plug the phone into vehicle power " +
                        "if you have Roadguard set to start automatically."

                filter == GalleryFilter.Protected ->
                    "Use Protect recording, or let incident detection save something automatically. " +
                        "Protected clips are never deleted by the loop."

                else -> "Incident clips appear here when the accelerometer sees an impact signature."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun formatSize(bytes: Long): String {
    val megabytes = bytes.toDouble() / (1024.0 * 1024)
    return if (megabytes >= 1024) "%.2f GB".format(megabytes / 1024) else "%.0f MB".format(megabytes)
}

/** Shares one recording through Roadguard's FileProvider, at explicit user request only. */
private fun shareSegment(context: android.content.Context, item: GalleryItem) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share this clip"))
    }
}
