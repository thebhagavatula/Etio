package com.etio.ot.ui

import com.etio.ot.core.Clock
import com.etio.ot.data.FakeStore
import com.etio.ot.data.config.ChecklistConfig
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.config.ChecklistSource
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.ui.checklist.ChecklistGateController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single place the rest of the app touches the WHO checklist. It decides whether
 * a mark may be written, and it must never decide yes on the strength of anything the
 * coordinator did not do herself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistGateControllerTest {

    private val t0 = 1_700_000_000_000L

    private val config = ChecklistSource {
        ChecklistConfig(
            phases = mapOf(
                "SIGN_IN" to listOf(
                    ChecklistItem("si_identity", "Identity", critical = true),
                    ChecklistItem("si_site", "Site", critical = true),
                    ChecklistItem("si_notes", "Notes", critical = false),
                ),
                "TIME_OUT" to listOf(ChecklistItem("to_team", "Team", critical = true)),
                "SIGN_OUT" to listOf(ChecklistItem("so_count", "Count", critical = true)),
            ),
        )
    }

    private class Rig(scope: kotlinx.coroutines.CoroutineScope) {
        val store = FakeStore()
        lateinit var repo: ChecklistRepository
        lateinit var gate: ChecklistGateController
    }

    private fun rig(scope: kotlinx.coroutines.CoroutineScope): Rig {
        val r = Rig(scope)
        r.repo = ChecklistRepository(r.store.checklists, config, Clock { t0 })
        r.gate = ChecklistGateController(scope, r.repo)
        return r
    }

    @Test
    fun `nothing is blocked before a phase comes due`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)
        assertTrue(r.gate.allows("c1", EventType.PATIENT_IN_ROOM, emptySet()))
        assertNull(r.gate.prompt.value)
    }

    @Test
    fun `a due but unanswered phase blocks the next mark and opens itself`() =
        runTest(StandardTestDispatcher()) {
            val r = rig(this)

            val allowed = r.gate.allows(
                "c1",
                EventType.ANAESTHESIA_START,
                setOf(EventType.PATIENT_IN_ROOM),
            )

            assertFalse("the mark must not be written", allowed)
            val prompt = r.gate.prompt.value
            assertNotNull(prompt)
            assertEquals(ChecklistPhase.SIGN_IN, prompt!!.phase)
            assertTrue("the operator is told why", r.gate.message.value!!.isNotBlank())
        }

    @Test
    fun `a blocked prompt knows how many critical items are left`() =
        runTest(StandardTestDispatcher()) {
            val r = rig(this)
            r.gate.allows("c1", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM))

            val prompt = r.gate.prompt.value!!
            assertEquals(2, prompt.totalCritical)
            assertEquals(0, prompt.confirmedCritical)
            assertEquals(2, prompt.remainingCritical)
            assertFalse(prompt.canComplete)
        }

    @Test
    fun `confirming the criticals lets the mark through`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)
        r.repo.toggleItem("c1", ChecklistPhase.SIGN_IN, "si_identity")
        r.repo.toggleItem("c1", ChecklistPhase.SIGN_IN, "si_site")
        r.repo.complete("c1", ChecklistPhase.SIGN_IN)

        assertTrue(
            r.gate.allows("c1", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM)),
        )
    }

    @Test
    fun `marking a trigger event opens that phase`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)

        r.gate.onEventMarked("c1", EventType.PATIENT_IN_ROOM)
        runCurrent()

        assertEquals(ChecklistPhase.SIGN_IN, r.gate.prompt.value?.phase)
    }

    @Test
    fun `marking an event that triggers nothing opens nothing`() =
        runTest(StandardTestDispatcher()) {
            val r = rig(this)

            r.gate.onEventMarked("c1", EventType.ROOM_READY)
            runCurrent()

            assertNull(r.gate.prompt.value)
        }

    @Test
    fun `toggling an item updates the prompt in place`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)
        r.gate.onEventMarked("c1", EventType.PATIENT_IN_ROOM)
        runCurrent()

        r.gate.toggle("si_identity")
        runCurrent()

        assertEquals(1, r.gate.prompt.value!!.confirmedCritical)
        assertEquals(1, r.gate.prompt.value!!.remainingCritical)
    }

    @Test
    fun `a phase cannot be completed until every critical item is confirmed`() =
        runTest(StandardTestDispatcher()) {
            val r = rig(this)
            r.gate.onEventMarked("c1", EventType.PATIENT_IN_ROOM)
            runCurrent()

            r.gate.complete()
            runCurrent()

            assertNull(
                "an incomplete phase must not record a completion time",
                r.repo.get("c1", ChecklistPhase.SIGN_IN)?.completedAtMs,
            )
        }

    @Test
    fun `completing a satisfied phase closes the prompt`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)
        r.gate.onEventMarked("c1", EventType.PATIENT_IN_ROOM)
        runCurrent()
        r.gate.toggle("si_identity")
        r.gate.toggle("si_site")
        runCurrent()

        r.gate.complete()
        runCurrent()

        assertNotNull(r.repo.get("c1", ChecklistPhase.SIGN_IN)!!.completedAtMs)
        assertNull("the dialog gets out of the way once it is answered", r.gate.prompt.value)
    }

    @Test
    fun `skipping records the reason and closes the prompt`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)
        r.gate.onEventMarked("c1", EventType.PATIENT_IN_ROOM)
        runCurrent()

        r.gate.skip("emergency laparotomy")
        runCurrent()

        val run = r.repo.get("c1", ChecklistPhase.SIGN_IN)!!
        assertTrue(run.skipped)
        assertEquals("emergency laparotomy", run.skipReason)
        assertNull(r.gate.prompt.value)
    }

    @Test
    fun `a skip with no reason given is not a skip`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)
        r.gate.onEventMarked("c1", EventType.PATIENT_IN_ROOM)
        runCurrent()

        r.gate.skip("   ")
        runCurrent()

        val run = r.repo.get("c1", ChecklistPhase.SIGN_IN)
        val skippedWithReason = run?.skipped == true && !run.skipReason.isNullOrBlank()
        assertFalse("a blank reason must not satisfy the phase", skippedWithReason)
        assertFalse(
            "and it must still block the next mark",
            r.gate.allows("c1", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM)),
        )
    }

    @Test
    fun `each case answers for its own checklist`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)
        r.repo.toggleItem("c1", ChecklistPhase.SIGN_IN, "si_identity")
        r.repo.toggleItem("c1", ChecklistPhase.SIGN_IN, "si_site")
        r.repo.complete("c1", ChecklistPhase.SIGN_IN)

        assertTrue(r.gate.allows("c1", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM)))
        assertFalse(r.gate.allows("c2", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM)))
    }

    @Test
    fun `the message is consumed once so it cannot repeat`() = runTest(StandardTestDispatcher()) {
        val r = rig(this)
        r.gate.allows("c1", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM))
        assertNotNull(r.gate.message.value)

        r.gate.consumeMessage()

        assertNull(r.gate.message.value)
    }
}
