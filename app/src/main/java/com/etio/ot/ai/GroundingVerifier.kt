package com.etio.ot.ai

/**
 * The invariant, enforced in code: no field of a DelayRecord may carry information
 * that was not in the transcript. The model may select and compress. It may not add.
 *
 * Every check here is deterministic Kotlin. Nothing in this file asks a model whether
 * a model was right — that is circular, and it is the failure mode this exists to
 * rule out. It is also why the checks are blunt: a blunt rule that always runs the
 * same way is worth more than a clever one nobody can predict.
 *
 * Pure, allocation-light, no Android imports. Measured well under 1ms per record.
 */
object GroundingVerifier {

    data class Result(
        val estimatedMin: Int?,
        val estimatedMinGrounded: Boolean,
        val note: String,
        val noteGrounded: Boolean,
        val attributedDept: String,
        val deptGrounded: Boolean,
        /** For the debug view: which note words were not found in the transcript. */
        val ungroundedNoteWords: List<String> = emptyList(),
    )

    fun verify(
        transcript: String,
        estimatedMin: Int?,
        note: String,
        attributedDept: String,
        codeDefaultDept: String,
        deptAliases: Map<String, List<String>>,
    ): Result {
        val spoken = spokenMinutes(transcript)
        val minGrounded = estimatedMin != null && estimatedMin in spoken

        val ungrounded = ungroundedWords(note, transcript)
        val noteOk = note.isNotBlank() && ungrounded.isEmpty()

        val deptOk = deptSupported(attributedDept, transcript, deptAliases)

        return Result(
            // A number nobody said is worse than no number, so it goes rather than
            // getting a warning label.
            estimatedMin = if (minGrounded) estimatedMin else null,
            estimatedMinGrounded = minGrounded,
            note = if (noteOk) note else excerpt(transcript),
            noteGrounded = noteOk,
            // The department is kept either way — attribution has to land somewhere —
            // but an unsupported one falls back to the code's own default and is shown
            // as inferred rather than heard.
            attributedDept = if (deptOk) attributedDept else codeDefaultDept.ifBlank { attributedDept },
            deptGrounded = deptOk,
            ungroundedNoteWords = ungrounded,
        )
    }

    // --- 3A · numeric ---------------------------------------------------------

    /**
     * Every duration the transcript could be said to contain, in minutes.
     *
     * Digits and number words both count, and so do the phrases people actually use
     * out loud — "half an hour" is a duration even though it contains no number at
     * all. Bare numerals count too: "CSSD says forty" is a duration in a theatre.
     */
    fun spokenMinutes(transcript: String): Set<Int> {
        val t = transcript.lowercase().replace('-', ' ').replace(Regex("\\s+"), " ")
        val out = mutableSetOf<Int>()

        PHRASES.forEach { (phrase, minutes) -> if (t.contains(phrase)) out += minutes }

        // "40 minutes", "2 hrs", or a bare "40".
        NUMERIC.findAll(t).forEach { m ->
            val value = m.groupValues[1].toIntOrNull() ?: return@forEach
            out += if (isHour(m.groupValues[2])) value * 60 else value
        }

        // "forty minutes", "two hours", or a bare "forty".
        WORDED.findAll(t).forEach { m ->
            val value = NUMBER_WORDS[m.groupValues[1]] ?: return@forEach
            out += if (isHour(m.groupValues[2])) value * 60 else value
        }

        return out
    }

    private fun isHour(unit: String) = unit.startsWith("h")

    // --- 3B · lexical grounding of the note -----------------------------------

    /** Content words in [note] that the transcript does not support. */
    fun ungroundedWords(note: String, transcript: String): List<String> {
        val source = tokens(transcript)
        return tokens(note)
            .asSequence()
            .filterNot { it in STOPWORDS }
            .filterNot { it in ALLOWED }
            .filterNot { it.length < 3 }
            .filterNot { it.all(Char::isDigit) }
            .filterNot { word -> supported(word, source) }
            .distinct()
            .toList()
    }

