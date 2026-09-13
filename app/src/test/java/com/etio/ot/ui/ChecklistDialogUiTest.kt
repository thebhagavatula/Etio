package com.etio.ot.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.ui.checklist.ChecklistDialog
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stop. It is the one surface in the app designed to be in the way, and the
 * behaviour that matters is what it refuses to do: complete a phase the coordinator
 * has not completed, or let a skip through without a reason written down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChecklistDialogUiTest {

    @get:Rule val compose = createComposeRule()

    @Before fun setUp() = UiTestSupport.initServiceLocator()

    private val items = listOf(
        ChecklistItem("si_identity", "Patient identity confirmed", critical = true),
        ChecklistItem("si_site", "Surgical site marked", critical = true),
        ChecklistItem("si_notes", "Notes available", critical = false),
    )

    private fun run(confirmed: List<String> = emptyList()) = ChecklistRunEntity(
        id = "r1",
        caseId = "c1",
        phase = ChecklistPhase.SIGN_IN,
        itemsConfirmed = confirmed,
    )

    private fun render(
        run: ChecklistRunEntity? = run(),
        onToggle: (String) -> Unit = {},
        onComplete: () -> Unit = {},
        onSkip: (String) -> Unit = {},
    ) {
        compose.setEtioContent {
            ChecklistDialog(
                phase = ChecklistPhase.SIGN_IN,
                items = items,
                run = run,
                onToggle = onToggle,
                onComplete = onComplete,
                onSkip = onSkip,
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the phase and every item are on screen`() {
        render()

        compose.onNodeWithText("WHO SIGN IN").assertIsDisplayed()
        items.forEach { compose.onNodeWithText(it.text).assertIsDisplayed() }
    }

    @Test
    fun `progress is stated as a count, not drawn as a bar`() {
        render(run(confirmed = listOf("si_identity")))

        compose.onNodeWithText("1 of 2 confirmed").assertIsDisplayed()
    }

    @Test
    fun `it says out loud that nothing here is automated`() {
        render()

        compose.onNodeWithText("EVERY ITEM IS CONFIRMED BY YOU. NOTHING HERE IS AUTOMATED.")
            .assertIsDisplayed()
    }

    @Test
    fun `completing is refused until every critical item is confirmed`() {
        render(run(confirmed = listOf("si_identity")))

        compose.onNodeWithText("Complete phase").assertIsNotEnabled()
    }

    @Test
    fun `a non-critical item is not what stands between her and completing`() {
        render(run(confirmed = listOf("si_identity", "si_site")))

        compose.onNodeWithText("2 of 2 confirmed").assertIsDisplayed()
        compose.onNodeWithText("Complete phase").performClick()
    }

    @Test
    fun `completing a satisfied phase reports it once`() {
        var completed = 0
        render(run(confirmed = listOf("si_identity", "si_site")), onComplete = { completed++ })

        compose.onNodeWithText("Complete phase").performClick()

        assertEquals(1, completed)
    }

    @Test
    fun `tapping an item reports exactly that item`() {
        var toggled: String? = null
        render(onToggle = { toggled = it })

        compose.onNodeWithText("Surgical site marked").performClick()

        assertEquals("si_site", toggled)
    }

    @Test
    fun `skipping asks for a reason before it will confirm`() {
        render()

        compose.onNodeWithText("Skip phase").performClick()

        compose.onNodeWithText("Reason for skipping").assertIsDisplayed()
        compose.onNodeWithText("Confirm skip").assertIsNotEnabled()
    }

    @Test
    fun `a skip can be backed out of`() {
        render()

        compose.onNodeWithText("Skip phase").performClick()
        compose.onNodeWithText("Cancel skip").performClick()

        compose.onNodeWithText("Skip phase").assertIsDisplayed()
    }

    @Test
    fun `an unopened phase still renders rather than crashing`() {
        render(run = null)

        compose.onNodeWithText("0 of 2 confirmed").assertIsDisplayed()
    }
}
