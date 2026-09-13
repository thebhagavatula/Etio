package com.etio.ot.data

import com.etio.ot.data.model.Audience
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closed sets the model is held to, and the ordering the whole day depends on.
 */
class EnumsTest {

    @Test
    fun `the model's code is matched case-insensitively and trimmed`() {
        assertEquals(DelayCode.SURGEON_LATE, DelayCode.fromModelOutput("SURGEON_LATE"))
        assertEquals(DelayCode.SURGEON_LATE, DelayCode.fromModelOutput("surgeon_late"))
        assertEquals(DelayCode.SURGEON_LATE, DelayCode.fromModelOutput("  Surgeon_Late  "))
    }

    @Test
    fun `anything outside the set becomes OTHER rather than throwing`() {
        assertEquals(DelayCode.OTHER, DelayCode.fromModelOutput("SURGEON_IS_LATE"))
        assertEquals(DelayCode.OTHER, DelayCode.fromModelOutput(""))
        assertEquals(DelayCode.OTHER, DelayCode.fromModelOutput(null))
        assertEquals(DelayCode.OTHER, DelayCode.fromModelOutput("¯\\_(ツ)_/¯"))
    }

    @Test
    fun `avoidability accepts booleans and their string forms, and nothing else`() {
        assertEquals(Avoidability.AVOIDABLE, Avoidability.fromModelOutput(true))
        assertEquals(Avoidability.UNAVOIDABLE, Avoidability.fromModelOutput(false))
        assertEquals(Avoidability.AVOIDABLE, Avoidability.fromModelOutput("true"))
        assertEquals(Avoidability.AVOIDABLE, Avoidability.fromModelOutput("TRUE"))
        assertEquals(Avoidability.UNAVOIDABLE, Avoidability.fromModelOutput("false"))
    }

    @Test
    fun `an unclear or absent avoidability stays unclear`() {
        assertEquals(Avoidability.UNCLEAR, Avoidability.fromModelOutput(null))
        assertEquals(Avoidability.UNCLEAR, Avoidability.fromModelOutput("maybe"))
        assertEquals(Avoidability.UNCLEAR, Avoidability.fromModelOutput(0))
    }

    @Test
    fun `event ordinal order is the clinical order the whole app assumes`() {
        assertEquals(
            listOf(
                EventType.PATIENT_SENT_FOR,
                EventType.PATIENT_IN_ROOM,
                EventType.ANAESTHESIA_START,
                EventType.KNIFE_TO_SKIN,
                EventType.CLOSURE_COMPLETE,
                EventType.PATIENT_OUT,
                EventType.ROOM_CLEAN_START,
                EventType.ROOM_READY,
            ),
            EventType.ordered,
        )
    }

    @Test
    fun `every event type carries a label and a short label`() {
        EventType.entries.forEach {
            assertTrue(it.name, it.label.isNotBlank())
            assertTrue(it.name, it.shortLabel.isNotBlank())
        }
    }

    @Test
    fun `each checklist phase is triggered by exactly one event`() {
        assertEquals(ChecklistPhase.SIGN_IN, ChecklistPhase.forEvent(EventType.PATIENT_IN_ROOM))
        assertEquals(ChecklistPhase.TIME_OUT, ChecklistPhase.forEvent(EventType.ANAESTHESIA_START))
        assertEquals(ChecklistPhase.SIGN_OUT, ChecklistPhase.forEvent(EventType.CLOSURE_COMPLETE))
        assertEquals(3, ChecklistPhase.entries.map { it.triggerEvent }.distinct().size)
    }

    @Test
    fun `events that trigger nothing return null`() {
        assertNull(ChecklistPhase.forEvent(EventType.PATIENT_SENT_FOR))
        assertNull(ChecklistPhase.forEvent(EventType.ROOM_READY))
        assertNull(ChecklistPhase.forEvent(EventType.PATIENT_OUT))
    }

    @Test
    fun `the demo order puts surgeon and family next to each other`() {
        val order = Audience.demoOrder
        assertEquals(Audience.entries.size, order.size)
        assertEquals(Audience.entries.toSet(), order.toSet())
        val surgeon = order.indexOf(Audience.SURGEON)
        val family = order.indexOf(Audience.FAMILY)
        assertEquals("surgeon and family must be adjacent", 1, kotlin.math.abs(surgeon - family))
    }

    @Test
    fun `every delay code has a display name and the taxonomy is eleven values`() {
        assertEquals(11, DelayCode.entries.size)
        DelayCode.entries.forEach { assertTrue(it.name, it.display.isNotBlank()) }
        assertNotNull(DelayCode.entries.firstOrNull { it == DelayCode.OTHER })
    }
}
