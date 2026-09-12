package com.etio.ot.ai

import android.util.Log
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * PRD §9 validation layer. Deterministic, in code, and the app's real guarantee —
 * the prompt asks nicely, this enforces.
 *
 * Never throws. Never surfaces a parse error to the user. Worst case it returns an
 * [Parsed] with code = OTHER, the raw transcript as the note, and [fellBack] = true,
 * which the review card renders as "needs your check" rather than as an error.
 */
object DelayJsonValidator {

    data class Parsed(
        val code: DelayCode,
        val attributedDept: String,
        val avoidable: Avoidability,
        val estimatedMin: Int?,
        val note: String,
        val confidence: Float,
        val fellBack: Boolean,
        val rawModelOutput: String,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Digits spoken as words still need a spoken number — we do not infer from context. */
    private val SPOKEN_DURATION = Regex(
        """\b(\d{1,3})\s*(min|mins|minute|minutes|m)\b|\b(half an hour|an hour|one hour|two hours)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(rawOutput: String, transcript: String): Parsed {
        val body = extractJsonObject(rawOutput)
            ?: return fallback(transcript, rawOutput, "no JSON object found")

        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return fallback(transcript, rawOutput, "malformed JSON")

        val code = DelayCode.fromModelOutput(obj.str("code"))

        // RULE: estimated_min survives only if a duration was actually spoken.
        val claimedMin = obj["estimated_min"]?.jsonPrimitive?.intOrNull
        val durationWasSpoken = SPOKEN_DURATION.containsMatchIn(transcript)
        val estimatedMin = claimedMin?.takeIf { durationWasSpoken && it in 1..600 }
        if (claimedMin != null && estimatedMin == null) {
            Log.w(TAG, "Dropped hallucinated estimated_min=$claimedMin — not spoken in transcript")
        }

        val note = obj.str("note")
            ?.trim()
            ?.take(MAX_NOTE_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: transcript.take(MAX_NOTE_CHARS)

        val dept = obj.str("attributed_dept")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 40 }
            ?: DEFAULT_DEPT

        val avoidable = obj["avoidable"]?.jsonPrimitive?.let { p ->
            Avoidability.fromModelOutput(p.booleanOrNull ?: p.content)
        } ?: Avoidability.UNCLEAR

        val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull
            ?.coerceIn(0f, 1f) ?: 0f

        return Parsed(
            code = code,
            attributedDept = dept,
            avoidable = avoidable,
            estimatedMin = estimatedMin,
            note = note,
            confidence = confidence,
            // An OTHER that the model chose is legitimate; an OTHER we forced is a fallback.
            fellBack = obj.str("code")?.let { DelayCode.fromModelOutput(it) == DelayCode.OTHER && it.trim().uppercase() != "OTHER" } ?: true,
            rawModelOutput = rawOutput,
        )
    }

    /**
     * Small models wrap JSON in prose or a markdown fence despite being told not to.
     * Take the first balanced {...} block rather than failing the whole call.
     */
    private fun extractJsonObject(raw: String): String? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun fallback(transcript: String, raw: String, why: String): Parsed {
        Log.w(TAG, "Falling back to OTHER: $why")
        return Parsed(
            code = DelayCode.OTHER,
            attributedDept = DEFAULT_DEPT,
            avoidable = Avoidability.UNCLEAR,
            estimatedMin = null,
            note = transcript.take(MAX_NOTE_CHARS),
            confidence = 0f,
            fellBack = true,
            rawModelOutput = raw,
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it != "null" }

    private const val TAG = "DelayJsonValidator"
    private const val DEFAULT_DEPT = "Unattributed"
    private const val MAX_NOTE_CHARS = 140
}
