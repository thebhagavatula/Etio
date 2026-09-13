package com.etio.ot.ai

import com.etio.ot.data.model.DelayCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the A/B/C table. If these are wrong, every number the eval
 * screen shows is wrong in a way that looks entirely plausible — which is the worst
 * kind of wrong for a screen whose whole purpose is to be believed.
 */
class EvalHarnessMetricsTest {

    private fun item(
        id: String,
        expected: DelayCode,
        actual: DelayCode,
        elapsedMs: Long = 1_000,
        parseFailed: Boolean = false,
        noteGrounded: Boolean = true,
        minGrounded: Boolean = true,
        deptGrounded: Boolean = true,
        expectedMin: Int? = null,
        actualMin: Int? = null,
        agreement: String? = null,
    ) = EvalHarness.ItemResult(
        id = id,
        expected = expected.name,
        actual = actual.name,
        correct = expected == actual,
        elapsedMs = elapsedMs,
        parseFailed = parseFailed,
        noteGrounded = noteGrounded,
        minGrounded = minGrounded,
        deptGrounded = deptGrounded,
        expectedMin = expectedMin,
        actualMin = actualMin,
        agreement = agreement,
    )

    private fun mode(items: List<EvalHarness.ItemResult>) =
        EvalHarness.ModeResult(EvalHarness.Mode.CACHED_PREFIX.name, items)

    @Test
    fun `accuracy is correct over total`() {
        val m = mode(
            listOf(
                item("1", DelayCode.SURGEON_LATE, DelayCode.SURGEON_LATE),
                item("2", DelayCode.SURGEON_LATE, DelayCode.OTHER),
                item("3", DelayCode.OTHER, DelayCode.OTHER),
                item("4", DelayCode.OTHER, DelayCode.OTHER),
            ),
        )
        assertEquals(3, m.correct)
        assertEquals(4, m.total)
        assertEquals(0.75f, m.accuracy, 0.001f)
    }

    @Test
    fun `an empty run reports zeroes rather than dividing by zero`() {
        val m = mode(emptyList())
        assertEquals(0f, m.accuracy, 0.001f)
        assertEquals(0f, m.parseFailureRate, 0.001f)
        assertEquals(0f, m.groundingRejectionRate, 0.001f)
        assertEquals(0L, m.meanMs)
        assertEquals(0L, m.p50Ms)
        assertEquals(0L, m.p95Ms)
    }

    @Test
    fun `percentiles come off the sorted latencies`() {
        val items = (1..100).map {
            item("i$it", DelayCode.OTHER, DelayCode.OTHER, elapsedMs = it * 10L)
        }
        val m = mode(items)
        assertEquals(505L, m.meanMs)
        assertEquals(500L, m.p50Ms)
        assertEquals(950L, m.p95Ms)
    }

    @Test
    fun `a single item is its own p50 and p95`() {
        val m = mode(listOf(item("1", DelayCode.OTHER, DelayCode.OTHER, elapsedMs = 4_242)))
        assertEquals(4_242L, m.p50Ms)
        assertEquals(4_242L, m.p95Ms)
    }

    @Test
    fun `parse failure rate counts only the items that failed to parse`() {
        val m = mode(
            listOf(
                item("1", DelayCode.OTHER, DelayCode.OTHER, parseFailed = true),
                item("2", DelayCode.OTHER, DelayCode.OTHER),
                item("3", DelayCode.OTHER, DelayCode.OTHER),
                item("4", DelayCode.OTHER, DelayCode.OTHER),
            ),
        )
        assertEquals(0.25f, m.parseFailureRate, 0.001f)
    }

    @Test
    fun `grounding rejections count per field, over the three fields per item`() {
        val m = mode(
            listOf(
                // Two refusals: note and dept. The duration was never claimed, so it
                // cannot be refused.
                item("1", DelayCode.OTHER, DelayCode.OTHER, noteGrounded = false, deptGrounded = false),
                item("2", DelayCode.OTHER, DelayCode.OTHER),
            ),
        )
        assertEquals(2f / 6f, m.groundingRejectionRate, 0.001f)
    }

