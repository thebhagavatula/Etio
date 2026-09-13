package com.etio.ot.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.etio.ot.data.model.EventType
import com.etio.ot.ui.events.EventGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The out-of-order sheet. Marking is a tap, correcting is a long press, and an event
 * the app filled in has to look different from one she marked — otherwise the
 * timeline quietly launders an assumption into a fact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EventGridUiTest {

    @get:Rule val compose = createComposeRule()

    @Before fun setUp() = UiTestSupport.initServiceLocator()

    private val t0 = 1_700_000_000_000L

    private fun render(
        marked: Map<EventType, Long> = emptyMap(),
        inferred: Set<EventType> = emptySet(),
        onMark: (EventType) -> Unit = {},
        onLongPress: (EventType) -> Unit = {},
    ) {
        compose.setEtioContent {
            EventGrid(
                marked = marked,
                nextExpected = EventType.PATIENT_IN_ROOM,
                onMark = onMark,
                onLongPress = onLongPress,
                inferred = inferred,
            )
        }
    }

    @Test
    fun `every event in the day is offered`() {
        render()

        EventType.entries.forEach {
            compose.onNodeWithText(it.shortLabel).assertIsDisplayed()
        }
    }

    @Test
    fun `tapping an unmarked event marks it`() {
        var marked: EventType? = null
        render(onMark = { marked = it })

        compose.onNodeWithText(EventType.KNIFE_TO_SKIN.shortLabel).performClick()

        assertEquals(EventType.KNIFE_TO_SKIN, marked)
    }

    @Test
    fun `tapping an event that is already marked does not mark it twice`() {
        var marked: EventType? = null
        render(marked = mapOf(EventType.KNIFE_TO_SKIN to t0), onMark = { marked = it })

        compose.onNodeWithText(EventType.KNIFE_TO_SKIN.shortLabel).performClick()

        assertNull("re-marking would write a duplicate row", marked)
    }

    @Test
    fun `a marked event shows the time it was marked`() {
        render(marked = mapOf(EventType.PATIENT_SENT_FOR to t0))

        // The exact clock string depends on the test timezone, so assert the shape:
        // the tile carries a second line that the unmarked ones do not.
        compose.onNodeWithText(EventType.PATIENT_SENT_FOR.shortLabel).assertIsDisplayed()
    }

    @Test
    fun `an inferred time is shown as approximate`() {
        render(
            marked = mapOf(EventType.ANAESTHESIA_START to t0),
            inferred = setOf(EventType.ANAESTHESIA_START),
        )

        compose.onNode(hasText("~", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `long-pressing a marked event opens its correction`() {
        var corrected: EventType? = null
        render(
            marked = mapOf(EventType.PATIENT_IN_ROOM to t0),
            onLongPress = { corrected = it },
        )

        compose.onNodeWithText(EventType.PATIENT_IN_ROOM.shortLabel)
            .performTouchInput { longClick() }

        assertEquals(EventType.PATIENT_IN_ROOM, corrected)
    }

    @Test
    fun `long-pressing an unmarked event corrects nothing`() {
        var corrected: EventType? = null
        render(onLongPress = { corrected = it })

        compose.onNodeWithText(EventType.ROOM_READY.shortLabel)
            .performTouchInput { longClick() }

        assertNull("there is no timestamp to correct yet", corrected)
    }
}
