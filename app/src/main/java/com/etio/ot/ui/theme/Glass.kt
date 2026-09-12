package com.etio.ot.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etio.ot.di.AiModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Glass is an accent on chrome, not a theme.
 *
 * ALLOWED: top app bar, the bottom action bar, bottom sheets, the delay-capture
 * overlay. FORBIDDEN anywhere a timer, the WHO checklist, the report, or a generated
 * message lives — those are read, not decorated, and a translucent ground under a
 * number costs contrast for nothing.
 */

/**
 * Inference in flight, from the two places that already know.
 *
 * Job 1 raises [classifying] from the capture ViewModel; Job 2 is visible in the
 * draft coordinator's pending set. Neither is new state and neither touches the
 * inference path — the point is only that the GPU is not asked to blur a backdrop
 * while it is decoding tokens.
 */
object InferenceSignal {

    private val _classifying = MutableStateFlow(false)
    val classifying: StateFlow<Boolean> = _classifying.asStateFlow()

    fun classifyingStarted() { _classifying.value = true }
    fun classifyingFinished() { _classifying.value = false }

    /** True while either job is running. */
    val active: kotlinx.coroutines.flow.Flow<Boolean> by lazy {
        combine(
            _classifying,
            AiModule.draftCoordinator.pending.map { it.isNotEmpty() },
        ) { job1, job2 -> job1 || job2 }
    }
}

/**
 * Whether a real blur may run right now.
 *
 * Two gates, both hard: the effect exists only on API 31+, and it is switched off
 * for as long as either model job is running. A blur that competes with inference
 * for the GPU shows up as a stutter in exactly the second someone is watching.
 */
@Composable
fun rememberBlurAllowed(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val inferenceActive by InferenceSignal.active.collectAsStateWithLifecycle(initialValue = false)
    return !inferenceActive
}

/** Max 24dp, and only where [rememberBlurAllowed] says so. */
val GLASS_BLUR_RADIUS: Dp = 24.dp

/**
 * Blurs the content BEHIND a glass surface — the only blur in the app, applied to a
 * composable we own rather than pretending Compose can sample the window backdrop.
 * A no-op below API 31 or while inference is running, so callers need no branch.
 */
@Composable
fun Modifier.backdropBlur(active: Boolean, radius: Dp = GLASS_BLUR_RADIUS): Modifier =
    if (active && rememberBlurAllowed()) blur(radius) else this

/**
 * The recipe, in one place: translucent ground, a hairline border, and a soft
 * highlight along the top edge where the light would catch a real pane.
 *
 * When no blur is running underneath — below API 31, or during inference — the
 * ground steps up to [GLASS_ALPHA_FLAT] so text keeps its contrast rather than
 * sitting on whatever happens to scroll past. Contrast wins over the effect.
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(EtioRadius.card),
    blurring: Boolean = rememberBlurAllowed(),
): Modifier {
    val colors = Etio.colors
    val alpha = when {
        !blurring -> GLASS_ALPHA_FLAT
        colors.isDark -> GLASS_ALPHA_BLURRED_DARK
        else -> GLASS_ALPHA_BLURRED_LIGHT
    }
    return this
        .clip(shape)
        .background(colors.surfaceGlass.copy(alpha = alpha), shape)
        .background(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.35f),
                0.35f to Color.Transparent,
            ),
            shape = shape,
        )
        .border(1.dp, colors.border, shape)
}
