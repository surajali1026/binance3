package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val DarkColorScheme = darkColorScheme(
    primary = AmberGold,
    onPrimary = Color(0xFF0B0E11),
    primaryContainer = AmberContainer,
    onPrimaryContainer = AmberGold,
    secondary = CyanInfo,
    onSecondary = Color(0xFF0B0E11),
    secondaryContainer = ToolbarSurface,
    onSecondaryContainer = TextPrimary,
    tertiary = BullishGreen,
    onTertiary = Color(0xFF0B0E11),
    error = BearishRed,
    onError = Color.White,
    background = CanvasVoid,
    onBackground = TextPrimary,
    surface = ElevatedSurface,
    onSurface = TextPrimary,
    surfaceVariant = ToolbarSurface,
    onSurfaceVariant = TextMuted,
    outline = BorderSubtle,
    outlineVariant = GridHairline
)

private val LightColorScheme = lightColorScheme(
    primary = AmberGold,
    onPrimary = Color(0xFF0B0E11),
    secondary = CyanInfo,
    onSecondary = Color.White,
    tertiary = BullishGreen,
    error = BearishRed,
    background = LightCanvas,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextMuted,
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
)

private val TerminalShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun BinanceProTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = TerminalShapes,
        content = content
    )
}
