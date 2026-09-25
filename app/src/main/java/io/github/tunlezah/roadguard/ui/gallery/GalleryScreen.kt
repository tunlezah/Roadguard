package io.github.tunlezah.roadguard.ui.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors
import java.io.File

/**
 * The recordings list.
 *
 * Grouped by day and then by trip, because that is how somebody looks for footage ("the drive to
 * Braddon on Saturday afternoon"). Each trip card names its two ends from the offline map, says how
 * long and how far, and carries the trip's track: one tap opens it in a map app, another shares it.
 * Under the card, every clip row carries the three facts that matter when picking one: when, how
 * long, and whether it is protected. Incident clips also show what triggered them, including the
 * peak g, so a user can tell a real impact from a pothole the detector was unsure about.
 */
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onOpenSegment: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    GalleryContent(
        state = state,
        onBack = onBack,
        onOpenSegment = onOpenSegment,
        onSetFilter = viewModel::setFilter,
        onToggleTrip = viewModel::toggleTrip,
        onProtect = viewModel::protect,
        onUnprotect = viewModel::unprotect,
        onDelete = viewModel::delete,
        onShareClip = { item -> shareSegment(context, item) },
        onOpenTrack = { file ->
            when (TrackIntents.open(context, file)) {
                TrackIntents.Outcome.Opened -> Unit
                TrackIntents.Outcome.SharedInstead ->
                    viewModel.post("No installed app opens GPX tracks directly, so the share sheet was offered instead.")
                TrackIntents.Outcome.Failed -> viewModel.post("The track could not be handed to another app.")
            }
        },
        onShareTrack = { file ->
            if (!TrackIntents.share(context, file)) viewModel.post("The track could not be shared.")
        },
        onProtectTrip = viewModel::protectTrip,
        onDeleteUnprotected = viewModel::deleteUnprotected,
        onMessageShown = viewModel::clearMessage,
        modifier = modifier,
    )
}

/** The screen with its state and callbacks made explicit, so it can be composed in a JVM test. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryContent(
    state: GalleryUiState,
    onBack: () -> Unit,
    onOpenSegment: (Long) -> Unit,
    onSetFilter: (GalleryFilter) -> Unit,
    onToggleTrip: (Long) -> Unit,
    onProtect: (Long) -> Unit,
    onUnprotect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onShareClip: (GalleryItem) -> Unit,
    onOpenTrack: (File) -> Unit,
    onShareTrack: (File) -> Unit,
    onProtectTrip: (Long) -> Unit,
    onDeleteUnprotected: (Long) -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<GalleryItem?>(null) }
    var pendingTripDelete by remember { mutableStateOf<GalleryTrip?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
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
                        onClick = { onSetFilter(option) },
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.days.forEach { day ->
                        item(key = "day-${day.label}") {
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                            )
                        }
                        items(day.trips.size, key = { index -> day.trips[index].key }) { index ->
                            val card = day.trips[index]
                            TripCard(
                                card = card,
                                onToggle = { card.tripId?.let(onToggleTrip) },
                                onOpenTrack = { card.trackFile?.let(onOpenTrack) },
                                onShareTrack = { card.trackFile?.let(onShareTrack) },
                                onProtectTrip = { card.tripId?.let(onProtectTrip) },
                                onDeleteUnprotected = { pendingTripDelete = card },
                                onOpenSegment = onOpenSegment,
                                onProtect = onProtect,
                                onUnprotect = onUnprotect,
                                onShareClip = onShareClip,
                                onDeleteClip = { pendingDelete = it },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
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
                        onDelete(item.segment.id)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            },
        )
    }

    pendingTripDelete?.let { card ->
        val count = card.unprotectedCount
        AlertDialog(
            onDismissRequest = { pendingTripDelete = null },
            title = { Text(if (count == 1) "Delete 1 unprotected clip?" else "Delete $count unprotected clips?") },
            text = {
                Text(
                    buildString {
                        append("This removes the unprotected video files of \"${card.title}\" from the device. ")
                        if (card.protectedCount > 0) {
                            append("The ${card.protectedCount} protected clip(s) stay, and so does the track. ")
                        } else {
                            append("The trip's GPX track goes with its last clip. ")
                        }
                        append("It cannot be undone.")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        card.tripId?.let(onDeleteUnprotected)
                        pendingTripDelete = null
                    },
                    enabled = count > 0,
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTripDelete = null }) { Text("Keep them") }
            },
        )
    }
}

@Composable
private fun TripCard(
    card: GalleryTrip,
    onToggle: () -> Unit,
    onOpenTrack: () -> Unit,
    onShareTrack: () -> Unit,
    onProtectTrip: () -> Unit,
    onDeleteUnprotected: () -> Unit,
    onOpenSegment: (Long) -> Unit,
    onProtect: (Long) -> Unit,
    onUnprotect: (Long) -> Unit,
    onShareClip: (GalleryItem) -> Unit,
    onDeleteClip: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalRoadguardStatusColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val collapsible = card.trip != null

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = collapsible, onClick = onToggle)
                    .semantics { contentDescription = "Trip ${card.title}, ${card.meta}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RouteThumbnail(points = card.sketch, recording = card.isRecording, modifier = Modifier.size(44.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = card.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = card.meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (collapsible) {
                    Icon(
                        imageVector = if (card.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (card.expanded) "Hide the clips in this trip" else "Show the clips in this trip",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (card.isRecording) {
                    TripChip(text = "Recording now", iconRes = R.drawable.ic_fiber_manual_record, container = status.recordingContainer, content = status.onRecordingContainer)
                }
                if (card.incidentCount > 0) {
                    TripChip(
                        text = if (card.incidentCount == 1) "1 incident" else "${card.incidentCount} incidents",
                        iconRes = R.drawable.ic_report_problem,
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        content = status.warning,
                    )
                }
                if (card.protectedCount > 0) {
                    TripChip(
                        text = "${card.protectedCount} protected",
                        iconRes = R.drawable.ic_lock,
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        content = status.protected,
                    )
                }
                if (!card.isRecording && card.incidentCount == 0 && card.protectedCount == 0) {
                    Text(
                        text = "No incidents  ·  nothing protected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (card.trip != null) {
                    FilledTonalIconButton(
                        onClick = onOpenTrack,
                        enabled = card.trackFile != null,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_map),
                            contentDescription = "Open GPX track in a map app",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onShareTrack,
                        enabled = card.trackFile != null,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share GPX track", modifier = Modifier.size(22.dp))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                            Icon(painterResource(R.drawable.ic_more_horiz), contentDescription = "More actions for this trip")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Open track in a map app") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_map), contentDescription = null) },
                                enabled = card.trackFile != null,
                                onClick = {
                                    onOpenTrack()
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share track (GPX)") },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                enabled = card.trackFile != null,
                                onClick = {
                                    onShareTrack()
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (card.items.size == 1) "Protect this clip" else "Protect all ${card.items.size} clips") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                                enabled = card.unprotectedCount > 0,
                                onClick = {
                                    onProtectTrip()
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (card.unprotectedCount) {
                                            0 -> "Delete unprotected clips"
                                            1 -> "Delete the 1 unprotected clip"
                                            else -> "Delete the ${card.unprotectedCount} unprotected clips"
                                        },
                                    )
                                },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = null) },
                                enabled = card.unprotectedCount > 0 && !card.isRecording,
                                onClick = {
                                    onDeleteUnprotected()
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            if (card.trackFile == null && card.trip != null && !card.isRecording) {
                Text(
                    text = "No GPX track was saved for this trip.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            card.namesHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (card.expanded) {
                Column {
                    HorizontalDivider()
                    card.items.forEach { item ->
                        SegmentRow(
                            item = item,
                            onOpen = { onOpenSegment(item.segment.id) },
                            onProtect = { onProtect(item.segment.id) },
                            onUnprotect = { onUnprotect(item.segment.id) },
                            onShare = { onShareClip(item) },
                            onDelete = { onDeleteClip(item) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TripChip(text: String, iconRes: Int, container: Color, content: Color) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/**
 * The route's shape, drawn from the track points alone.
 *
 * No tiles: a list of trips would otherwise be a list of map renders, and the baseline phone's GPU
 * belongs to the video encoder. A green dot marks the start and a warm one the end, in the same
 * colours the map pane uses for the vehicle, so the picture reads without a legend.
 */
