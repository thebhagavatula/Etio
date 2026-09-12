package com.etio.ot.ai

import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode

/**
 * Confidence you can defend: how often the model agreed with itself, measured.
 *
 * The number a model reports about its own certainty is not calibrated against
 * anything — it is text it produced, in the same pass, with the same incentives. Run
 * the same utterance a few times with real sampling temperature and the spread
 * between the answers is a different kind of signal: it is behaviour, not
 * self-assessment.
 *
 * Pure aggregation. No engine, no config, no Android.
 */
object SelfConsistency {

    enum class Agreement {
        /** Every sample said the same thing. */
        HIGH,

        /** A majority, but not unanimous. */
        UNCERTAIN,

        /** No majority at all — as many answers as samples. */
        LOW,
    }

    data class Outcome(
        val merged: DelayJsonValidator.Parsed,
        /** Share of samples that voted for the winning code. Never rescaled. */
        val agreementRatio: Float,
        val agreement: Agreement,
        /** Every sample, kept for the debug view. Judges ask to see these. */
        val samples: List<DelayJsonValidator.Parsed>,
    )

    fun aggregate(samples: List<DelayJsonValidator.Parsed>): Outcome {
        require(samples.isNotEmpty()) { "aggregate() needs at least one sample" }
        if (samples.size == 1) {
            return Outcome(samples.first(), 1f, Agreement.HIGH, samples)
        }

        // Primary output. Ties break towards the earliest sample, which is the one
        // decoded from the coldest, least-drifted state.
        val byCode = samples.groupBy { it.code }
        val winningCode = byCode.maxByOrNull { (code, rows) ->
            rows.size * 1000 - samples.indexOfFirst { it.code == code }
        }!!.key
        val winners = byCode.getValue(winningCode)

        val ratio = winners.size.toFloat() / samples.size
        val distinctCodes = byCode.keys.size
        val agreement = when {
            distinctCodes == 1 -> Agreement.HIGH
            distinctCodes == samples.size -> Agreement.LOW
            else -> Agreement.UNCERTAIN
        }

        val merged = winners.first().copy(
            code = winningCode,
            // Department is decided only among the samples that agreed on the cause;
            // a department attached to a rejected code is not evidence for anything.
            attributedDept = majorityOf(winners.map { it.attributedDept })
                ?: winners.first().attributedDept,
            avoidable = majorityOf(samples.map { it.avoidable }) ?: Avoidability.UNCLEAR,
            // A disputed number is worse than no number: if two runs of the same model
            // on the same words disagree about how long, nobody should be told either.
            estimatedMin = unanimousMinutes(samples),
            // Shortest note among the winners. Short notes have less room to invent.
            note = winners.minByOrNull { it.note.length }?.note ?: winners.first().note,
            // The self-reported number is kept but averaged rather than picked, so the
            // two confidences on screen are both honest about what they are.
            confidence = samples.map { it.confidence }.average().toFloat(),
        )

        return Outcome(merged, ratio, agreement, samples)
    }

    /** Null unless every sample that offered a duration offered the same one. */
    private fun unanimousMinutes(samples: List<DelayJsonValidator.Parsed>): Int? {
        val stated = samples.mapNotNull { it.estimatedMin }
        if (stated.isEmpty()) return null
        return stated.distinct().singleOrNull()
    }

    private fun <T> majorityOf(values: List<T>): T? = values
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    /** The band the review card reacts to, from the measured ratio alone. */
    fun bandFor(agreement: Agreement): ConfidenceBand = when (agreement) {
        Agreement.HIGH -> ConfidenceBand.CONFIDENT
        Agreement.UNCERTAIN -> ConfidenceBand.UNCERTAIN
        Agreement.LOW -> ConfidenceBand.LOW
    }

    /** Codes that appeared, for the debug view and the eval harness. */
    fun spread(samples: List<DelayJsonValidator.Parsed>): Map<DelayCode, Int> =
        samples.groupingBy { it.code }.eachCount()
}
