package com.etio.ot.data

import com.etio.ot.data.local.Converters
import com.etio.ot.data.model.Audience
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every value that crosses the database boundary, round-tripped.
 *
 * These are the cheapest tests in the project and they guard the most expensive
 * failure: a column that reads back as something other than what was written is a
 * bug nobody sees until the data matters.
 */
class ConvertersTest {

    private val c = Converters()

    @Test
    fun `every enum survives a round trip by name`() {
        CaseStatus.entries.forEach { assertEquals(it, c.caseStatusFrom(c.caseStatusTo(it))) }
        EventType.entries.forEach { assertEquals(it, c.eventTypeFrom(c.eventTypeTo(it))) }
        EventSource.entries.forEach { assertEquals(it, c.eventSourceFrom(c.eventSourceTo(it))) }
        DelayCode.entries.forEach { assertEquals(it, c.delayCodeFrom(c.delayCodeTo(it))) }
        Avoidability.entries.forEach { assertEquals(it, c.avoidabilityFrom(c.avoidabilityTo(it))) }
        Audience.entries.forEach { assertEquals(it, c.audienceFrom(c.audienceTo(it))) }
        ChecklistPhase.entries.forEach { assertEquals(it, c.phaseFrom(c.phaseTo(it))) }
    }

    @Test
    fun `an unknown delay code decodes to OTHER rather than throwing`() {
        assertEquals(DelayCode.OTHER, c.delayCodeFrom("SOMETHING_WE_RETIRED"))
    }

    @Test
    fun `an unknown avoidability decodes to UNCLEAR rather than throwing`() {
        assertEquals(Avoidability.UNCLEAR, c.avoidabilityFrom("MAYBE"))
    }

    // --- the one that actually matters: confirmed checklist items -------------

    @Test
    fun `a list of checklist item ids survives a round trip`() {
        val ids = listOf("sign_in_identity", "sign_in_site", "sign_in_consent")
        assertEquals(ids, c.stringListFrom(c.stringListTo(ids)))
    }

    @Test
    fun `a single item survives a round trip`() {
        val ids = listOf("time_out_antibiotics")
        assertEquals(ids, c.stringListFrom(c.stringListTo(ids)))
    }

    @Test
    fun `an empty list round trips to an empty list, not a list holding a blank`() {
        assertEquals(emptyList<String>(), c.stringListFrom(c.stringListTo(emptyList())))
    }

    @Test
    fun `ids containing a comma or a space still survive`() {
        val ids = listOf("swab, needle and instrument count", "implant present")
        assertEquals(ids, c.stringListFrom(c.stringListTo(ids)))
    }
}
