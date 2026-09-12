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

    suspend fun classify(transcript: String): DelayJsonValidator.Parsed {
        val cfg = config.prompts()
        val profile = profile()

        val started = System.currentTimeMillis()
        val raw = engine.generate(
            profile = profile,
            stablePrefix = stablePrefix(),
            variableSuffix = variableSuffix(transcript),
            maxTokens = cfg.classification.maxTokens,
        ).getOrElse {
            Log.e(TAG, "Job 1 inference failed", it)
            // Inference failure is not a user-visible error — it is an OTHER we ask
            // the coordinator to correct.
            return DelayJsonValidator.parse("", transcript)
        }
        Log.i(TAG, "Job 1 in ${System.currentTimeMillis() - started}ms")

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
    fun variableSuffix(transcript: String): String =
        GemmaChatTemplate.tail("\n\nCoordinator: ${transcript.trim()}", modelPrefix = "JSON:")

    private companion object { const val TAG = "DelayClassifier" }
}
