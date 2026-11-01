package com.shegrowsdev.mail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette taken from the She Grows for Development logo: leaf green (primary), sun yellow (accent) and the
// logo's black. The green is darkened a little so white text on it stays readable (WCAG AA).
private val LightColors = lightColorScheme(
    primary = Color(0xFF3E7D1E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EFBE),
    onPrimaryContainer = Color(0xFF0C2200),
    secondary = Color(0xFF5A6B45),
    secondaryContainer = Color(0xFFF6EE9A),
    onSecondaryContainer = Color(0xFF211F00),
    tertiary = Color(0xFF7A6F00),
    surface = Color(0xFFFAFCF6),
    background = Color(0xFFFAFCF6),
    onSurface = Color(0xFF1A1C18),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ED76B),
    onPrimary = Color(0xFF133800),
    primaryContainer = Color(0xFF2B5309),
    onPrimaryContainer = Color(0xFFD7EFBE),
    secondary = Color(0xFFBFCCA8),
    secondaryContainer = Color(0xFF55511A),
    onSecondaryContainer = Color(0xFFF6EE9A),
    tertiary = Color(0xFFE8D945),
    surface = Color(0xFF121510),
    background = Color(0xFF121510),
    onSurface = Color(0xFFE3E4DB),
)

@Composable
fun CompanyMailTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
