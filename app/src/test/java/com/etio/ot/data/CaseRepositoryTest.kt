package com.etio.ot.data

import com.etio.ot.core.Clock
import com.etio.ot.data.config.SeedCase
import com.etio.ot.data.config.SeedConfig
import com.etio.ot.data.config.SeedSource
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.CaseRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Marking, inferring and correcting — the writes the whole day is built out of.
 *
 * The inference arithmetic in particular has never had a test: it decides what
 * timestamp a coordinator is shown for an event she never marked, and it has to be
 * monotonic or the timers built on top of it go backwards.
 */
class CaseRepositoryTest {

    private val t0 = 1_700_000_000_000L
    private fun min(n: Int) = n * 60_000L

    private val seed = SeedSource {
        SeedConfig(
            cases = listOf(
                SeedCase(
                    caseNumber = "1",
                    theatreId = "OT-2",
                    procedureName = "Lap chole",
                    surgeon = "Dr A",
                    scheduledStart = "09:00",
                    scheduledDurationMin = 60,
                ),
                SeedCase(
                    caseNumber = "2",
                    theatreId = "OT-2",
                    procedureName = "Hernia",
                    surgeon = "Dr B",
                    scheduledStart = "10:30",
                    scheduledDurationMin = 45,
                ),
            ),
        )
    }

    private fun repo(store: FakeStore, nowMs: Long = t0) = CaseRepository(
        caseDao = store.cases,
        eventDao = store.events,
        config = seed,
        clock = Clock { nowMs },
    )

    // --- seeding --------------------------------------------------------------

    @Test
    fun `seeding fills an empty database from config`() = runTest {
        val store = FakeStore()
        repo(store).seedIfEmpty()

        val cases = store.cases.getAll()
        assertEquals(2, cases.size)
        assertEquals(listOf("1", "2"), cases.map { it.caseNumber })
        assertEquals(listOf(0, 1), cases.map { it.orderIndex })
        assertTrue(cases.all { it.status == CaseStatus.SCHEDULED })
    }

