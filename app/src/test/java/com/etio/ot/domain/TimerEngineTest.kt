package com.etio.ot.domain

import com.etio.ot.core.Clock
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.timing.TimerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timers are the spine. If these pass, the app is demoable with the model
 * switched off entirely.
 */
class TimerEngineTest {

    private val t0 = 1_700_000_000_000L
    private fun min(n: Int) = n * 60_000L

    private fun case(id: String, number: String, order: Int, startOffsetMin: Int, durationMin: Int) =
        CaseEntity(
            id = id,
            caseNumber = number,
            theatreId = "OT-2",
            procedureName = "Test",
            surgeon = "Dr. Test",
            scheduledStartMs = t0 + min(startOffsetMin),
            scheduledDurationMin = durationMin,
            orderIndex = order,
        )

    private fun event(id: String, caseId: String, type: EventType, atMin: Int) =
        EventEntity(id = id, caseId = caseId, type = type, timestampMs = t0 + min(atMin))

    @Test
    fun `turnover spans previous patient out to next patient in`() {
        val cases = listOf(case("a", "1", 0, 0, 60), case("b", "2", 1, 90, 60))
        val events = listOf(
            event("e1", "a", EventType.PATIENT_OUT, 70),
            event("e2", "b", EventType.PATIENT_IN_ROOM, 95),
        )
        val metrics = TimerEngine.compute(cases, events, Clock { t0 + min(200) })
        assertEquals(min(25), metrics.forCase("b")!!.turnoverMs)
    }

    @Test
    fun `open span measures to now instead of returning null`() {
        val cases = listOf(case("a", "1", 0, 0, 60))
        val events = listOf(event("e1", "a", EventType.KNIFE_TO_SKIN, 10))
        val metrics = TimerEngine.compute(cases, events, Clock { t0 + min(40) })
        assertEquals(min(30), metrics.forCase("a")!!.procedureMs)
    }

    @Test
    fun `first case start delay is knife minus scheduled start`() {
        val cases = listOf(case("a", "1", 0, 0, 60))
        val events = listOf(event("e1", "a", EventType.KNIFE_TO_SKIN, 22))
        val metrics = TimerEngine.compute(cases, events, Clock { t0 + min(30) })
        assertEquals(22, metrics.firstCaseStartDelayMin)
    }

    @Test
    fun `superseded events are ignored`() {
        val cases = listOf(case("a", "1", 0, 0, 60))
        val original = event("e1", "a", EventType.KNIFE_TO_SKIN, 10).copy(supersededByEventId = "e2")
        val corrected = event("e2", "a", EventType.KNIFE_TO_SKIN, 15).copy(correctedFromEventId = "e1")
        val metrics = TimerEngine.compute(cases, listOf(original, corrected), Clock { t0 + min(30) })
        assertEquals(15, metrics.firstCaseStartDelayMin)
    }

    @Test
    fun `unmarked case has no spans and is not running`() {
        val cases = listOf(case("a", "1", 0, 0, 60))
        val metrics = TimerEngine.compute(cases, emptyList(), Clock { t0 })
        val cm = metrics.forCase("a")!!
        assertNull(cm.procedureMs)
        assertNull(cm.startVarianceMin)
        assertTrue(!cm.isRunning && !cm.isComplete)
    }

    @Test
    fun `next expected event follows the clinical order`() {
        assertEquals(EventType.PATIENT_SENT_FOR, TimerEngine.nextExpectedEvent(emptyMap()))
        assertEquals(
            EventType.ANAESTHESIA_START,
            TimerEngine.nextExpectedEvent(
                mapOf(EventType.PATIENT_SENT_FOR to t0, EventType.PATIENT_IN_ROOM to t0),
            ),
        )
    }
}
