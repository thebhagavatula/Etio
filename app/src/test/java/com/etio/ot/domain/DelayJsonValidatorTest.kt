package com.etio.ot.domain

import com.etio.ot.ai.DelayJsonValidator
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a duration spoken as words survives, because offline ASR writes it that way`() {
        // "CSSD says forty minutes" is how the recogniser actually returns this. A
        // digits-only check silently discarded every duration that was really said.
        val parsed = DelayJsonValidator.parse(
            """{"code":"STERILE_SET_UNAVAILABLE","attributed_dept":"CSSD","estimated_min":40,"note":"wet set"}""",
            "set came back wet, CSSD says forty minutes",
        )
        assertEquals(40, parsed.estimatedMin)
    }

    @Test
    fun `a bare number with no unit is not a duration`() {
        // "stuck on three" is a case number. Reading it as three minutes would put a
        // fabricated ETA into a message sent to a family.
        val parsed = DelayJsonValidator.parse(
            """{"code":"OTHER","attributed_dept":"Theatre","estimated_min":3,"note":"stuck"}""",
            "we are stuck on three",
        )
        assertNull(parsed.estimatedMin)
    }

    @Test
    fun `an hour phrase counts as spoken`() {
        val parsed = DelayJsonValidator.parse(
            """{"code":"ICU_BED_UNAVAILABLE","attributed_dept":"ICU","estimated_min":30,"note":"no bed"}""",
            "no ICU bed, they said about half an hour",
        )
        assertEquals(30, parsed.estimatedMin)
    }

    @Test
    fun `an out-of-range estimate is dropped even when a duration was spoken`() {
        val parsed = DelayJsonValidator.parse(
            """{"code":"OTHER","attributed_dept":"Theatre","estimated_min":4000,"note":"x"}""",
            "they said 40 minutes",
        )
        assertNull(parsed.estimatedMin)
    }

    @Test
    fun `a blank note falls back to the transcript rather than shipping empty`() {
        val parsed = DelayJsonValidator.parse(
            """{"code":"SURGEON_LATE","attributed_dept":"Surgery","note":"   "}""",
            "sir is still in OPD",
        )
        assertEquals("sir is still in OPD", parsed.note)
    }

    @Test
    fun `an over-long note is truncated to the field limit`() {
        val long = "x".repeat(400)
        val parsed = DelayJsonValidator.parse(
            """{"code":"OTHER","attributed_dept":"Theatre","note":"$long"}""",
            "something",
        )
        assertTrue(parsed.note.length <= 120)
    }

    @Test
    fun `a blank department falls back to Unattributed`() {
        val parsed = DelayJsonValidator.parse(
            """{"code":"SURGEON_LATE","attributed_dept":"","note":"late"}""",
            "surgeon is late",
        )
        assertEquals("Unattributed", parsed.attributedDept)
    }

    @Test
    fun `a missing code is a parse failure, not a partial record`() {
        val parsed = DelayJsonValidator.parse(
            """{"attributed_dept":"CSSD","note":"wet set"}""",
            "set came back wet",
        )
        assertTrue(parsed.parseFailed)
        assertEquals(DelayCode.OTHER, parsed.code)
    }

    @Test
    fun `a model-chosen OTHER is not a parse failure`() {
        val parsed = DelayJsonValidator.parse(
            """{"code":"OTHER","attributed_dept":"Theatre","note":"lift broken","confidence":0.7}""",
            "the lift is broken",
        )
        assertFalse("a legitimate OTHER must not trigger a retry", parsed.parseFailed)
        assertFalse(parsed.fellBack)
    }

    @Test
    fun `confidence outside zero to one is clamped, not trusted`() {
        val parsed = DelayJsonValidator.parse(
            """{"code":"OTHER","attributed_dept":"Theatre","note":"x","confidence":7.5}""",
            "something",
        )
        assertEquals(1f, parsed.confidence, 0.001f)
    }
}
