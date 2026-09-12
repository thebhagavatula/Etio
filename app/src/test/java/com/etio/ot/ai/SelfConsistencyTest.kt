package com.etio.ot.ai

import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfConsistencyTest {

    private fun sample(
        code: DelayCode,
        dept: String = "CSSD",
        min: Int? = null,
        note: String = "note",
        avoidable: Avoidability = Avoidability.AVOIDABLE,
        confidence: Float = 0.8f,
    ) = DelayJsonValidator.Parsed(
        code = code,
        attributedDept = dept,
        avoidable = avoidable,
        estimatedMin = min,
        note = note,
        confidence = confidence,
        fellBack = false,
        parseFailed = false,
        rawModelOutput = "",
    )

    @Test
    fun `unanimous samples are HIGH at ratio 1`() {
        val out = SelfConsistency.aggregate(List(3) { sample(DelayCode.SURGEON_LATE) })
        assertEquals(SelfConsistency.Agreement.HIGH, out.agreement)
        assertEquals(1f, out.agreementRatio, 0.001f)
        assertEquals(DelayCode.SURGEON_LATE, out.merged.code)
    }

    @Test
    fun `two of three is UNCERTAIN and the majority wins`() {
        val out = SelfConsistency.aggregate(
            listOf(
                sample(DelayCode.SURGEON_LATE),
                sample(DelayCode.PORTER_TRANSPORT),
                sample(DelayCode.SURGEON_LATE),
            ),
        )
        assertEquals(SelfConsistency.Agreement.UNCERTAIN, out.agreement)
        assertEquals(2f / 3f, out.agreementRatio, 0.001f)
        assertEquals(DelayCode.SURGEON_LATE, out.merged.code)
    }

    @Test
    fun `a three-way split is LOW`() {
        val out = SelfConsistency.aggregate(
            listOf(
                sample(DelayCode.SURGEON_LATE),
                sample(DelayCode.PORTER_TRANSPORT),
                sample(DelayCode.OTHER),
            ),
        )
        assertEquals(SelfConsistency.Agreement.LOW, out.agreement)
    }

    @Test
    fun `two samples that disagree are LOW, not a coin toss dressed as a majority`() {
        val out = SelfConsistency.aggregate(
            listOf(sample(DelayCode.SURGEON_LATE), sample(DelayCode.PORTER_TRANSPORT)),
        )
        assertEquals(SelfConsistency.Agreement.LOW, out.agreement)
        assertEquals(0.5f, out.agreementRatio, 0.001f)
    }

    @Test
    fun `department is decided only among samples that agreed on the code`() {
        val out = SelfConsistency.aggregate(
            listOf(
                sample(DelayCode.STERILE_SET_UNAVAILABLE, dept = "CSSD"),
                sample(DelayCode.PORTER_TRANSPORT, dept = "Portering"),
                sample(DelayCode.STERILE_SET_UNAVAILABLE, dept = "CSSD"),
            ),
        )
        assertEquals("CSSD", out.merged.attributedDept)
    }

    @Test
    fun `a disputed duration becomes null`() {
        val out = SelfConsistency.aggregate(
            listOf(
                sample(DelayCode.SURGEON_LATE, min = 20),
                sample(DelayCode.SURGEON_LATE, min = 30),
                sample(DelayCode.SURGEON_LATE, min = 20),
            ),
        )
        assertNull(out.merged.estimatedMin)
    }

    @Test
    fun `a duration only some samples stated still counts if they agree`() {
        val out = SelfConsistency.aggregate(
            listOf(
                sample(DelayCode.SURGEON_LATE, min = 20),
                sample(DelayCode.SURGEON_LATE, min = null),
                sample(DelayCode.SURGEON_LATE, min = 20),
            ),
        )
        assertEquals(20, out.merged.estimatedMin)
    }

    @Test
    fun `the shortest note among the winners is kept`() {
        val out = SelfConsistency.aggregate(
            listOf(
                sample(DelayCode.SURGEON_LATE, note = "Surgeon is still in the outpatient department"),
                sample(DelayCode.SURGEON_LATE, note = "Surgeon in OPD"),
                sample(DelayCode.SURGEON_LATE, note = "Surgeon has not arrived yet at all"),
            ),
        )
        assertEquals("Surgeon in OPD", out.merged.note)
    }

    @Test
    fun `avoidability is a majority across every sample, not just the winners`() {
        val out = SelfConsistency.aggregate(
            listOf(
                sample(DelayCode.SURGEON_LATE, avoidable = Avoidability.AVOIDABLE),
                sample(DelayCode.PORTER_TRANSPORT, avoidable = Avoidability.UNAVOIDABLE),
                sample(DelayCode.SURGEON_LATE, avoidable = Avoidability.UNAVOIDABLE),
            ),
        )
        assertEquals(Avoidability.UNAVOIDABLE, out.merged.avoidable)
    }

    @Test
    fun `a single sample is passed through unchanged`() {
        val one = sample(DelayCode.EQUIPMENT_FAILURE, min = 15)
        val out = SelfConsistency.aggregate(listOf(one))
        assertEquals(one, out.merged)
        assertEquals(1f, out.agreementRatio, 0.001f)
    }

    @Test
    fun `the spread is reported for the debug view`() {
        val spread = SelfConsistency.spread(
            listOf(
                sample(DelayCode.SURGEON_LATE),
                sample(DelayCode.SURGEON_LATE),
                sample(DelayCode.OTHER),
            ),
        )
        assertEquals(2, spread[DelayCode.SURGEON_LATE])
        assertEquals(1, spread[DelayCode.OTHER])
    }
}
