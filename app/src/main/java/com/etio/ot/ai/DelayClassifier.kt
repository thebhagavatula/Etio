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
    suspend fun classify(transcript: String): DelayJsonValidator.Parsed =
        ground(transcript, classifyRaw(transcript))

    /**
     * Classify the same utterance [samples] times and aggregate by majority.
     *
     * The samples are drawn at the profile's own temperature, which is what makes the
     * spread meaningful: at temperature 0.1 three runs agree because the sampler had
     * nowhere else to go, not because the model was sure. Grounding runs once, on the
     * merged record, so a field only has to survive the check that will be shown.
     *
     * Sequential on purpose. MediaPipe sessions are not safe to use concurrently, and
     * two overlapping decodes on a loaner phone is how the demo ends.
     */
    /** Samples drawn per classification, from config. 1 means no voting. */
    fun voteSamples(): Int = runCatching { config.prompts().classification.voteSamples }.getOrDefault(1)

    suspend fun classifyVoted(transcript: String, samples: Int = voteSamples()): SelfConsistency.Outcome {
        if (samples <= 1) {
            val single = classify(transcript)
            return SelfConsistency.Outcome(single, 1f, SelfConsistency.Agreement.HIGH, listOf(single))
        }

        val started = System.currentTimeMillis()
        val drawn = (1..samples).map { classifyRaw(transcript, voting = true) }
        val outcome = SelfConsistency.aggregate(drawn)
        val merged = ground(transcript, outcome.merged)

        Log.i(
            TAG,
            "Job 1 voted x$samples in ${System.currentTimeMillis() - started}ms: " +
                "${outcome.agreement} ratio=${outcome.agreementRatio} spread=${SelfConsistency.spread(drawn)}",
        )
        InferenceTelemetry.vote(outcome.agreement.name)

        return outcome.copy(merged = merged)
    }

    /**
     * The invariant, applied to whatever came back: a number nobody said is dropped, a
     * note containing words nobody said is replaced by words she did say, and a
     * department nobody named falls back to the code's own default.
     *
     * Deterministic, and deliberately outside the retry loop — re-asking the model
     * would just be asking the thing that invented it to check itself.
     */
    fun ground(transcript: String, parsed: DelayJsonValidator.Parsed): DelayJsonValidator.Parsed {
        val taxonomy = runCatching { config.taxonomy() }.getOrNull()
        val defaultDept = taxonomy?.codes
            ?.firstOrNull { it.code.equals(parsed.code.name, ignoreCase = true) }
            ?.typicalDept
            .orEmpty()

        val checked = GroundingVerifier.verify(
            transcript = transcript,
            estimatedMin = parsed.estimatedMin,
            note = parsed.note,
            attributedDept = parsed.attributedDept,
            codeDefaultDept = defaultDept,
            deptAliases = runCatching { config.prompts().departmentAliases }.getOrDefault(emptyMap()),
        )

        if (parsed.estimatedMin != null && !checked.estimatedMinGrounded) {
            Log.w(TAG, "Dropped estimated_min=${parsed.estimatedMin}: no matching duration in transcript")
            InferenceTelemetry.groundingRejection("estimated_min")
        }
        if (!checked.noteGrounded) {
            Log.w(TAG, "Replaced note; ungrounded words=${checked.ungroundedNoteWords}")
            InferenceTelemetry.groundingRejection("note")
        }
        if (!checked.deptGrounded) InferenceTelemetry.groundingRejection("dept")

        return parsed.copy(
            estimatedMin = checked.estimatedMin,
            note = checked.note,
            attributedDept = checked.attributedDept,
            noteGrounded = checked.noteGrounded,
            estimatedMinGrounded = checked.estimatedMinGrounded,
            deptGrounded = checked.deptGrounded,
            ungroundedNoteWords = checked.ungroundedNoteWords,
        )
    }

    private suspend fun classifyRaw(
        transcript: String,
        voting: Boolean = false,
    ): DelayJsonValidator.Parsed {
        // Null means the engine itself failed, which a retry cannot help: it fails the
        // same way a second later, and spending another three seconds proving that is
        // three seconds she is standing in front of a screen that says nothing.
        val first = attempt(transcript, retry = false, voting = voting)
            ?: return DelayJsonValidator.parse("", transcript)
        if (!first.parseFailed) return first

        InferenceTelemetry.parseRetry()
        Log.w(TAG, "Job 1 returned no usable JSON; retrying once")

        val second = attempt(transcript, retry = true, voting = voting)
            ?: return DelayJsonValidator.parse("", transcript)
        if (!second.parseFailed) return second

        InferenceTelemetry.parseFallback()
        Log.w(TAG, "Job 1 retry also unusable; falling back to OTHER")
        return second
    }

    /** Null when the engine call itself failed, as opposed to returning unusable text. */
    private suspend fun attempt(
        transcript: String,
        retry: Boolean,
        voting: Boolean = false,
    ): DelayJsonValidator.Parsed? {
        val cfg = config.prompts()
        val started = System.currentTimeMillis()

        val raw = engine.generate(
            profile = profile(voting),
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

    /**
     * Greedy for a single answer, sampled for a vote.
     *
     * At temperature 0.1 three runs agree because the sampler had nowhere else to go,
     * which measures the decoder, not the model's grip on the utterance. Voting draws
     * need real variance or the agreement ratio means nothing — and a separate profile
     * name keeps the two prefixes primed independently.
     */
    fun profile(voting: Boolean = false): DecodeProfile {
        val cfg = config.prompts().classification
        return if (voting) {
            DecodeProfile(
                name = DecodeProfile.CLASSIFY_VOTE,
                temperature = cfg.voteTemperature,
                topK = cfg.voteTopK,
            )
        } else {
            DecodeProfile(
                name = DecodeProfile.CLASSIFY,
                temperature = cfg.temperature,
                topK = cfg.topK,
            )
        }
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