    @Test
    fun `seeding twice does not duplicate the day`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        r.seedIfEmpty()
        assertEquals(2, store.cases.count())
    }

    @Test
    fun `scheduled times land on today at the configured clock time`() = runTest {
        val store = FakeStore()
        repo(store).seedIfEmpty()

        val first = store.cases.getAll().first()
        val cal = Calendar.getInstance().apply { timeInMillis = first.scheduledStartMs }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `reset wipes the day and seeds it again`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val caseId = store.cases.getAll().first().id
        r.markEvent(caseId, EventType.PATIENT_SENT_FOR)
        assertEquals(1, store.events.getLive().size)

        r.resetDay()

        assertEquals(2, store.cases.count())
        assertTrue("events must cascade away with the cases", store.events.getLive().isEmpty())
        assertTrue("ids are regenerated", store.cases.getAll().none { it.id == caseId })
    }

    // --- marking --------------------------------------------------------------

    @Test
    fun `marking in order infers nothing`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id

        val result = r.markEvent(id, EventType.PATIENT_SENT_FOR)

        assertTrue(result.inferred.isEmpty())
        assertEquals(EventSource.TAP, result.event.source)
        assertEquals(1, store.events.getLive().size)
    }

    @Test
    fun `patient in room and patient out move the case status`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id

        r.markEvent(id, EventType.PATIENT_IN_ROOM)
        assertEquals(CaseStatus.IN_PROGRESS, store.cases.getById(id)!!.status)

        r.markEvent(id, EventType.PATIENT_OUT)
        assertEquals(CaseStatus.COMPLETED, store.cases.getById(id)!!.status)
    }

    // --- inference ------------------------------------------------------------

    @Test
    fun `a skipped event is written as inferred rather than dropped`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id

        r.markEvent(id, EventType.PATIENT_SENT_FOR, atMs = t0)
        val result = r.markEvent(id, EventType.KNIFE_TO_SKIN, atMs = t0 + min(60))

        assertEquals(
            listOf(EventType.PATIENT_IN_ROOM, EventType.ANAESTHESIA_START),
            result.inferred.map { it.type },
        )
        assertTrue(result.inferred.all { it.source == EventSource.INFERRED })
    }

    @Test
    fun `inferred timestamps are evenly spaced and strictly inside the gap`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id

        r.markEvent(id, EventType.PATIENT_SENT_FOR, atMs = t0)
        val result = r.markEvent(id, EventType.KNIFE_TO_SKIN, atMs = t0 + min(60))

        val times = result.inferred.map { it.timestampMs }
        assertEquals(listOf(t0 + min(20), t0 + min(40)), times)
        assertTrue("must stay before the event that triggered them", times.all { it < t0 + min(60) })
        assertTrue("must stay after the last real mark", times.all { it > t0 })
    }

    @Test
    fun `the timeline stays monotonic after inference`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id

        r.markEvent(id, EventType.PATIENT_SENT_FOR, atMs = t0)
        r.markEvent(id, EventType.PATIENT_OUT, atMs = t0 + min(100))

        val live = store.events.getLive().sortedBy { it.type.ordinal }
        assertEquals(
            "every event up to PATIENT_OUT should now exist",
            6,
            live.size,
        )
        val stamps = live.map { it.timestampMs }
        assertEquals("clinical order must equal chronological order", stamps.sorted(), stamps)
    }

    @Test
    fun `inference with nothing marked before it does not invent a gap`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id

        val result = r.markEvent(id, EventType.KNIFE_TO_SKIN, atMs = t0 + min(30))

        assertEquals(3, result.inferred.size)
        assertTrue(
            "with no anchor there is nothing to spread across, so they share the instant",
            result.inferred.all { it.timestampMs == t0 + min(30) },
        )
    }

    @Test
    fun `an inferred event that triggers a checklist phase still reaches the case`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id

        // KNIFE_TO_SKIN infers PATIENT_IN_ROOM, whose phase is Sign In. The gate reads
        // marked events, so the inferred row has to be there for it to fire later.
        r.markEvent(id, EventType.KNIFE_TO_SKIN, atMs = t0 + min(30))

        val marked = store.events.getLive().map { it.type }.toSet()
        assertTrue(EventType.PATIENT_IN_ROOM in marked)
        assertEquals(CaseStatus.IN_PROGRESS, store.cases.getById(id)!!.status)
    }

    @Test
    fun `marking an earlier event out of order does not infer anything after it`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id

        r.markEvent(id, EventType.PATIENT_OUT, atMs = t0 + min(60))
        val before = store.events.getLive().size

        val result = r.markEvent(id, EventType.ROOM_CLEAN_START, atMs = t0 + min(70))

        assertTrue(result.inferred.isEmpty())
        assertEquals(before + 1, store.events.getLive().size)
    }

    @Test
    fun `inference is scoped to one case`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val (first, second) = store.cases.getAll()

        r.markEvent(first.id, EventType.KNIFE_TO_SKIN, atMs = t0 + min(30))
        val secondCaseEvents = store.events.getLive().filter { it.caseId == second.id }

        assertTrue("case 2 must be untouched", secondCaseEvents.isEmpty())
    }

    // --- correction -----------------------------------------------------------

    @Test
    fun `a correction supersedes the old row instead of mutating it`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id
        // Marking in-room first also infers the sent-for it skipped, so the timeline
        // already holds two rows before the correction is made.
        val original = r.markEvent(id, EventType.PATIENT_IN_ROOM, atMs = t0).event

        r.correctEvent(original, t0 + min(5))

        val all = store.events.getAllIncludingSuperseded()
        assertEquals("inferred + original + corrected", 3, all.size)

        val old = all.first { it.id == original.id }
        assertNotNull("the old row points at its replacement", old.supersededByEventId)
        assertEquals("the superseded row keeps its original time", t0, old.timestampMs)

        val liveInRoom = store.events.getLive().filter { it.type == EventType.PATIENT_IN_ROOM }
        assertEquals(1, liveInRoom.size)
        assertEquals(t0 + min(5), liveInRoom.single().timestampMs)
        assertEquals(original.id, liveInRoom.single().correctedFromEventId)
    }

    @Test
    fun `a corrected event can be corrected again`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id
        val first = r.markEvent(id, EventType.PATIENT_IN_ROOM, atMs = t0).event

        r.correctEvent(first, t0 + min(5))
        val second = store.events.getLive().single { it.type == EventType.PATIENT_IN_ROOM }
        r.correctEvent(second, t0 + min(9))

        val liveInRoom = store.events.getLive().filter { it.type == EventType.PATIENT_IN_ROOM }
        assertEquals("only ever one live row per corrected event", 1, liveInRoom.size)
        assertEquals(t0 + min(9), liveInRoom.single().timestampMs)
        assertEquals("inferred + three generations of in-room", 4, store.events.getAllIncludingSuperseded().size)
    }

    // --- reads ----------------------------------------------------------------

    @Test
    fun `getCase returns null for an id that is not there`() = runTest {
        val store = FakeStore()
        repo(store).seedIfEmpty()
        assertNull(repo(store).getCase("nope"))
    }

    @Test
    fun `live events exclude superseded rows`() = runTest {
        val store = FakeStore()
        val r = repo(store)
        r.seedIfEmpty()
        val id = store.cases.getAll().first().id
        val e = r.markEvent(id, EventType.PATIENT_IN_ROOM, atMs = t0).event
        r.correctEvent(e, t0 + min(2))

        // The inferred sent-for plus exactly one live in-room — the superseded one is gone.
        assertEquals(2, r.liveEvents().size)
        assertEquals(1, r.liveEvents().count { it.type == EventType.PATIENT_IN_ROOM })
    }
}