    @Test
    fun `an ungrounded duration only counts when one was actually claimed`() {
        val claimed = mode(
            listOf(item("1", DelayCode.OTHER, DelayCode.OTHER, minGrounded = false, actualMin = 40)),
        )
        val notClaimed = mode(
            listOf(item("1", DelayCode.OTHER, DelayCode.OTHER, minGrounded = false, actualMin = null)),
        )
        assertEquals(1f / 3f, claimed.groundingRejectionRate, 0.001f)
        assertEquals(0f, notClaimed.groundingRejectionRate, 0.001f)
    }

    @Test
    fun `an invented duration is one the gold set says was never spoken`() {
        val m = mode(
            listOf(
                item("1", DelayCode.OTHER, DelayCode.OTHER, expectedMin = null, actualMin = 40),
                item("2", DelayCode.OTHER, DelayCode.OTHER, expectedMin = 40, actualMin = 40),
                item("3", DelayCode.OTHER, DelayCode.OTHER, expectedMin = 40, actualMin = null),
            ),
        )
        assertEquals("only the first invented anything", 1, m.hallucinatedMinutes)
    }

    @Test
    fun `precision and recall separate the two ways a class can be wrong`() {
        // SURGEON_LATE: predicted 3 times, right twice -> P 2/3. Gold has it 4 times,
        // found twice -> R 2/4.
        val m = mode(
            listOf(
                item("1", DelayCode.SURGEON_LATE, DelayCode.SURGEON_LATE),
                item("2", DelayCode.SURGEON_LATE, DelayCode.SURGEON_LATE),
                item("3", DelayCode.SURGEON_LATE, DelayCode.OTHER),
                item("4", DelayCode.SURGEON_LATE, DelayCode.OTHER),
                item("5", DelayCode.OTHER, DelayCode.SURGEON_LATE),
            ),
        )
        val surgeon = m.perClass().getValue(DelayCode.SURGEON_LATE.name)
        assertEquals(2f / 3f, surgeon.precision, 0.001f)
        assertEquals(2f / 4f, surgeon.recall, 0.001f)
        assertEquals(4, surgeon.support)
    }

    @Test
    fun `the confusion matrix is square over the whole enum`() {
        val m = mode(
            listOf(
                item("1", DelayCode.SURGEON_LATE, DelayCode.SURGEON_LATE),
                item("2", DelayCode.SURGEON_LATE, DelayCode.PORTER_TRANSPORT),
            ),
        )
        val matrix = m.confusion()

        assertEquals(DelayCode.entries.size, matrix.size)
        matrix.values.forEach { assertEquals(DelayCode.entries.size, it.size) }
        assertEquals(1, matrix[DelayCode.SURGEON_LATE.name]!![DelayCode.SURGEON_LATE.name])
        assertEquals(1, matrix[DelayCode.SURGEON_LATE.name]!![DelayCode.PORTER_TRANSPORT.name])
        assertEquals(0, matrix[DelayCode.OTHER.name]!![DelayCode.OTHER.name])

        val total = matrix.values.sumOf { row -> row.values.sum() }
        assertEquals("every item lands in exactly one cell", 2, total)
    }

    @Test
    fun `agreement counts are tallied for the voting column`() {
        val m = mode(
            listOf(
                item("1", DelayCode.OTHER, DelayCode.OTHER, agreement = "HIGH"),
                item("2", DelayCode.OTHER, DelayCode.OTHER, agreement = "HIGH"),
                item("3", DelayCode.OTHER, DelayCode.OTHER, agreement = "UNCERTAIN"),
                item("4", DelayCode.OTHER, DelayCode.OTHER, agreement = null),
            ),
        )
        assertEquals(mapOf("HIGH" to 2, "UNCERTAIN" to 1), m.agreementCounts)
    }

    @Test
    fun `a mode that could not run as described carries its caveat`() {
        val m = EvalHarness.ModeResult(
            mode = EvalHarness.Mode.CACHED_PREFIX.name,
            items = emptyList(),
            caveat = "Backend cannot clone a session",
        )
        assertTrue(m.caveat!!.isNotBlank())
    }
}