@Composable
fun RouteThumbnail(points: List<Pair<Float, Float>>, recording: Boolean, modifier: Modifier = Modifier) {
    val status = LocalRoadguardStatusColors.current
    val line = MaterialTheme.colorScheme.primary
    val start = status.ok
    val end = if (recording) status.recording else MaterialTheme.colorScheme.tertiary
    val placeholder = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty()) {
            Icon(
                painterResource(R.drawable.ic_route),
                contentDescription = null,
                tint = placeholder,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(7.dp)) {
                val toOffset: (Pair<Float, Float>) -> Offset = { (x, y) -> Offset(x * size.width, y * size.height) }
                if (points.size > 1) {
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        val offset = toOffset(point)
                        if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                    }
                    drawPath(path, color = line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawCircle(color = start, radius = 3.5.dp.toPx(), center = toOffset(points.first()))
                drawCircle(color = end, radius = 3.5.dp.toPx(), center = toOffset(points.last()))
            }
        }
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
            // A clip still being written has no index to play from yet.
            .clickable(enabled = item.exists && !item.inProgress, onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(
                when {
                    !item.exists -> R.drawable.ic_error_outline
                    item.inProgress -> R.drawable.ic_fiber_manual_record
                    item.event != null -> R.drawable.ic_report_problem
                    item.isProtected -> R.drawable.ic_lock
                    else -> R.drawable.ic_movie
                },
            ),
            contentDescription = null,
            tint = when {
                !item.exists -> status.critical
                item.inProgress -> status.recording
                item.event != null -> status.warning
                item.isProtected -> status.protected
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.timeLabel,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (item.inProgress) {
                    "Still recording  ·  playable once it is finished"
                } else {
                    buildList {
                        add(GalleryFormat.clipDuration(segment.durationMs))
                        add(GalleryFormat.size(segment.sizeBytes))
                        if (segment.widthPx > 0) add("${segment.widthPx}x${segment.heightPx}")
                        if (segment.frameRate > 0) add("${segment.frameRate} fps")
                        if (segment.hasAudio) add("audio")
                    }.joinToString("  ·  ")
                },
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
                    enabled = item.exists && !item.inProgress,
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
                    enabled = !item.isProtected && !item.inProgress,
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
        if (totalCount == 0) {
            // Somebody who expected footage here needs to know where to look, not just that the
            // list is empty: files still on disk, files in quarantine, and what the last start-up
            // did with them are all one screen away.
            Text(
                text = "If you expected recordings here, Settings → Diagnostics shows what is on disk, " +
                    "what is in quarantine, and what the last start-up repaired or removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Shares one recording through Roadguard's FileProvider, at explicit user request only. */
private fun shareSegment(context: android.content.Context, item: GalleryItem) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share this clip"))
    }
}
