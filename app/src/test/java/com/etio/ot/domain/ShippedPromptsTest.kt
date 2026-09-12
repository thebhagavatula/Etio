package com.etio.ot.domain

import com.etio.ot.ai.DelayJsonValidator
import com.etio.ot.data.model.DelayCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The prompt files are tuned by hand on a phone during Red Light hours, with no
 * compiler in the loop. These tests are the compiler for that edit.
 *
 * The one that matters is [every few-shot example survives its own validator]. An
 * example is a promise: "say this, get that back". If the validator would strip a
 * field the example teaches, the prompt is training the model toward an output the
 * app then silently undoes — which is invisible on device and shows up only as a
 * quietly missing ETA on stage. That exact drift was real: "maybe another half hour"
 * taught estimated_min 30 while the duration check required a digit or a numeral,
 * so the 30 was dropped on every such utterance.
 */
class ShippedPromptsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Unit tests run with the module directory as the working directory. */
    private fun asset(name: String) = File("src/main/assets/config/$name")
        .also { assertTrue("missing asset ${it.path}", it.exists()) }
        .readText()

    private val prompts: JsonObject get() = json.parseToJsonElement(asset("prompts.json")).jsonObject
    private val taxonomy: JsonObject get() = json.parseToJsonElement(asset("taxonomy.json")).jsonObject

    private val fewShot
        get() = prompts["classification"]!!.jsonObject["few_shot"]!!.jsonArray.map { it.jsonObject }

    private fun exampleJson(i: Int): JsonObject =
        json.parseToJsonElement(fewShot[i]["json"]!!.jsonPrimitive.content).jsonObject

    @Test
    fun `every few-shot example survives its own validator`() {
        fewShot.indices.forEach { i ->
            val transcript = fewShot[i]["transcript"]!!.jsonPrimitive.content
            val taught = exampleJson(i)
            val parsed = DelayJsonValidator.parse(taught.toString(), transcript)

            val taughtCode = taught["code"]!!.jsonPrimitive.content
            assertEquals("example $i: code changed by the validator", taughtCode, parsed.code.name)

            val taughtMin = taught["estimated_min"]!!.jsonPrimitive.content.toIntOrNull()
            assertEquals(
                "example $i taught estimated_min=$taughtMin but the validator returned " +
                    "${parsed.estimatedMin} for \"$transcript\" — the example and the duration " +
                    "check disagree, so the model is being trained toward a field the app strips",
                taughtMin,
                parsed.estimatedMin,
            )
            assertTrue("example $i: note was emptied", parsed.note.isNotBlank())
        }
    }

    @Test
    fun `every few-shot code is a real taxonomy value`() {
        val allowed = taxonomy["codes"]!!.jsonArray
            .map { it.jsonObject["code"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals("taxonomy.json and the DelayCode enum have drifted", DelayCode.entries.size, allowed.size)
        DelayCode.entries.forEach { assertTrue("taxonomy.json is missing ${it.name}", it.name in allowed) }

        fewShot.indices.forEach { i ->
            val code = exampleJson(i)["code"]!!.jsonPrimitive.content
            assertTrue("example $i uses unknown code $code", code in allowed)
        }
    }

    @Test
    fun `every few-shot department is one the prompt offers`() {
        val hints = taxonomy["department_hints"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        fewShot.indices.forEach { i ->
            val dept = exampleJson(i)["attributed_dept"]!!.jsonPrimitive.content
            assertTrue("example $i attributes to '$dept', which is not offered to the model", dept in hints)
        }
    }

    @Test
    fun `the examples cover the delays that actually stop a list`() {
        val codes = fewShot.indices.map { exampleJson(it)["code"]!!.jsonPrimitive.content }.toSet()
        listOf(
            "STERILE_SET_UNAVAILABLE",
            "SURGEON_LATE",
            "PATIENT_NOT_READY",
            "PORTER_TRANSPORT",
            "PREVIOUS_CASE_OVERRUN",
            "EQUIPMENT_FAILURE",
        ).forEach { assertTrue("no worked example for $it", it in codes) }

        // OTHER has to be shown being chosen. A model that has never seen it picked
        // reaches for a wrong specific code instead of admitting the gap.
        assertTrue("no example teaches that OTHER is an acceptable answer", "OTHER" in codes)
    }

    @Test
    fun `at least one example has no spoken duration and a null estimate`() {
        val nullEstimates = fewShot.indices.count {
            exampleJson(it)["estimated_min"]!!.jsonPrimitive.content == "null"
        }
        // The most common hallucination in this task is a confident invented number.
        assertTrue("nothing teaches the model to leave estimated_min null", nullEstimates >= 1)
    }

    @Test
    fun `the adversarial example is not also a confident one`() {
        val other = fewShot.indices.firstOrNull {
            exampleJson(it)["code"]!!.jsonPrimitive.content == "OTHER"
        }
        assertNotNull("no OTHER example to check", other)
        val confidence = exampleJson(other!!)["confidence"]!!.jsonPrimitive.content.toFloat()
        // OTHER paired with high confidence teaches the wrong lesson twice over.
        assertTrue("the OTHER example claims confidence $confidence", confidence < 0.6f)
    }

    @Test
    fun `classification decodes greedily and drafting does not`() {
        val classification = prompts["classification"]!!.jsonObject
        val drafting = prompts["drafting"]!!.jsonObject

        // Job 1 is not a creative task; sampling is run-to-run drift on stage.
        assertEquals(1, classification["top_k"]!!.jsonPrimitive.content.toInt())
        assertTrue(classification["temperature"]!!.jsonPrimitive.content.toFloat() <= 0.1f)
        assertEquals(128, classification["max_tokens"]!!.jsonPrimitive.content.toInt())

        // Job 2 should read as written rather than templated.
        assertTrue(drafting["temperature"]!!.jsonPrimitive.content.toFloat() >= 0.5f)
        assertEquals(200, drafting["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the classification prompt still fits the token budget`() {
        // This is the test that would have caught the crash.
        //
        // Going over the model's token budget does not raise a Kotlin exception:
        // MediaPipe leaves one pending and the JNI layer aborts the process, so the
        // app disappears with no stack trace in Logcat that points here. Growing
        // few-shot from four examples to seven is exactly how it happened.
        //
        // The budget is 2048 and cannot simply be raised — above that the GPU
        // refuses to load at all ("maximum cache size supported: 2048") and the whole
        // app silently drops to the CPU backend, where a decode takes three times as
        // long. So the prompt is what has to give.
        //
        // Measured on device: this prefix is 4476 chars and 957 tokens, about 4.7
        // chars per token. The 3.5 used here is deliberately pessimistic, so the test
        // trips well before the process would.
        val prefix = buildString {
            appendLine(prompts["system_prefix"]!!.jsonPrimitive.content)
            val tax = taxonomy
            tax["department_hints"]!!.jsonArray.forEach { appendLine(it.jsonPrimitive.content) }
            tax["codes"]!!.jsonArray.forEach {
                appendLine(it.jsonObject["code"]!!.jsonPrimitive.content)
                appendLine(it.jsonObject["description"]!!.jsonPrimitive.content)
            }
            fewShot.forEach {
                appendLine(it["transcript"]!!.jsonPrimitive.content)
                appendLine(it["json"]!!.jsonPrimitive.content)
            }
            appendLine(prompts["classification"]!!.jsonObject["instruction"]!!.jsonPrimitive.content)
        }

        val pessimisticTokens = prefix.length / 3.5
        assertTrue(
            "The classification prefix is ${prefix.length} chars (~${pessimisticTokens.toInt()} tokens " +
                "at a pessimistic 3.5 chars/token) against a $PREFIX_CEILING_TOKENS ceiling. " +
                "Overflowing aborts the process rather than throwing. Remove a few-shot example.",
            pessimisticTokens < PREFIX_CEILING_TOKENS,
        )
    }

    private companion object {
        /** MediaPipeLlmEngine: 2048 budget, less room for the utterance and the reply. */
        const val PREFIX_CEILING_TOKENS = 2048 - 256 - 256
    }
}
