package com.etio.ot.domain

import com.etio.ot.ui.theme.InferenceSignal
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flag every animation in the app is gated on.
 *
 * The failure that matters is not it being wrong for a frame — it is it getting
 * stuck. A classify that throws without clearing this would leave the heartbeat, the
 * next-event crossfade, the field reveal and the backdrop blur switched off for the
 * rest of the session, with nothing on screen explaining why. That is why the caller
 * clears it in a `finally` and why this is pinned here.
 *
 * The combined [InferenceSignal.active] flow also folds in Job 2, but reading it
 * builds the drafting coordinator and therefore the database, so it belongs in an
 * instrumentation test rather than this one.
 */
class InferenceSignalTest {

    @After
    fun tearDown() = InferenceSignal.classifyingFinished()

    @Test
    fun `starts clear`() {
        InferenceSignal.classifyingFinished()
        assertFalse(InferenceSignal.classifying.value)
    }

    @Test
    fun `set while a classification runs, cleared when it ends`() {
        InferenceSignal.classifyingStarted()
        assertTrue(InferenceSignal.classifying.value)
        InferenceSignal.classifyingFinished()
        assertFalse(InferenceSignal.classifying.value)
    }

    @Test
    fun `a throwing classification still clears it`() {
        // Mirrors the try/finally in DelayCaptureViewModel. Without the finally this
        // is the bug that silently freezes every effect in the app.
        runCatching {
            InferenceSignal.classifyingStarted()
            try {
                error("inference blew up")
            } finally {
                InferenceSignal.classifyingFinished()
            }
        }
        assertFalse("the gate leaked; every animation would stay off", InferenceSignal.classifying.value)
    }
}
