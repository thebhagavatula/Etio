package com.etio.ot.ai

import android.util.Log

/**
 * LLM Job 1 (PRD §9). One utterance in, one validated [DelayJsonValidator.Parsed] out.
 *
 * The system prefix is byte-identical on every call so the KV cache holds across the
 * session — that is most of the difference between a 3s and a 6s classification.
 */
class DelayClassifier(
    private val engine: LlmEngine,
    private val config: PromptSource,
) {

    suspend fun classify(transcript: String): DelayJsonValidator.Parsed {
        val cfg = config.prompts()
        val prompt = buildPrompt(transcript)

        val started = System.currentTimeMillis()
        val raw = engine.generate(
            prompt = prompt,
            maxTokens = cfg.classification.maxTokens,
            temperature = cfg.classification.temperature,
            topK = cfg.classification.topK,
        ).getOrElse {
            Log.e(TAG, "Job 1 inference failed", it)
            // Inference failure is not a user-visible error — it is an OTHER we ask
            // the coordinator to correct.
            return DelayJsonValidator.parse("", transcript)
        }
        Log.i(TAG, "Job 1 in ${System.currentTimeMillis() - started}ms")

        return DelayJsonValidator.parse(raw, transcript)
    }

    private fun buildPrompt(transcript: String): String {
        val cfg = config.prompts()
        val taxonomy = config.taxonomy()

        val userContent = buildString {
            // --- stable prefix: identical every call, keeps the KV cache warm ---
            appendLine(cfg.systemPrefix)
            appendLine()
            appendLine(cfg.classification.instruction)
            appendLine()
            appendLine("Codes:")
            taxonomy.codes.forEach { appendLine("- ${it.code}: ${it.description}") }
            appendLine()
            appendLine("Departments you may use: ${taxonomy.departmentHints.joinToString(", ")}")
            appendLine()
            cfg.classification.fewShot.forEach { ex ->
                appendLine("Coordinator: ${ex.transcript}")
                appendLine("JSON: ${ex.json}")
                appendLine()
            }
            // --- variable suffix ---
            append("Coordinator: ${transcript.trim()}")
        }
        return GemmaChatTemplate.wrap(userContent, modelPrefix = "JSON:")
    }

    private companion object { const val TAG = "DelayClassifier" }
}
