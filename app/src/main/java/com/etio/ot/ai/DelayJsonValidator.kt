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
 * Never throws. Never surfaces a parse error to the user. Worst case it returns a
 * [Parsed] with code = OTHER, the raw transcript as the note, and [fellBack] = true,
 * which the review card renders as "needs your check" rather than as an error.
 *
 * Every field is checked, not just the code. A small model that gets the code right
 * and the department blank is still a record someone has to fix by hand.
 */
object DelayJsonValidator {

    data class Parsed(
        val code: DelayCode,
        val attributedDept: String,
        val avoidable: Avoidability,
        val estimatedMin: Int?,
        val note: String,
        val confidence: Float,
        /** Shown to the user as "needs your check". */
        val fellBack: Boolean,
        /**
         * No usable JSON came back at all — as opposed to JSON that parsed fine and
         * said OTHER. Only this warrants spending a second inference on a retry.
         */
        val parseFailed: Boolean,
        val rawModelOutput: String,
        /** Set by [GroundingVerifier] after parsing; true until something checks. */
        val noteGrounded: Boolean = true,
        val estimatedMinGrounded: Boolean = true,
        val deptGrounded: Boolean = true,
        /** Debug only: note words the transcript did not support. */
        val ungroundedNoteWords: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Was a duration actually spoken?
     *
     * Digits are the easy half. The hard half is that offline Indian-English ASR
     * usually writes numbers as words — "CSSD says forty minutes" comes back spelled
     * out, and a digits-only check silently discarded every duration that was really
     * said. Both halves need a unit nearby, so a case number ("we're stuck on three")
     * is never read as three minutes.
     */
    private const val NUM_WORD =
        "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
            "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fourty|fifty|sixty|ninety"

    private val SPOKEN_DURATION = Regex(
        """\b\d{1,3}\s*(?:-|\s)?\s*(?:min|mins|minute|minutes|m|hour|hours|hr|hrs)\b""" +
            """|\b(?:$NUM_WORD)(?:[\s-](?:$NUM_WORD))?\s*(?:min|mins|minute|minutes|hour|hours|hr|hrs)\b""" +
            // Fractional hours are spoken, not counted: "another half hour" is the most
            // common way an overrun gets quoted, and it carries no digit and no numeral.
            """|\b(?:half[\s-]?(?:an[\s-])?hour|an?[\s-]hour|quarter[\s-]of[\s-]an[\s-]hour""" +
            """|hour[\s-]and[\s-]a[\s-]half)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(rawOutput: String, transcript: String): Parsed {
        val body = extractJsonObject(rawOutput)
            ?: return fallback(transcript, rawOutput, "no JSON object found")

        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return fallback(transcript, rawOutput, "malformed JSON")

        val rawCode = obj.str("code")
        // A record with no code at all is not a partial success, it is a parse failure.
        if (rawCode.isNullOrBlank()) return fallback(transcript, rawOutput, "no code field")

        val code = DelayCode.fromModelOutput(rawCode)

        // RULE: estimated_min survives only if a duration was actually spoken, and only
        // as a positive whole number of plausible size.
        val claimedMin = obj["estimated_min"]?.jsonPrimitive?.intOrNull
        val durationWasSpoken = SPOKEN_DURATION.containsMatchIn(transcript)
        val estimatedMin = claimedMin?.takeIf { durationWasSpoken && it in 1..MAX_ESTIMATE_MIN }
        if (claimedMin != null && estimatedMin == null) {
            Log.w(
                TAG,
                if (!durationWasSpoken) "Dropped hallucinated estimated_min=$claimedMin — not spoken in transcript"
                else "Dropped out-of-range estimated_min=$claimedMin",
            )
        }

        val note = obj.str("note")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_NOTE_CHARS)
            ?: transcript.trim().take(MAX_NOTE_CHARS).ifBlank { DEFAULT_NOTE }

        val dept = obj.str("attributed_dept")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_DEPT_CHARS }
            ?: DEFAULT_DEPT

        val avoidable = obj["avoidable"]?.jsonPrimitive?.let { p ->
            Avoidability.fromModelOutput(p.booleanOrNull ?: p.content)
        } ?: Avoidability.UNCLEAR

        val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f)
            ?: 0f

        // An OTHER the model chose is legitimate; an OTHER we coerced is a fallback.
        val coercedToOther = code == DelayCode.OTHER && rawCode.trim().uppercase() != "OTHER"
        if (coercedToOther) Log.w(TAG, "Unknown code '$rawCode' coerced to OTHER")

        return Parsed(
            code = code,
            attributedDept = dept,
            avoidable = avoidable,
            estimatedMin = estimatedMin,
            note = note,
            confidence = confidence,
            fellBack = coercedToOther,
            parseFailed = false,
            rawModelOutput = rawOutput,
        )
    }

    /**
     * Small models wrap JSON in prose or a markdown fence despite being told not to.
     * Take the first balanced {...} block rather than failing the whole call.
     */
    private fun extractJsonObject(raw: String): String? {
        val text = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
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
            note = transcript.trim().take(MAX_NOTE_CHARS).ifBlank { DEFAULT_NOTE },
            confidence = 0f,
            fellBack = true,
            parseFailed = true,
            rawModelOutput = raw,
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it != "null" }

    private const val TAG = "DelayJsonValidator"
    private const val DEFAULT_DEPT = "Unattributed"
    private const val DEFAULT_NOTE = "Reason not captured"
    private const val MAX_NOTE_CHARS = 120
    private const val MAX_DEPT_CHARS = 40
    private const val MAX_ESTIMATE_MIN = 600
}
