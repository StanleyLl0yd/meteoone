package com.sl.meteoone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF035BE1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EAFF),
    onPrimaryContainer = Color(0xFF101828),
    secondary = Color(0xFF10A1F9),
    secondaryContainer = Color(0xFFDDF4FF),
    onSecondaryContainer = Color(0xFF12324A),
    tertiary = Color(0xFF656AF5),
    tertiaryContainer = Color(0xFFE6E4FF),
    onTertiaryContainer = Color(0xFF24245A),
    background = Color(0xFFF7FAFF),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFEEF5FF),
    onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFFD7E1EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF001D36),
    primaryContainer = Color(0xFF123D70),
    onPrimaryContainer = Color(0xFFF5F8FC),
    secondary = Color(0xFF24D0F6),
    secondaryContainer = Color(0xFF123A4A),
    onSecondaryContainer = Color(0xFFDDF8FF),
    tertiary = Color(0xFF9C97F9),
    tertiaryContainer = Color(0xFF2D2C62),
    onTertiaryContainer = Color(0xFFEAE8FF),
    background = Color(0xFF071426),
    onBackground = Color(0xFFF5F8FC),
    surface = Color(0xFF0D2038),
    onSurface = Color(0xFFF5F8FC),
    surfaceVariant = Color(0xFF122B49),
    onSurfaceVariant = Color(0xFFB8C4D4),
    outline = Color(0xFF2A4665),
)

@Composable
fun MeteoOneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
