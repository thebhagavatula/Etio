package com.etio.ot.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two small things that are load-bearing in different ways: the numbers the
 * diagnostics screen reports about itself, and the control tokens without which the
 * model does not answer in JSON at all.
 */
class TelemetryAndTemplateTest {

    @Before
    fun clean() = InferenceTelemetry.reset()

    // --- telemetry ------------------------------------------------------------

    @Test
    fun `a fresh snapshot claims nothing`() {
        val s = InferenceTelemetry.snapshot.value
        assertNull(s.backend)
        assertNull(s.engineLoadMs)
        assertTrue(s.calls.isEmpty())
        assertEquals(0, s.parseRetries)
    }

    @Test
    fun `a correction rate over zero records is null, not zero percent`() {
        assertNull(
            "0% would read as perfect when it actually means nothing happened yet",
            InferenceTelemetry.snapshot.value.correctionRate,
        )

        InferenceTelemetry.recordConfirmed(edited = false)
        assertEquals(0f, InferenceTelemetry.snapshot.value.correctionRate!!, 0.001f)
    }

    @Test
    fun `the correction rate counts every edit honestly`() {
        InferenceTelemetry.recordConfirmed(edited = true)
        InferenceTelemetry.recordConfirmed(edited = false)
        InferenceTelemetry.recordConfirmed(edited = true)
        InferenceTelemetry.recordConfirmed(edited = false)

        assertEquals(0.5f, InferenceTelemetry.snapshot.value.correctionRate!!, 0.001f)
    }

    @Test
    fun `median is per profile and ignores the other one`() {
        listOf(100L, 300L, 200L).forEach {
            InferenceTelemetry.call(InferenceTelemetry.Call("classify", it, 10, ok = true))
        }
        InferenceTelemetry.call(InferenceTelemetry.Call("draft", 9_000, 10, ok = true))

        assertEquals(200L, InferenceTelemetry.snapshot.value.medianMs("classify"))
        assertEquals(9_000L, InferenceTelemetry.snapshot.value.medianMs("draft"))
        assertNull(InferenceTelemetry.snapshot.value.medianMs("nothing"))
    }

    @Test
    fun `the last call is the most recent one for that profile`() {
        InferenceTelemetry.call(InferenceTelemetry.Call("classify", 100, 10, ok = true))
        InferenceTelemetry.call(InferenceTelemetry.Call("draft", 500, 10, ok = true))
        InferenceTelemetry.call(InferenceTelemetry.Call("classify", 250, 10, ok = true))

        assertEquals(250L, InferenceTelemetry.snapshot.value.lastMs("classify"))
    }

    @Test
    fun `the call log is bounded so a long day cannot grow without limit`() {
        repeat(200) {
            InferenceTelemetry.call(InferenceTelemetry.Call("classify", it.toLong(), 10, ok = true))
        }
        val calls = InferenceTelemetry.snapshot.value.calls
        assertTrue("bounded", calls.size <= 60)
        assertEquals("the newest are the ones kept", 199L, calls.last().elapsedMs)
    }

    @Test
    fun `grounding refusals and votes are tallied by name`() {
        InferenceTelemetry.groundingRejection("note")
        InferenceTelemetry.groundingRejection("note")
        InferenceTelemetry.groundingRejection("estimated_min")
        InferenceTelemetry.vote("HIGH")
        InferenceTelemetry.vote("LOW")
        InferenceTelemetry.vote("HIGH")

        val s = InferenceTelemetry.snapshot.value
        assertEquals(mapOf("note" to 2, "estimated_min" to 1), s.groundingRejections)
        assertEquals(mapOf("HIGH" to 2, "LOW" to 1), s.agreementBands)
    }

    @Test
    fun `priming records whether the prefix is actually cached`() {
        InferenceTelemetry.primed(1_200, mapOf("classify" to 957), prefixCached = false)

        val s = InferenceTelemetry.snapshot.value
        assertEquals(1_200L, s.primeMs)
        assertEquals(957, s.prefixTokens["classify"])
        assertEquals(false, s.prefixCached)
    }

    // --- chat template --------------------------------------------------------

    @Test
    fun `the two halves concatenate to exactly what wrap produces`() {
        val whole = GemmaChatTemplate.wrap("the body", modelPrefix = "JSON:")
        val split = GemmaChatTemplate.head("") + GemmaChatTemplate.tail("the body", "JSON:")
        assertEquals(whole, split)
    }

    @Test
    fun `the turn carries the control tokens the model was trained on`() {
        val turn = GemmaChatTemplate.wrap("hello", modelPrefix = "JSON:")
        assertTrue(turn, turn.contains("<start_of_turn>user"))
        assertTrue(turn, turn.contains("<end_of_turn>"))
        assertTrue(turn, turn.contains("<start_of_turn>model"))
        assertTrue("the prefix steers the continuation", turn.trimEnd().endsWith("JSON:"))
    }

    @Test
    fun `the stable half holds no per-call content`() {
        val head = GemmaChatTemplate.head("system text and examples")
        assertTrue(head.startsWith("<start_of_turn>user"))
        assertTrue("nothing closes the turn in the cacheable half", !head.contains("<end_of_turn>"))
    }

    @Test
    fun `head is byte-identical for identical stable content`() {
        assertEquals(GemmaChatTemplate.head("same"), GemmaChatTemplate.head("same"))
    }
}
