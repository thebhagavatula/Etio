package com.etio.ot.data

import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.ai.RecordingLlmEngine
import com.etio.ot.ai.fakePromptSource
import com.etio.ot.core.Clock
import com.etio.ot.data.config.SeedCase
import com.etio.ot.data.config.SeedConfig
import com.etio.ot.data.config.SeedSource
import com.etio.ot.data.model.Audience
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.DelayRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a model answer becomes a row. The grounding flags and the "nothing is saved
 * until she confirms" rule both live on this seam.
 */
class DelayRepositoryTest {

    private val t0 = 1_700_000_000_000L

    private val seed = SeedSource {
        SeedConfig(
            cases = listOf(
                SeedCase("1", "OT-2", "Lap chole", "Dr A", "09:00", 60),
                SeedCase("2", "OT-2", "Hernia", "Dr B", "10:30", 45),
            ),
        )
    }

    private class Harness {
        val store = FakeStore()
        val engine = RecordingLlmEngine()
        lateinit var cases: CaseRepository
        lateinit var delays: DelayRepository
    }

    private suspend fun harness(): Harness {
        val h = Harness()
        h.cases = CaseRepository(h.store.cases, h.store.events, seed, Clock { t0 })
        h.cases.seedIfEmpty()
        h.delays = DelayRepository(
            delayDao = h.store.delays,
            messageDao = h.store.messages,
            classifier = DelayClassifier(h.engine, fakePromptSource()),
            drafter = MessageDrafter(h.engine, fakePromptSource()),
            caseRepository = h.cases,
            clock = Clock { t0 },
        )
        return h
    }

    private val goodJson = """
        {"code":"STERILE_SET_UNAVAILABLE","attributed_dept":"CSSD","avoidable":true,
         "estimated_min":40,"note":"Set came back wet","confidence":0.9}
    """.trimIndent()

    @Test
    fun `a classified record carries the transcript and is not yet saved`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id

        val record = h.delays.classify(caseId, "the set came back wet, cssd says 40 minutes")

        assertEquals(DelayCode.STERILE_SET_UNAVAILABLE, record.code)
        assertEquals("the set came back wet, cssd says 40 minutes", record.transcriptRaw)
        assertEquals(t0, record.createdAtMs)
        assertTrue("classification must not persist anything", h.store.delays.rows.value.isEmpty())
    }

    @Test
    fun `saving is what persists it`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id

        val record = h.delays.classify(caseId, "the set came back wet, cssd says 40 minutes")
        h.delays.save(record)

        assertEquals(1, h.store.delays.rows.value.size)
        assertNotNull(h.delays.get(record.id))
    }

    @Test
    fun `a grounded duration survives onto the record`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id

        val record = h.delays.classify(caseId, "the set came back wet, cssd says 40 minutes")

        assertEquals(40, record.estimatedMin)
        assertTrue(record.estimatedMinGrounded)
    }

    @Test
    fun `a duration nobody said is dropped and flagged on the record`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id

        // Same model answer, but the transcript never mentions forty minutes.
        val record = h.delays.classify(caseId, "the set came back wet")

        assertNull(record.estimatedMin)
        assertFalse(record.estimatedMinGrounded)
    }

    @Test
    fun `an unusable model answer becomes an OTHER carrying her words`() = runTest {
        val h = harness()
        h.engine.queueSuccess("I think the set is wet?")
        h.engine.queueSuccess("still not JSON")
        val caseId = h.store.cases.getAll().first().id

        val record = h.delays.classify(caseId, "the set came back wet")

        assertEquals(DelayCode.OTHER, record.code)
        assertTrue(record.fellBackToOther)
        assertTrue(record.note.isNotBlank())
    }

    @Test
    fun `a schedule shift is computed only from a stated duration`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id
        val record = h.delays.classify(caseId, "the set came back wet, cssd says 40 minutes")

        val shift = h.delays.shiftFor(record)

        assertNotNull(shift)
        assertEquals(40, shift!!.pushMin)
        assertEquals(1, shift.downstream.size)
    }

    @Test
    fun `no stated duration means no schedule shift at all`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id
        val record = h.delays.classify(caseId, "the set came back wet")

        assertNull(h.delays.shiftFor(record))
    }

    @Test
    fun `drafting writes one message per audience and replaces a previous run`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id
        val record = h.delays.classify(caseId, "the set came back wet, cssd says 40 minutes")
        h.delays.save(record)

        repeat(Audience.entries.size) { h.engine.queueSuccess("A short update.") }
        val first = h.delays.draftMessages(record).toList()
        assertEquals(Audience.entries.size, first.size)
        assertEquals(Audience.demoOrder, first.map { it.audience })

        repeat(Audience.entries.size) { h.engine.queueSuccess("A different update.") }
        h.delays.draftMessages(record).toList()

        assertEquals(
            "a second run replaces the first rather than doubling it",
            Audience.entries.size,
            h.store.messages.rows.value.count { it.delayRecordId == record.id },
        )
    }

    @Test
    fun `marking a message copied sticks`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id
        val record = h.delays.classify(caseId, "the set came back wet, cssd says 40 minutes")
        h.delays.save(record)
        repeat(Audience.entries.size) { h.engine.queueSuccess("body") }
        val messages = h.delays.draftMessages(record).toList()

        h.delays.markCopied(messages.first().id)

        assertTrue(h.store.messages.rows.value.first { it.id == messages.first().id }.copied)
    }

    @Test
    fun `deleting a record removes it`() = runTest {
        val h = harness()
        h.engine.queueSuccess(goodJson)
        val caseId = h.store.cases.getAll().first().id
        val record = h.delays.classify(caseId, "the set came back wet")
        h.delays.save(record)

        h.delays.delete(record.id)

        assertTrue(h.store.delays.rows.value.isEmpty())
        assertNull(h.delays.get(record.id))
    }
}
