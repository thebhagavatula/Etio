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
    /** The active case only. One step further from the background than [surface]. */
    val surfaceHero: Color,
    /** Chrome only. See [glass] for where this is allowed. */
    val surfaceGlass: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val running: Color,
    val warning: Color,
    val delay: Color,
    /**
     * Chip and banner grounds, opaque and chosen, not a status hue at 16% alpha.
     *
     * Deriving them by alpha was the bug: a hue composited over a warm ground keeps
     * its own temperature, so a cool green went mint on beige and the chip read as
     * borrowed from another app. These are picked against the ground they sit on, and
     * checked for contrast with the hue that sits on THEM — which alpha-derived tints
     * never were.
     */
    val runningTint: Color,
    val warningTint: Color,
    val delayTint: Color,
    val accentTint: Color,
    /** WHO checklist only. If this appears anywhere else, that is a bug. */
    val safety: Color,
    val isDark: Boolean,
)

/**
 * The same warm palette after dark: espresso rather than beige, and the gold rises to
 * carry it. Dark mode is not a different design — a theatre that dims the lights
 * should not get an app that changes its mind about what colour it is.
 */
val DarkEtioColors = EtioColors(
    background = Color(0xFF17120B),
    surface = Color(0xFF211A10),
    surfaceHero = Color(0xFF2B2216),
    surfaceGlass = Color(0xFF2B2216),
    border = Color(0xFFEBC96A).copy(alpha = 0.14f),
    textPrimary = Color(0xFFEBC96A),
    textSecondary = Color(0xFFC0A48C),
    accent = Color(0xFFE5A94A),
    // Warmed toward olive so "on time" belongs to a gold palette instead of reading
    // as a stray mint from the old blue-grey one. Still unmistakably green.
    running = Color(0xFF8FCE6A),
    warning = Color(0xFFF0B44A),
    delay = Color(0xFFE8796F),
    runningTint = Color(0xFF2A3119),
    warningTint = Color(0xFF352815),
    delayTint = Color(0xFF35201C),
    accentTint = Color(0xFF332714),
    safety = Color(0xFFC9A6F0),
    isDark = true,
)

/**
 * Beige ground, gold headings, light brown beneath them.
 *
 * The gold is the part worth explaining. Metallic gold (#D4AF37) measures 1.83:1 on
 * this beige and goldenrod 2.84:1 — both are unreadable as text, not marginal. The
 * value here is a deep antique gold that still reads gold against a warm ground and
 * clears 4.5:1 with room to spare. Every role in this palette was measured on all
 * three grounds before it was written down; run tools/check-contrast.py after any
 * edit, because on a warm low-contrast ground the eye is a much worse judge than it
 * is on white.
 *
 * textPrimary is headings and body; textSecondary is the labels, captions and
 * supporting lines under them. Keeping the brown lighter than the gold is what makes
 * the hierarchy read — 6.61:1 against 4.98:1.
 */
val LightEtioColors = EtioColors(
    background = Color(0xFFF5EFE1),
    surface = Color(0xFFFBF7EE),
    // A third step up, so the active case lifts off the beige without a shadow.
    surfaceHero = Color(0xFFFFFDF7),
    surfaceGlass = Color(0xFFFBF7EE),
    border = Color(0xFF6B5000).copy(alpha = 0.16f),
    textPrimary = Color(0xFF6B5000),
    textSecondary = Color(0xFF7D6055),
    accent = Color(0xFF8A5A1B),
    // Warmed toward the ground so the status hues belong to this palette rather than
    // looking borrowed from the old blue one, and re-measured after warming.
    // Olive rather than forest: #2F6B43 sits at hue 150 and stayed cool however it
    // was tinted, which is why the on-time chip read mint against the beige. This is
    // hue 88 — still green, and at home on a warm ground.
    running = Color(0xFF4F6B2F),
    warning = Color(0xFF8A5A00),
    delay = Color(0xFFA33228),
    runningTint = Color(0xFFE4E6D2),
    warningTint = Color(0xFFF3E4C6),
    delayTint = Color(0xFFF1DCD4),
    accentTint = Color(0xFFEFE3CE),
    safety = Color(0xFF6B4A9E),
    isDark = false,
)

val LocalEtioColors = staticCompositionLocalOf { DarkEtioColors }

/** Alpha for a glass surface: lower when a real blur is behind it, higher when not. */
const val GLASS_ALPHA_BLURRED_DARK = 0.72f
const val GLASS_ALPHA_BLURRED_LIGHT = 0.78f
const val GLASS_ALPHA_FLAT = 0.92f

// --- spacing and shape -------------------------------------------------------

/**
 * 4dp base. Everything on the screen is a multiple of it.
 *
 * [within] and [section] exist to stop the layout reading as generated. A single
 * spacing value used everywhere gives every gap the same meaning, so nothing groups
 * and the eye has no structure to follow: 12dp inside a group, 32dp between groups.
 */
object EtioSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val gutter = 20.dp
    val card = 20.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Between items that belong together. */
    val within = 12.dp

    /** Between one group and the next. */
    val section = 32.dp

    /** Internal padding of the active case card — more than any other surface gets. */
    val heroCard = 24.dp
}

object EtioRadius {
    val card = 20.dp
    val sheet = 28.dp
    val pill = 16.dp

    /** The active case. Larger than everything else, on purpose. */
    val hero = 24.dp

    /** Secondary rows, when they need a shape at all. */
    val row = 16.dp
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

    /**
     * The chip ground that belongs with a status hue.
     *
     * Call sites pick a hue for meaning and then need somewhere to put it; without
     * this they reach for `.copy(alpha = ...)`, which is how the temperature drifted
     * in the first place. Anything unrecognised falls back to the plain surface
     * rather than inventing a tint.
     */
    @Composable
    @ReadOnlyComposable
    fun tintFor(status: Color): Color = with(Etio.colors) {
        when (status) {
            running -> runningTint
            warning -> warningTint
            delay -> delayTint
            accent -> accentTint
            else -> surface
        }
    }

    val onTime: Color @Composable @ReadOnlyComposable get() = Etio.colors.running
    val warning: Color @Composable @ReadOnlyComposable get() = Etio.colors.warning
    val late: Color @Composable @ReadOnlyComposable get() = Etio.colors.delay
    val idle: Color @Composable @ReadOnlyComposable get() = Etio.colors.textSecondary
}

private fun darkScheme(c: EtioColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = Color(0xFF201400),
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
    outline = Color(0xFF5A4A33),
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
    surfaceVariant = Color(0xFFF0E9D9),
    onSurfaceVariant = c.textSecondary,
    outline = Color(0xFFCBBB9E),
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
