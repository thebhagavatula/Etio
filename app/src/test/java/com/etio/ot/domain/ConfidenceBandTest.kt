package com.etio.ot.domain

import com.etio.ot.ai.ConfidenceBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bands decide how hard the UI works to get someone's attention, so the
 * boundaries have to be exactly where they are documented — an off-by-one here is a
 * record that quietly stops asking to be checked.
 */
class ConfidenceBandTest {

    @Test
    fun `the boundaries are inclusive at the bottom of each band`() {
        assertEquals(ConfidenceBand.CONFIDENT, ConfidenceBand.of(0.80f))
        assertEquals(ConfidenceBand.UNCERTAIN, ConfidenceBand.of(0.79f))
        assertEquals(ConfidenceBand.UNCERTAIN, ConfidenceBand.of(0.55f))
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(0.54f))
    }

    @Test
    fun `the extremes land where you would expect`() {
        assertEquals(ConfidenceBand.CONFIDENT, ConfidenceBand.of(1f))
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(0f))
    }

    @Test
    fun `a forced OTHER needs attention whatever number came with it`() {
        // A parse failure leaves whatever confidence fell out of a reply we could not
        // read. Trusting that number over the fact that we failed to parse would hide
        // exactly the records most likely to be wrong.
        assertTrue(ConfidenceBand.needsAttention(0.99f, fellBackToOther = true))
        assertTrue(ConfidenceBand.needsAttention(0f, fellBackToOther = true))
    }

    @Test
    fun `a confident record that parsed cleanly does not open for editing`() {
        assertFalse(ConfidenceBand.needsAttention(0.91f, fellBackToOther = false))
        assertFalse(ConfidenceBand.needsAttention(0.60f, fellBackToOther = false))
    }

    @Test
    fun `a low-confidence record opens for editing even when it parsed`() {
        assertTrue(ConfidenceBand.needsAttention(0.42f, fellBackToOther = false))
    }

    @Test
    fun `the adversarial few-shot example lands in a band that asks to be checked`() {
        // prompts.json teaches OTHER at 0.42 for an unmappable utterance. If that ever
        // drifts above the threshold the example stops doing its job on the review card.
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(0.42f))
    }
}
