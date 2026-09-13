package com.etio.ot.data

import com.etio.ot.core.Clock
import com.etio.ot.data.config.ChecklistConfig
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.config.ChecklistSource
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.ChecklistRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety path. Nothing here may complete a phase the coordinator did not, and
 * skipping has to stay possible but never silent.
 */
class ChecklistRepositoryTest {

    private val t0 = 1_700_000_000_000L

    private val config = ChecklistSource {
        ChecklistConfig(
            phases = mapOf(
                "SIGN_IN" to listOf(
                    ChecklistItem("si_identity", "Patient identity confirmed", critical = true),
                    ChecklistItem("si_site", "Site marked", critical = true),
                    ChecklistItem("si_allergy", "Known allergies", critical = false),
                ),
                "TIME_OUT" to listOf(
                    ChecklistItem("to_team", "Team introduced", critical = true),
                ),
                "SIGN_OUT" to listOf(
                    ChecklistItem("so_count", "Swab and instrument count", critical = true),
                ),
            ),
        )
    }

    private fun repo(store: FakeStore) =
        ChecklistRepository(dao = store.checklists, config = config, clock = Clock { t0 })

    @Test
    fun `items come from config, per phase`() {
        val r = repo(FakeStore())
        assertEquals(3, r.items(ChecklistPhase.SIGN_IN).size)
        assertEquals(1, r.items(ChecklistPhase.TIME_OUT).size)
        assertEquals(3, r.itemsByPhase().size)
    }

    @Test
    fun `opening a phase twice returns the same run`() = runTest {
        val store = FakeStore()
        val r = repo(store)

        val first = r.openPhase("case-1", ChecklistPhase.SIGN_IN)
        val second = r.openPhase("case-1", ChecklistPhase.SIGN_IN)

        assertEquals(first.id, second.id)
        assertEquals(1, store.checklists.rows.value.size)
    }

    @Test
    fun `toggling an item on and off is symmetrical`() = runTest {
        val store = FakeStore()
        val r = repo(store)

        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        assertEquals(listOf("si_identity"), r.get("case-1", ChecklistPhase.SIGN_IN)!!.itemsConfirmed)

        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        assertTrue(r.get("case-1", ChecklistPhase.SIGN_IN)!!.itemsConfirmed.isEmpty())
    }

    @Test
    fun `a phase cannot complete until every critical item is confirmed`() = runTest {
        val store = FakeStore()
        val r = repo(store)

        assertFalse("nothing confirmed", r.complete("case-1", ChecklistPhase.SIGN_IN))

        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        assertFalse("one of two criticals", r.complete("case-1", ChecklistPhase.SIGN_IN))

        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_site")
        assertTrue("both criticals confirmed", r.complete("case-1", ChecklistPhase.SIGN_IN))
        assertEquals(t0, r.get("case-1", ChecklistPhase.SIGN_IN)!!.completedAtMs)
    }

    @Test
    fun `a non-critical item is not required to complete`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_site")

        assertTrue(r.complete("case-1", ChecklistPhase.SIGN_IN))
        assertFalse("si_allergy was never confirmed", "si_allergy" in r.get("case-1", ChecklistPhase.SIGN_IN)!!.itemsConfirmed)
    }

    @Test
    fun `un-confirming an item after completing reopens the phase`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_site")
        r.complete("case-1", ChecklistPhase.SIGN_IN)

        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_site")

        assertNull(
            "a phase whose item was un-ticked is no longer complete",
            r.get("case-1", ChecklistPhase.SIGN_IN)!!.completedAtMs,
        )
    }

    @Test
    fun `skipping stores the reason and the time`() = runTest {
        val store = FakeStore()
        val r = repo(store)

        r.skip("case-1", ChecklistPhase.SIGN_IN, "  emergency laparotomy  ")

        val run = r.get("case-1", ChecklistPhase.SIGN_IN)!!
        assertTrue(run.skipped)
        assertEquals("emergency laparotomy", run.skipReason)
        assertEquals(t0, run.completedAtMs)
    }

    @Test
    fun `completing after a skip clears the skip`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.skip("case-1", ChecklistPhase.SIGN_IN, "rushed")
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_site")

        assertTrue(r.complete("case-1", ChecklistPhase.SIGN_IN))
        val run = r.get("case-1", ChecklistPhase.SIGN_IN)!!
        assertFalse(run.skipped)
        assertNull(run.skipReason)
    }

    // --- the gate -------------------------------------------------------------

    @Test
    fun `nothing is gated before a trigger event is marked`() = runTest {
        val r = repo(FakeStore())
        assertNull(r.gateFor("case-1", EventType.PATIENT_IN_ROOM, emptySet()))
    }

    @Test
    fun `an unsatisfied sign-in blocks the next event`() = runTest {
        val r = repo(FakeStore())
        val blocking = r.gateFor(
            caseId = "case-1",
            next = EventType.ANAESTHESIA_START,
            markedEvents = setOf(EventType.PATIENT_IN_ROOM),
        )
        assertEquals(ChecklistPhase.SIGN_IN, blocking)
    }

    @Test
    fun `a completed sign-in stops blocking`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_site")
        r.complete("case-1", ChecklistPhase.SIGN_IN)

        assertNull(
            r.gateFor("case-1", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM)),
        )
    }

    @Test
    fun `a skipped-with-reason sign-in stops blocking`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.skip("case-1", ChecklistPhase.SIGN_IN, "emergency")

        assertNull(
            r.gateFor("case-1", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM)),
        )
    }

    @Test
    fun `the gate is scoped to one case`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_site")
        r.complete("case-1", ChecklistPhase.SIGN_IN)

        assertNotNull(
            "case 2 has its own sign-in to answer for",
            r.gateFor("case-2", EventType.ANAESTHESIA_START, setOf(EventType.PATIENT_IN_ROOM)),
        )
    }

    @Test
    fun `runs survive being read back, item ids intact`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_identity")
        r.toggleItem("case-1", ChecklistPhase.SIGN_IN, "si_site")

        assertEquals(
            setOf("si_identity", "si_site"),
            r.allRuns().single().itemsConfirmed.toSet(),
        )
    }
}
