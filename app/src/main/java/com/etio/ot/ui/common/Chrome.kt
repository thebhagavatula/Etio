package com.etio.ot.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.etio.ot.ui.theme.Etio

/**
 * A section heading with no container around it.
 *
 * The label IS the separation — wrapping it in a surface adds a second box to a
 * screen that is trying to have fewer of them, and makes a heading look like content.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = Etio.colors.textSecondary,
        modifier = modifier.fillMaxWidth(),
    )
}

/** The hairline between borderless rows. The only thing separating them. */
@Composable
fun HairlineSeparator(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Etio.colors.border),
    )
}

/**
 * What a screen says when it has nothing to show.
 *
 * One line and one action, never a bare void. A blank screen in a demo reads as a
 * feature that was not finished; the same screen with a sentence reads as a state
 * someone thought about. The line says what is true, not what went wrong.
 */
@Composable
fun EmptyState(
    line: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Etio.space.section, horizontal = Etio.space.gutter),
    ) {
        Text(
            line,
            style = MaterialTheme.typography.bodyLarge,
            color = Etio.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Etio.space.within))
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Fades content in after [delayMs], sliding it up from 12dp.
 *
 * Used to let a set of fields arrive in sequence instead of all at once. A three
 * second inference that ends in an instant full-screen swap reads as a freeze
 * followed by a jump; the same three seconds ending in four fields landing one after
 * another reads as work finishing.
 *
 * [enabled] is the GPU gate. When a model job is running this becomes a plain
 * pass-through — the content is there immediately, unanimated.
 */
@Composable
fun RevealAfter(
    delayMs: Int,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) { content(); return }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        shown = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(REVEAL_FADE_MS),
        label = "revealAlpha",
    )
    val offsetY by animateDpAsState(
        targetValue = if (shown) 0.dp else REVEAL_SLIDE,
        animationSpec = tween(REVEAL_FADE_MS),
        label = "revealOffset",
    )
    Box(Modifier.offset(y = offsetY).alpha(alpha)) { content() }
}

private val REVEAL_SLIDE = 12.dp
private const val REVEAL_FADE_MS = 180

/**
 * A blocked state that is worth designing rather than flashing past.
 *
 * Model missing, microphone refused, recogniser absent: each is a thing someone can
 * act on, and each used to be a toast — which is gone before the sentence is read and
 * leaves the screen looking broken rather than blocked. Amber, because none of these
 * stop the day being logged; they stop one convenience.
 */
@Composable
fun BlockedState(
    headline: String,
    line: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Etio.colors.warningTint, RoundedCornerShape(Etio.radius.row))
            .padding(Etio.space.l),
    ) {
        Text(
            headline,
            style = MaterialTheme.typography.titleMedium,
            color = Etio.colors.warning,
        )
        Spacer(Modifier.height(Etio.space.xs))
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = Etio.colors.textSecondary,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Etio.space.s))
            TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
