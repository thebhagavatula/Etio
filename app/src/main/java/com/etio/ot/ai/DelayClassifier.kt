package com.etio.ot.ai

import android.util.Log

/**
 * LLM Job 1 (PRD §9). One utterance in, one validated [DelayJsonValidator.Parsed] out.
 *
 * The prompt is built in two halves and handed to the engine that way. [stablePrefix]
 * is byte-identical on every call — system text, instruction, the code list, the
 * examples — so the engine can prime one session with it at start-up and clone that
 * primed cache per call. Only the utterance is decoded at classification time, which
 * is most of the difference between a 3s and a 6s classification.
 *
 * Nothing per-call may move into the prefix. The engine logs it if that ever happens.
 */
class DelayClassifier(
    private val engine: LlmEngine,
    private val config: PromptSource,
) {

    /**
     * Classify one utterance. Two inference calls at most, and the second one is
     * invisible: a model that returns prose instead of JSON usually returns JSON when
     * asked a second time with the instruction restated last, and a coordinator who
     * waited three seconds would rather wait six than correct a wrong record by hand.
     *
     * Beyond that we stop. A third attempt is a worse use of the time than handing her
     * an OTHER with her own words in the note and letting her fix it in two taps.
     */
    suspend fun classify(transcript: String): DelayJsonValidator.Parsed {
        // Null means the engine itself failed, which a retry cannot help: it fails the
        // same way a second later, and spending another three seconds proving that is
        // three seconds she is standing in front of a screen that says nothing.
        val first = attempt(transcript, retry = false)
            ?: return DelayJsonValidator.parse("", transcript)
        if (!first.parseFailed) return first

        InferenceTelemetry.parseRetry()
        Log.w(TAG, "Job 1 returned no usable JSON; retrying once")

        val second = attempt(transcript, retry = true)
            ?: return DelayJsonValidator.parse("", transcript)
        if (!second.parseFailed) return second

        InferenceTelemetry.parseFallback()
        Log.w(TAG, "Job 1 retry also unusable; falling back to OTHER")
        return second
    }

    /** Null when the engine call itself failed, as opposed to returning unusable text. */
    private suspend fun attempt(transcript: String, retry: Boolean): DelayJsonValidator.Parsed? {
        val cfg = config.prompts()
        val started = System.currentTimeMillis()

        val raw = engine.generate(
            profile = profile(),
            stablePrefix = stablePrefix(),
            variableSuffix = variableSuffix(transcript, terse = retry),
            maxTokens = cfg.classification.maxTokens,
        ).getOrElse {
            // Not a user-visible error — the caller turns this into an OTHER we ask
            // the coordinator to correct.
            Log.e(TAG, "Job 1 inference failed", it)
            return null
        }
        Log.i(TAG, "Job 1${if (retry) " (retry)" else ""} in ${System.currentTimeMillis() - started}ms")

        return DelayJsonValidator.parse(raw, transcript)
    }

    fun profile(): DecodeProfile {
        val cfg = config.prompts().classification
        return DecodeProfile(
            name = DecodeProfile.CLASSIFY,
            temperature = cfg.temperature,
            topK = cfg.topK,
        )
    }

    /**
     * Everything that does not depend on what was just said.
     *
     * The code list sits immediately before the output instruction rather than at the
     * top: a 1B model attends far more reliably to the constraint it read last, and
     * the codes are the constraint that actually has to hold.
     */
    fun stablePrefix(): String {
        val cfg = config.prompts()
        val taxonomy = config.taxonomy()

        val content = buildString {
            appendLine(cfg.systemPrefix)
            appendLine()
            appendLine("Departments you may use: ${taxonomy.departmentHints.joinToString(", ")}")
            appendLine()
            taxonomy.codes.forEach { appendLine("- ${it.code}: ${it.description}") }
            appendLine()
            cfg.classification.fewShot.forEach { ex ->
                appendLine("Coordinator: ${ex.transcript}")
                appendLine("JSON: ${ex.json}")
                appendLine()
            }
            appendLine("The only allowed values for \"code\" are, exactly:")
            appendLine(taxonomy.codes.joinToString(", ") { it.code })
            appendLine()
            append(cfg.classification.instruction)
        }
        return GemmaChatTemplate.head(content)
    }

    /** The one thing that changes per call. */
    /**
     * [terse] is the retry form: the same utterance with the format demand repeated
     * immediately before generation, which is where a 1B model is most likely to still
     * be holding it. The prefix is untouched, so the retry reuses the same primed cache.
     */
    fun variableSuffix(transcript: String, terse: Boolean = false): String {
        val body = buildString {
            append("\n\nCoordinator: ")
            append(transcript.trim())
            if (terse) append("\n\nRespond with JSON only.")
        }
        return GemmaChatTemplate.tail(body, modelPrefix = "JSON:")
    }

    private companion object { const val TAG = "DelayClassifier" }
}
