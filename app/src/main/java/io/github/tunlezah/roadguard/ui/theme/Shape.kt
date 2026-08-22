package io.github.tunlezah.roadguard.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Slightly softer than the Material 3 defaults: the two large panes (video and map) sit in
 * rounded containers, and status chips are fully rounded so they read as separate objects
 * over the camera image.
 */
val RoadguardShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Corner radius shared by the video and map panes. */
val PaneCorner = RoundedCornerShape(16.dp)

/** Fully rounded shape for the status chips drawn over the camera image. */
val ChipCorner = RoundedCornerShape(percent = 50)
