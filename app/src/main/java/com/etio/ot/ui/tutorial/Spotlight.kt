package com.etio.ot.ui.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * How the coach marks find the real controls.
 *
 * The tutorial is run over the actual screens, not a mock of them, so each target has
 * to say where it is. A composable opts in with [Modifier.spotlight]; when no registry
 * is installed — which is every run after the first — the modifier does nothing at
 * all beyond reading its own bounds, and no tutorial code is on the normal path.
 */
enum class SpotlightTarget { NEXT_EVENT, MIC, LAST_MARK, RECORD_CONTROL, TRANSCRIPT }

class SpotlightRegistry {
    private val bounds = mutableStateMapOf<SpotlightTarget, Rect>()

    fun report(target: SpotlightTarget, rect: Rect) {
        bounds[target] = rect
    }

    operator fun get(target: SpotlightTarget): Rect? = bounds[target]
}

val LocalSpotlight = compositionLocalOf<SpotlightRegistry?> { null }

/** Reports this composable's bounds to the tutorial, if one is running. */
@Composable
fun Modifier.spotlight(target: SpotlightTarget): Modifier {
    val registry = LocalSpotlight.current ?: return this
    return this.onGloballyPositioned { registry.report(target, it.boundsInRoot()) }
}

/**
 * The dimmed surround, drawn as four rectangles rather than a cleared layer.
 *
 * A blend-mode hole needs its own compositing layer, which is the one thing the
 * blur rules say not to spend GPU on. Four opaque rects around the target achieve
 * the same read and cost nothing.
 */
@Composable
fun SpotlightScrim(
    hole: Rect?,
    scrimColor: Color,
    modifier: Modifier = Modifier,
    padding: Float = 12f,
) {
    if (hole == null) {
        Box(modifier.fillMaxSize().background(scrimColor))
        return
    }
    androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
        val l = (hole.left - padding).coerceAtLeast(0f)
        val t = (hole.top - padding).coerceAtLeast(0f)
        val r = (hole.right + padding).coerceAtMost(size.width)
        val b = (hole.bottom + padding).coerceAtMost(size.height)

        drawRect(scrimColor, Offset(0f, 0f), Size(size.width, t))
        drawRect(scrimColor, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(scrimColor, Offset(0f, t), Size(l, b - t))
        drawRect(scrimColor, Offset(r, t), Size(size.width - r, b - t))
    }
}
