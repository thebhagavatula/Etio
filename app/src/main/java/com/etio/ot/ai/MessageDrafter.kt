package com.etio.ot.ai

import android.util.Log
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Audience
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * LLM Job 2 (PRD §9). One DelayRecord → four messages, generated SEQUENTIALLY and
 * emitted as they complete, so the UI shows three filled cards and one spinner rather
 * than a blank screen for twelve seconds.
 */
class MessageDrafter(
    private val engine: LlmEngine,
    private val config: PromptSource,
) {

    data class Draft(val audience: Audience, val body: String, val elapsedMs: Long)

    /** Emits one [Draft] per audience, in [Audience.demoOrder]. */
    fun draftAll(record: DelayRecordEntity, case: CaseEntity?): Flow<Draft> = flow {
        val cfg = config.prompts().drafting
        Audience.demoOrder.forEach { audience ->
            val started = System.currentTimeMillis()
            val body = engine.generate(
                prompt = buildPrompt(audience, record, case),
                maxTokens = cfg.maxTokens,
                temperature = cfg.temperature,
                topK = cfg.topK,
            ).getOrElse {
                Log.e(TAG, "Job 2 failed for $audience", it)
                fallbackBody(audience, record, case)
            }
            emit(Draft(audience, clean(body), System.currentTimeMillis() - started))
        }
    }

    suspend fun draftOne(audience: Audience, record: DelayRecordEntity, case: CaseEntity?): String {
        val cfg = config.prompts().drafting
        return engine.generate(
            prompt = buildPrompt(audience, record, case),
            maxTokens = cfg.maxTokens,
            temperature = cfg.temperature,
            topK = cfg.topK,
        ).map(::clean).getOrElse { fallbackBody(audience, record, case) }
    }

    private fun buildPrompt(
        audience: Audience,
        record: DelayRecordEntity,
        case: CaseEntity?,
    ): String {
        val prompts = config.prompts()
        val cfg = prompts.drafting
        val rule = cfg.audiences[audience.name]

        val userContent = buildString {
            appendLine(prompts.systemPrefix)
            appendLine()
            appendLine(cfg.instruction)
            appendLine()
            appendLine("Audience: ${audience.display}")
            rule?.let {
                appendLine("Register: ${it.register}")
                appendLine("Must include: ${it.include.joinToString("; ")}")
                appendLine("Must NOT include: ${it.exclude.joinToString("; ")}")
                it.example?.let { ex ->
                    appendLine()
                    appendLine("Example of the right register for this audience:")
                    appendLine(ex)
                }
            }
            appendLine()
            appendLine("Delay record:")
            appendLine("- Case: ${case?.caseNumber ?: "?"} (${case?.procedureName ?: "procedure"})")
            appendLine("- Theatre: ${case?.theatreId ?: "?"}")
            appendLine("- Cause: ${record.code.display}")
            appendLine("- Department: ${record.attributedDept}")
            appendLine("- Detail: ${record.note}")
            append(
                "- Expected delay: " +
                    (record.estimatedMin?.let { "$it minutes" } ?: "not stated — do not invent a number"),
            )
        }
        return GemmaChatTemplate.wrap(userContent, modelPrefix = "Message for ${audience.display}:")
    }

    /** Used only when inference fails. Deterministic, obviously templated, never blank. */
    private fun fallbackBody(
        audience: Audience,
        record: DelayRecordEntity,
        case: CaseEntity?,
    ): String {
        val caseNo = case?.caseNumber ?: "?"
        val eta = record.estimatedMin?.let { "about $it minutes" } ?: "an unknown amount of time"
        return when (audience) {
            Audience.SURGEON -> "Case $caseNo delayed $eta. ${record.code.display}."
            Audience.FAMILY -> "A short update: your family member is safe and still on today's list. " +
                "The theatre team needs a little more time, so we expect to start $eta later than planned. " +
                "We will update you as soon as they go in."
            Audience.WARD -> "Case $caseNo delayed $eta — ${record.code.display}. Hold the send-for until confirmed."
            Audience.ANAESTHESIA -> "Case $caseNo induction delayed $eta — ${record.code.display} (${record.attributedDept})."
        }
    }

    /** Small models like to add a label, a fence, or an apology. Strip all three. */
    private fun clean(raw: String): String = raw
        .trim()
        .removePrefix("```").removeSuffix("```")
        .removePrefix("Message:").removePrefix("Message")
        .trim()
        .removeSurrounding("\"")
        .lineSequence()
        .filterNot { it.trimStart().startsWith("Audience:") }
        .joinToString("\n")
        .trim()

    private companion object { const val TAG = "MessageDrafter" }
}
