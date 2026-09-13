package com.etio.ot.ui

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.etio.ot.data.model.EventType
import com.etio.ot.ui.caselist.NextEventBar
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one control she uses while walking. Its label has to be the next event, its
 * target has to be big enough for a gloved thumb, and the mic beside it has to be a
 * separate target rather than part of the same tap area.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NextEventBarUiTest {

    @get:Rule val compose = createComposeRule()

    @Before fun setUp() = UiTestSupport.initServiceLocator()

    @Test
    fun `the button is labelled with the event that comes next`() {
        compose.setEtioContent {
            NextEventBar(
                caseNumber = "3",
                nextEvent = EventType.KNIFE_TO_SKIN,
                onMark = {},
                onOtherEvent = {},
                onRecord = {},
            )
        }

        compose.onNodeWithText("Knife to skin").assertIsDisplayed()
    }

    @Test
    fun `the primary target clears the gloved-thumb minimum`() {
        compose.setEtioContent {
            NextEventBar(
                caseNumber = "3",
                nextEvent = EventType.PATIENT_OUT,
                onMark = {},
                onOtherEvent = {},
                onRecord = {},
            )
        }

        compose.onNodeWithText("Patient out")
            .assertHasClickAction()
            .assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun `tapping marks exactly the event on the label`() {
        var marked: EventType? = null
        compose.setEtioContent {
            NextEventBar(
                caseNumber = "3",
                nextEvent = EventType.ANAESTHESIA_START,
                onMark = { marked = it },
                onOtherEvent = {},
                onRecord = {},
            )
        }

        compose.onNodeWithText("Anaesthesia start").performClick()

        assertEquals(EventType.ANAESTHESIA_START, marked)
    }

    @Test
    fun `the mic is its own target, not part of the primary one`() {
        var recorded = 0
        var marked = 0
        compose.setEtioContent {
            NextEventBar(
                caseNumber = "3",
                nextEvent = EventType.KNIFE_TO_SKIN,
                onMark = { marked++ },
                onOtherEvent = {},
                onRecord = { recorded++ },
            )
        }

        compose.onNodeWithContentDescription("Say what's holding it up").performClick()

        assertEquals(1, recorded)
        assertEquals("marking must not fire from the mic", 0, marked)
    }

    @Test
    fun `the out-of-order sheet is one tap away`() {
        var opened = 0
        compose.setEtioContent {
            NextEventBar(
                caseNumber = "3",
                nextEvent = EventType.KNIFE_TO_SKIN,
                onMark = {},
                onOtherEvent = { opened++ },
                onRecord = {},
            )
        }

        compose.onNodeWithText("Other event").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a fully marked case says so instead of offering a button`() {
        compose.setEtioContent {
            NextEventBar(
                caseNumber = "3",
                nextEvent = null,
                onMark = {},
                onOtherEvent = {},
                onRecord = {},
            )
        }

        compose.onNodeWithText("Case 3 fully marked").assertIsDisplayed()
    }

    @Test
    fun `the send-for offer appears only when there is one, and answers in one tap`() {
        var sentFor = 0
        compose.setEtioContent {
            NextEventBar(
                caseNumber = "3",
                nextEvent = EventType.ROOM_READY,
                onMark = {},
                onOtherEvent = {},
                onRecord = {},
                sendForLabel = "Send for case 4?",
                onSendFor = { sentFor++ },
            )
        }

        compose.onNodeWithText("Send for case 4?").assertIsDisplayed()
        compose.onNodeWithText("Send for").performClick()

        assertEquals(1, sentFor)
    }

    @Test
    fun `dismissing the send-for writes nothing`() {
        var sentFor = 0
        var dismissed = 0
        compose.setEtioContent {
            NextEventBar(
                caseNumber = "3",
                nextEvent = EventType.ROOM_READY,
                onMark = {},
                onOtherEvent = {},
                onRecord = {},
                sendForLabel = "Send for case 4?",
                onSendFor = { sentFor++ },
                onDismissSendFor = { dismissed++ },
            )
        }

        compose.onNodeWithText("Not yet").performClick()

        assertEquals(1, dismissed)
        assertEquals(0, sentFor)
    }
}
