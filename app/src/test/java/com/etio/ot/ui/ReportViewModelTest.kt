package com.etio.ot.ui

import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.ai.RecordingLlmEngine
import com.etio.ot.ai.fakePromptSource
import com.etio.ot.core.Clock
import com.etio.ot.data.FakeStore
import com.etio.ot.data.config.ChecklistConfig
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.config.ChecklistSource
import com.etio.ot.data.config.SeedCase
import com.etio.ot.data.config.SeedConfig
import com.etio.ot.data.config.SeedSource
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.ui.report.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The last screen anyone sees. Two claims on it have to be exactly right: a skipped
 * checklist phase must never be counted as a confirmed one, and an event the app
 * filled in must never be reported as one somebody stood there and marked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val t0 = 1_700_000_000_000L
    private fun min(n: Int) = n * 60_000L

    private lateinit var store: FakeStore
    private lateinit var cases: CaseRepository
    private lateinit var delays: DelayRepository
    private lateinit var checklists: ChecklistRepository

    private val checklistConfig = ChecklistSource {
        ChecklistConfig(
            phases = mapOf(
                "SIGN_IN" to listOf(ChecklistItem("si", "Identity", critical = true)),
                "TIME_OUT" to listOf(ChecklistItem("to", "Team", critical = true)),
                "SIGN_OUT" to listOf(ChecklistItem("so", "Count", critical = true)),
            ),
        )
    }

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        store = FakeStore()
        cases = CaseRepository(
            store.cases,
            store.events,
            SeedSource { SeedConfig(listOf(SeedCase("1", "OT-2", "Lap chole", "Dr A", "09:00", 60))) },
            Clock { t0 },
        )
        cases.seedIfEmpty()
        val engine = RecordingLlmEngine()
        delays = DelayRepository(
            store.delays,
            store.messages,
            DelayClassifier(engine, fakePromptSource()),
            MessageDrafter(engine, fakePromptSource()),
            cases,
            Clock { t0 },
        )
        checklists = ChecklistRepository(store.checklists, checklistConfig, Clock { t0 })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ReportViewModel(cases, delays, checklists)

    private suspend fun success(model: ReportViewModel): ReportViewModel.UiState.Success =
        model.uiState.value as ReportViewModel.UiState.Success

    @Test
    fun `an empty day builds a report rather than an error`() = runTest(dispatcher) {
        val model = vm()
        val job = launch { model.uiState.collect { } }
        runCurrent()

        val state = success(model)
        assertEquals(0, state.report.lostMinutes)
        assertTrue(state.headline.isNotBlank())
        job.cancel()
    }

    @Test
    fun `lost minutes come from the delays`() = runTest(dispatcher) {
        val caseId = store.cases.getAll().first().id
        store.delays.upsert(
            DelayRecordEntity(
                id = "d1",
                caseId = caseId,
                createdAtMs = t0,
                transcriptRaw = "cssd says 40 minutes",
                code = DelayCode.STERILE_SET_UNAVAILABLE,
                attributedDept = "CSSD",
                avoidable = Avoidability.AVOIDABLE,
                estimatedMin = 40,
                note = "Set wet",
            ),
        )
        val model = vm()
        val job = launch { model.uiState.collect { } }
        runCurrent()

        assertEquals(40, success(model).report.lostMinutes)
        job.cancel()
    }

    @Test
    fun `a phase is only due once its trigger event is marked`() = runTest(dispatcher) {
        val model = vm()
        val job = launch { model.uiState.collect { } }
        runCurrent()

        assertEquals(0, success(model).compliance.due)

        cases.markEvent(store.cases.getAll().first().id, EventType.PATIENT_IN_ROOM)
        runCurrent()

        val signIn = success(model).compliance.phases.first { it.phase == ChecklistPhase.SIGN_IN }
        assertEquals(1, signIn.due)
        assertEquals(0, signIn.confirmed)
        assertEquals(1, signIn.outstanding)
        job.cancel()
    }

    @Test
    fun `a skipped phase is reported as skipped, never as confirmed`() = runTest(dispatcher) {
        val caseId = store.cases.getAll().first().id
        cases.markEvent(caseId, EventType.PATIENT_IN_ROOM)
        checklists.skip(caseId, ChecklistPhase.SIGN_IN, "emergency laparotomy")

        val model = vm()
        val job = launch { model.uiState.collect { } }
        runCurrent()

        val state = success(model)
        val signIn = state.compliance.phases.first { it.phase == ChecklistPhase.SIGN_IN }
        assertEquals(1, signIn.due)
        assertEquals("a skip is not a confirmation", 0, signIn.confirmed)
        assertEquals(1, signIn.skipped)
        assertEquals(1, state.compliance.skips.size)
        assertEquals("emergency laparotomy", state.compliance.skips.single().reason)
        job.cancel()
    }

    @Test
    fun `a completed phase counts as confirmed and leaves nothing outstanding`() =
        runTest(dispatcher) {
            val caseId = store.cases.getAll().first().id
            cases.markEvent(caseId, EventType.PATIENT_IN_ROOM)
            checklists.toggleItem(caseId, ChecklistPhase.SIGN_IN, "si")
            checklists.complete(caseId, ChecklistPhase.SIGN_IN)

            val model = vm()
            val job = launch { model.uiState.collect { } }
            runCurrent()

            val signIn = success(model).compliance.phases.first { it.phase == ChecklistPhase.SIGN_IN }
            assertEquals(1, signIn.confirmed)
            assertEquals(0, signIn.outstanding)
            assertEquals(0, signIn.skipped)
            job.cancel()
        }

    @Test
    fun `a day with a skip on it is never clean`() = runTest(dispatcher) {
        val caseId = store.cases.getAll().first().id
        cases.markEvent(caseId, EventType.PATIENT_IN_ROOM)
        checklists.skip(caseId, ChecklistPhase.SIGN_IN, "emergency")

        val model = vm()
        val job = launch { model.uiState.collect { } }
        runCurrent()

        assertTrue("a skipped phase must disqualify a clean day", !success(model).compliance.clean)
        job.cancel()
    }

    @Test
    fun `events the app filled in are reported separately from the ones she marked`() =
        runTest(dispatcher) {
            val caseId = store.cases.getAll().first().id
            // Knife skips two events; both are written as INFERRED behind it.
            cases.markEvent(caseId, EventType.KNIFE_TO_SKIN, atMs = t0 + min(30))

            val model = vm()
            val job = launch { model.uiState.collect { } }
            runCurrent()

            val provenance = success(model).provenance
            assertEquals("one tap", 1, provenance.tapped)
            assertEquals("three filled in behind it", 3, provenance.appFilled)
            assertEquals(4, provenance.total)
            job.cancel()
        }

    @Test
    fun `a corrected timestamp is counted as a correction`() = runTest(dispatcher) {
        val caseId = store.cases.getAll().first().id
        val marked = cases.markEvent(caseId, EventType.PATIENT_SENT_FOR, atMs = t0)
        cases.correctEvent(marked.event, t0 + min(5))

        val model = vm()
        val job = launch { model.uiState.collect { } }
        runCurrent()

        assertEquals(1, success(model).provenance.corrected)
        job.cancel()
    }

    @Test
    fun `a voice-sourced event is not counted as app-filled`() = runTest(dispatcher) {
        val caseId = store.cases.getAll().first().id
        cases.markEvent(caseId, EventType.PATIENT_SENT_FOR, source = EventSource.VOICE)

        val model = vm()
        val job = launch { model.uiState.collect { } }
        runCurrent()

        val p = success(model).provenance
        assertEquals(1, p.voice)
        assertEquals(0, p.appFilled)
        job.cancel()
    }

    @Test
    fun `the plain-text export names the theatre and the lost minutes`() = runTest(dispatcher) {
        val caseId = store.cases.getAll().first().id
        store.delays.upsert(
            DelayRecordEntity(
                id = "d1",
                caseId = caseId,
                createdAtMs = t0,
                transcriptRaw = "cssd says 40 minutes",
                code = DelayCode.STERILE_SET_UNAVAILABLE,
                attributedDept = "CSSD",
                avoidable = Avoidability.AVOIDABLE,
                estimatedMin = 40,
                note = "Set wet",
            ),
        )
        val model = vm()
        val job = launch { model.uiState.collect { } }
        runCurrent()

        val text = model.asPlainText()
        assertTrue(text, text.contains("OT-2"))
        assertTrue(text, text.contains("40"))
        job.cancel()
    }
}
