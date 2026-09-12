package com.etio.ot.ai

import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Audience
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Job 2 prompt building and output cleanup (PRD §9). Mirrors the coverage style of
 * [DelayClassifierTest]: what goes into the model per audience, and what the
 * deterministic fallback/cleanup does when the model can't be trusted.
 */
class MessageDrafterTest {

    private val engine = RecordingLlmEngine()
    private val drafter = MessageDrafter(engine, fakePromptSource())

    private val case = CaseEntity(
        id = "c1",
        caseNumber = "3",
        theatreId = "T1",
        procedureName = "Lap chole",
        surgeon = "Dr X",
        scheduledStartMs = 0L,
        scheduledDurationMin = 60,
        orderIndex = 0,
    )

    private fun record(estimatedMin: Int? = 40) = DelayRecordEntity(
        id = "d1",
        caseId = "c1",
        createdAtMs = 0L,
        transcriptRaw = "set came back wet, CSSD says forty minutes",
        code = DelayCode.STERILE_SET_UNAVAILABLE,
        attributedDept = "CSSD",
        avoidable = Avoidability.AVOIDABLE,
        estimatedMin = estimatedMin,
        note = "Set returned wet; CSSD reprocessing",
        modelConfidence = 0.9f,
    )

    // --- prompt building -----------------------------------------------------

    @Test
    fun `prompt carries the audience rule for an audience that has one`() = runTest {
        engine.queueSuccess("draft")

        drafter.draftOne(Audience.SURGEON, record(), case)

        val prompt = engine.calls.single().prompt
        val rule = fakePrompts().drafting.audiences.getValue("SURGEON")
        assertTrue(prompt.contains("Audience: ${Audience.SURGEON.display}"))
        assertTrue(prompt.contains("Register: ${rule.register}"))
        assertTrue(prompt.contains("Must include: ${rule.include.joinToString("; ")}"))
        assertTrue(prompt.contains("Must NOT include: ${rule.exclude.joinToString("; ")}"))
        assertTrue(prompt.contains(rule.example!!))
    }

    @Test
    fun `an audience with no configured rule still gets a prompt, just without the rule block`() = runTest {
        engine.queueSuccess("draft")

        drafter.draftOne(Audience.WARD, record(), case)

        val prompt = engine.calls.single().prompt
        assertTrue(prompt.contains("Audience: ${Audience.WARD.display}"))
        assertFalse(prompt.contains("Register:"))
        assertFalse(prompt.contains("Must include:"))
    }

    @Test
    fun `prompt grounds the delay record fields, including a null estimate`() = runTest {
        engine.queueSuccess("draft")

        drafter.draftOne(Audience.SURGEON, record(estimatedMin = null), case)

        val prompt = engine.calls.single().prompt
        assertTrue(prompt.contains("case 3 (Lap chole) in theatre T1"))
        assertTrue(prompt.contains("Cause: ${DelayCode.STERILE_SET_UNAVAILABLE.display}, attributed to CSSD"))
        assertTrue(prompt.contains("Set returned wet; CSSD reprocessing"))
        assertTrue(prompt.contains("not stated — do not invent a number"))
        assertTrue(prompt.trimEnd().endsWith("Message for ${Audience.SURGEON.display}:"))
    }

    @Test
    fun `the facts are one flowing sentence, not a bulleted field list`() = runTest {
        // On-device finding: a model asked to continue right after a bulleted field
        // list tends to just add more bullets instead of writing prose. Pin the
        // absence of that shape so nobody reintroduces it.
        engine.queueSuccess("draft")

        drafter.draftOne(Audience.SURGEON, record(), case)

        val prompt = engine.calls.single().prompt
        assertFalse(prompt.contains("- Case:"))
        assertFalse(prompt.contains("- Theatre:"))
        assertFalse(prompt.contains("- Department:"))
    }

    @Test
    fun `a spoken duration is rendered as minutes, never invented`() = runTest {
        engine.queueSuccess("draft")

        drafter.draftOne(Audience.SURGEON, record(estimatedMin = 40), case)

        assertTrue(engine.calls.single().prompt.contains("Expected delay: 40 minutes"))
    }

    @Test
    fun `a missing case falls back to placeholder text rather than a null crash`() = runTest {
        engine.queueSuccess("draft")

        drafter.draftOne(Audience.SURGEON, record(), case = null)

        val prompt = engine.calls.single().prompt
        assertTrue(prompt.contains("case ? (procedure) in theatre ?"))
    }

    @Test
    fun `sampling params come from the drafting config, not the classification config`() = runTest {
        engine.queueSuccess("draft")

        drafter.draftOne(Audience.SURGEON, record(), case)

        val call = engine.calls.single()
        val cfg = fakePrompts().drafting
        assertEquals(cfg.maxTokens, call.maxTokens)
        assertEquals(cfg.temperature, call.temperature)
        assertEquals(cfg.topK, call.topK)
    }

    // --- draftAll: sequencing --------------------------------------------------

    @Test
    fun `draftAll emits all four audiences in demo order with one call each`() = runTest {
        Audience.demoOrder.forEach { engine.queueSuccess("draft for ${it.name}") }

        val drafts = drafter.draftAll(record(), case).toList()

        assertEquals(Audience.demoOrder, drafts.map { it.audience })
        assertEquals(4, engine.calls.size)
        engine.calls.forEachIndexed { i, call ->
            assertTrue(call.prompt.contains("Audience: ${Audience.demoOrder[i].display}"))
        }
    }

    // --- output cleanup ----------------------------------------------------

