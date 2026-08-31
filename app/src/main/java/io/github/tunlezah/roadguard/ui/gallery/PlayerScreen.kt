// media3's Compose surface and its state holders are still @UnstableApi. This screen is a
// convenience for reviewing footage, not part of the recording path, so an unstable playback API
// here cannot affect the one thing Roadguard must not get wrong.
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.tunlezah.roadguard.ui.gallery

import android.content.Intent
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.storage.Mp4Inspector
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors
import kotlinx.coroutines.delay

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
 * A segment can be missing (the user deleted it) or unplayable (the process was killed mid-write and
 * the MP4 never got its index). Both are checked before the player is built, and reported plainly,
 * because "the video player crashed" tells the user nothing useful about footage they may have been
 * counting on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    segmentId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val item = remember(segmentId, state) { viewModel.itemFor(segmentId) }
    val context = LocalContext.current
    val status = LocalRoadguardStatusColors.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(item?.timeLabel ?: "Recording")
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

                else -> {
                    val verdict = remember(item.file) { Mp4Inspector.inspect(item.file) }
                    if (!verdict.isUsable) {
                        Message(
                            "This clip cannot be played: ${verdict.summary}. Roadguard keeps files it " +
                                "cannot verify rather than deleting them, in case they can be " +
                                "recovered with a repair tool.",
                            Modifier.fillMaxSize(),
                        )
                    } else {
                        VideoPlayer(item = item, modifier = Modifier.fillMaxWidth().weight(1f))
                        SegmentDetails(item = item, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(item: GalleryItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(item.file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(item.file)))
            prepare()
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    // A released player is the difference between reviewing footage and leaking a codec instance
    // every time the screen is opened.
    DisposableEffect(player) { onDispose { player.release() } }

    val playPause = rememberPlayPauseButtonState(player)
    val presentation = rememberPresentationState(player)

    var positionMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) positionMs = player.currentPosition
            delay(250)
        }
    }
    val durationMs = player.duration.takeIf { it > 0 } ?: item.segment.durationMs

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            Text(
                text = formatClock(if (scrubbing) scrubTarget.toLong() else positionMs),
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = if (scrubbing) scrubTarget else positionMs.toFloat(),
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                onValueChange = { value ->
                    scrubbing = true
                    scrubTarget = value
                },
                onValueChangeFinished = {
                    player.seekTo(scrubTarget.toLong())
                    positionMs = scrubTarget.toLong()
                    scrubbing = false
                },
                modifier = Modifier.weight(1f),
            )
            Text(text = formatClock(durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
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
