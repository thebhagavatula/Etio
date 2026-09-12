package com.etio.ot.domain

import com.etio.ot.ui.splash.SplashTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splash is the one screen with a hard deadline in front of an audience, and the
 * one whose override is typed in a hurry. Both ends of that are worth pinning.
 */
class SplashTimingTest {

    @Test
    fun `no override falls back to the built-in duration`() {
        assertEquals(SplashTiming.SPLASH_DURATION_MS, SplashTiming.sanitise(null))
    }

    @Test
    fun `an override is honoured`() {
        assertEquals(1500L, SplashTiming.sanitise(1500L))
    }

    @Test
    fun `a nonsense override cannot strand anyone on the splash`() {
        // The override is set from a debug screen with fat fingers near a stage.
        assertEquals(0L, SplashTiming.sanitise(-9000L))
        assertTrue(SplashTiming.sanitise(Long.MAX_VALUE) <= 15_000L)
    }

    @Test
    fun `zero is allowed, because skipping entirely is a real choice on stage`() {
        assertEquals(0L, SplashTiming.sanitise(0L))
    }

    @Test
    fun `the intro finishes before the default hold ends`() {
        // Warm-up starts when the intro does; if the intro outlasted the whole splash
        // the model would load with animation still running over the top of it.
        assertTrue(
            "intro must leave room for the hold and the fade-out",
            SplashTiming.INTRO_MS + SplashTiming.FADE_OUT_MS < SplashTiming.SPLASH_DURATION_MS,
        )
    }

    @Test
    fun `the wordmark gap is half the logo height`() {
        assertEquals(SplashTiming.LOGO_SIZE / 2, SplashTiming.LOGO_TO_WORDMARK_GAP)
    }

    @Test
    fun `the tagline gets its own beat instead of fading through the wordmark`() {
        val wordmarkEnds = SplashTiming.WORDMARK_DELAY_MS + SplashTiming.WORDMARK_FADE_MS
        // It used to start at 400ms while the wordmark ran to 500ms. Two things fading
        // through each other read as one thing appearing, so the fade was present and
        // simply invisible. Starting after the wordmark settles is the whole fix.
        assertTrue(
            "tagline starts at ${SplashTiming.TAGLINE_DELAY_MS}ms, before the wordmark " +
                "finishes at ${wordmarkEnds}ms — they will read as one element",
            SplashTiming.TAGLINE_DELAY_MS >= wordmarkEnds,
        )
    }

    @Test
    fun `the tagline fade is slow enough to notice`() {
        // A short fade on a small line of secondary text is indistinguishable from a
        // cut. This is the number that makes it perceptible.
        assertTrue(
            "tagline fade is only ${SplashTiming.TAGLINE_FADE_MS}ms",
            SplashTiming.TAGLINE_FADE_MS >= 400,
        )
    }

    @Test
    fun `the intro accounts for the tagline, so warm-up never starts under it`() {
        // INTRO_MS gates when model loading begins. If it ended before the tagline
        // did, inference would start while something was still animating over it.
        assertTrue(
            SplashTiming.INTRO_MS >= SplashTiming.TAGLINE_DELAY_MS + SplashTiming.TAGLINE_FADE_MS,
        )
    }
}
