package com.shegrowsdev.mail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neutral placeholder palette. The company's official brand colours and logo were not supplied;
// replace these values (and res/drawable/ic_launcher_foreground.xml) when they are.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E6E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDED),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    surface = Color(0xFFF8FAFA),
    background = Color(0xFFF8FAFA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD3D3),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F),
    onPrimaryContainer = Color(0xFFCDEDED),
    secondary = Color(0xFFB0CCCB),
    surface = Color(0xFF101414),
    background = Color(0xFF101414),
)

@Composable
fun CompanyMailTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
