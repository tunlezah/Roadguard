package io.github.tunlezah.roadguard.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors

/**
 * The building blocks of the Settings screen.
 *
 * Roadguard has around forty settings and every one of them needs the same three things: a
 * readable label, room to explain *why* the setting matters, and a way to say "you cannot have
 * this, and here is the reason". Hand-rolling that forty times would guarantee it drifts, so the
 * shapes live here.
 *
 * Two rules are baked into every row rather than left to the caller:
 *
 *  * nothing truncates. No `maxLines`, no ellipsis, and no fixed-height rows -- a row grows
 *    downwards at a 200% font scale instead of hiding half of its explanation.
 *  * a row's whole area is the touch target, at least [RowMinHeight] tall, and it carries one
 *    merged semantics node so TalkBack reads "title, subtitle, on" as a single item rather than
 *    three.
 */
private val RowMinHeight = 56.dp

/** Material's disabled-content opacity, applied to titles but deliberately not to reasons. */
private const val DISABLED_ALPHA = 0.38f

/**
 * A titled group of rows.
 *
 * The grouping is the navigation: this screen is one long scroll rather than a tree of
 * sub-screens, because a driver returning to a parked car wants to find one switch, not learn a
 * hierarchy. [subtitle] carries the honest caveat for the whole group (what the overlays do to
 * the file, what event detection is not) where a per-row subtitle would repeat it four times.
 */
@Composable
fun SettingsSection(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 4.dp),
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

/**
 * A boolean setting.
 *
 * When [enabled] is false the row still shows its current value and its [subtitle]: a switch that
 * cannot be moved is only acceptable if the user can see why, so the reason belongs in the
 * subtitle and is drawn at full contrast even though the title is dimmed.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    iconRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(iconRes = iconRes, enabled = enabled)
        RowLabels(title = title, subtitle = subtitle, enabled = enabled, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        // Null callback: the row owns the gesture, so the switch is not a second target.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A setting with several named values, opening a [SettingsChoiceDialog].
 *
 * The current value is a line of its own rather than a right-hand column: "Overlay, metadata and
 * GPX track" beside a title is unreadable on a phone at a large font scale, and squeezing it
 * would mean truncating exactly the words that say what the setting does.
 */
@Composable
fun SettingsChoiceRow(
    title: String,
    subtitle: String? = null,
    iconRes: Int,
    currentLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(iconRes = iconRes, enabled = enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColour(enabled),
            )
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (enabled) {
            Spacer(Modifier.width(12.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The picker behind [SettingsChoiceRow].
 *
 * [descriptionFor] is the interesting parameter: several of Roadguard's choices have consequences
 * a label cannot carry ("burned into the picture", "stops as soon as ignition power goes"), and a
 * settings screen that hides those is not informing anyone. [enabledFor] exists so an option the
 * hardware does not offer can be shown, greyed, with its reason -- rather than silently accepted
 * and then quietly overridden by the profile selector.
 *
 * [onPick] is expected to apply the value *and* dismiss; the dialog does not close itself.
 */
@Composable
fun <T> SettingsChoiceDialog(
    title: String,
    options: List<T>,
    currentValue: T,
    labelFor: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    descriptionFor: (T) -> String? = { null },
    enabledFor: (T) -> Boolean = { true },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                options.forEach { option ->
                    val optionEnabled = enabledFor(option)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RowMinHeight)
                            .selectable(
                                selected = option == currentValue,
                                enabled = optionEnabled,
                                role = Role.RadioButton,
                                onClick = { onPick(option) },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == currentValue,
                            onClick = null,
                            enabled = optionEnabled,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = labelFor(option),
                                style = MaterialTheme.typography.bodyLarge,
                                color = titleColour(optionEnabled),
                            )
                            descriptionFor(option)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/**
 * A numeric setting.
 *
 * Sliders are used only for the settings where the exact number matters less than the rough
 * amount (delays, thresholds, zoom). [steps] is always set so the value lands on a round figure a
 * user can repeat, and [valueLabel] is the value in words -- a slider position is not a reading.
 */
@Composable
fun SettingsSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColour(enabled),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            // The thumb itself is small; the semantics node carries the name so TalkBack can
            // announce "Start-up delay, 3 seconds" while adjusting.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "$title, $valueLabel" },
        )
    }
}

/** A read-only fact: what the device reported, what is installed, where recordings go. */
@Composable
fun SettingsInfoRow(
    title: String,
    value: String,
    iconRes: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        iconRes?.let { RowIcon(iconRes = it, enabled = true) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The "this will cost you heat, battery or storage" note.
 *
 * Deliberately a warning colour and a warning icon: these appear next to the handful of settings
 * that can turn a reliable dashcam into an unreliable one, and the user is entitled to know
 * before they choose rather than afterwards from a thermal message.
 */
@Composable
fun SettingsWarning(text: String, modifier: Modifier = Modifier) {
    val status = LocalRoadguardStatusColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                color = status.warning.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_report_problem),
            contentDescription = "Warning",
            tint = status.warning,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Neutral explanatory text inside a section.
 *
 * Separate from [SettingsWarning] on purpose: a privacy statement or a "this is not a certified
 * crash detector" note is not a hazard, and dressing it as one would teach the user to ignore the
 * notes that are.
 */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A row that leads to another screen. */
@Composable
fun SettingsNavigationRow(
    title: String,
    subtitle: String? = null,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(iconRes = iconRes, enabled = true)
        RowLabels(title = title, subtitle = subtitle, enabled = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun RowIcon(iconRes: Int, enabled: Boolean) {
    Icon(
        painter = painterResource(iconRes),
        // The title says what the row is; repeating it here would make TalkBack say it twice.
        contentDescription = null,
        tint = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        },
        modifier = Modifier.size(24.dp),
    )
    Spacer(Modifier.width(16.dp))
}

@Composable
private fun RowLabels(title: String, subtitle: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = titleColour(enabled),
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun titleColour(enabled: Boolean): Color = if (enabled) {
    MaterialTheme.colorScheme.onSurface
} else {
    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
}
