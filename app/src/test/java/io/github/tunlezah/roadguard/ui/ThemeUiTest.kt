package io.github.tunlezah.roadguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.settings.ThemeSetting
import io.github.tunlezah.roadguard.ui.theme.LocalRoadguardStatusColors
import io.github.tunlezah.roadguard.ui.theme.RoadguardStatusColors
import io.github.tunlezah.roadguard.ui.theme.RoadguardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The four themes, resolved through the real [RoadguardTheme].
 *
 * The interesting one is OLED. It is a *separate setting* from Dark rather than a tweak to it,
 * because on an OLED panel a true-black pixel costs no backlight at all -- which matters for an
 * app that runs for hours in a cradle. These tests are what stop OLED quietly degrading into "dark
 * with slightly different greys" after a palette edit.
 *
 * Compose's test rule allows one `setContent` per test, so every theme is composed inside a single
 * composition and captured in one pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
class ThemeUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the light theme is light and its content is dark`() {
        val light = captureAll().getValue(ThemeSetting.Light)

        assertThat(light.background.luminance()).isGreaterThan(0.5f)
        assertThat(light.onBackground.luminance()).isLessThan(0.5f)
    }

    @Test
    fun `the dark theme is dark but deliberately not pure black`() {
        val dark = captureAll().getValue(ThemeSetting.Dark)

        assertThat(dark.background.luminance()).isLessThan(0.2f)
        // A slight lift off black is what keeps Material's surface elevation readable.
        assertThat(dark.background).isNotEqualTo(Color.Black)
    }

    @Test
    fun `the OLED theme is true black in every surface role`() {
        val oled = captureAll().getValue(ThemeSetting.Oled)

        assertThat(oled.background).isEqualTo(Color.Black)
        assertThat(oled.surface).isEqualTo(Color.Black)
        assertThat(oled.surfaceContainerLowest).isEqualTo(Color.Black)
    }

    @Test
    fun `OLED keeps the dark theme's content colours, so text stays legible`() {
        val captured = captureAll()
        val dark = captured.getValue(ThemeSetting.Dark)
        val oled = captured.getValue(ThemeSetting.Oled)

        assertThat(oled.onBackground).isEqualTo(dark.onBackground)
        assertThat(oled.primary).isEqualTo(dark.primary)
    }

    @Test
    fun `OLED differs from Dark in its surfaces, so it is a real setting`() {
        val captured = captureAll()
        val dark = captured.getValue(ThemeSetting.Dark)
        val oled = captured.getValue(ThemeSetting.Oled)

        assertThat(oled.background).isNotEqualTo(dark.background)
        assertThat(oled.surfaceContainerLow).isNotEqualTo(dark.surfaceContainerLow)
    }

    @Test
    fun `light and dark supply different status palettes`() {
        val captured = captureAll()

        assertThat(captured.getValue(ThemeSetting.Light).status.recording)
            .isNotEqualTo(captured.getValue(ThemeSetting.Dark).status.recording)
    }

    @Test
    fun `OLED shares the dark status palette`() {
        // OLED changes surfaces, not the semantic colour language: the recording red must stay the
        // same red so a user switching themes does not have to relearn what red means.
        val captured = captureAll()

        assertThat(captured.getValue(ThemeSetting.Oled).status)
            .isEqualTo(captured.getValue(ThemeSetting.Dark).status)
    }

    @Test
    fun `every theme provides a usable scrim and overlay text colour for the on-video HUD`() {
        val captured = captureAll()

        ThemeSetting.entries.forEach { setting ->
            val status = captured.getValue(setting).status
            assertThat(status.overlayText.alpha).isGreaterThan(0.9f)
            assertThat(status.overlayScrim.alpha).isGreaterThan(0.3f)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h892dp-notnight-xhdpi")
    fun `follow-system resolves to light when the system is light`() {
        val captured = captureAll()

        assertThat(captured.getValue(ThemeSetting.System).background)
            .isEqualTo(captured.getValue(ThemeSetting.Light).background)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h892dp-night-xhdpi")
    fun `follow-system resolves to dark when the system is dark`() {
        val captured = captureAll()

        assertThat(captured.getValue(ThemeSetting.System).background)
            .isEqualTo(captured.getValue(ThemeSetting.Dark).background)
    }

    @Test
    fun `dynamic colour still forces true black surfaces under OLED`() {
        // The user keeps their wallpaper hues, but not at the cost of the thing OLED is for.
        var background = Color.Unspecified
        compose.setContent {
            RoadguardTheme(themeSetting = ThemeSetting.Oled, useDynamicColour = true) {
                background = MaterialTheme.colorScheme.background
            }
        }
        compose.waitForIdle()

        assertThat(background).isEqualTo(Color.Black)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────

    private data class Captured(
        val background: Color,
        val onBackground: Color,
        val surface: Color,
        val surfaceContainerLow: Color,
        val surfaceContainerLowest: Color,
        val primary: Color,
        val status: RoadguardStatusColors,
    )

    private fun captureAll(): Map<ThemeSetting, Captured> {
        val captured = mutableMapOf<ThemeSetting, Captured>()
        compose.setContent {
            ThemeSetting.entries.forEach { setting ->
                RoadguardTheme(themeSetting = setting) {
                    val scheme = MaterialTheme.colorScheme
                    captured[setting] = Captured(
                        background = scheme.background,
                        onBackground = scheme.onBackground,
                        surface = scheme.surface,
                        surfaceContainerLow = scheme.surfaceContainerLow,
                        surfaceContainerLowest = scheme.surfaceContainerLowest,
                        primary = scheme.primary,
                        status = LocalRoadguardStatusColors.current,
                    )
                }
            }
        }
        compose.waitForIdle()
        return captured.toMap()
    }
}

/** Rough relative luminance: enough to tell a light palette from a dark one. */
private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
