package io.github.tunlezah.roadguard.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Status colours that Material 3's role set does not cover but a dashcam needs: recording,
 * protected footage, thermal pressure, GPS quality and storage pressure.
 *
 * They live in their own [Immutable] holder behind a [staticCompositionLocalOf] rather than
 * being sprinkled through the UI, so the light, dark and OLED variants stay consistent and
 * so the contrast test can check them all in one place.
 */
@Immutable
data class RoadguardStatusColors(
    val recording: Color,
    val onRecording: Color,
    val recordingContainer: Color,
    val onRecordingContainer: Color,
    val protected: Color,
    val onProtected: Color,
    val ok: Color,
    val onOk: Color,
    val warning: Color,
    val onWarning: Color,
    val critical: Color,
    val onCritical: Color,
    val idle: Color,
    val onIdle: Color,
    /** Scrim drawn behind text laid over the camera image so it stays legible. */
    val overlayScrim: Color,
    /** Text colour for the on-video HUD, which must read against any road scene. */
    val overlayText: Color,
)

internal val DarkStatusColors = RoadguardStatusColors(
    recording = BrandRecordRed,
    onRecording = Color(0xFFFFFFFF),
    recordingContainer = Color(0xFF54100C),
    onRecordingContainer = Color(0xFFFFDAD5),
    protected = Color(0xFFFFC44D),
    onProtected = Color(0xFF3B2A00),
    ok = Color(0xFF7BDC96),
    onOk = Color(0xFF00391C),
    warning = Color(0xFFFFC44D),
    onWarning = Color(0xFF3B2A00),
    critical = Color(0xFFFF8A80),
    onCritical = Color(0xFF4E0002),
    idle = Color(0xFF9AA3A8),
    onIdle = Color(0xFF12171A),
    overlayScrim = Color(0x99000000),
    overlayText = Color(0xFFFFFFFF),
)

internal val LightStatusColors = RoadguardStatusColors(
    recording = Color(0xFFC1291F),
    onRecording = Color(0xFFFFFFFF),
    recordingContainer = Color(0xFFFFDAD5),
    onRecordingContainer = Color(0xFF410100),
    protected = Color(0xFF7A5900),
    onProtected = Color(0xFFFFFFFF),
    ok = Color(0xFF186B37),
    onOk = Color(0xFFFFFFFF),
    warning = Color(0xFF7A5900),
    onWarning = Color(0xFFFFFFFF),
    critical = Color(0xFFB3261E),
    onCritical = Color(0xFFFFFFFF),
    idle = Color(0xFF5A6368),
    onIdle = Color(0xFFFFFFFF),
    overlayScrim = Color(0x99000000),
    overlayText = Color(0xFFFFFFFF),
)

val LocalRoadguardStatusColors = staticCompositionLocalOf { DarkStatusColors }
