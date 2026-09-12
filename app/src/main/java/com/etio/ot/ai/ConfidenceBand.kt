package com.etio.ot.ai

/**
 * How much of the record the coordinator should read before confirming it.
 *
 * Three bands, fixed thresholds, and no arithmetic on the way to the screen. The
 * number shown is the number the model returned: not floored to look better, not
 * rescaled to spread the bands out, not hidden when it is bad. A confidence display
 * that flatters the model is worse than none, because it teaches her to trust a
 * record she should have checked.
 *
 * The bands only change how hard the UI works to get her attention. They never change
 * the stored record, and they never gate saving.
 */
enum class ConfidenceBand {
    /** Read it if you like. */
    CONFIDENT,

    /** Worth a glance — the field is marked, nothing is moved. */
    UNCERTAIN,

    /** Start here. The card opens on the cause, already editable. */
    LOW,
    ;

    companion object {
        const val CONFIDENT_AT = 0.80f
        const val UNCERTAIN_AT = 0.55f

        fun of(confidence: Float): ConfidenceBand = when {
            confidence >= CONFIDENT_AT -> CONFIDENT
            confidence >= UNCERTAIN_AT -> UNCERTAIN
            else -> LOW
        }

        /**
         * Whether the card should open ready to be corrected.
         *
         * A forced OTHER counts regardless of the number beside it: the model did not
         * fail to be sure, it failed to answer, and the confidence in that case is
         * whatever fell out of a reply we could not parse.
         */
        fun needsAttention(confidence: Float, fellBackToOther: Boolean): Boolean =
            fellBackToOther || of(confidence) == LOW
    }
}
