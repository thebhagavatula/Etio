package com.etio.ot.domain

import com.etio.ot.ai.DelayJsonValidator
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validator is the real guarantee behind the JSON contract. These are the
 * failure modes a 1B model actually produces — test them, do not hope.
 */
class DelayJsonValidatorTest {

    private val transcript = "set came back wet, CSSD says forty minutes"
    private val transcriptWithDigits = "set came back wet, CSSD says 40 minutes"

    @Test
    fun `clean json parses`() {
        val raw = """{"code":"STERILE_SET_UNAVAILABLE","attributed_dept":"CSSD","avoidable":true,"estimated_min":40,"note":"Set wet","confidence":0.9}"""
        val p = DelayJsonValidator.parse(raw, transcriptWithDigits)
        assertEquals(DelayCode.STERILE_SET_UNAVAILABLE, p.code)
        assertEquals("CSSD", p.attributedDept)
        assertEquals(Avoidability.AVOIDABLE, p.avoidable)
        assertEquals(40, p.estimatedMin)
    }

    @Test
    fun `markdown fence and preamble are stripped`() {
        val raw = "Sure! Here is the JSON:\n```json\n{\"code\":\"SURGEON_LATE\",\"attributed_dept\":\"Surgery\",\"avoidable\":true,\"estimated_min\":null,\"note\":\"Late\",\"confidence\":0.8}\n```"
        val p = DelayJsonValidator.parse(raw, "sir is still in OPD")
        assertEquals(DelayCode.SURGEON_LATE, p.code)
    }

    @Test
    fun `unknown code falls back to OTHER`() {
        val raw = """{"code":"CSSD_PROBLEM","attributed_dept":"CSSD","avoidable":true,"estimated_min":null,"note":"x","confidence":0.5}"""
        val p = DelayJsonValidator.parse(raw, transcript)
        assertEquals(DelayCode.OTHER, p.code)
        assertTrue(p.fellBack)
    }

    @Test
    fun `hallucinated duration is dropped when nothing was spoken`() {
        val raw = """{"code":"SURGEON_LATE","attributed_dept":"Surgery","avoidable":true,"estimated_min":30,"note":"Late","confidence":0.8}"""
        val p = DelayJsonValidator.parse(raw, "sir is still in OPD, he is coming")
        assertNull(p.estimatedMin)
    }

    @Test
    fun `unparseable output becomes OTHER with the transcript as the note`() {
        val p = DelayJsonValidator.parse("I'm not sure what you mean.", transcript)
        assertEquals(DelayCode.OTHER, p.code)
        assertEquals(transcript, p.note)
        assertTrue(p.fellBack)
        assertEquals(0f, p.confidence, 0.001f)
    }

    @Test
    fun `empty output does not throw`() {
        val p = DelayJsonValidator.parse("", transcript)
        assertEquals(DelayCode.OTHER, p.code)
    }

    @Test
    fun `missing avoidable becomes UNCLEAR`() {
        val raw = """{"code":"PORTER_TRANSPORT","attributed_dept":"Portering","note":"No porter","confidence":0.7}"""
        val p = DelayJsonValidator.parse(raw, "no porter has come")
        assertEquals(Avoidability.UNCLEAR, p.avoidable)
    }
}