    /**
     * Exact match, or a shared four-character prefix in either direction — enough to
     * accept "reprocessing" for "reprocess" without importing a stemmer, and short
     * enough to stay predictable.
     */
    private fun supported(word: String, source: Set<String>): Boolean {
        if (word in source) return true
        if (word.length < STEM_MIN) return false
        val stem = word.take(STEM_MIN)
        return source.any { it.length >= STEM_MIN && (it.startsWith(stem) || word.startsWith(it.take(STEM_MIN))) }
    }

    private fun tokens(text: String): Set<String> =
        TOKEN.findAll(text.lowercase()).map { it.value }.toSet()

    /**
     * What replaces a rejected note: the longest clause the coordinator actually said
     * that fits. Her words, badly chosen, beat the model's words, invented.
     */
    fun excerpt(transcript: String, limit: Int = EXCERPT_LIMIT): String {
        val clauses = transcript.split(',', '.', ';', '—', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val best = clauses.filter { it.length <= limit }.maxByOrNull { it.length }
        return best ?: transcript.trim().take(limit).trim()
    }

    // --- 3C · department -------------------------------------------------------

    private fun deptSupported(
        dept: String,
        transcript: String,
        aliases: Map<String, List<String>>,
    ): Boolean {
        if (dept.isBlank()) return false
        val t = transcript.lowercase()
        if (t.contains(dept.lowercase())) return true

        val key = aliases.keys.firstOrNull { it.equals(dept, ignoreCase = true) } ?: return false
        return aliases[key].orEmpty().any { alias -> alias.isNotBlank() && t.contains(alias.lowercase()) }
    }

    // --- tables ---------------------------------------------------------------

    private const val STEM_MIN = 4
    private const val EXCERPT_LIMIT = 120

    private val TOKEN = Regex("[a-z][a-z']*|\\d+")

    private val NUMBER_WORDS: Map<String, Int> = buildMap {
        listOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "fifteen" to 15, "twenty" to 20, "twentyfive" to 25,
            "thirty" to 30, "forty" to 40, "fourty" to 40, "forty five" to 45,
            "fifty" to 50, "sixty" to 60, "ninety" to 90,
        ).forEach { (k, v) -> put(k, v) }
    }

    /** Durations said without a number in them. */
    private val PHRASES: List<Pair<String, Int>> = listOf(
        "quarter of an hour" to 15,
        "quarter hour" to 15,
        "half an hour" to 30,
        "half hour" to 30,
        "hour and a half" to 90,
        "one and a half hours" to 90,
        "an hour" to 60,
        "a hour" to 60,
        "couple of hours" to 120,
    )

    private val NUMERIC = Regex("""(\d{1,3})\s*(minutes|minute|mins|min|hours|hour|hrs|hr)?""")

    private val WORDED = Regex(
        "(" + NUMBER_WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")" +
            """\s*(minutes|minute|mins|min|hours|hour|hrs|hr)?""",
    )

    /**
     * Words the model may introduce when compressing — the connective tissue of a
     * summary, plus the handful of clinical nouns it legitimately reaches for. Kept
     * deliberately short: everything here is a word we have decided not to check.
     */
    private val ALLOWED = setOf(
        "delay", "delayed", "delays", "unavailable", "pending", "awaiting", "await",
        "hold", "held", "holding", "reprocessing", "reprocess", "processing",
        "not", "no", "none", "still", "yet", "case", "patient", "theatre", "theater",
        "room", "team", "staff", "required", "needed", "ready", "unready",
    )

    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "am",
        "and", "or", "but", "if", "then", "than", "so", "because", "as", "at", "by",
        "for", "from", "in", "into", "of", "on", "onto", "to", "with", "without",
        "up", "down", "out", "off", "over", "under", "again", "very", "just", "only",
        "it", "its", "this", "that", "these", "those", "there", "here", "he", "she",
        "they", "them", "his", "her", "their", "we", "our", "you", "your", "i", "me",
        "my", "has", "have", "had", "do", "does", "did", "will", "would", "can",
        "could", "should", "may", "might", "must", "now", "also", "some", "any",
        "about", "after", "before", "while", "when", "where", "who", "what", "which",
        "sir", "madam", "ok", "okay", "yes", "yeah", "please",
    )
}
