package com.etio.ot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * One family, one scale, one set of colours. No dynamic colour: the wallpaper on a
 * loaner phone is not something we control, and a demo that recolours itself on the
 * morning is a demo that fails in a way nobody can debug.
 *
 * Dark is the target — it is what the theatre and the projector both favour, and it
 * is what this is tuned against. Light is functional, not the showpiece.
 */

// --- palette -----------------------------------------------------------------

@Immutable
data class EtioColors(
    val background: Color,
    val surface: Color,
    /** Chrome only. See [glass] for where this is allowed. */
    val surfaceGlass: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val running: Color,
    val warning: Color,
    val delay: Color,
    /** WHO checklist only. If this appears anywhere else, that is a bug. */
    val safety: Color,
    val isDark: Boolean,
)

val DarkEtioColors = EtioColors(
    background = Color(0xFF0B0F14),
    surface = Color(0xFF141A21),
    surfaceGlass = Color(0xFF1C242E),
    border = Color(0xFFFFFFFF).copy(alpha = 0.08f),
    textPrimary = Color(0xFFF2F5F8),
    textSecondary = Color(0xFF8E9BA8),
    accent = Color(0xFF4DA3FF),
    running = Color(0xFF3DDC97),
    warning = Color(0xFFFFB84D),
    delay = Color(0xFFFF6B6B),
    safety = Color(0xFFB794F6),
    isDark = true,
)

/**
 * Same roles, inverted ground. The four status hues are darkened ~15% so they clear
 * 4.5:1 against white — the dark values are tuned for a near-black background and
 * are too light to read on one.
 */
val LightEtioColors = EtioColors(
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF),
    surfaceGlass = Color(0xFFFFFFFF),
    border = Color(0xFF0B0F14).copy(alpha = 0.10f),
    textPrimary = Color(0xFF0B0F14),
    textSecondary = Color(0xFF5A6875),
    accent = Color(0xFF0B62C4),
    // Darker than a straight 15% step: the obvious value measured 4.48:1 on light
    // glass, so it was taken down until it cleared 4.5 with room to spare (5.16:1).
    running = Color(0xFF0F7C52),
    warning = Color(0xFF8A5A00),
    delay = Color(0xFFC02B2B),
    safety = Color(0xFF6B3FB5),
    isDark = false,
)

val LocalEtioColors = staticCompositionLocalOf { DarkEtioColors }

/** Alpha for a glass surface: lower when a real blur is behind it, higher when not. */
const val GLASS_ALPHA_BLURRED_DARK = 0.72f
const val GLASS_ALPHA_BLURRED_LIGHT = 0.78f
const val GLASS_ALPHA_FLAT = 0.92f

// --- spacing and shape -------------------------------------------------------

/** 4dp base. Everything on the screen is a multiple of it. */
object EtioSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val gutter = 20.dp
    val card = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object EtioRadius {
    val card = 20.dp
    val sheet = 28.dp
    val pill = 16.dp
}

/** Standard 180ms, sheets 240ms, both FastOutSlowIn. Timers never use either. */
object EtioMotion {
    const val STANDARD_MS = 180
    const val SHEET_MS = 240
}

// --- type --------------------------------------------------------------------

/**
 * Tabular figures, on everything.
 *
 * A running clock in proportional digits changes width as it ticks, and the whole
 * line shuffles sideways. On a projector that reads as an unfinished app, so "tnum"
 * is set on every style here rather than remembered at each call site.
 */
private const val TNUM = "tnum"

private val Family = FontFamily.Default

private val EtioTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        fontFeatureSettings = TNUM,
    ),
    headlineMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        fontFeatureSettings = TNUM,
    ),
    titleLarge = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        fontFeatureSettings = TNUM,
    ),
    titleMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        fontFeatureSettings = TNUM,
    ),
    bodyLarge = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        fontFeatureSettings = TNUM,
    ),
    bodyMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        fontFeatureSettings = TNUM,
    ),
    labelLarge = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.08.em,
        fontFeatureSettings = TNUM,
    ),
    labelMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.08.em,
        fontFeatureSettings = TNUM,
    ),
    labelSmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.08.em,
        fontFeatureSettings = TNUM,
    ),
)

/** The transcript, and nothing else. Monospace is the signal that these are the exact words. */
val EtioMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    fontFeatureSettings = TNUM,
)

/**
 * Kept because every style above already carries tnum — call sites that were written
 * before this file can keep asking for it without the request meaning anything new.
 */
fun TextStyle.tabular(): TextStyle =
    if (fontFeatureSettings == TNUM) this else copy(fontFeatureSettings = TNUM)

// --- entry point -------------------------------------------------------------

/** Token access: `Etio.colors.accent`, `Etio.space.gutter`. */
object Etio {
    val colors: EtioColors
        @Composable @ReadOnlyComposable get() = LocalEtioColors.current
    val space = EtioSpace
    val radius = EtioRadius
    val motion = EtioMotion
}

/**
 * Semantic status colours, resolved from the active scheme.
 *
 * Named for what the number means, not what colour it is, so the report and the case
 * card cannot drift apart.
 */
object EtioStatus {
    val onTime: Color @Composable @ReadOnlyComposable get() = Etio.colors.running
    val warning: Color @Composable @ReadOnlyComposable get() = Etio.colors.warning
    val late: Color @Composable @ReadOnlyComposable get() = Etio.colors.delay
    val idle: Color @Composable @ReadOnlyComposable get() = Etio.colors.textSecondary
}

private fun darkScheme(c: EtioColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = Color(0xFF04121F),
    secondary = c.warning,
    onSecondary = Color(0xFF1A1200),
    tertiary = c.safety,
    error = c.delay,
    onError = Color(0xFF2A0A0A),
    background = c.background,
    onBackground = c.textPrimary,
    surface = c.surface,
    onSurface = c.textPrimary,
    surfaceVariant = c.surfaceGlass,
    onSurfaceVariant = c.textSecondary,
    outline = Color(0xFF3A4454),
)

private fun lightScheme(c: EtioColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = Color(0xFFFFFFFF),
    secondary = c.warning,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = c.safety,
    error = c.delay,
    onError = Color(0xFFFFFFFF),
    background = c.background,
    onBackground = c.textPrimary,
    surface = c.surface,
    onSurface = c.textPrimary,
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = c.textSecondary,
    outline = Color(0xFFC4CDD6),
)

@Composable
fun EtioTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkEtioColors else LightEtioColors
    CompositionLocalProvider(LocalEtioColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(colors) else lightScheme(colors),
            typography = EtioTypography,
            content = content,
        )
    }
}
