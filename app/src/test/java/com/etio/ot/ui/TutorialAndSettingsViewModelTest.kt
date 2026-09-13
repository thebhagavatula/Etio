package com.etio.ot.ui

import com.etio.ot.core.Clock
import com.etio.ot.data.FakeStore
import com.etio.ot.data.config.SeedCase
import com.etio.ot.data.config.SeedConfig
import com.etio.ot.data.config.SeedSource
import com.etio.ot.data.model.EventType
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.settings.AppSettings
import com.etio.ot.data.settings.ThemeMode
import com.etio.ot.ui.settings.SettingsViewModel
import com.etio.ot.ui.tutorial.TutorialStep
import com.etio.ot.ui.tutorial.TutorialViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

/** Settings kept in memory, so neither test needs a Context or a file on disk. */
private class InMemorySettings : AppSettings {
    private val theme = MutableStateFlow(ThemeMode.SYSTEM)
    private val tutorial = MutableStateFlow(false)
    private val splash = MutableStateFlow<Long?>(null)

    override val themeMode: Flow<ThemeMode> = theme
    override suspend fun setThemeMode(mode: ThemeMode) { theme.value = mode }

    override val tutorialCompleted: Flow<Boolean> = tutorial
    override suspend fun setTutorialCompleted(completed: Boolean) { tutorial.value = completed }

    override val splashDurationMs: Flow<Long?> = splash
    override suspend fun setSplashDurationMs(ms: Long?) { splash.value = ms }

    val tutorialFlag: Boolean get() = tutorial.value
    val themeValue: ThemeMode get() = theme.value
}

