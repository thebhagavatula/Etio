package com.etio.ot.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.etio.ot.R
import com.etio.ot.ui.theme.Etio
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Timing lives in one place so it can be changed without hunting through the
 * composable, and overridden from Settings without a rebuild.
 */
object SplashTiming {

    /**
     * Minimum time the splash is on screen, fade-out included.
     *
     * Roughly two seconds more than the bare system splash this replaces, and those
     * two seconds are not a pause — they are where the model loads, the sessions are
     * primed and the throwaway decode runs. Spending them here is the reason the first
     * real classification is not the slow one.
     */
    const val SPLASH_DURATION_MS = 2800L

    const val LOGO_FADE_MS = 400
    /** The wordmark starts this long after the logo starts, not after it finishes. */
    const val WORDMARK_DELAY_MS = 200L
    const val WORDMARK_FADE_MS = 300

    /**
     * The tagline is a third beat, not a second half of the wordmark.
     *
     * It used to start at 400ms — before the wordmark had finished at 500ms — over the
     * same 300ms. Two things fading through each other read as one thing appearing, so
     * the fade was there and simply could not be seen. Starting after the wordmark
     * settles, and taking longer over it, is what makes it land as its own moment.
     */
    const val TAGLINE_DELAY_MS = 550L
    const val TAGLINE_FADE_MS = 450

    const val FADE_OUT_MS = 300

    /** Everything before the hold. Warm-up is deliberately not started until after it. */
    const val INTRO_MS = TAGLINE_DELAY_MS + TAGLINE_FADE_MS

    /**
     * How long to hold for a warm-up that has not finished. Past this the app opens
     * anyway: a model still loading is a usable app with a banner on the delay screen,
     * and a splash that never ends is not.
     */
    const val WARM_UP_CEILING_MS = 20_000L

    /** How far the tagline travels while it fades up. Small on purpose. */
    val TAGLINE_RISE = 8.dp

    val LOGO_SIZE = 112.dp

    /** Spec'd as half the logo's height, so it is derived rather than typed twice. */
    val LOGO_TO_WORDMARK_GAP = LOGO_SIZE / 2

    /** Clamped so a bad override cannot strand anyone on the splash. */
    fun sanitise(ms: Long?): Long = (ms ?: SPLASH_DURATION_MS).coerceIn(0L, 15_000L)
}

/**
 * The designed splash, in Compose, over the theme the app actually resolved.
 *
 * Sequence: the logo fades up and settles from 0.94, the wordmark follows 200ms
 * behind it, the tagline behind that, then everything holds still, then the group
 * fades out.
 *
 * The hold is the important part, and it is deliberately motionless. [warmUp] does not
 * start until the intro animation has finished, so model loading and screen animation
 * never contend for the GPU — and while it runs there is nothing moving to stutter.
 * There is no spinner: it would advertise a wait rather than cover one, and it is the
 * one element that would still be animating during inference.
 *
 * Tap anywhere to skip. On stage the extra two seconds are optional.
 */
@Composable
fun SplashScreen(
    onDone: () -> Unit,
    durationMs: Long = SplashTiming.SPLASH_DURATION_MS,
    warmUp: suspend () -> Unit = {},
) {
    val finish by rememberUpdatedState(onDone)
    val currentWarmUp by rememberUpdatedState(warmUp)

    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.94f) }
    val wordmarkAlpha = remember { Animatable(0f) }
    val taglineAlpha = remember { Animatable(0f) }
    val groupAlpha = remember { Animatable(1f) }

    var skipped by remember { mutableStateOf(false) }
    var handedOver by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        logoScale.animateTo(1f, tween(SplashTiming.LOGO_FADE_MS, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        logoAlpha.animateTo(1f, tween(SplashTiming.LOGO_FADE_MS, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(SplashTiming.WORDMARK_DELAY_MS)
        wordmarkAlpha.animateTo(1f, tween(SplashTiming.WORDMARK_FADE_MS, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(SplashTiming.TAGLINE_DELAY_MS)
        taglineAlpha.animateTo(1f, tween(SplashTiming.TAGLINE_FADE_MS, easing = FastOutSlowInEasing))
    }

    // Intro, then warm-up in the stillness, then out.
    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()

        delay(SplashTiming.INTRO_MS)

        withTimeoutOrNull(SplashTiming.WARM_UP_CEILING_MS) { currentWarmUp() }

        // Honour the minimum even when the model was already warm, so a rehearsal and
        // a cold start look the same from the front row. If warm-up ran long, this is
        // already negative and we leave immediately.
        val remaining = durationMs - SplashTiming.FADE_OUT_MS - (System.currentTimeMillis() - startedAt)
        if (remaining > 0) delay(remaining)

        if (!skipped) {
            groupAlpha.animateTo(0f, tween(SplashTiming.FADE_OUT_MS, easing = FastOutSlowInEasing))
            if (!handedOver) { handedOver = true; finish() }
        }
    }

    LaunchedEffect(skipped) {
        if (!skipped) return@LaunchedEffect
        // A skip fades rather than cuts — 300ms is imperceptible as a delay and the
        // alternative is a visible seam in front of the room.
        groupAlpha.animateTo(0f, tween(SplashTiming.FADE_OUT_MS, easing = FastOutSlowInEasing))
        if (!handedOver) { handedOver = true; finish() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Etio.colors.background)
            .pointerInput(Unit) { detectTapGestures(onTap = { skipped = true }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(groupAlpha.value),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(SplashTiming.LOGO_SIZE)
                    .alpha(logoAlpha.value)
                    .scale(logoScale.value),
            )

            Spacer(Modifier.height(SplashTiming.LOGO_TO_WORDMARK_GAP))

            Text(
                "Etio",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.02.em,
                ),
                color = Etio.colors.textPrimary,
                modifier = Modifier.alpha(wordmarkAlpha.value),
            )

            Spacer(Modifier.height(Etio.space.s))

            // Rises as it fades, the drift driven by the same value as the alpha so
            // the two cannot drift apart. Pure opacity change is hard to notice on a
            // small line of secondary text; a few dp of travel is what makes the eye
            // register it as arriving rather than as having always been there.
            Text(
                "On-device theatre log",
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
                modifier = Modifier
                    .offset(y = SplashTiming.TAGLINE_RISE * (1f - taglineAlpha.value))
                    .alpha(taglineAlpha.value),
            )
        }
    }
}
