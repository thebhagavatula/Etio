package com.etio.ot.ui

import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.MessageDraftCoordinator
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.ai.RecordingLlmEngine
import com.etio.ot.ai.SpeechCapture
import com.etio.ot.ai.fakePromptSource
import com.etio.ot.core.Clock
import com.etio.ot.data.FakeStore
import com.etio.ot.data.config.SeedCase
import com.etio.ot.data.config.SeedConfig
import com.etio.ot.data.config.SeedSource
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.ui.delay.CapturePhase
import com.etio.ot.ui.delay.DelayCaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The capture screen's state machine: tap to start, tap to finish, and every way that
 * can go wrong. Nothing here touches a real microphone or a real model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelayCaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val t0 = 1_700_000_000_000L

    /** A microphone we drive by hand. */
    private class FakeSpeech : SpeechCapture {
        val events = MutableSharedFlow<SpeechCapture.Event>(extraBufferCapacity = 16)
        var available = true
        var stopCalls = 0
        var listenCalls = 0

        override fun listen(): Flow<SpeechCapture.Event> = callbackFlow {
            listenCalls++
            val job = launch { events.collect { trySend(it) } }
            awaitClose { job.cancel() }
        }

        override fun stop() { stopCalls++ }
        override fun isAvailable(): Boolean = available
    }

    private lateinit var speech: FakeSpeech
    private lateinit var store: FakeStore
    private lateinit var engine: RecordingLlmEngine
    private lateinit var caseId: String
    private lateinit var vm: DelayCaptureViewModel

    private val goodJson =
        """{"code":"STERILE_SET_UNAVAILABLE","attributed_dept":"CSSD","avoidable":true,""" +
            """"estimated_min":40,"note":"Set came back wet","confidence":0.9}"""

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        speech = FakeSpeech()
        store = FakeStore()
        engine = RecordingLlmEngine()

        val seed = SeedSource {
            SeedConfig(listOf(SeedCase("1", "OT-2", "Lap chole", "Dr A", "09:00", 60)))
        }
        val cases = CaseRepository(store.cases, store.events, seed, Clock { t0 })
        cases.seedIfEmpty()
        caseId = store.cases.getAll().first().id

        val delays = DelayRepository(
            delayDao = store.delays,
            messageDao = store.messages,
            classifier = DelayClassifier(engine, fakePromptSource()),
            drafter = MessageDrafter(engine, fakePromptSource()),
            caseRepository = cases,
            clock = Clock { t0 },
        )

        vm = DelayCaptureViewModel(
            caseId = caseId,
            speech = speech,
            delays = delays,
            cases = cases,
            engine = engine,
            drafts = MessageDraftCoordinator(delays, kotlinx.coroutines.CoroutineScope(dispatcher)),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the case is named on the card before anything is recorded`() = runTest(dispatcher) {
        runCurrent()
        assertEquals("1", vm.state.value.caseNumber)
        assertEquals("Lap chole", vm.state.value.procedureName)
    }

    @Test
    fun `toggling starts listening and toggling again asks the recogniser to finish`() =
        runTest(dispatcher) {
            runCurrent()

            vm.toggleListening()
            runCurrent()
            assertEquals(CapturePhase.LISTENING, vm.state.value.phase)
            assertEquals(1, speech.listenCalls)

            vm.toggleListening()
            runCurrent()
            assertEquals("stop, never cancel — cancelling discards the transcript", 1, speech.stopCalls)
        }

    @Test
    fun `a final transcript is classified and reviewed`() = runTest(dispatcher) {
        runCurrent()
        engine.queueSuccess(goodJson)

        vm.toggleListening()
        runCurrent()
        speech.events.emit(SpeechCapture.Event.Final("the set came back wet, cssd says 40 minutes"))
        runCurrent()

        assertEquals(CapturePhase.REVIEW, vm.state.value.phase)
        assertEquals(DelayCode.STERILE_SET_UNAVAILABLE, vm.state.value.record!!.code)
    }

    @Test
    fun `partial results show as they arrive`() = runTest(dispatcher) {
        runCurrent()
        vm.toggleListening()
        runCurrent()

        speech.events.emit(SpeechCapture.Event.Partial("the set"))
        runCurrent()

        assertEquals("the set", vm.state.value.transcript)
        assertEquals(CapturePhase.LISTENING, vm.state.value.phase)
    }

    @Test
    fun `the elapsed counter ticks while listening`() = runTest(dispatcher) {
        runCurrent()
        vm.toggleListening()
        runCurrent()
        advanceTimeBy(3_100)

        assertEquals(3, vm.state.value.elapsedSec)
    }

    @Test
    fun `an open microphone is closed at the hard cap`() = runTest(dispatcher) {
        runCurrent()
        vm.toggleListening()
        runCurrent()

        advanceTimeBy(DelayCaptureViewModel.MAX_RECORDING_SEC * 1000L + 500)

        assertEquals("the cap must finish the session for her", 1, speech.stopCalls)
    }

    @Test
    fun `a transcript too short to mean anything never reaches the model`() = runTest(dispatcher) {
        runCurrent()
        vm.toggleListening()
        runCurrent()

        speech.events.emit(SpeechCapture.Event.Final("uh"))
        runCurrent()

        assertEquals(CapturePhase.IDLE, vm.state.value.phase)
        assertNotNull(vm.state.value.error)
        assertTrue("no inference should have run", engine.calls.isEmpty())
    }

    @Test
    fun `a recogniser error surfaces and leaves the screen usable`() = runTest(dispatcher) {
        runCurrent()
        vm.toggleListening()
        runCurrent()

        speech.events.emit(SpeechCapture.Event.Error("Nothing was picked up", isNoMatch = true))
        runCurrent()

        assertEquals(CapturePhase.IDLE, vm.state.value.phase)
        assertEquals("Nothing was picked up", vm.state.value.error)
    }

    @Test
    fun `typing is classified the same way speech is`() = runTest(dispatcher) {
        runCurrent()
        engine.queueSuccess(goodJson)

        vm.classifyTyped("the set came back wet, cssd says 40 minutes")
        runCurrent()

        assertEquals(CapturePhase.REVIEW, vm.state.value.phase)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `correcting the transcript re-runs the classification`() = runTest(dispatcher) {
        runCurrent()
        engine.queueSuccess(goodJson)
        vm.classifyTyped("the set came back wet, cssd says 40 minutes")
        runCurrent()

        engine.queueSuccess(
            """{"code":"SURGEON_LATE","attributed_dept":"Surgery","avoidable":true,""" +
                """"estimated_min":null,"note":"Surgeon still in OPD","confidence":0.8}""",
        )
        vm.reclassify("surgeon is still in opd, he is coming")
        runCurrent()

        assertEquals(
            "the card must follow the corrected words",
            DelayCode.SURGEON_LATE,
            vm.state.value.record!!.code,
        )
        assertEquals(2, engine.calls.size)
    }

    @Test
    fun `field edits mark the record as edited`() = runTest(dispatcher) {
        runCurrent()
        engine.queueSuccess(goodJson)
        vm.classifyTyped("the set came back wet, cssd says 40 minutes")
        runCurrent()
        assertFalse(vm.state.value.record!!.userEdited)

        vm.editCode(DelayCode.EQUIPMENT_FAILURE)
        runCurrent()

        assertEquals(DelayCode.EQUIPMENT_FAILURE, vm.state.value.record!!.code)
        assertTrue(vm.state.value.record!!.userEdited)
    }

    @Test
    fun `discarding leaves nothing behind`() = runTest(dispatcher) {
        runCurrent()
        engine.queueSuccess(goodJson)
        vm.classifyTyped("the set came back wet, cssd says 40 minutes")
        runCurrent()

        vm.discard()
        runCurrent()

        assertEquals(CapturePhase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.record)
        assertTrue(store.delays.rows.value.isEmpty())
    }

    @Test
    fun `confirming saves the record and hands back its id`() = runTest(dispatcher) {
        runCurrent()
        engine.queueSuccess(goodJson)
        vm.classifyTyped("the set came back wet, cssd says 40 minutes")
        runCurrent()

        var savedId: String? = null
        vm.confirm { savedId = it }
        runCurrent()

        assertNotNull(savedId)
        assertEquals(1, store.delays.rows.value.size)
        assertEquals(savedId, store.delays.rows.value.single().id)
    }

    @Test
    fun `an unavailable recogniser is reported rather than hidden`() = runTest(dispatcher) {
        speech.available = false
        runCurrent()

        val fresh = DelayCaptureViewModel(
            caseId = caseId,
            speech = speech,
            delays = DelayRepository(
                store.delays,
                store.messages,
                DelayClassifier(engine, fakePromptSource()),
                MessageDrafter(engine, fakePromptSource()),
                CaseRepository(store.cases, store.events, SeedSource { SeedConfig(emptyList()) }, Clock { t0 }),
                Clock { t0 },
            ),
            cases = CaseRepository(store.cases, store.events, SeedSource { SeedConfig(emptyList()) }, Clock { t0 }),
            engine = engine,
            drafts = MessageDraftCoordinator(
                DelayRepository(
                    store.delays,
                    store.messages,
                    DelayClassifier(engine, fakePromptSource()),
                    MessageDrafter(engine, fakePromptSource()),
                    CaseRepository(store.cases, store.events, SeedSource { SeedConfig(emptyList()) }, Clock { t0 }),
                    Clock { t0 },
                ),
                kotlinx.coroutines.CoroutineScope(dispatcher),
            ),
        )
        runCurrent()

        assertFalse(fresh.state.value.micAvailable)
    }
}
