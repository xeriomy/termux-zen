package com.termux.app.plugins.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Zen plugin hub theme: dark-first to match the terminal, with a green accent.
 * Dynamic color is intentionally off so the hub looks the same on every device.
 */
private val ZenDarkColors = darkColorScheme(
    primary = Color(0xFF7BD88F),
    onPrimary = Color(0xFF0A2912),
    primaryContainer = Color(0xFF1D3B26),
    onPrimaryContainer = Color(0xFFB9F0C4),
    secondary = Color(0xFF6BA8FF),
    onSecondary = Color(0xFF0A1B33),
    secondaryContainer = Color(0xFF1C2F4A),
    onSecondaryContainer = Color(0xFFCFE0FF),
    tertiary = Color(0xFFFFD166),
    onTertiary = Color(0xFF33270A),
    background = Color(0xFF0F141A),
    onBackground = Color(0xFFE3E8EE),
    surface = Color(0xFF151B23),
    onSurface = Color(0xFFE3E8EE),
    surfaceVariant = Color(0xFF1E2630),
    onSurfaceVariant = Color(0xFFAEB9C4),
    surfaceContainer = Color(0xFF1A212B),
    surfaceContainerHigh = Color(0xFF212A36),
    surfaceContainerHighest = Color(0xFF283241),
    outline = Color(0xFF3A4552),
    outlineVariant = Color(0xFF2A3340),
    error = Color(0xFFFF8F8F),
    onError = Color(0xFF3D0A0A),
    errorContainer = Color(0xFF4A1D1D),
    onErrorContainer = Color(0xFFFFD5D5)
)

private val ZenShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun ZenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZenDarkColors,
        shapes = ZenShapes,
        content = content
    )
}

/** Parses a #RGB/#RRGGBB/#RRRGGBBB/#RRRRGGGGBBBB color for previews; null when invalid. */
fun parsePreviewColor(value: String): Color? {
    if (!value.startsWith("#")) return null
    val hex = value.substring(1)
    return try {
        when (hex.length) {
            3 -> Color(
                Integer.parseInt(hex[0].toString().repeat(2), 16),
                Integer.parseInt(hex[1].toString().repeat(2), 16),
                Integer.parseInt(hex[2].toString().repeat(2), 16)
            )
            6 -> Color(
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)
            )
            9 -> Color(
                Integer.parseInt(hex.substring(0, 3), 16) / 4095f,
                Integer.parseInt(hex.substring(3, 6), 16) / 4095f,
                Integer.parseInt(hex.substring(6, 9), 16) / 4095f
            )
            12 -> Color(
                Integer.parseInt(hex.substring(0, 4), 16) / 65535f,
                Integer.parseInt(hex.substring(4, 8), 16) / 65535f,
                Integer.parseInt(hex.substring(8, 12), 16) / 65535f
            )
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
}
