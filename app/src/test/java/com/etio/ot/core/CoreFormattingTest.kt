package com.etio.ot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helpers every screen formats its numbers through. Small, and on the projector
 * in front of everyone — a rounding rule that surprises someone at the podium is a
 * worse bug than it looks.
 */
class CoreFormattingTest {

    @Test
    fun `minutes round to nearest, not down`() {
        assertEquals(0, 0L.msToMinutes())
        assertEquals(0, 29_000L.msToMinutes())
        assertEquals(1, 30_000L.msToMinutes())
        assertEquals(1, 89_000L.msToMinutes())
        assertEquals(2, 90_000L.msToMinutes())
        assertEquals(40, (40 * 60_000L).msToMinutes())
    }

    @Test
    fun `a negative span reads as zero rather than a negative duration`() {
        assertEquals(0, (-5_000L).msToMinutes())
        assertEquals("0:00", (-5_000L).formatMmSs())
    }

    @Test
    fun `mm ss pads the seconds and never the minutes`() {
        assertEquals("0:00", 0L.formatMmSs())
        assertEquals("0:09", 9_000L.formatMmSs())
        assertEquals("1:00", 60_000L.formatMmSs())
        assertEquals("12:34", (12 * 60_000L + 34_000L).formatMmSs())
        assertEquals("100:00", (100 * 60_000L).formatMmSs())
    }

    @Test
    fun `signed minutes always carry their sign`() {
        assertEquals("+0m", 0.formatSignedMinutes())
        assertEquals("+18m", 18.formatSignedMinutes())
        assertEquals("-7m", (-7).formatSignedMinutes())
    }

    @Test
    fun `ids are unique across a large batch`() {
        val ids = List(5_000) { newId() }
        assertEquals(5_000, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `the system clock moves forward`() {
        val first = Clock.System.nowMs()
        assertTrue(first > 0)
        assertTrue(Clock.System.nowMs() >= first)
    }

    @Test
    fun `an injected clock is honoured exactly`() {
        val fixed = Clock { 1_234L }
        assertEquals(1_234L, fixed.nowMs())
    }
}