    @Test
    fun `a markdown fence and a leaked Audience line are stripped`() = runTest {
        engine.queueSuccess(
            "```\nCase 3 knife approx 40 min late. Set wet, CSSD reprocessing. Will confirm.\nAudience: Surgeon\n```",
        )

        val body = drafter.draftOne(Audience.SURGEON, record(), case)

        assertEquals("Case 3 knife approx 40 min late. Set wet, CSSD reprocessing. Will confirm.", body)
    }

    @Test
    fun `a Message label and surrounding quotes are stripped`() = runTest {
        engine.queueSuccess("Message: \"Case 3 knife approx 40 min late.\"")

        val body = drafter.draftOne(Audience.SURGEON, record(), case)

        assertEquals("Case 3 knife approx 40 min late.", body)
    }

    // --- fallback on inference failure --------------------------------------

    @Test
    fun `inference failure falls back to a deterministic body per audience, not a blank one`() = runTest {
        engine.queueFailure()
        val surgeon = drafter.draftOne(Audience.SURGEON, record(), case)

        assertEquals("Case 3 delayed about 40 minutes. Sterile set unavailable.", surgeon)
    }

    @Test
    fun `fallback never invents a duration either`() = runTest {
        engine.queueFailure()

        val surgeon = drafter.draftOne(Audience.SURGEON, record(estimatedMin = null), case)

        assertEquals("Case 3 delayed an unknown amount of time. Sterile set unavailable.", surgeon)
    }

    @Test
    fun `fallback for every audience via draftAll when every call fails`() = runTest {
        repeat(4) { engine.queueFailure() }

        val drafts = drafter.draftAll(record(), case).toList().associate { it.audience to it.body }

        assertEquals("Case 3 delayed about 40 minutes. Sterile set unavailable.", drafts.getValue(Audience.SURGEON))
        assertEquals(
            "A short update: your family member is safe and still on today's list. " +
                "The theatre team needs a little more time, so we expect to start about 40 minutes later than planned. " +
                "We will update you as soon as they go in.",
            drafts.getValue(Audience.FAMILY),
        )
        assertEquals(
            "Case 3 delayed about 40 minutes — Sterile set unavailable. Hold the send-for until confirmed.",
            drafts.getValue(Audience.WARD),
        )
        assertEquals(
            "Case 3 induction delayed about 40 minutes — Sterile set unavailable (CSSD).",
            drafts.getValue(Audience.ANAESTHESIA),
        )
    }

    // --- duration-consistency guard: Job 2's equivalent of DelayJsonValidator -----

    @Test
    fun `a hallucinated duration is rejected in favour of the deterministic fallback`() = runTest {
        // Reproduces an on-device finding verbatim: a FAMILY draft said this while
        // the record's actual estimatedMin was 40 - spelled out, not "4".
        engine.queueSuccess(
            "Your family member's surgery has been delayed by approximately four hours. " +
                "They anticipate resuming in around 40 minutes.",
        )

        val body = drafter.draftOne(Audience.FAMILY, record(estimatedMin = 40), case)

        assertEquals(
            "A short update: your family member is safe and still on today's list. " +
                "The theatre team needs a little more time, so we expect to start about 40 minutes later than planned. " +
                "We will update you as soon as they go in.",
            body,
        )
    }

    @Test
    fun `a duration matching the record passes through untouched`() = runTest {
        engine.queueSuccess("Case 3 knife approx 40 min late. Set wet, CSSD reprocessing. Will confirm.")

        val body = drafter.draftOne(Audience.SURGEON, record(estimatedMin = 40), case)

        assertEquals("Case 3 knife approx 40 min late. Set wet, CSSD reprocessing. Will confirm.", body)
    }

    @Test
    fun `an hour mention is converted to minutes before comparing`() = runTest {
        engine.queueSuccess("Delayed for 2 hours total.")

        // 2 hours = 120 minutes, disagrees with the record's 40 - falls back.
        val body = drafter.draftOne(Audience.SURGEON, record(estimatedMin = 40), case)

        assertEquals("Case 3 delayed about 40 minutes. Sterile set unavailable.", body)
    }

    @Test
    fun `no duration mentioned at all is fine regardless of the record`() = runTest {
        engine.queueSuccess("Case 3 on hold, sterile set being reprocessed. Will confirm once ready.")

        val body = drafter.draftOne(Audience.SURGEON, record(estimatedMin = 40), case)

        assertEquals("Case 3 on hold, sterile set being reprocessed. Will confirm once ready.", body)
    }

    @Test
    fun `the duration guard applies per-audience inside draftAll too`() = runTest {
        engine.queueSuccess("Delayed for 2 hours total.") // SURGEON: hallucinated - record says 40 min
        engine.queueSuccess("Still finishing up, no time yet.") // FAMILY: no mention, fine
        engine.queueSuccess("On hold, 40 minutes expected.") // WARD: matches, fine
        engine.queueSuccess("Induction pushed back.") // ANAESTHESIA: no mention, fine

        val drafts = drafter.draftAll(record(estimatedMin = 40), case).toList().associate { it.audience to it.body }

        assertEquals("Case 3 delayed about 40 minutes. Sterile set unavailable.", drafts.getValue(Audience.SURGEON))
        assertEquals("Still finishing up, no time yet.", drafts.getValue(Audience.FAMILY))
        assertEquals("On hold, 40 minutes expected.", drafts.getValue(Audience.WARD))
        assertEquals("Induction pushed back.", drafts.getValue(Audience.ANAESTHESIA))
    }
}
