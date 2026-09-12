package com.etio.ot.domain

import com.etio.ot.domain.timing.DayFlow
import com.etio.ot.ui.caselist.TimerState
import com.etio.ot.ui.caselist.thresholdForSpan
import com.etio.ot.ui.caselist.timerState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The timer's colour is the first thing anyone reads on the case card, so where it
 * changes matters more than how it looks. Pure arithmetic, tested rather than judged
 * by eye on a device.
 */
class TimerStateTest {

    private fun min(m: Double) = (m * 60_000).toLong()

    @Test
    fun `green until four fifths of the way to a breach`() {
        assertEquals(TimerState.RUNNING, timerState(min(0.0), 30))
        assertEquals(TimerState.RUNNING, timerState(min(23.9), 30))
    }

    @Test
    fun `amber before the breach, not at it`() {
        // The number should have been warning her before the banner appears.
        assertEquals(TimerState.WARNING, timerState(min(24.0), 30))
        assertEquals(TimerState.WARNING, timerState(min(29.9), 30))
    }

    @Test
    fun `red at the breach threshold and past it`() {
        assertEquals(TimerState.DELAY, timerState(min(30.0), 30))
        assertEquals(TimerState.DELAY, timerState(min(90.0), 30))
    }

    @Test
    fun `a span with no threshold never leaves green`() {
        // Procedure time on a case with no planned duration is not a delay.
        assertEquals(TimerState.RUNNING, timerState(min(300.0), 0))
        assertEquals(TimerState.RUNNING, timerState(min(300.0), -1))
    }

    @Test
    fun `the spans use the same thresholds the breach banner uses`() {
        // If these drift apart the card and the banner start disagreeing about what
        // counts as late, which is worse than either being wrong on its own.
        assertEquals(DayFlow.IN_ROOM_TO_KNIFE_BREACH_MIN, thresholdForSpan("In room → knife", 60))
        assertEquals(DayFlow.TURNOVER_BREACH_MIN, thresholdForSpan("Turnover", 60))
    }

    @Test
    fun `procedure is measured against what was planned for this case`() {
        assertEquals(75, thresholdForSpan("Procedure", 75))
    }

    @Test
    fun `an unknown span has no threshold rather than a guessed one`() {
        assertEquals(0, thresholdForSpan("Something else", 60))
    }
}
