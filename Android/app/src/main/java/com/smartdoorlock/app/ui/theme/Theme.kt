package com.smartdoorlock.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF1F3A5F)
private val Teal = Color(0xFF2BB3A3)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F7),
    onPrimaryContainer = Color(0xFF0B1D33),
    secondary = Teal,
    onSecondary = Color.White,
    background = Color(0xFFF6F8FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF5),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7F0),
    onPrimary = Color(0xFF0B1D33),
    primaryContainer = Color(0xFF29486F),
    onPrimaryContainer = Color(0xFFD6E3F7),
    secondary = Color(0xFF6FD9CB),
    background = Color(0xFF0F1419),
    surface = Color(0xFF161C23),
    surfaceVariant = Color(0xFF222B35),
    error = Color(0xFFF2B8B5),
)

/** Colours for status chips, readable in both themes. */
object StatusColors {
    val Good = Color(0xFF2E7D32)
    val Warn = Color(0xFFB26A00)
    val Bad = Color(0xFFC62828)
    val Neutral = Color(0xFF5F6B7A)
    val Busy = Color(0xFF1565C0)
}

@Composable
fun SmartDoorLockTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
