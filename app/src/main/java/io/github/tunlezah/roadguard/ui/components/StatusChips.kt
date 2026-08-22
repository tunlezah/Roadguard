package io.github.tunlezah.roadguard.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.ui.theme.ChipCorner

/**
 * A compact status pill for the driving screen.
 *
 * Chips are the only chrome allowed over the camera image, and they follow three rules taken from
 * Android's guidance for in-vehicle use: a single glance must be enough, the touch targets are
 * either absent (pure status) or at least 48 dp (interactive), and every chip carries a content
 * description because the icon alone is not the message.
 */
@Composable
fun StatusChip(
    text: String,
    iconRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    containerColour: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
    contentColour: Color = MaterialTheme.colorScheme.onSurface,
    pulsing: Boolean = false,
) {
    val alpha = if (pulsing) rememberRecordingPulse() else 1f
    Row(
        modifier = modifier
            .clip(ChipCorner)
            .background(containerColour)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            // The whole chip reads as one thing to TalkBack; the icon is decoration.
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColour,
            modifier = Modifier
                .size(16.dp)
                .alpha(alpha),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColour,
        )
    }
}

/** The recording indicator: a red dot that breathes, matching the artwork's record light. */
@Composable
fun RecordingDot(modifier: Modifier = Modifier, colour: Color, contentDescription: String) {
    val alpha = rememberRecordingPulse()
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(12.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(colour)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    )
}

/**
 * The shared recording pulse.
 *
 * Slow and gentle on purpose: a fast blink in peripheral vision is a distraction, and the
 * specification is explicit that the app must not encourage interaction while driving. Held at a
 * constant value when animations are reduced under thermal pressure.
 */
@Composable
fun rememberRecordingPulse(animate: Boolean = true): Float {
    if (!animate) return 1f
    val transition = rememberInfiniteTransition(label = "recording-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording-pulse-alpha",
    )
    return alpha
}

/** Standard icon for a GNSS fix quality, using familiar Material location symbols. */
fun gpsIconFor(quality: io.github.tunlezah.roadguard.location.FixQuality): Int = when (quality) {
    io.github.tunlezah.roadguard.location.FixQuality.NoSignal -> R.drawable.ic_gps_off
    io.github.tunlezah.roadguard.location.FixQuality.Searching -> R.drawable.ic_gps_not_fixed
    io.github.tunlezah.roadguard.location.FixQuality.Poor -> R.drawable.ic_gps_not_fixed
    io.github.tunlezah.roadguard.location.FixQuality.Good,
    io.github.tunlezah.roadguard.location.FixQuality.Excellent,
    -> R.drawable.ic_gps_fixed
}

/** Standard icon for a thermal level, using the familiar thermostat symbol. */
fun thermalIconFor(level: io.github.tunlezah.roadguard.thermal.ThermalLevel): Int = when (level) {
    io.github.tunlezah.roadguard.thermal.ThermalLevel.Normal -> R.drawable.ic_device_thermostat
    else -> R.drawable.ic_thermostat
}
