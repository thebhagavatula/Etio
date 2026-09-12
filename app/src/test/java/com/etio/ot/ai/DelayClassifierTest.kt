package com.etio.ot.ai

import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Job 1 prompt building (PRD §9). The validator already has its own coverage
 * ([com.etio.ot.domain.DelayJsonValidatorTest]) for parsing — these tests are about
 * what goes INTO the model: the stable prefix, the taxonomy, and the sampling
 * params actually reaching [LlmEngine.generate].
 */
class DelayClassifierTest {

    private val engine = RecordingLlmEngine()
    private val classifier = DelayClassifier(engine, fakePromptSource())

    @Test
    fun `prompt carries the stable prefix and instruction verbatim`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        classifier.classify("set came back wet")

        val prompt = engine.calls.single().prompt
        assertTrue(prompt.contains(fakePrompts().systemPrefix))
        assertTrue(prompt.contains(fakePrompts().classification.instruction))
    }

    @Test
    fun `prompt lists every taxonomy code with its description`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        classifier.classify("anything")

        val prompt = engine.calls.single().prompt
        fakeTaxonomy().codes.forEach { entry ->
            assertTrue("missing code ${entry.code}", prompt.contains(entry.code))
            assertTrue("missing description for ${entry.code}", prompt.contains(entry.description))
        }
        assertTrue(prompt.contains("Departments you may use: CSSD, Surgery, Ward, Unattributed"))
    }

    @Test
    fun `prompt includes each few-shot example`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        classifier.classify("anything")

        val prompt = engine.calls.single().prompt
        val example = fakePrompts().classification.fewShot.single()
        assertTrue(prompt.contains(example.transcript))
        assertTrue(prompt.contains(example.json))
    }

    @Test
    fun `prompt ends with the trimmed transcript and a JSON cue`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        classifier.classify("  set came back wet  ")

        val prompt = engine.calls.single().prompt
        assertTrue(prompt.trimEnd().endsWith("JSON:"))
        assertTrue(prompt.contains("Coordinator: set came back wet"))
        assertFalse("must not leak the untrimmed transcript", prompt.contains("Coordinator:   set"))
    }

    @Test
    fun `sampling params come from the classification config, not the drafting config`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        classifier.classify("anything")

        val call = engine.calls.single()
        val cfg = fakePrompts().classification
        assertEquals(cfg.maxTokens, call.maxTokens)
        assertEquals(cfg.temperature, call.temperature)
        assertEquals(cfg.topK, call.topK)
    }

    @Test
    fun `a well-formed model reply comes back parsed`() = runTest {
        engine.queueSuccess(
            """{"code":"STERILE_SET_UNAVAILABLE","attributed_dept":"CSSD","avoidable":true,"estimated_min":40,"note":"Set wet","confidence":0.9}""",
        )

        val parsed = classifier.classify("set came back wet, CSSD says 40 minutes")

        assertEquals(DelayCode.STERILE_SET_UNAVAILABLE, parsed.code)
        assertEquals("CSSD", parsed.attributedDept)
        assertEquals(Avoidability.AVOIDABLE, parsed.avoidable)
        assertEquals(40, parsed.estimatedMin)
        assertFalse(parsed.fellBack)
    }

    @Test
    fun `inference failure falls back to OTHER instead of throwing or blanking the note`() = runTest {
        engine.queueFailure()

        val parsed = classifier.classify("set came back wet")

        assertEquals(DelayCode.OTHER, parsed.code)
        assertEquals("set came back wet", parsed.note)
        assertTrue(parsed.fellBack)
        assertEquals(0f, parsed.confidence, 0.001f)
    }

    @Test
    fun `the stable prefix is byte-identical across different transcripts`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        classifier.classify("set came back wet, CSSD says forty minutes")
        classifier.classify("porter has not come, patient still upstairs")

        val (first, second) = engine.calls
        // This is the whole session-hygiene claim: if the prefix ever varies, the
        // primed KV cache is discarded and every classification pays for the full
        // prompt again. Byte-identical, not merely equivalent.
        assertEquals(first.stablePrefix, second.stablePrefix)
        assertNotEquals(first.variableSuffix, second.variableSuffix)
    }

    @Test
    fun `the transcript never leaks into the stable prefix`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        // Deliberately unlike any few-shot example, so a hit is a real leak rather
        // than an overlap with the fixture's own sample utterances.
        val utterance = "quaffle trolley jammed in lift seven"
        classifier.classify(utterance)

        val call = engine.calls.single()
        assertFalse(
            "transcript found in the cached prefix — the cache would be rebuilt every call",
            call.stablePrefix.contains(utterance),
        )
        assertTrue(call.variableSuffix.contains(utterance))
    }

    @Test
    fun `the allowed code list is restated immediately before the output instruction`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        classifier.classify("anything")

        val prefix = engine.calls.single().stablePrefix
        val codeList = prefix.indexOf("The only allowed values for \"code\" are, exactly:")
        val instruction = prefix.indexOf(fakePrompts().classification.instruction)
        assertTrue("code list missing from the prefix", codeList >= 0)
        // A 1B model attends to the last constraint it read; the codes have to be it.
        assertTrue("code list must come after the examples and before the instruction", codeList < instruction)
    }

    @Test
    fun `the classify profile is greedy and comes from config`() = runTest {
        engine.queueSuccess("""{"code":"OTHER","note":"x"}""")

        classifier.classify("anything")

        val profile = engine.calls.single().profile
        assertEquals(DecodeProfile.CLASSIFY, profile.name)
        assertEquals(fakePrompts().classification.temperature, profile.temperature, 0.0001f)
        assertEquals(fakePrompts().classification.topK, profile.topK)
    }
}
