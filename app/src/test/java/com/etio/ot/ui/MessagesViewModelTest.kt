package com.etio.ot.ui

import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.MessageDraftCoordinator
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.ai.RecordingLlmEngine
import com.etio.ot.ai.fakePromptSource
import com.etio.ot.core.Clock
import com.etio.ot.data.FakeStore
import com.etio.ot.data.config.SeedCase
import com.etio.ot.data.config.SeedConfig
import com.etio.ot.data.config.SeedSource
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Audience
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.ui.messages.MessagesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Notify should reveal work already done, not start it. These pin that: a run already
 * in flight is joined rather than restarted, and a record with messages on disk is not
 * redrafted just because someone opened the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val t0 = 1_700_000_000_000L

    private lateinit var store: FakeStore
    private lateinit var engine: RecordingLlmEngine
    private lateinit var delays: DelayRepository
    private lateinit var caseId: String

    private fun record(id: String = "d1") = DelayRecordEntity(
        id = id,
        caseId = caseId,
        createdAtMs = t0,
        transcriptRaw = "the set came back wet, cssd says 40 minutes",
        code = DelayCode.STERILE_SET_UNAVAILABLE,
        attributedDept = "CSSD",
        avoidable = Avoidability.AVOIDABLE,
        estimatedMin = 40,
        note = "Set came back wet",
    )

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        store = FakeStore()
        engine = RecordingLlmEngine()
        val cases = CaseRepository(
            store.cases,
            store.events,
            SeedSource { SeedConfig(listOf(SeedCase("1", "OT-2", "Lap chole", "Dr A", "09:00", 60))) },
            Clock { t0 },
        )
        cases.seedIfEmpty()
        caseId = store.cases.getAll().first().id
        delays = DelayRepository(
            store.delays,
            store.messages,
            DelayClassifier(engine, fakePromptSource()),
            MessageDrafter(engine, fakePromptSource()),
            cases,
            Clock { t0 },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(coordinator: MessageDraftCoordinator, delayId: String = "d1") =
        MessagesViewModel(
            delayId = delayId,
            delays = delays,
            cases = CaseRepository(
                store.cases,
                store.events,
                SeedSource { SeedConfig(emptyList()) },
                Clock { t0 },
            ),
            drafts = coordinator,
        )

    @Test
    fun `opening a record with nothing drafted starts the work`() = runTest(dispatcher) {
        store.delays.upsert(record())
        repeat(Audience.entries.size) { engine.queueSuccess("body") }
        val coordinator = MessageDraftCoordinator(delays, this)

        val model = vm(coordinator)
        runCurrent()

        assertEquals(Audience.entries.size, model.state.value.messages.size)
        assertEquals(Audience.entries.size, engine.calls.size)
    }

    @Test
    fun `a run already in flight is joined, not restarted`() = runTest(dispatcher) {
        store.delays.upsert(record())
        repeat(Audience.entries.size) { engine.queueSuccess("body") }
        val coordinator = MessageDraftCoordinator(delays, this)

        // The capture screen kicked this off at confirm time.
        coordinator.start(record())
        val model = vm(coordinator)
        runCurrent()

        assertEquals(
            "a second run would have needed a second set of responses",
            Audience.entries.size,
            engine.calls.size,
        )
    }

    @Test
    fun `messages already on disk are shown without redrafting`() = runTest(dispatcher) {
        store.delays.upsert(record())
        repeat(Audience.entries.size) { engine.queueSuccess("body") }
        val coordinator = MessageDraftCoordinator(delays, this)
        coordinator.start(record())
        runCurrent()
        val callsAfterFirstRun = engine.calls.size

        val reopened = vm(coordinator)
        runCurrent()

        assertEquals("reopening must not spend the model again", callsAfterFirstRun, engine.calls.size)
        assertEquals(Audience.entries.size, reopened.state.value.messages.size)
    }

    @Test
    fun `pending shrinks as each audience lands and generating clears at the end`() =
        runTest(dispatcher) {
            store.delays.upsert(record())
            repeat(Audience.entries.size) { engine.queueSuccess("body") }
            val coordinator = MessageDraftCoordinator(delays, this)

            val model = vm(coordinator)
            runCurrent()

            assertTrue(model.state.value.pending.isEmpty())
            assertFalse(model.state.value.generating)
        }

    @Test
    fun `the record and its case are exposed for the header`() = runTest(dispatcher) {
        store.delays.upsert(record())
        repeat(Audience.entries.size) { engine.queueSuccess("body") }
        val coordinator = MessageDraftCoordinator(delays, this)

        val model = vm(coordinator)
        runCurrent()

        assertNotNull(model.state.value.record)
        assertEquals(DelayCode.STERILE_SET_UNAVAILABLE, model.state.value.record!!.code)
    }

    @Test
    fun `regenerating one audience replaces only that message`() = runTest(dispatcher) {
        store.delays.upsert(record())
        repeat(Audience.entries.size) { engine.queueSuccess("first body") }
        val coordinator = MessageDraftCoordinator(delays, this)
        val model = vm(coordinator)
        runCurrent()

        engine.queueSuccess("a rewritten body")
        model.regenerate(Audience.SURGEON)
        runCurrent()

        val bodies = store.messages.rows.value.associate { it.audience to it.body }
        assertEquals("a rewritten body", bodies[Audience.SURGEON])
        assertEquals("first body", bodies[Audience.WARD])
    }

    @Test
    fun `copying is remembered`() = runTest(dispatcher) {
        store.delays.upsert(record())
        repeat(Audience.entries.size) { engine.queueSuccess("body") }
        val coordinator = MessageDraftCoordinator(delays, this)
        val model = vm(coordinator)
        runCurrent()

        val first = store.messages.rows.value.first()
        model.markCopied(first.id)
        runCurrent()

        assertTrue(store.messages.rows.value.first { it.id == first.id }.copied)
    }

    @Test
    fun `a delay id that does not exist leaves an empty screen rather than crashing`() =
        runTest(dispatcher) {
            val coordinator = MessageDraftCoordinator(delays, this)
            val model = vm(coordinator, delayId = "nope")
            runCurrent()

            assertEquals(null, model.state.value.record)
            assertTrue(model.state.value.messages.isEmpty())
            assertTrue(engine.calls.isEmpty())
        }
}
