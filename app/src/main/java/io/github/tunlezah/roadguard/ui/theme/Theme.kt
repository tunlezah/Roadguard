package io.github.tunlezah.roadguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import io.github.tunlezah.roadguard.settings.ThemeSetting

/**
 * Roadguard's Material 3 theme, with the four modes the specification requires:
 * follow-system, light, dark, and OLED (true black).
 *
 * Dynamic colour is offered but **off by default**: a vehicle app benefits more from a
 * predictable, contrast-checked palette than from wallpaper tinting, and the dashcam status
 * colours (recording red, protected amber) must not drift with the user's wallpaper. When
 * dynamic colour is on, the Roadguard status colours are still used unchanged.
 */
@Composable
fun RoadguardTheme(
    themeSetting: ThemeSetting = ThemeSetting.System,
    useDynamicColour: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val mode = when (themeSetting) {
        ThemeSetting.System -> if (systemDark) ThemeMode.Dark else ThemeMode.Light
        ThemeSetting.Light -> ThemeMode.Light
        ThemeSetting.Dark -> ThemeMode.Dark
        ThemeSetting.Oled -> ThemeMode.Oled
    }

    // Dynamic colour arrived in API 31 and minSdk is 34, so it needs no availability check.
    val context = LocalContext.current
    val scheme = when {
        useDynamicColour && mode == ThemeMode.Light -> dynamicLightColorScheme(context)

        useDynamicColour && mode == ThemeMode.Dark -> dynamicDarkColorScheme(context)

        useDynamicColour && mode == ThemeMode.Oled ->
            // Keep the user's dynamic hues but force the true-black surfaces OLED implies.
            dynamicDarkColorScheme(context).copy(
                background = OledBackground,
                surface = OledSurface,
                surfaceContainerLowest = OledSurfaceContainerLowest,
                surfaceContainerLow = OledSurfaceContainerLow,
                surfaceContainer = OledSurfaceContainer,
                surfaceContainerHigh = OledSurfaceContainerHigh,
                surfaceContainerHighest = OledSurfaceContainerHighest,
                outlineVariant = OledOutlineVariant,
            )

        mode == ThemeMode.Light -> RoadguardLightScheme
        mode == ThemeMode.Oled -> RoadguardOledScheme
        else -> RoadguardDarkScheme
    }

    val statusColors = if (mode == ThemeMode.Light) LightStatusColors else DarkStatusColors

    CompositionLocalProvider(LocalRoadguardStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = RoadguardTypography,
            shapes = RoadguardShapes,
            content = content,
        )
    }
}

/** Which concrete palette a [ThemeSetting] resolved to for the current system state. */
enum class ThemeMode { Light, Dark, Oled }

internal val RoadguardDarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
)

internal val RoadguardOledScheme = RoadguardDarkScheme.copy(
    background = OledBackground,
    surface = OledSurface,
    surfaceContainerLowest = OledSurfaceContainerLowest,
    surfaceContainerLow = OledSurfaceContainerLow,
    surfaceContainer = OledSurfaceContainer,
    surfaceContainerHigh = OledSurfaceContainerHigh,
    surfaceContainerHighest = OledSurfaceContainerHighest,
    outlineVariant = OledOutlineVariant,
)

internal val RoadguardLightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
)
