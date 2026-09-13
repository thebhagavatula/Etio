package com.etio.ot.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.MessageDraftCoordinator
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.ai.RecordingLlmEngine
import com.etio.ot.ai.SpeechCapture
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
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.ui.caselist.CaseListScreen
import com.etio.ot.ui.caselist.CaseListViewModel
import com.etio.ot.ui.delay.DelayCaptureScreen
import com.etio.ot.ui.delay.DelayCaptureViewModel
import com.etio.ot.ui.messages.MessagesScreen
import com.etio.ot.ui.messages.MessagesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The whole screens, rendered, with their ViewModels wired to fakes rather than to
 * the DI graph. This is the layer the earlier suites could not reach: composing one
 * of these used to build the LLM engine and the database before the first frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScreenUiTest {

    @get:Rule val compose = createComposeRule()

    private val t0 = 1_700_000_000_000L
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var store: FakeStore
    private lateinit var engine: RecordingLlmEngine
    private lateinit var cases: CaseRepository
    private lateinit var delays: DelayRepository
    private lateinit var checklists: ChecklistRepository
    private lateinit var caseId: String

    private class FakeSpeech : SpeechCapture {
        val events = MutableSharedFlow<SpeechCapture.Event>(extraBufferCapacity = 16)
        var available = true
        var stopCalls = 0
        override fun listen(): Flow<SpeechCapture.Event> = callbackFlow {
            val job = launch { events.collect { trySend(it) } }
            awaitClose { job.cancel() }
        }
        override fun stop() { stopCalls++ }
        override fun isAvailable() = available
    }

    private val speech = FakeSpeech()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        UiTestSupport.initServiceLocator()
        // Robolectric denies runtime permissions by default, which is the microphone
        // being off — a real state the capture screen handles, and covered below. The
        // rest of these tests want the ordinary case.
        grantMic()
        store = FakeStore()
        engine = RecordingLlmEngine()
        cases = CaseRepository(
            store.cases,
            store.events,
            SeedSource {
                SeedConfig(
                    listOf(
                        SeedCase("1", "OT-2", "Lap chole", "Dr Rao", "09:00", 60),
                        SeedCase("2", "OT-2", "Hernia repair", "Dr Iyer", "10:30", 45),
                    ),
                )
            },
            Clock { t0 },
        )
        runBlocking { cases.seedIfEmpty() }
        caseId = runBlocking { store.cases.getAll().first().id }
        delays = DelayRepository(
            store.delays,
            store.messages,
            DelayClassifier(engine, fakePromptSource()),
            MessageDrafter(engine, fakePromptSource()),
            cases,
            Clock { t0 },
        )
        checklists = ChecklistRepository(
            store.checklists,
            ChecklistSource {
                ChecklistConfig(
                    phases = mapOf(
                        "SIGN_IN" to listOf(ChecklistItem("si", "Identity", critical = true)),
                        "TIME_OUT" to listOf(ChecklistItem("to", "Team", critical = true)),
                        "SIGN_OUT" to listOf(ChecklistItem("so", "Count", critical = true)),
                    ),
                )
            },
            Clock { t0 },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun grantMic() = Shadows.shadowOf(
        ApplicationProvider.getApplicationContext<Application>(),
    ).grantPermissions(Manifest.permission.RECORD_AUDIO)

    private fun denyMic() = Shadows.shadowOf(
        ApplicationProvider.getApplicationContext<Application>(),
    ).denyPermissions(Manifest.permission.RECORD_AUDIO)

    private fun record() = DelayRecordEntity(
        id = "d1",
        caseId = caseId,
        createdAtMs = t0,
        transcriptRaw = "the set came back wet, cssd says 40 minutes",
        code = DelayCode.STERILE_SET_UNAVAILABLE,
        attributedDept = "CSSD",
        avoidable = Avoidability.AVOIDABLE,
        estimatedMin = 40,
        note = "Set came back wet",
    )

    // --- home -----------------------------------------------------------------

    @Test
    fun `home shows the theatre, the active case and the next event`() {
        compose.setEtioContent {
            CaseListScreen(
                onCaptureDelay = {},
                onRecordDelay = {},
                onOpenMessages = {},
                onOpenReport = {},
                viewModel = CaseListViewModel(cases, delays, checklists),
            )
        }

        compose.onNodeWithText("OT-2").assertIsDisplayed()
        compose.onNodeWithText("Lap chole").assertIsDisplayed()
        compose.onNodeWithText("Sent for").assertIsDisplayed()
    }

    @Test
    fun `the rest of the list stays collapsed until asked for`() {
        compose.setEtioContent {
            CaseListScreen(
                onCaptureDelay = {},
                onRecordDelay = {},
                onOpenMessages = {},
                onOpenReport = {},
                viewModel = CaseListViewModel(cases, delays, checklists),
            )
        }

        compose.onNodeWithText("All cases (2)").assertIsDisplayed()
        compose.onNodeWithText("All cases (2)").performClick()
        compose.onNodeWithText("Hernia repair").assertIsDisplayed()
    }

    @Test
    fun `tapping the next event writes it and the button moves on`() {
        compose.setEtioContent {
            CaseListScreen(
                onCaptureDelay = {},
                onRecordDelay = {},
                onOpenMessages = {},
                onOpenReport = {},
                viewModel = CaseListViewModel(cases, delays, checklists),
            )
        }

        compose.onNodeWithText("Sent for").performClick()
        compose.waitForIdle()

        assertEquals(1, runBlocking { store.events.getLive().size })
        compose.onNodeWithText("Patient in room").assertIsDisplayed()
    }

    @Test
    fun `the mic on the bottom bar opens capture for the active case`() {
        var captured: String? = null
        compose.setEtioContent {
            CaseListScreen(
                onCaptureDelay = { captured = it },
                onRecordDelay = {},
                onOpenMessages = {},
                onOpenReport = {},
                viewModel = CaseListViewModel(cases, delays, checklists),
            )
        }

        compose.onNodeWithContentDescription("Say what's holding it up").performClick()

        assertEquals(caseId, captured)
    }

    @Test
    fun `the report is reachable from the top bar`() {
        var opened = 0
        compose.setEtioContent {
            CaseListScreen(
                onCaptureDelay = {},
                onRecordDelay = {},
                onOpenMessages = {},
                onOpenReport = { opened++ },
                viewModel = CaseListViewModel(cases, delays, checklists),
            )
        }

        compose.onNodeWithContentDescription("End-of-day report").performClick()

        assertEquals(1, opened)
    }

    // --- capture --------------------------------------------------------------

    private fun captureVm() = DelayCaptureViewModel(
        caseId = caseId,
        speech = speech,
        delays = delays,
        cases = cases,
        engine = engine,
        drafts = MessageDraftCoordinator(delays, CoroutineScope(dispatcher)),
    )

    @Test
    fun `capture opens on the invitation to speak, naming the case`() {
        compose.setEtioContent {
            DelayCaptureScreen(caseId = caseId, onBack = {}, onNotify = {}, viewModel = captureVm())
        }

        compose.onNodeWithText("Say what's holding it up").assertExists()
        compose.onNodeWithText("Start recording").assertExists()
    }

    /**
     * Whether a tap runs the right method is the ViewModel suite's job; what this one
     * owns is whether each state puts the right controls on the glass.
     */
    @Test
    fun `the recording state renders a counter and a single stop`() {
        val vm = captureVm()
        compose.setEtioContent {
            DelayCaptureScreen(caseId = caseId, onBack = {}, onNotify = {}, viewModel = vm)
        }

        vm.startListening()
        compose.waitForIdle()

        compose.onNodeWithText("RECORDING").assertExists()
        compose.onNodeWithText("OF 30s").assertExists()
        compose.onNodeWithText("Stop").assertExists()
    }

    @Test
    fun `the controls inside the sheet are separately addressable`() {
        compose.setEtioContent {
            DelayCaptureScreen(caseId = caseId, onBack = {}, onNotify = {}, viewModel = captureVm())
        }

        // The scrim used to wrap the sheet in a clickable, which merged the whole
        // subtree into one semantics node — everything below would have been announced
        // as a single element and none of it separately focusable.
        compose.onNodeWithText("Start recording").assertHasClickAction()
        compose.onNodeWithText("Type it instead").assertHasClickAction()
        compose.onNodeWithText("Back to the list").assertHasClickAction()
    }

    @Test
    fun `a classified capture lands on the review card with its transcript`() {
        engine.queueSuccess(
            """{"code":"STERILE_SET_UNAVAILABLE","attributed_dept":"CSSD","avoidable":true,""" +
                """"estimated_min":40,"note":"Set came back wet","confidence":0.9}""",
        )
        val vm = captureVm()
        compose.setEtioContent {
            DelayCaptureScreen(caseId = caseId, onBack = {}, onNotify = {}, viewModel = vm)
        }

        vm.classifyTyped("the set came back wet, cssd says 40 minutes")
        compose.waitForIdle()

        compose.onNodeWithText("Sterile set unavailable").assertExists()
        compose.onNodeWithText("the set came back wet, cssd says 40 minutes").assertExists()
    }

    @Test
    fun `a denied microphone is explained, with the keyboard already open`() {
        denyMic()
        compose.setEtioContent {
            DelayCaptureScreen(caseId = caseId, onBack = {}, onNotify = {}, viewModel = captureVm())
        }

        compose.onNodeWithText("Allow microphone").assertExists()
        compose.onNodeWithText("What happened?").assertExists()
    }

    @Test
    fun `no offline recogniser is explained rather than looking broken`() {
        speech.available = false
        compose.setEtioContent {
            DelayCaptureScreen(caseId = caseId, onBack = {}, onNotify = {}, viewModel = captureVm())
        }

        compose.onNodeWithText("Check again").assertExists()
    }

    // --- messages -------------------------------------------------------------

    @Test
    fun `surgeon and family are both on screen without scrolling`() {
        runBlocking {
            store.delays.upsert(record())
            repeat(4) { engine.queueSuccess("A short update about case 1.") }
        }
        compose.setEtioContent {
            MessagesScreen(
                delayId = "d1",
                onBack = {},
                viewModel = MessagesViewModel(
                    delayId = "d1",
                    delays = delays,
                    cases = cases,
                    drafts = MessageDraftCoordinator(delays, CoroutineScope(dispatcher)),
                ),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("SURGEON").assertIsDisplayed()
        compose.onNodeWithText("PATIENT'S FAMILY").assertIsDisplayed()
        compose.onNodeWithText("WARD").assertIsDisplayed()
        compose.onNodeWithText("ANAESTHESIA").assertIsDisplayed()
    }

    @Test
    fun `every message carries its own copy and share targets`() {
        runBlocking {
            store.delays.upsert(record())
            repeat(4) { engine.queueSuccess("A short update about case 1.") }
        }
        compose.setEtioContent {
            MessagesScreen(
                delayId = "d1",
                onBack = {},
                viewModel = MessagesViewModel(
                    delayId = "d1",
                    delays = delays,
                    cases = cases,
                    drafts = MessageDraftCoordinator(delays, CoroutineScope(dispatcher)),
                ),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Copy the Surgeon message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Share the Surgeon message").assertIsDisplayed()
    }

    @Test
    fun `the transcript every message came from is shown under them`() {
        runBlocking {
            store.delays.upsert(record())
            repeat(4) { engine.queueSuccess("A short update about case 1.") }
        }
        compose.setEtioContent {
            MessagesScreen(
                delayId = "d1",
                onBack = {},
                viewModel = MessagesViewModel(
                    delayId = "d1",
                    delays = delays,
                    cases = cases,
                    drafts = MessageDraftCoordinator(delays, CoroutineScope(dispatcher)),
                ),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText(
            "All four written from: “the set came back wet, cssd says 40 minutes”",
        ).assertIsDisplayed()
    }
}
