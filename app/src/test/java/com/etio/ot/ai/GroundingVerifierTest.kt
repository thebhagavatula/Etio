package com.etio.ot.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant has to hold against the phrasing people actually use, not against
 * the phrasing that makes the rule easy. These cases are written from the messy end.
 */
class GroundingVerifierTest {

    private val aliases = mapOf(
        "CSSD" to listOf("cssd", "sterile", "sterilisation", "tsu"),
        "Portering" to listOf("porter", "transport", "trolley"),
        "Ward" to listOf("ward", "upstairs"),
    )

    private fun verify(
        transcript: String,
        min: Int? = null,
        note: String = "",
        dept: String = "",
        defaultDept: String = "Unattributed",
    ) = GroundingVerifier.verify(transcript, min, note, dept, defaultDept, aliases)

    // --- 3A numeric ----------------------------------------------------------

    @Test
    fun `digits in the transcript ground a duration`() {
        val r = verify("cssd says 40 minutes", min = 40)
        assertEquals(40, r.estimatedMin)
        assertTrue(r.estimatedMinGrounded)
    }

    @Test
    fun `number words ground a duration`() {
        assertTrue(verify("cssd says forty minutes", min = 40).estimatedMinGrounded)
        assertTrue(verify("maybe twenty minutes only", min = 20).estimatedMinGrounded)
    }

    @Test
    fun `hours are converted, not taken literally`() {
        assertTrue(verify("anaesthesia wants minimum two hours", min = 120).estimatedMinGrounded)
        assertFalse(verify("anaesthesia wants minimum two hours", min = 2).estimatedMinGrounded)
    }

    @Test
    fun `durations with no number in them still count`() {
        assertTrue(verify("maybe another half an hour", min = 30).estimatedMinGrounded)
        assertTrue(verify("he will take an hour", min = 60).estimatedMinGrounded)
        assertTrue(verify("hour and a half at least", min = 90).estimatedMinGrounded)
    }

    @Test
    fun `a duration nobody said is dropped, not flagged and kept`() {
        val r = verify("set came back wet, they are reprocessing", min = 40)
        assertNull(r.estimatedMin)
        assertFalse(r.estimatedMinGrounded)
    }

    @Test
    fun `a plausible but different number is still not grounded`() {
        val r = verify("cssd says forty minutes", min = 45)
        assertNull(r.estimatedMin)
    }

    // --- 3B note -------------------------------------------------------------

    @Test
    fun `a note built from the transcript survives`() {
        val r = verify(
            transcript = "the set came back wet, cssd is reprocessing it",
            note = "Set came back wet; CSSD reprocessing",
        )
        assertTrue(r.ungroundedNoteWords.toString(), r.noteGrounded)
        assertEquals("Set came back wet; CSSD reprocessing", r.note)
    }

    /**
     * Strict extraction has a cost, and this is it: "returned" is a fair summary of
     * "came back" and the rule still rejects it, because the rule cannot tell a
     * synonym from an invention without asking a model — which is the one thing it
     * may not do. The rejection rate this produces is measured in the eval harness
     * rather than argued about here.
     */
    @Test
    fun `a synonym the transcript never used is rejected, deliberately`() {
        val r = verify(
            transcript = "the set came back wet, cssd is reprocessing it",
            note = "Set returned wet; CSSD reprocessing",
        )
        assertFalse(r.noteGrounded)
        assertEquals(listOf("returned"), r.ungroundedNoteWords)
    }

    @Test
    fun `an invented detail is rejected and replaced with her own words`() {
        val r = verify(
            transcript = "sir is still in opd, he said he is coming",
            note = "Surgeon stuck in traffic on the ring road",
        )
        assertFalse(r.noteGrounded)
        assertTrue("traffic" in r.ungroundedNoteWords || "ring" in r.ungroundedNoteWords)
        // The replacement is a clause she actually said, not a model sentence.
        assertTrue(r.note, "sir is still in opd, he said he is coming".contains(r.note))
    }

    @Test
    fun `stem matching accepts a compressed form of a spoken word`() {
        val r = verify(
            transcript = "they are reprocessing the instruments now",
            note = "Instrument reprocess pending",
        )
        assertTrue(r.ungroundedNoteWords.toString(), r.noteGrounded)
    }

    @Test
    fun `the excerpt stays under the limit`() {
        val long = "a".repeat(400)
        assertTrue(GroundingVerifier.excerpt(long).length <= 120)
    }

    // --- 3C department -------------------------------------------------------

    @Test
    fun `an alias in the transcript supports the department`() {
        assertTrue(verify("the sterile set is not back", dept = "CSSD").deptGrounded)
        assertTrue(verify("no trolley, porter has not come", dept = "Portering").deptGrounded)
    }

    @Test
    fun `the department name itself counts`() {
        assertTrue(verify("ward says patient had tea", dept = "Ward").deptGrounded)
    }

    @Test
    fun `an unsupported department falls back to the code default and is marked`() {
        val r = verify("case is delayed", dept = "CSSD", defaultDept = "Theatre")
        assertFalse(r.deptGrounded)
        assertEquals("Theatre", r.attributedDept)
    }

    // --- cost ----------------------------------------------------------------

    @Test
    fun `verification is far inside the 10ms budget`() {
        val transcript = "we're stuck on three, the set came back wet, cssd says forty minutes, " +
            "and the porter has not come for the next patient either"
        // Warm the regex caches first; the budget is about steady state.
        repeat(20) { verify(transcript, 40, "Set returned wet; CSSD reprocessing", "CSSD") }

        val start = System.nanoTime()
        repeat(100) { verify(transcript, 40, "Set returned wet; CSSD reprocessing", "CSSD") }
        val perCallMs = (System.nanoTime() - start) / 100.0 / 1_000_000.0

        assertTrue("grounding took ${perCallMs}ms per call", perCallMs < 10.0)
    }
}