@OptIn(ExperimentalCoroutinesApi::class)
class TutorialAndSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val t0 = 1_700_000_000_000L

    private lateinit var store: FakeStore
    private lateinit var settings: InMemorySettings
    private lateinit var cases: CaseRepository

    private val seed = SeedSource {
        SeedConfig(
            listOf(
                SeedCase("1", "OT-2", "Lap chole", "Dr A", "09:00", 60),
                SeedCase("2", "OT-2", "Hernia", "Dr B", "10:30", 45),
            ),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = FakeStore()
        settings = InMemorySettings()
        cases = CaseRepository(store.cases, store.events, seed, Clock { t0 })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun tutorial() = TutorialViewModel(
        cases = cases,
        settings = settings,
        caseDao = store.cases,
        clock = Clock { t0 },
    )

    // --- the tutorial ---------------------------------------------------------

    @Test
    fun `the sandbox is one dummy case and nothing else`() = runTest(dispatcher) {
        val vm = tutorial()
        vm.startSandbox()
        runCurrent()

        val all = store.cases.getAll()
        assertEquals(1, all.size)
        assertEquals("OT1", all.single().theatreId)
        assertEquals("Demo Case", all.single().procedureName)
        assertEquals("Dr Placeholder", all.single().surgeon)
        assertNotNull(vm.sandboxCaseId.value)
    }

    @Test
    fun `starting the sandbox twice does not create a second one`() = runTest(dispatcher) {
        val vm = tutorial()
        vm.startSandbox()
        runCurrent()
        vm.startSandbox()
        runCurrent()

        assertEquals(1, store.cases.getAll().size)
    }

    @Test
    fun `it opens on the welcome step`() = runTest(dispatcher) {
        assertEquals(TutorialStep.WELCOME, tutorial().step.value)
    }

    @Test
    fun `marking an event in the sandbox advances past the marking step`() = runTest(dispatcher) {
        val vm = tutorial()
        vm.startSandbox()
        runCurrent()
        vm.goTo(TutorialStep.MARK_EVENT)

        cases.markEvent(store.cases.getAll().single().id, EventType.PATIENT_SENT_FOR)
        runCurrent()

        assertEquals(TutorialStep.CORRECT_TIME, vm.step.value)
    }

    @Test
    fun `correcting a time advances past the correction step`() = runTest(dispatcher) {
        val vm = tutorial()
        vm.startSandbox()
        runCurrent()
        val id = store.cases.getAll().single().id
        vm.goTo(TutorialStep.MARK_EVENT)
        val marked = cases.markEvent(id, EventType.PATIENT_SENT_FOR)
        runCurrent()

        cases.correctEvent(marked.event, t0 + 60_000)
        runCurrent()

        assertEquals(TutorialStep.SPEAK_DELAY, vm.step.value)
    }

    @Test
    fun `steps never run backwards`() = runTest(dispatcher) {
        val vm = tutorial()
        vm.goTo(TutorialStep.TRANSCRIPT)
        vm.goTo(TutorialStep.WELCOME)

        assertEquals(TutorialStep.TRANSCRIPT, vm.step.value)
    }

    @Test
    fun `advance walks forward and stops at the last step`() = runTest(dispatcher) {
        val vm = tutorial()
        repeat(TutorialStep.entries.size + 3) { vm.advance() }
        assertEquals(TutorialStep.FINALE, vm.step.value)
        assertTrue(vm.step.value.isLast)
    }

    @Test
    fun `finishing sets the flag and loads the seeded day`() = runTest(dispatcher) {
        val vm = tutorial()
        vm.startSandbox()
        runCurrent()

        var done = false
        vm.finish { done = true }
        runCurrent()

        assertTrue("the flag is what stops it reappearing on stage", settings.tutorialFlag)
        assertTrue(done)
        assertEquals("the sandbox is replaced by the real day", 2, store.cases.getAll().size)
        assertEquals(listOf("1", "2"), store.cases.getAll().map { it.caseNumber })
    }

    @Test
    fun `skipping makes exactly the same promise as finishing`() = runTest(dispatcher) {
        val vm = tutorial()
        vm.startSandbox()
        runCurrent()

        var done = false
        vm.skip { done = true }
        runCurrent()

        assertTrue(settings.tutorialFlag)
        assertTrue(done)
        assertEquals(2, store.cases.getAll().size)
    }

    @Test
    fun `a demo reset cannot bring the tutorial back`() = runTest(dispatcher) {
        val vm = tutorial()
        vm.finish { }
        runCurrent()
        assertTrue(settings.tutorialFlag)

        // The reset gesture wipes Room. The flag lives outside it, on purpose.
        cases.resetDay()
        runCurrent()

        assertTrue("still completed after a full wipe and re-seed", settings.tutorialFlag)
    }

    // --- settings -------------------------------------------------------------

    @Test
    fun `the theme choice is written through`() = runTest(dispatcher) {
        val vm = SettingsViewModel(settings)

        vm.setThemeMode(ThemeMode.DARK)
        runCurrent()
        assertEquals(ThemeMode.DARK, settings.themeValue)

        vm.setThemeMode(ThemeMode.LIGHT)
        runCurrent()
        assertEquals(ThemeMode.LIGHT, settings.themeValue)
    }

    @Test
    fun `the default is to follow the system`() = runTest(dispatcher) {
        assertEquals(ThemeMode.SYSTEM, SettingsViewModel(settings).themeMode.first())
    }

    @Test
    fun `replaying the tutorial clears the flag and nothing else`() = runTest(dispatcher) {
        settings.setTutorialCompleted(true)
        val vm = SettingsViewModel(settings)

        vm.replayTutorial()
        runCurrent()

        assertFalse(settings.tutorialFlag)
        assertEquals("the theme is not touched", ThemeMode.SYSTEM, settings.themeValue)
    }

    @Test
    fun `a splash override round trips, and clearing it restores the default`() =
        runTest(dispatcher) {
            val vm = SettingsViewModel(settings)

            vm.setSplashDurationMs(500)
            runCurrent()
            assertEquals(500L, settings.splashDurationMs.first())

            vm.setSplashDurationMs(null)
            runCurrent()
            assertEquals(null, settings.splashDurationMs.first())
        }
}
