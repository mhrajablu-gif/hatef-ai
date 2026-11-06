package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = DarkBackground,
    primaryContainer = TealSecondary,
    onPrimaryContainer = MintLight,
    secondary = TealAccent,
    onSecondary = Color.White,
    background = DarkBackground,
    onBackground = Color(0xFFE0F2F1),
    surface = DarkSurface,
    onSurface = Color(0xFFE0F2F1),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB2DFDB),
    error = CoralUrgent
)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = TealSecondary,
    secondary = TealAccent,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF003830),
    surface = LightSurface,
    onSurface = Color(0xFF003830),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF004D40),
    error = CoralUrgent
)

@Composable
fun HatefAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography
    ) {
        // Enforce RTL layout direction for authentic Persian UI
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
