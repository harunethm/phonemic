package com.scylla.tool.phonemic.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// Matches the UI spec's --radius (14px) / --radius-sm (8px).
private val BrandShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(14.dp)
)

// Brand only defines a dark theme (see docs/index.html) - no light variant to switch to.
private val BrandColors = darkColorScheme(
    primary = PhoneMicAccent,
    onPrimary = PhoneMicAccentInk,
    secondary = PhoneMicAccent2,
    onSecondary = PhoneMicAccent2Ink,
    background = PhoneMicBg,
    onBackground = PhoneMicInk,
    surface = PhoneMicBg2,
    onSurface = PhoneMicInk,
    surfaceVariant = PhoneMicBg3,
    onSurfaceVariant = PhoneMicInkMuted,
    outline = PhoneMicEdge,
    outlineVariant = PhoneMicEdge,
    error = PhoneMicCritical,
    onError = PhoneMicCriticalInk
)

@Composable
fun PhoneMicTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BrandColors,
        typography = Typography,
        shapes = BrandShapes,
        content = content
    )
}
