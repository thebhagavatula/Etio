package com.etio.ot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * High-contrast, large-type. Read at arm's length, in a corridor, in a hurry.
 * Dark is the default because the projector and the OT both favour it.
 */

val EtioBlue = Color(0xFF2E6BE6)
val EtioAmber = Color(0xFFE8A33D)
val EtioRed = Color(0xFFD1453B)
val EtioGreen = Color(0xFF2E9E5B)
val EtioSlate = Color(0xFF14181F)
val EtioSurface = Color(0xFF1C222C)

private val DarkColors = darkColorScheme(
    primary = EtioBlue,
    onPrimary = Color.White,
    secondary = EtioAmber,
    error = EtioRed,
    background = EtioSlate,
    surface = EtioSurface,
    surfaceVariant = Color(0xFF262E3A),
    onBackground = Color(0xFFECEFF4),
    onSurface = Color(0xFFECEFF4),
    onSurfaceVariant = Color(0xFFA9B4C4),
    outline = Color(0xFF3A4454),
)

private val LightColors = lightColorScheme(
    primary = EtioBlue,
    secondary = EtioAmber,
    error = EtioRed,
)

/** Semantic colours for delay state — used by cards and the report. */
object EtioStatus {
    val onTime = EtioGreen
    val warning = EtioAmber
    val late = EtioRed
    val idle = Color(0xFF6B7685)
}

private val EtioTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 17.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
)

@Composable
fun EtioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = EtioTypography,
        content = content,
    )
}
