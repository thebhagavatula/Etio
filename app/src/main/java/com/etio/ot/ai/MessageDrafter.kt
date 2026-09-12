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
            val raw = engine.generate(
                prompt = buildPrompt(audience, record, case),
                maxTokens = cfg.maxTokens,
                temperature = cfg.temperature,
                topK = cfg.topK,
            ).getOrElse {
                Log.e(TAG, "Job 2 failed for $audience", it)
                fallbackBody(audience, record, case)
            }
            val body = groundedBody(raw, audience, record, case)
            emit(Draft(audience, body, System.currentTimeMillis() - started))
        }
    }

    suspend fun draftOne(audience: Audience, record: DelayRecordEntity, case: CaseEntity?): String {
        val cfg = config.prompts().drafting
        val raw = engine.generate(
            prompt = buildPrompt(audience, record, case),
            maxTokens = cfg.maxTokens,
            temperature = cfg.temperature,
            topK = cfg.topK,
        ).getOrElse { fallbackBody(audience, record, case) }
        return groundedBody(raw, audience, record, case)
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
            }
            appendLine()
            // One flowing sentence, not a bulleted field list. A small model asked to
            // continue right after a bulleted list tends to just add more bullets
            // instead of writing prose — confirmed on-device: WARD/ANAESTHESIA drafts
            // came back as field dumps ("Case: 3", "Theatre: OT-2", one per line)
            // until this stopped presenting the facts as a list at all.
            val expectedDelay = record.estimatedMin?.let { "$it minutes" }
                ?: "not stated — do not invent a number or a time"
            appendLine(
                "Facts: case ${case?.caseNumber ?: "?"} (${case?.procedureName ?: "procedure"}) in theatre " +
                    "${case?.theatreId ?: "?"}. Cause: ${record.code.display}, attributed to " +
                    "${record.attributedDept}. ${record.note}. Expected delay: $expectedDelay.",
            )
            // The example goes last, right before generation starts, so the most
            // recent pattern the model has seen is natural prose, not the facts list.
            rule?.example?.let { ex ->
                appendLine()
                appendLine("Write in flowing sentences like this example, using the facts above, not this example's facts:")
                append(ex)
            }
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

    /**
     * Cleans formatting cruft, then rejects a hallucinated duration in favour of the
     * deterministic fallback, which is always grounded in [record] by construction.
     */
    private fun groundedBody(
        raw: String,
        audience: Audience,
        record: DelayRecordEntity,
        case: CaseEntity?,
    ): String {
        val cleaned = clean(raw)
        if (isConsistent(cleaned, record.estimatedMin)) return cleaned
        Log.w(TAG, "Job 2 draft for $audience mentioned a duration inconsistent with the record; using fallback")
        return fallbackBody(audience, record, case)
    }

    /**
     * Job 2 has no JSON contract to police the way Job 1 does, but it inherits the
     * same hallucination risk: a small model asked to restate a number sometimes
     * invents a different one — observed on-device, a FAMILY draft said "delayed by
     * approximately **four hours**" in the same sentence the record's real 40
     * minutes also appeared in. Spelled out, not "4" — a digit-only check would have
     * missed the exact bug that motivated this. The only duration allowed to appear
     * is [estimatedMin] itself; no mention at all is fine.
     */
    private fun isConsistent(body: String, estimatedMin: Int?): Boolean =
        DURATION_MENTION.findAll(body).map { m ->
            val amountText = m.groupValues[1].lowercase()
            val amount = amountText.toIntOrNull() ?: NUMBER_WORDS.getValue(amountText)
            val unit = m.groupValues[2]
            if (unit.startsWith("h", ignoreCase = true)) amount * 60 else amount
        }.all { it == estimatedMin }

    private companion object {
        const val TAG = "MessageDrafter"

        val NUMBER_WORDS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        )

        val DURATION_MENTION = Regex(
            """\b(\d{1,3}|${NUMBER_WORDS.keys.joinToString("|")})\s*(minutes?|mins?|hours?|hrs?)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
