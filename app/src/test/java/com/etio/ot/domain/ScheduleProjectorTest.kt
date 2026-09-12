package com.etio.ot.domain

import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.domain.timing.ScheduleProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The revised times that end up inside the ward, surgeon and anaesthesia messages.
 * If this is wrong, four people are told a wrong time in four different registers.
 */
class ScheduleProjectorTest {

    private val t0 = 1_700_000_000_000L
    private fun min(n: Int) = n * 60_000L

    private fun case(id: String, number: String, order: Int) =
        CaseEntity(
            id = id,
            caseNumber = number,
            theatreId = "OT-2",
            procedureName = "Test",
            surgeon = "Dr. Test",
            scheduledStartMs = t0 + min(order * 90),
            scheduledDurationMin = 60,
            orderIndex = order,
        )

    private val cases = listOf(
        case("a", "1", 0),
        case("b", "2", 1),
        case("c", "3", 2),
        case("d", "4", 3),
    )

    @Test
    fun `downstream cases move by exactly the stated minutes`() {
        val shift = ScheduleProjector.project(cases, "b", estimatedMin = 40, nowMs = t0)!!

        assertEquals(2, shift.downstream.size)
        assertEquals(t0 + min(180) + min(40), shift.downstream[0].revisedStartMs)
        assertEquals(t0 + min(270) + min(40), shift.downstream[1].revisedStartMs)
    }

    @Test
    fun `no stated duration means no shift at all`() {
        assertNull(ScheduleProjector.project(cases, "b", estimatedMin = null, nowMs = t0))
        assertNull(ScheduleProjector.project(cases, "b", estimatedMin = 0, nowMs = t0))
    }

    @Test
    fun `a case already past its slot is measured from now, not from the past`() {
        // Case 2 was due at t0+90min; it is now t0+120min and 40 more are needed.
        val shift = ScheduleProjector.project(cases, "b", estimatedMin = 40, nowMs = t0 + min(120))!!
        assertEquals(t0 + min(120) + min(40), shift.delayed.revisedStartMs)
    }

    @Test
    fun `a case still ahead of its slot is measured from the schedule`() {
        val shift = ScheduleProjector.project(cases, "c", estimatedMin = 15, nowMs = t0)!!
        assertEquals(t0 + min(180) + min(15), shift.delayed.revisedStartMs)
    }

    @Test
    fun `summary names the range of cases that moved`() {
        val shift = ScheduleProjector.project(cases, "b", estimatedMin = 40, nowMs = t0)!!
        assertEquals("Cases 3–4 pushed ~40 min", shift.summary)
    }

    @Test
    fun `summary is singular for one downstream case, and absent for none`() {
        assertEquals(
            "Case 4 pushed ~25 min",
            ScheduleProjector.project(cases, "c", estimatedMin = 25, nowMs = t0)!!.summary,
        )
        val last = ScheduleProjector.project(cases, "d", estimatedMin = 25, nowMs = t0)!!
        assertNull(last.summary)
        assertTrue(last.downstream.isEmpty())
    }

    @Test
    fun `an unknown case id shifts nothing`() {
        assertNull(ScheduleProjector.project(cases, "nope", estimatedMin = 40, nowMs = t0))
    }
}
