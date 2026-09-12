package com.etio.ot.domain

import com.etio.ot.core.Clock
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.timing.DayFlow
import com.etio.ot.domain.timing.TimerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the primary button offers, and when the send-for question appears. Both are
 * list order and marked events only — no clinical judgement is encoded here.
 */
class DayFlowTest {

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

    private fun event(id: String, caseId: String, type: EventType, atMin: Int) =
        EventEntity(id = id, caseId = caseId, type = type, timestampMs = t0 + min(atMin))

    private fun metrics(cases: List<CaseEntity>, events: List<EventEntity>) =
        TimerEngine.compute(cases, events, Clock { t0 + min(500) })

    @Test
    fun `next action is the first unmarked event of the first case`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        val action = DayFlow.nextAction(cases, metrics(cases, emptyList()))
        assertEquals("a", action!!.caseId)
        assertEquals(EventType.PATIENT_SENT_FOR, action.event)
    }

    @Test
    fun `room events of a finished case come before the next case is sent for`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        val events = EventType.ordered
            .takeWhile { it != EventType.ROOM_CLEAN_START }
            .mapIndexed { i, type -> event("e$i", "a", type, i * 10) }

        val action = DayFlow.nextAction(cases, metrics(cases, events))
        // Case 1 is complete (PATIENT_OUT marked) and case 2 is active, but the room
        // still has to be cleaned — that is what the button must offer.
        assertEquals("a", action!!.caseId)
        assertEquals(EventType.ROOM_CLEAN_START, action.event)
    }

    @Test
    fun `next action moves to the following case once the first is fully marked`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        val events = EventType.ordered.mapIndexed { i, type -> event("e$i", "a", type, i * 10) }

        val action = DayFlow.nextAction(cases, metrics(cases, events))
        assertEquals("b", action!!.caseId)
        assertEquals(EventType.PATIENT_SENT_FOR, action.event)
    }

    // --- focus case: what the screen shows ---------------------------------------

    @Test
    fun `focus follows the active case during the day`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        val events = listOf(event("e1", "a", EventType.PATIENT_IN_ROOM, 5))
        val m = metrics(cases, events)
        assertEquals(m.activeCaseId, DayFlow.focusCaseId(cases, m))
        assertEquals("a", DayFlow.focusCaseId(cases, m))
    }

    @Test
    fun `focus falls back to the last case once the day is fully marked`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        val events = cases.flatMap { c ->
            EventType.ordered.mapIndexed { i, type -> event("e-${c.id}-$i", c.id, type, i * 10) }
        }
        val m = metrics(cases, events)

        // Nothing is active at end of day - that is by design, and the screen must
        // not go blank because of it.
        assertNull(m.activeCaseId)
        assertEquals("b", DayFlow.focusCaseId(cases, m))
    }

    @Test
    fun `the day is complete only once every case is marked out`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        val partial = listOf(event("e1", "a", EventType.PATIENT_IN_ROOM, 5))
        assertFalse(DayFlow.isDayComplete(cases, metrics(cases, partial)))

        val all = cases.flatMap { c ->
            EventType.ordered.mapIndexed { i, type -> event("e-${c.id}-$i", c.id, type, i * 10) }
        }
        assertTrue(DayFlow.isDayComplete(cases, metrics(cases, all)))
    }

    @Test
    fun `an empty list is not a complete day`() {
        assertFalse(DayFlow.isDayComplete(emptyList(), metrics(emptyList(), emptyList())))
    }

    @Test
    fun `focus is null only when there are no cases`() {
        assertNull(DayFlow.focusCaseId(emptyList(), metrics(emptyList(), emptyList())))
    }

    @Test
    fun `send-for is offered only after room ready and only until it is marked`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        val throughReady = EventType.ordered.mapIndexed { i, type -> event("e$i", "a", type, i * 10) }

        val offer = DayFlow.sendForOffer(cases, metrics(cases, throughReady))
        assertEquals("b", offer!!.caseId)
        assertEquals("1", offer.afterCaseNumber)

        val sentFor = throughReady + event("s1", "b", EventType.PATIENT_SENT_FOR, 200)
        assertNull(DayFlow.sendForOffer(cases, metrics(cases, sentFor)))
    }

    @Test
    fun `no send-for offer before the room is ready`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        val events = listOf(event("e1", "a", EventType.PATIENT_OUT, 70))
        assertNull(DayFlow.sendForOffer(cases, metrics(cases, events)))
    }

    @Test
    fun `no send-for offer after the last case`() {
        val cases = listOf(case("a", "1", 0))
        val events = EventType.ordered.mapIndexed { i, type -> event("e$i", "a", type, i * 10) }
        assertNull(DayFlow.sendForOffer(cases, metrics(cases, events)))
    }
}
