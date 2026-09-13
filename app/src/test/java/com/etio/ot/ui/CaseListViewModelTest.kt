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
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.ui.caselist.CaseListViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The home screen's state, including the one interaction the safety gate owns:
 * an event that must not be marked until the checklist in front of it is answered.
 *
 * Note the deliberate absence of advanceUntilIdle — this ViewModel holds a 1s ticker
 * that never completes, so anything that drains all scheduled work never returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaseListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val t0 = 1_700_000_000_000L

    private val seed = SeedSource {
        SeedConfig(
            listOf(
                SeedCase("1", "OT-2", "Lap chole", "Dr A", "09:00", 60),
                SeedCase("2", "OT-2", "Hernia", "Dr B", "10:30", 45),
            ),
        )
    }

    private val checklistConfig = ChecklistSource {
        ChecklistConfig(
            phases = mapOf(
                "SIGN_IN" to listOf(ChecklistItem("si_identity", "Identity", critical = true)),
                "TIME_OUT" to listOf(ChecklistItem("to_team", "Team", critical = true)),
                "SIGN_OUT" to listOf(ChecklistItem("so_count", "Count", critical = true)),
            ),
        )
    }

    private lateinit var store: FakeStore
    private lateinit var cases: CaseRepository
    private lateinit var checklists: ChecklistRepository
    private lateinit var vm: CaseListViewModel

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        store = FakeStore()
        cases = CaseRepository(store.cases, store.events, seed, Clock { t0 })
        cases.seedIfEmpty()

        val engine = RecordingLlmEngine()
        val delays = DelayRepository(
            store.delays,
            store.messages,
            DelayClassifier(engine, fakePromptSource()),
            MessageDrafter(engine, fakePromptSource()),
            cases,
            Clock { t0 },
        )
        checklists = ChecklistRepository(store.checklists, checklistConfig, Clock { t0 })
        vm = CaseListViewModel(cases = cases, delays = delays, checklists = checklists)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the day arrives in list order`() = runTest(dispatcher) {
        val job = launch { vm.uiState.collect { } }
        runCurrent()

        val state = vm.uiState.value
        assertEquals(listOf("1", "2"), state.cases.map { it.caseNumber })
        assertEquals("case 1 is active before anything is marked", state.cases.first().id, state.metrics.activeCaseId)
        job.cancel()
    }

    @Test
    fun `marking an event reaches the state`() = runTest(dispatcher) {
        val job = launch { vm.uiState.collect { } }
        runCurrent()
        val caseId = store.cases.getAll().first().id

        vm.markEvent(caseId, EventType.PATIENT_SENT_FOR)
        runCurrent()

        assertEquals(1, vm.uiState.value.eventsByCase[caseId]?.size)
        job.cancel()
    }

    @Test
    fun `the gate blocks an event whose checklist has not been answered`() = runTest(dispatcher) {
        val job = launch { vm.uiState.collect { } }
        runCurrent()
        val caseId = store.cases.getAll().first().id

        // Patient in room opens Sign In. Anaesthesia start must then wait for it.
        vm.markEvent(caseId, EventType.PATIENT_IN_ROOM)
        runCurrent()
        val afterInRoom = store.events.getLive().size

        vm.markEvent(caseId, EventType.ANAESTHESIA_START)
        runCurrent()

        assertEquals("the blocked mark must not be written", afterInRoom, store.events.getLive().size)
        assertNotNull("and the phase must be put in front of her", vm.checklistGate.prompt.value)
        job.cancel()
    }

    @Test
    fun `answering the checklist lets the same mark through`() = runTest(dispatcher) {
        val job = launch { vm.uiState.collect { } }
        runCurrent()
        val caseId = store.cases.getAll().first().id

        vm.markEvent(caseId, EventType.PATIENT_IN_ROOM)
        runCurrent()
        checklists.toggleItem(caseId, com.etio.ot.data.model.ChecklistPhase.SIGN_IN, "si_identity")
        checklists.complete(caseId, com.etio.ot.data.model.ChecklistPhase.SIGN_IN)

        vm.markEvent(caseId, EventType.ANAESTHESIA_START)
        runCurrent()

        assertTrue(
            store.events.getLive().any { it.type == EventType.ANAESTHESIA_START },
        )
        job.cancel()
    }

    @Test
    fun `a correction updates the state without losing the audit row`() = runTest(dispatcher) {
        val job = launch { vm.uiState.collect { } }
        runCurrent()
        val caseId = store.cases.getAll().first().id
        vm.markEvent(caseId, EventType.PATIENT_SENT_FOR)
        runCurrent()
        val event = store.events.getLive().first()

        vm.correctEvent(event, t0 + 300_000)
        runCurrent()

        assertEquals(t0 + 300_000, store.events.getLive().first().timestampMs)
        assertEquals(2, store.events.getAllIncludingSuperseded().size)
        job.cancel()
    }

    @Test
    fun `resetting the day clears the marks and re-seeds`() = runTest(dispatcher) {
        val job = launch { vm.uiState.collect { } }
        runCurrent()
        val caseId = store.cases.getAll().first().id
        vm.markEvent(caseId, EventType.PATIENT_SENT_FOR)
        runCurrent()

        vm.resetDay()
        runCurrent()

        assertEquals(2, store.cases.count())
        assertTrue(store.events.getLive().isEmpty())
        assertTrue(store.cases.getAll().all { it.status == CaseStatus.SCHEDULED })
        job.cancel()
    }

    @Test
    fun `a stated duration pushes the rest of the day and shows as one line`() =
        runTest(dispatcher) {
            val job = launch { vm.uiState.collect { } }
            runCurrent()
            val caseId = store.cases.getAll().first().id

            store.delays.upsert(
                com.etio.ot.data.local.entity.DelayRecordEntity(
                    id = "d1",
                    caseId = caseId,
                    createdAtMs = t0,
                    transcriptRaw = "cssd says 40 minutes",
                    code = com.etio.ot.data.model.DelayCode.STERILE_SET_UNAVAILABLE,
                    attributedDept = "CSSD",
                    avoidable = com.etio.ot.data.model.Avoidability.AVOIDABLE,
                    estimatedMin = 40,
                    note = "Set wet",
                ),
            )
            runCurrent()

            val shift = vm.uiState.value.shift
            assertNotNull(shift)
            assertEquals(40, shift!!.pushMin)
            assertEquals("Case 2 pushed ~40 min", shift.summary)
            job.cancel()
        }

    @Test
    fun `no delay with a duration means no shift line at all`() = runTest(dispatcher) {
        val job = launch { vm.uiState.collect { } }
        runCurrent()

        assertNull(vm.uiState.value.shift)
        job.cancel()
    }

    @Test
    fun `an out-of-order mark fills the gap and the state shows it as inferred`() =
        runTest(dispatcher) {
            val job = launch { vm.uiState.collect { } }
            runCurrent()
            val caseId = store.cases.getAll().first().id

            vm.markEvent(caseId, EventType.PATIENT_SENT_FOR)
            runCurrent()
            // Knife skips in-room and anaesthesia; the gate has nothing due yet.
            vm.markEvent(caseId, EventType.KNIFE_TO_SKIN)
            runCurrent()

            val inferred = vm.uiState.value.eventsByCase[caseId].orEmpty()
                .filter { it.source == EventSource.INFERRED }
            assertEquals(2, inferred.size)
            job.cancel()
        }
}
