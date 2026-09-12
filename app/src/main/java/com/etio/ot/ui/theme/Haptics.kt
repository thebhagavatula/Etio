package com.etio.ot.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Three weights, used consistently, so the phone means something in a pocket or
 * through a glove:
 *
 *  - [tick]     marking an event. The smallest confirmation that lands.
 *  - [medium]   recording started or stopped. You must feel this one without looking.
 *  - [double]   a checklist phase completed. Two beats, because it is a different
 *               kind of event from every other tap in the app.
 *
 * Compose's own HapticFeedbackType only carries two constants, so this goes through
 * the View, where the platform actually has a vocabulary.
 */
class EtioHaptics(private val view: View) {

    fun tick() = view.perform(HapticFeedbackConstants.CLOCK_TICK)

    fun medium() = view.perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    /** Two beats. The platform's own double is API 30+; below that we ask twice. */
    fun double() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.perform(HapticFeedbackConstants.CONFIRM)
            view.postDelayed({ view.perform(HapticFeedbackConstants.CONFIRM) }, DOUBLE_GAP_MS)
        } else {
            view.perform(HapticFeedbackConstants.LONG_PRESS)
            view.postDelayed({ view.perform(HapticFeedbackConstants.CLOCK_TICK) }, DOUBLE_GAP_MS)
        }
    }

    private fun View.perform(constant: Int) {
        performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    private companion object { const val DOUBLE_GAP_MS = 90L }
}

@Composable
fun rememberEtioHaptics(): EtioHaptics {
    val view = LocalView.current
    return remember(view) { EtioHaptics(view) }
}
