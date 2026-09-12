package com.etio.ot.domain

import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.checklist.ChecklistStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecklistStateMachineTest {

    private val signInItems = listOf(
        ChecklistItem("a", "Identity confirmed", critical = true),
        ChecklistItem("b", "Site marked", critical = true),
        ChecklistItem("c", "Pulse oximeter on", critical = false),
    )
    private val itemsByPhase = mapOf(
        ChecklistPhase.SIGN_IN to signInItems,
        ChecklistPhase.TIME_OUT to emptyList(),
        ChecklistPhase.SIGN_OUT to emptyList(),
    )

    @Test
    fun `unsatisfied sign in blocks a later event`() {
        val blocking = ChecklistStateMachine.gate(
            next = EventType.KNIFE_TO_SKIN,
            markedEvents = setOf(EventType.PATIENT_IN_ROOM),
            runs = mapOf(ChecklistPhase.SIGN_IN to null),
            itemsByPhase = itemsByPhase,
        )
        assertEquals(ChecklistPhase.SIGN_IN, blocking)
    }

    @Test
    fun `all critical items confirmed clears the gate`() {
        val run = ChecklistRunEntity(
            id = "r", caseId = "c", phase = ChecklistPhase.SIGN_IN,
            itemsConfirmed = listOf("a", "b"), completedAtMs = 1L,
        )
        val blocking = ChecklistStateMachine.gate(
            next = EventType.KNIFE_TO_SKIN,
            markedEvents = setOf(EventType.PATIENT_IN_ROOM),
            runs = mapOf(ChecklistPhase.SIGN_IN to run),
            itemsByPhase = itemsByPhase,
        )
        assertNull(blocking)
    }

    @Test
    fun `skip with a reason clears the gate, skip without one does not`() {
        val withReason = ChecklistRunEntity(
            id = "r", caseId = "c", phase = ChecklistPhase.SIGN_IN,
            skipped = true, skipReason = "Emergency case", completedAtMs = 1L,
        )
        val withoutReason = withReason.copy(skipReason = null)
        assertEquals(true, ChecklistStateMachine.isSatisfied(withReason, signInItems))
        assertEquals(false, ChecklistStateMachine.isSatisfied(withoutReason, signInItems))
    }

    @Test
    fun `a phase whose trigger has not been marked never blocks`() {
        val blocking = ChecklistStateMachine.gate(
            next = EventType.PATIENT_IN_ROOM,
            markedEvents = setOf(EventType.PATIENT_SENT_FOR),
            runs = emptyMap(),
            itemsByPhase = itemsByPhase,
        )
        assertNull(blocking)
    }

    @Test
    fun `progress counts only critical items`() {
        val run = ChecklistRunEntity(
            id = "r", caseId = "c", phase = ChecklistPhase.SIGN_IN,
            itemsConfirmed = listOf("a", "c"),
        )
        assertEquals(1 to 2, ChecklistStateMachine.progress(run, signInItems))
    }
}
