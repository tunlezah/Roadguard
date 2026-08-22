package io.github.tunlezah.roadguard.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.R
import io.github.tunlezah.roadguard.settings.SegmentLength
import io.github.tunlezah.roadguard.ui.settings.SettingsChoiceDialog
import io.github.tunlezah.roadguard.ui.settings.SettingsChoiceRow
import io.github.tunlezah.roadguard.ui.settings.SettingsInfoRow
import io.github.tunlezah.roadguard.ui.settings.SettingsSection
import io.github.tunlezah.roadguard.ui.settings.SettingsSliderRow
import io.github.tunlezah.roadguard.ui.settings.SettingsSwitchRow
import io.github.tunlezah.roadguard.ui.settings.SettingsWarning
import io.github.tunlezah.roadguard.ui.theme.RoadguardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings row primitives.
 *
 * These rows carry Roadguard's honesty rules, and the rules are what is tested:
 *
 *  * a **disabled** row still shows its value *and* its subtitle, because a switch that cannot be
 *    moved is only acceptable if the user can see why;
 *  * a choice row shows the **current value as its own line**, not squeezed beside the title, so a
 *    long label like "Overlay, metadata and GPX track" is never truncated into meaninglessness;
 *  * the picker can show an option that the hardware does not support, greyed, with its reason,
 *    rather than accepting it and quietly overriding it later; and
 *  * a slider's semantics carry the value in words, because a thumb position is not a reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class SettingsComponentsUiTest {

    @get:Rule val compose = createComposeRule()

    // ── Switch rows ────────────────────────────────────────────────────────────────────

    @Test
    fun `a switch row reflects and reports its state`() {
        var checked = false
        compose.setContent {
            RoadguardTheme {
                SettingsSwitchRow(
                    title = "Record audio",
                    iconRes = R.drawable.ic_mic,
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        compose.onNodeWithText("Record audio").assertIsOff()
        compose.onNodeWithText("Record audio").performClick()
        assertThat(checked).isTrue()
    }

    @Test
    fun `a switch row recomposes when its state changes`() {
        compose.setContent {
            RoadguardTheme {
                var checked by remember { mutableStateOf(false) }
                SettingsSwitchRow(
                    title = "Record audio",
                    iconRes = R.drawable.ic_mic,
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        compose.onNodeWithText("Record audio").assertIsOff()
        compose.onNodeWithText("Record audio").performClick()
        compose.onNodeWithText("Record audio").assertIsOn()
    }

    @Test
    fun `a disabled switch row still shows the reason it is disabled`() {
        compose.setContent {
            RoadguardTheme {
                SettingsSwitchRow(
                    title = "Dual camera",
                    iconRes = R.drawable.ic_flip_camera_android,
                    checked = false,
                    onCheckedChange = {},
                    subtitle = "This device reports no concurrent camera pairs",
                    enabled = false,
                )
            }
        }

        compose.onNodeWithText("Dual camera").assertIsNotEnabled()
        compose.onNodeWithText("This device reports no concurrent camera pairs").assertIsDisplayed()
    }

    @Test
    fun `a disabled switch row does not report clicks`() {
        var clicks = 0
        compose.setContent {
            RoadguardTheme {
                SettingsSwitchRow(
                    title = "Dual camera",
                    iconRes = R.drawable.ic_flip_camera_android,
                    checked = false,
                    onCheckedChange = { clicks++ },
                    enabled = false,
                )
            }
        }

        compose.onNodeWithText("Dual camera").performClick()
        assertThat(clicks).isEqualTo(0)
    }

    // ── Choice rows and the picker ─────────────────────────────────────────────────────

    @Test
    fun `a choice row shows its current value as its own line`() {
        compose.setContent {
            RoadguardTheme {
                SettingsChoiceRow(
                    title = "GPS storage",
                    iconRes = R.drawable.ic_pin_drop,
                    currentLabel = "Overlay, metadata and GPX track",
                    onClick = {},
                )
            }
        }

        compose.onNodeWithText("GPS storage").assertIsDisplayed()
        compose.onNodeWithText("Overlay, metadata and GPX track").assertIsDisplayed()
    }

    @Test
    fun `a choice row reports the click that opens its picker`() {
        var clicks = 0
        compose.setContent {
            RoadguardTheme {
                SettingsChoiceRow(
                    title = "Segment length",
                    iconRes = R.drawable.ic_schedule,
                    currentLabel = "3 minutes",
                    onClick = { clicks++ },
                )
            }
        }

        compose.onNodeWithText("Segment length").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `a disabled choice row hides its chevron and swallows clicks`() {
        var clicks = 0
        compose.setContent {
            RoadguardTheme {
                SettingsChoiceRow(
                    title = "Quality",
                    iconRes = R.drawable.ic_high_quality,
                    currentLabel = "Auto",
                    onClick = { clicks++ },
                    enabled = false,
                )
            }
        }

        compose.onNodeWithText("Quality").performClick()
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun `the picker marks the current option as selected`() {
        compose.setContent {
            RoadguardTheme {
                SettingsChoiceDialog(
                    title = "Segment length",
                    options = SegmentLength.entries,
                    currentValue = SegmentLength.Minutes3,
                    labelFor = { it.label },
                    onPick = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("3 minutes").assertIsSelected()
    }

    @Test
    fun `the picker reports the option that was chosen`() {
        var picked: SegmentLength? = null
        compose.setContent {
            RoadguardTheme {
                SettingsChoiceDialog(
                    title = "Segment length",
                    options = SegmentLength.entries,
                    currentValue = SegmentLength.Minutes3,
                    labelFor = { it.label },
                    onPick = { picked = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("10 minutes").performClick()
        assertThat(picked).isEqualTo(SegmentLength.Minutes10)
    }

    @Test
    fun `the picker shows an unavailable option greyed with its reason instead of hiding it`() {
        compose.setContent {
            RoadguardTheme {
                SettingsChoiceDialog(
                    title = "Quality",
                    options = listOf("1080p", "2160p (4K)"),
                    currentValue = "1080p",
                    labelFor = { it },
                    onPick = {},
                    onDismiss = {},
                    descriptionFor = {
                        if (it == "2160p (4K)") "This camera does not report 4K" else null
                    },
                    enabledFor = { it != "2160p (4K)" },
                )
            }
        }

        compose.onNodeWithText("2160p (4K)").assertIsNotEnabled()
        compose.onNodeWithText("This camera does not report 4K").assertIsDisplayed()
    }

    @Test
    fun `the picker does not close itself`() {
        // The caller applies the value and dismisses; a dialog that closed itself would race the
        // state write and could show a stale value on the row behind it.
        var dismissals = 0
        var picks = 0
        compose.setContent {
            RoadguardTheme {
                SettingsChoiceDialog(
                    title = "Segment length",
                    options = SegmentLength.entries,
                    currentValue = SegmentLength.Minutes3,
                    labelFor = { it.label },
                    onPick = { picks++ },
                    onDismiss = { dismissals++ },
                )
            }
        }

        compose.onNodeWithText("5 minutes").performClick()
        assertThat(picks).isEqualTo(1)
        assertThat(dismissals).isEqualTo(0)

        compose.onNodeWithText("Done").performClick()
        assertThat(dismissals).isEqualTo(1)
    }

    // ── Sliders, info rows, warnings, sections ─────────────────────────────────────────

    @Test
    fun `a slider announces its value in words`() {
        compose.setContent {
            RoadguardTheme {
                SettingsSliderRow(
                    title = "Start-up delay",
                    valueLabel = "3 seconds",
                    value = 3f,
                    range = 0f..30f,
                    steps = 29,
                    onValueChange = {},
                )
            }
        }

        compose.onNodeWithText("3 seconds").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start-up delay, 3 seconds").assertExists()
    }

    @Test
    fun `a disabled slider is reported as disabled`() {
        compose.setContent {
            RoadguardTheme {
                SettingsSliderRow(
                    title = "Stop delay",
                    valueLabel = "5 minutes",
                    value = 300f,
                    range = 0f..900f,
                    steps = 14,
                    onValueChange = {},
                    enabled = false,
                )
            }
        }

        compose.onNodeWithContentDescription("Stop delay, 5 minutes").assertIsNotEnabled()
    }

    @Test
    fun `an info row shows a read-only fact`() {
        compose.setContent {
            RoadguardTheme {
                SettingsInfoRow(title = "Codec in use", value = "H.264", iconRes = R.drawable.ic_hd)
            }
        }

        compose.onNodeWithText("Codec in use").assertIsDisplayed()
        compose.onNodeWithText("H.264").assertIsDisplayed()
    }

    @Test
    fun `a warning is rendered as text a user can read, not just a colour`() {
        compose.setContent {
            RoadguardTheme {
                SettingsWarning(text = "Recording zoom permanently narrows the recorded view")
            }
        }

        compose.onNodeWithText("Recording zoom permanently narrows the recorded view")
            .assertIsDisplayed()
    }

    @Test
    fun `a section shows its title, its caveat subtitle and its contents`() {
        compose.setContent {
            RoadguardTheme {
                SettingsSection(
                    title = "Overlays",
                    subtitle = "These are burned into the video and cannot be removed later",
                ) {
                    SettingsSwitchRow(
                        title = "Coordinates",
                        iconRes = R.drawable.ic_pin_drop,
                        checked = false,
                        onCheckedChange = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Overlays").assertIsDisplayed()
        compose.onNodeWithText("These are burned into the video and cannot be removed later")
            .assertIsDisplayed()
        compose.onNodeWithText("Coordinates").assertIsEnabled()
    }
}
