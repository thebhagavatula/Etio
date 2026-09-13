package com.etio.ot.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.ui.delay.DelayReviewCard
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The card that carries the model's answer, and the evidence for it.
 *
 * The two properties worth defending here are both about honesty: the transcript is
 * always on screen under the fields, and a field the transcript did not support says
 * so rather than looking the same as one it did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DelayReviewCardUiTest {

    @get:Rule val compose = createComposeRule()

    @Before fun setUp() = UiTestSupport.initServiceLocator()

    private fun record(
        code: DelayCode = DelayCode.STERILE_SET_UNAVAILABLE,
        dept: String = "CSSD",
        min: Int? = 40,
        note: String = "Set came back wet",
        avoidable: Avoidability = Avoidability.AVOIDABLE,
        transcript: String = "the set came back wet, cssd says 40 minutes",
        noteGrounded: Boolean = true,
        minGrounded: Boolean = true,
        deptGrounded: Boolean = true,
        fellBack: Boolean = false,
    ) = DelayRecordEntity(
        id = "d1",
        caseId = "c1",
        createdAtMs = 1_700_000_000_000L,
        transcriptRaw = transcript,
        code = code,
        attributedDept = dept,
        avoidable = avoidable,
        estimatedMin = min,
        note = note,
        modelConfidence = 0.9f,
        noteGrounded = noteGrounded,
        estimatedMinGrounded = minGrounded,
        deptGrounded = deptGrounded,
        fellBackToOther = fellBack,
    )

    private fun render(
        record: DelayRecordEntity,
        onCodeChange: (DelayCode) -> Unit = {},
        onDiscard: () -> Unit = {},
        onNotify: () -> Unit = {},
        onTranscriptCorrected: (String) -> Unit = {},
    ) {
        compose.setEtioContent {
            DelayReviewCard(
                record = record,
                onCodeChange = onCodeChange,
                onDeptChange = {},
                onAvoidableChange = {},
                onEstimateChange = {},
                onNoteChange = {},
                onTranscriptCorrected = onTranscriptCorrected,
                onDiscard = onDiscard,
                onNotify = onNotify,
            )
        }
    }

    @Test
    fun `every structured field is on the card`() {
        render(record())

        compose.onNodeWithText("Sterile set unavailable").assertIsDisplayed()
        compose.onNodeWithText("CSSD").assertIsDisplayed()
        compose.onNodeWithText("40 min").assertIsDisplayed()
        compose.onNodeWithText("Set came back wet").assertIsDisplayed()
    }

    @Test
    fun `the transcript is on screen under the fields, not behind a disclosure`() {
        render(record())

        compose.onNodeWithText("the set came back wet, cssd says 40 minutes").assertIsDisplayed()
    }

    @Test
    fun `a grounded field carries the heard mark`() {
        render(record())

        compose.onAllNodesWithText("✓ HEARD")[0].assertIsDisplayed()
    }

    @Test
    fun `an ungrounded note is marked as not heard`() {
        render(record(noteGrounded = false))

        compose.onNodeWithText("NOT HEARD").assertIsDisplayed()
    }

    @Test
    fun `a duration nobody claimed shows no verdict at all`() {
        render(record(min = null))

        compose.onNodeWithText("Not stated").assertIsDisplayed()
    }

    @Test
    fun `every field has its own edit target`() {
        render(record())

        listOf("Cause", "Department", "Avoidable", "Expected delay", "Note").forEach {
            compose.onNodeWithContentDescription("Edit $it").assertIsDisplayed()
        }
    }

    @Test
    fun `editing the cause offers the whole taxonomy and reports the choice`() {
        var chosen: DelayCode? = null
        render(record(), onCodeChange = { chosen = it })

        compose.onNodeWithContentDescription("Edit Cause").performClick()
        compose.onNodeWithText("Surgeon late").performClick()

        assertEquals(DelayCode.SURGEON_LATE, chosen)
    }

    @Test
    fun `a record the model could not place asks her to pick the cause`() {
        render(record(code = DelayCode.OTHER, fellBack = true))

        compose.onNodeWithText("Couldn't place this one — pick the cause").assertIsDisplayed()
    }

    @Test
    fun `notify and discard are both reachable and distinct`() {
        var notified = 0
        var discarded = 0
        render(record(), onNotify = { notified++ }, onDiscard = { discarded++ })

        compose.onNodeWithText("Notify").performClick()
        assertEquals(1, notified)
        assertEquals(0, discarded)

        compose.onNodeWithText("Discard").performClick()
        assertEquals(1, discarded)
    }

    @Test
    fun `correcting the wording is offered, and re-reads on demand`() {
        var corrected: String? = null
        render(record(), onTranscriptCorrected = { corrected = it })

        compose.onNodeWithText("Fix wording").performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed()

        assertEquals("nothing is re-read until she asks", null, corrected)
    }
}
