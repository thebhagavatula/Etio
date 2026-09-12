package com.etio.ot.ui.caselist

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etio.ot.core.formatMmSs
import com.etio.ot.domain.timing.DayFlow
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.InferenceSignal

/**
 * How a running span is doing against the threshold that would make it worth
 * explaining. Pure, so the thresholds can be tested rather than eyeballed.
 */
enum class TimerState { RUNNING, WARNING, DELAY }

/**
 * Warning at four fifths of the way to a breach, delay at it.
 *
 * The breach thresholds are [DayFlow]'s, so the number on the card turns amber before
 * the banner appears rather than at the same moment — by the time the app is asking
 * her to explain something, the timer should already have been telling her.
 */
fun timerState(elapsedMs: Long, thresholdMin: Int): TimerState {
    if (thresholdMin <= 0) return TimerState.RUNNING
    val minutes = elapsedMs / 60_000.0
    return when {
        minutes >= thresholdMin -> TimerState.DELAY
        minutes >= thresholdMin * 0.8 -> TimerState.WARNING
        else -> TimerState.RUNNING
    }
}

/**
 * The threshold that applies to the span currently running.
 *
 * Procedure time has no breach rule — a long operation is not a delay — so it is
 * measured against what was planned for this case instead, and only once that is
 * known. Zero means "no threshold", and the timer stays green.
 */
fun thresholdForSpan(spanLabel: String, scheduledDurationMin: Int): Int = when (spanLabel) {
    "In room → knife" -> DayFlow.IN_ROOM_TO_KNIFE_BREACH_MIN
    "Turnover" -> DayFlow.TURNOVER_BREACH_MIN
    "Procedure" -> scheduledDurationMin
    else -> 0
}

/**
 * The one genuinely live thing on the screen, treated as the hero.
 *
 * Display size, tabular figures (every style in the theme carries tnum, so the digits
 * do not shuffle as they tick), and the number itself is what changes colour. The
 * label under it stays secondary and the underline stays accent: the colour says
 * something about the span, so it belongs to the number and nowhere else.
 *
 * The dot is a heartbeat rather than an animation — one beat a second, 40% at its
 * dimmest, 2dp. It stops dead while either model job is running: it is the only thing
 * moving near the timer, and the one frame it would drop is the frame someone is
 * watching. The digits keep ticking regardless, because they are text changing once a
 * second, not an animation.
 */
@Composable
fun LiveTimer(
    elapsedMs: Long?,
    label: String,
    thresholdMin: Int,
    modifier: Modifier = Modifier,
) {
    val state = elapsedMs?.let { timerState(it, thresholdMin) }
    val numberColour = when (state) {
        TimerState.RUNNING -> Etio.colors.running
        TimerState.WARNING -> Etio.colors.warning
        TimerState.DELAY -> Etio.colors.delay
        null -> Etio.colors.textSecondary
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                elapsedMs?.formatMmSs() ?: "—",
                style = MaterialTheme.typography.displaySmall,
                color = numberColour,
            )
            if (elapsedMs != null) {
                Spacer(Modifier.width(Etio.space.m))
                Heartbeat(colour = numberColour)
            }
        }

        Spacer(Modifier.height(Etio.space.s))

        // 1px, accent, and the only other coloured thing in this region.
        Spacer(
            Modifier
                .width(TIMER_UNDERLINE_WIDTH)
                .height(1.dp)
                .background(Etio.colors.accent),
        )

        Spacer(Modifier.height(Etio.space.s))

        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Etio.colors.textSecondary,
        )
    }
}

@Composable
private fun Heartbeat(colour: Color) {
    val inferenceActive by InferenceSignal.active.collectAsStateWithLifecycle(initialValue = false)

    val alpha = if (inferenceActive) {
        // Held at its dimmest rather than paused mid-pulse, so stopping reads as
        // stillness instead of as a stutter.
        HEARTBEAT_MIN_ALPHA
    } else {
        val transition = rememberInfiniteTransition(label = "heartbeat")
        val pulsing by transition.animateFloat(
            initialValue = HEARTBEAT_MIN_ALPHA,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(HEARTBEAT_PERIOD_MS / 2, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "heartbeatAlpha",
        )
        pulsing
    }

    Spacer(
        Modifier
            .padding(bottom = Etio.space.s)
            .size(HEARTBEAT_SIZE)
            .alpha(alpha)
            .background(colour, CircleShape),
    )
}

private val TIMER_UNDERLINE_WIDTH = 48.dp
/**
 * Spec'd at 2dp. Beside display-size digits that is close to a speck — if it should
 * read as a presence indicator from a metre away, this is the single number to raise.
 */
private val HEARTBEAT_SIZE = 2.dp
private const val HEARTBEAT_MIN_ALPHA = 0.4f
private const val HEARTBEAT_PERIOD_MS = 1000
