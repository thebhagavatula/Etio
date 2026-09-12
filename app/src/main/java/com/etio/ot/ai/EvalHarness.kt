package com.etio.ot.ai

import com.etio.ot.data.model.DelayCode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * The claims in this project, turned into numbers the device produces itself.
 *
 * Thirty utterances with gold labels, the real Job 1 path, the real grounding
 * verifier, in airplane mode. Nothing here is a benchmark of the model in general —
 * it is a measurement of this pipeline on this phone, which is the only thing anyone
 * should be asked to believe.
 */
class EvalHarness(
    private val classifier: DelayClassifier,
    private val engine: LlmEngine,
) {

    @Serializable
    data class EvalItem(
        val id: String,
        val utterance: String,
        val expected_code: String,
        val expected_dept: String = "",
        val expected_min: Int? = null,
        /** An utterance two codes could fairly claim. Either counts as correct. */
        val alternate_code: String? = null,
    )

    @Serializable
    data class EvalSet(val notes: String = "", val items: List<EvalItem>)

    /** What the three A/B columns are. */
    enum class Mode(val label: String) {
        /** Whole prompt sent every call, primed session bypassed. */
        FULL_PREFILL("Full prefill"),

        /** Primed session reused where the backend allows it. */
        CACHED_PREFIX("Cached prefix"),

        /** Cached prefix, three samples, majority vote. */
        VOTING_3("Cached + vote×3"),
    }

    @Serializable
    data class ItemResult(
        val id: String,
        val expected: String,
        val actual: String,
        val correct: Boolean,
        val elapsedMs: Long,
        val parseFailed: Boolean,
        val noteGrounded: Boolean,
        val minGrounded: Boolean,
        val deptGrounded: Boolean,
        val expectedMin: Int? = null,
        val actualMin: Int? = null,
        val agreement: String? = null,
    )

    @Serializable
    data class ModeResult(
        val mode: String,
        val items: List<ItemResult>,
        /** Set when the mode could not run as described — e.g. no clone support. */
        val caveat: String? = null,
    ) {
        val total: Int get() = items.size
        val correct: Int get() = items.count { it.correct }
        val accuracy: Float get() = if (total == 0) 0f else correct.toFloat() / total

        val parseFailureRate: Float
            get() = if (total == 0) 0f else items.count { it.parseFailed }.toFloat() / total

        /** Any field the verifier refused, over all fields it could have refused. */
        val groundingRejectionRate: Float
            get() {
                if (total == 0) return 0f
                val refused = items.sumOf { r ->
                    (if (!r.noteGrounded) 1 else 0) +
                        (if (r.actualMin != null && !r.minGrounded) 1 else 0) +
                        (if (!r.deptGrounded) 1 else 0)
                }
                return refused.toFloat() / (total * 3)
            }

        /** A duration the transcript did not contain, surviving into the record. */
        val hallucinatedMinutes: Int
            get() = items.count { it.actualMin != null && it.expectedMin == null }

        val meanMs: Long get() = if (items.isEmpty()) 0 else items.sumOf { it.elapsedMs } / items.size
        val p50Ms: Long get() = percentile(50)
        val p95Ms: Long get() = percentile(95)

        private fun percentile(p: Int): Long {
            if (items.isEmpty()) return 0
            val sorted = items.map { it.elapsedMs }.sorted()
            val index = (p / 100.0 * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }

        val agreementCounts: Map<String, Int>
            get() = items.mapNotNull { it.agreement }.groupingBy { it }.eachCount()

        /** Precision and recall per class, over the codes that actually appear. */
        fun perClass(): Map<String, ClassMetrics> {
            val labels = (items.map { it.expected } + items.map { it.actual }).distinct().sorted()
            return labels.associateWith { label ->
                val tp = items.count { it.actual == label && it.correct }
                val predicted = items.count { it.actual == label }
                val actualCount = items.count { it.expected == label }
                ClassMetrics(
                    precision = if (predicted == 0) 0f else tp.toFloat() / predicted,
                    recall = if (actualCount == 0) 0f else tp.toFloat() / actualCount,
                    support = actualCount,
                )
            }
        }

        /** expected -> actual -> count. Square over the full enum, so gaps are visible. */
        fun confusion(): Map<String, Map<String, Int>> {
            val all = DelayCode.entries.map { it.name }
            return all.associateWith { expected ->
                all.associateWith { actual ->
                    items.count { it.expected == expected && it.actual == actual }
                }
            }
        }
    }

    @Serializable
    data class ClassMetrics(val precision: Float, val recall: Float, val support: Int)

    @Serializable
    data class Run(
        val startedAtMs: Long,
        val backend: String,
        val prefixCached: Boolean,
        val modes: List<ModeResult>,
    )

    data class Progress(val mode: Mode, val done: Int, val total: Int)

    /**
     * Runs [modes] over [set], reporting progress as it goes.
     *
     * Cancellable at every item — thirty classifications is minutes of GPU, and a
     * screen you cannot leave is a screen nobody will press twice.
     */
    suspend fun run(
        set: EvalSet,
        modes: List<Mode>,
        prefixCachingAvailable: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): List<ModeResult> {
        val results = mutableListOf<ModeResult>()

        for (mode in modes) {
            val items = mutableListOf<ItemResult>()

            // The comparison only means something where the backend can actually
            // clone. Where it cannot, say so rather than print two columns of the
            // same number and let someone read a speed-up into it.
            val caveat = when {
                mode == Mode.CACHED_PREFIX && !prefixCachingAvailable ->
                    "Backend cannot clone a session, so this ran the same full-prefill path as the first column."
                mode == Mode.VOTING_3 && !prefixCachingAvailable ->
                    "Three full prefills, not three clones — the per-sample cost is the full prompt on this backend."
                else -> null
            }

            engine.setForceFullPrefill(mode == Mode.FULL_PREFILL)

            set.items.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                onProgress(Progress(mode, index, set.items.size))
                items += runOne(item, mode)
            }

            engine.setForceFullPrefill(false)
            results += ModeResult(mode.name, items, caveat)
            onProgress(Progress(mode, set.items.size, set.items.size))
        }

        return results
    }

    private suspend fun runOne(item: EvalItem, mode: Mode): ItemResult {
        val started = System.currentTimeMillis()

        val (parsed, agreement) = if (mode == Mode.VOTING_3) {
            val samples = (1..VOTE_SAMPLES).map {
                currentCoroutineContext().ensureActive()
                classifier.classify(item.utterance)
            }
            val outcome = SelfConsistency.aggregate(samples)
            outcome.merged to outcome.agreement.name
        } else {
            classifier.classify(item.utterance) to null
        }

        val elapsed = System.currentTimeMillis() - started
        val actual = parsed.code.name
        val correct = actual == item.expected_code || actual == item.alternate_code

        return ItemResult(
            id = item.id,
            expected = item.expected_code,
            actual = actual,
            correct = correct,
            elapsedMs = elapsed,
            parseFailed = parsed.parseFailed,
            noteGrounded = parsed.noteGrounded,
            minGrounded = parsed.estimatedMinGrounded,
            deptGrounded = parsed.deptGrounded,
            expectedMin = item.expected_min,
            actualMin = parsed.estimatedMin,
            agreement = agreement,
        )
    }

    private companion object {
        /** Three is the smallest count where a majority can exist at all. */
        const val VOTE_SAMPLES = 3

        @Suppress("unused")
        val UNUSED = max(0, 0)
    }
}
