// media3's Compose surface and its state holders are still @UnstableApi. This screen is a
// convenience for reviewing footage, not part of the recording path, so an unstable playback API
// here cannot affect the one thing Roadguard must not get wrong.
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.tunlezah.roadguard.ui.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.storage.Mp4Inspector
import io.github.tunlezah.roadguard.storage.Mp4Verdict
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Plays one recorded segment.
 *
 * ### Rotation is not this screen's problem
 *
 * Roadguard records portrait video as a portrait-oriented MP4 by writing a rotation hint rather
 * than rotating pixels. ExoPlayer honours that hint, so there is deliberately no rotation logic
 * here: if a clip ever played sideways, the bug would be in what the recorder wrote, and adding a
 * correction here would hide it.
 *
 * ### A broken file is a real case
 *
 * A segment can be missing (the user deleted it), still being written (the recorder is on it now),
 * or unplayable (the process was killed mid-write and the MP4 never got its index). All three are
 * checked before the player is built -- off the main thread, since the check reads the file -- and
 * reported plainly, because "the video player crashed" tells the user nothing useful about footage
 * they may have been counting on. A failure the player itself reports is shown the same way, with
 * a way to try again.
 *
 * ### The player lives only while the screen is on
 *
 * The decoder is built when the screen starts and released when it stops, not when the screen is
 * finally left. A hardware decoder is a scarce resource on the phones this app targets, and the
 * recorder may need every codec instance the moment this screen goes into the background. Where
 * playback was, and whether it was playing, survives that and a rotation.
 *
 * ### The clip is shown inside its trip
 *
 * The title is the trip's name, the subtitle says which clip of how many this is, and the card
 * below the video carries the trip's track and a way back to its clips. Previous and next step
 * through the trip in playing order without going back to the list.
 *
 * @param onOpenSegment replaces this screen with another clip of the same trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    segmentId: Long,
    onBack: () -> Unit,
    onOpenSegment: (Long) -> Unit,
    onOpenTrack: (java.io.File) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val item = remember(segmentId, state) { viewModel.itemFor(segmentId) }
    val position = remember(segmentId, state) { viewModel.positionOf(segmentId) }
    val context = LocalContext.current
    val status = LocalRoadguardStatusColors.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = position?.trip?.title ?: item?.timeLabel ?: "Recording",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                        if (position != null && item != null) {
                            Text(
                                text = "Clip ${position.index + 1} of ${position.count}  ·  ${item.timeLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    item?.let { current ->
                        IconButton(
                            onClick = {
                                if (current.isProtected) {
                                    viewModel.unprotect(current.segment.id)
                                } else {
                                    viewModel.protect(current.segment.id)
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (current.isProtected) R.drawable.ic_lock else R.drawable.ic_lock_open,
                                ),
                                contentDescription = if (current.isProtected) {
                                    "Remove protection"
                                } else {
                                    "Protect this clip"
                                },
                                tint = if (current.isProtected) status.protected else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            enabled = current.exists,
                            onClick = {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        current.file,
                                    )
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "video/mp4"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            "Share this clip",
                                        ),
                                    )
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                item == null -> Message(
                    "That recording is no longer in the index.",
                    Modifier.fillMaxSize(),
                )

                !item.exists -> Message(
                    "The file is missing. It may have been deleted, or moved to quarantine after an " +
                        "interrupted session.",
                    Modifier.fillMaxSize(),
                )

                item.inProgress -> Message(
                    "This clip is still being recorded. It can be played once it is finished.",
                    Modifier.fillMaxSize(),
                )

                else -> {
                    // The check opens the file; on a slow memory card that is long enough to
                    // stutter the screen if done during composition.
                    val verdict by produceState<Mp4Verdict?>(initialValue = null, item.file) {
                        value = withContext(Dispatchers.IO) { Mp4Inspector.inspect(item.file) }
                    }
                    when (val checked = verdict) {
                        null -> Message("Checking the clip…", Modifier.fillMaxSize())

                        // Whole, or whole but not describable by the metadata reader just now:
                        // the player is the better judge, and reports its own failures.
                        is Mp4Verdict.Playable, is Mp4Verdict.IndexedButUnread -> {
                            VideoPlayer(item = item, modifier = Modifier.fillMaxWidth().weight(1f))
                            SegmentDetails(item = item, modifier = Modifier.fillMaxWidth())
                            position?.let { current ->
                                TripContext(
                                    position = current,
                                    onOpenTrack = onOpenTrack,
                                    onAllClips = onBack,
                                    onOpenSegment = onOpenSegment,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        else -> Message(
                            "This clip cannot be played: ${checked.summary}. Roadguard keeps files it " +
                                "cannot verify rather than deleting them, in case they can be " +
                                "recovered with a repair tool.",
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** The trip a clip belongs to, with its track and its neighbours. */
@Composable
private fun TripContext(
    position: ClipPosition,
    onOpenTrack: (java.io.File) -> Unit,
    onAllClips: () -> Unit,
    onOpenSegment: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = position.trip
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trip.trip != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "THIS TRIP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RouteThumbnail(points = trip.sketch, recording = trip.isRecording, modifier = Modifier.size(44.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(text = trip.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = trip.meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { trip.trackFile?.let(onOpenTrack) },
                            enabled = trip.trackFile != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(painterResource(R.drawable.ic_map), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Open track")
                        }
                        OutlinedButton(onClick = onAllClips, modifier = Modifier.weight(1f)) {
                            Text("All ${position.count} clips")
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { position.previousSegmentId?.let(onOpenSegment) },
                enabled = position.previousSegmentId != null,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                Text(
                    position.previousSegmentId?.let { id ->
                        trip.items.firstOrNull { it.segment.id == id }?.let { "Previous ${it.timeLabel}" }
                    } ?: "First clip",
                )
            }
            TextButton(
                onClick = { position.nextSegmentId?.let(onOpenSegment) },
                enabled = position.nextSegmentId != null,
            ) {
                Text(
                    position.nextSegmentId?.let { id ->
                        trip.items.firstOrNull { it.segment.id == id }?.let { "Next ${it.timeLabel}" }
                    } ?: "Last clip",
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun VideoPlayer(item: GalleryItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Where playback was, kept across a rotation and a spell in the background.
    var resumePositionMs by rememberSaveable(item.file.path) { mutableLongStateOf(0L) }
    var resumePlaying by rememberSaveable(item.file.path) { mutableStateOf(false) }
    var problem by remember(item.file) { mutableStateOf<String?>(null) }
    var attempt by remember(item.file) { mutableIntStateOf(0) }
    var player by remember(item.file) { mutableStateOf<ExoPlayer?>(null) }

    // Built when the screen starts, released when it stops: see the class documentation.
    LifecycleStartEffect(item.file, attempt) {
        // A fresh player gets a clean slate: a failure reported by the previous one is history.
        problem = null
        val created = ExoPlayer.Builder(context).build().apply {
            // Take audio focus like any other player, and stop when the headphones come out.
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        problem = PlaybackProblems.describe(error)
                    }
                },
            )
            setMediaItem(MediaItem.fromUri(Uri.fromFile(item.file)))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = resumePlaying
            seekTo(resumePositionMs)
            prepare()
        }
        player = created
        onStopOrDispose {
            resumePositionMs = created.currentPosition
            // Carry on playing across a rotation; come back paused from the background.
            resumePlaying = created.playWhenReady &&
                created.playbackState != Player.STATE_ENDED &&
                context.isChangingConfigurations()
            created.release()
            player = null
        }
    }

    var positionMs by remember { mutableLongStateOf(resumePositionMs) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableFloatStateOf(0f) }
    val current = player
    LaunchedEffect(current) {
        val active = current ?: return@LaunchedEffect
        while (true) {
            if (!scrubbing) positionMs = active.currentPosition
            delay(250)
        }
    }
    val durationMs = current?.duration?.takeIf { it > 0 } ?: item.segment.durationMs

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (current != null) {
                PlayerSurface(
                    player = current,
                    surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            problem?.let { text ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        onClick = {
                            problem = null
                            attempt++
                        },
                    ) {
                        Text("Try again")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (current != null) {
                val playPause = rememberPlayPauseButtonState(current)
                FilledIconButton(
                    onClick = playPause::onClick,
                    enabled = playPause.isEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (playPause.showPlay) R.drawable.ic_fiber_manual_record else R.drawable.ic_pause,
                        ),
                        contentDescription = if (playPause.showPlay) "Play" else "Pause",
                    )
                }
            } else {
                FilledIconButton(onClick = {}, enabled = false, modifier = Modifier.size(48.dp)) {
                    Icon(painter = painterResource(R.drawable.ic_fiber_manual_record), contentDescription = "Play")
                }
            }
            Text(
                text = formatClock(if (scrubbing) scrubTarget.toLong() else positionMs),
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = if (scrubbing) scrubTarget else positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                enabled = current != null,
                onValueChange = { value ->
                    scrubbing = true
                    scrubTarget = value
                },
                onValueChangeFinished = {
                    current?.seekTo(scrubTarget.toLong())
                    positionMs = scrubTarget.toLong()
                    scrubbing = false
                },
                modifier = Modifier.weight(1f),
            )
            Text(text = formatClock(durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** True while the hosting activity is being recreated for a configuration change, such as a rotation. */
private fun Context.isChangingConfigurations(): Boolean {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.isChangingConfigurations
        current = current.baseContext
    }
    return false
}

@Composable
private fun SegmentDetails(item: GalleryItem, modifier: Modifier = Modifier) {
    val status = LocalRoadguardStatusColors.current
    val segment = item.segment
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = buildList {
                if (segment.widthPx > 0) add("${segment.widthPx}x${segment.heightPx}")
                if (segment.frameRate > 0) add("${segment.frameRate} fps")
                if (segment.rotationDegrees != 0) add("${segment.rotationDegrees}° rotation")
                add(segment.codec.removePrefix("video/").uppercase())
                add(if (segment.hasAudio) "with audio" else "no audio")
            }.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${segment.sizeBytes / (1024 * 1024)} MB  ·  profile ${segment.profileLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.isProtected) {
            Text(
                text = "Protected" + (segment.protectionReason?.let { " ($it)" } ?: "") +
                    ". The loop will not delete it.",
                style = MaterialTheme.typography.labelMedium,
                color = status.protected,
            )
        }
        item.event?.let { event ->
            Text(
                text = event.note ?: "Incident: ${event.kind.lowercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = status.warning,
            )
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatClock(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
