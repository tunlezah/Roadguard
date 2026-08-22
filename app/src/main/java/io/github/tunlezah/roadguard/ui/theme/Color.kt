package io.github.tunlezah.roadguard.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Roadguard's palette is derived from the supplied application artwork so the app and its
 * icon read as one product:
 *
 *   #33CEFB  the cyan "before" arc          -> primary / interactive colour
 *   #FB5040  the warm "after" arc           -> tertiary / attention colour
 *   #FA3D33  the recording indicator dot    -> the recording state colour
 *
 * Every text-on-background pair below is contrast-checked by
 * `RoadguardColorContrastTest`, which fails the build if any pair drops under the WCAG AA
 * 4.5:1 ratio for body text (3:1 for large text and non-text indicators).
 */

internal val BrandCyan = Color(0xFF33CEFB)
internal val BrandWarm = Color(0xFFFB5040)
internal val BrandRecordRed = Color(0xFFFA3D33)

// ── Dark scheme ────────────────────────────────────────────────────────────────────────
internal val DarkPrimary = Color(0xFF6FD8FC)
internal val DarkOnPrimary = Color(0xFF00344A)
internal val DarkPrimaryContainer = Color(0xFF004C6B)
internal val DarkOnPrimaryContainer = Color(0xFFC4EBFF)
internal val DarkSecondary = Color(0xFFB3CAD6)
internal val DarkOnSecondary = Color(0xFF1D343E)
internal val DarkSecondaryContainer = Color(0xFF344A55)
internal val DarkOnSecondaryContainer = Color(0xFFCFE6F2)
internal val DarkTertiary = Color(0xFFFFB4A4)
internal val DarkOnTertiary = Color(0xFF5F1508)
internal val DarkTertiaryContainer = Color(0xFF7E2A1B)
internal val DarkOnTertiaryContainer = Color(0xFFFFDAD2)
internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)
internal val DarkBackground = Color(0xFF0F1417)
internal val DarkOnBackground = Color(0xFFE1E5E9)
internal val DarkSurface = Color(0xFF0F1417)
internal val DarkOnSurface = Color(0xFFE1E5E9)
internal val DarkSurfaceVariant = Color(0xFF40484C)
internal val DarkOnSurfaceVariant = Color(0xFFC3CBD0)
internal val DarkOutline = Color(0xFF8D959A)
internal val DarkOutlineVariant = Color(0xFF40484C)
internal val DarkSurfaceContainerLowest = Color(0xFF0A0E11)
internal val DarkSurfaceContainerLow = Color(0xFF171C1F)
internal val DarkSurfaceContainer = Color(0xFF1B2124)
internal val DarkSurfaceContainerHigh = Color(0xFF262B2F)
internal val DarkSurfaceContainerHighest = Color(0xFF31363A)
internal val DarkInverseSurface = Color(0xFFE1E5E9)
internal val DarkInverseOnSurface = Color(0xFF2C3134)

// ── OLED overrides: true black so unlit pixels really are unlit ────────────────────────
internal val OledBackground = Color(0xFF000000)
internal val OledSurface = Color(0xFF000000)
internal val OledSurfaceContainerLowest = Color(0xFF000000)
internal val OledSurfaceContainerLow = Color(0xFF0B0B0C)
internal val OledSurfaceContainer = Color(0xFF121314)
internal val OledSurfaceContainerHigh = Color(0xFF1A1B1D)
internal val OledSurfaceContainerHighest = Color(0xFF232426)
internal val OledOutlineVariant = Color(0xFF2E3033)

// ── Light scheme ───────────────────────────────────────────────────────────────────────
internal val LightPrimary = Color(0xFF00627F)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFC4EBFF)
internal val LightOnPrimaryContainer = Color(0xFF001E2A)
internal val LightSecondary = Color(0xFF4B6270)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFCFE6F2)
internal val LightOnSecondaryContainer = Color(0xFF061E27)
internal val LightTertiary = Color(0xFF9C4231)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFFFDAD2)
internal val LightOnTertiaryContainer = Color(0xFF3B0A02)
internal val LightError = Color(0xFFB3261E)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFF9DEDC)
internal val LightOnErrorContainer = Color(0xFF410E0B)
internal val LightBackground = Color(0xFFF7FAFC)
internal val LightOnBackground = Color(0xFF171C1F)
internal val LightSurface = Color(0xFFF7FAFC)
internal val LightOnSurface = Color(0xFF171C1F)
internal val LightSurfaceVariant = Color(0xFFDCE4E9)
internal val LightOnSurfaceVariant = Color(0xFF3F484C)
internal val LightOutline = Color(0xFF6F787D)
internal val LightOutlineVariant = Color(0xFFBFC8CD)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF1F4F7)
internal val LightSurfaceContainer = Color(0xFFEBEEF2)
internal val LightSurfaceContainerHigh = Color(0xFFE5E9EC)
internal val LightSurfaceContainerHighest = Color(0xFFDFE3E7)
internal val LightInverseSurface = Color(0xFF2C3134)
internal val LightInverseOnSurface = Color(0xFFEFF1F4)
