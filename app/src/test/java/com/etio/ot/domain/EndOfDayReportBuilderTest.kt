package com.etio.ot.domain

import com.etio.ot.core.Clock
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.report.EndOfDayReportBuilder
import com.etio.ot.domain.timing.DayMetrics
import com.etio.ot.domain.timing.TimerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report is the number the room remembers. Every figure in it has to be traceable
 * to either something someone said or something the clock measured — these pin which
 * of the two each one is.
 */
class EndOfDayReportBuilderTest {

    private val t0 = 1_700_000_000_000L
    private fun min(n: Int) = n * 60_000L

    private fun case(id: String, number: String, order: Int, durationMin: Int = 60) =
        CaseEntity(
            id = id,
            caseNumber = number,
            theatreId = "OT-2",
            procedureName = "Test",
            surgeon = "Dr Test",
            scheduledStartMs = t0 + min(order * 90),
            scheduledDurationMin = durationMin,
            orderIndex = order,
        )

    private fun event(id: String, caseId: String, type: EventType, atMin: Int) =
        EventEntity(id = id, caseId = caseId, type = type, timestampMs = t0 + min(atMin))

    private fun delay(
        id: String,
        caseId: String,
        code: DelayCode = DelayCode.STERILE_SET_UNAVAILABLE,
        dept: String = "CSSD",
        min: Int? = 40,
        avoidable: Avoidability = Avoidability.AVOIDABLE,
    ) = DelayRecordEntity(
        id = id,
        caseId = caseId,
        createdAtMs = t0,
        transcriptRaw = "the set came back wet",
        code = code,
        attributedDept = dept,
        avoidable = avoidable,
        estimatedMin = min,
        note = "Set came back wet",
    )

    private fun metrics(cases: List<CaseEntity>, events: List<EventEntity>): DayMetrics =
        TimerEngine.compute(cases, events, Clock { t0 + min(500) })

    @Test
    fun `a spoken duration is used as stated and marked as not measured`() {
        val cases = listOf(case("a", "1", 0))
        val report = EndOfDayReportBuilder.build(
            cases,
            listOf(delay("d1", "a", min = 40)),
            metrics(cases, emptyList()),
        )
        assertEquals(40, report.lostMinutes)
        assertFalse(report.attributions.single().measured)
    }

    @Test
    fun `no spoken duration falls back to the measured span and says so`() {
        val cases = listOf(case("a", "1", 0), case("b", "2", 1))
        // Case 1 out at +70, case 2 in at +95 — a 25 minute turnover on case 2.
        val events = listOf(
            event("e1", "a", EventType.PATIENT_OUT, 70),
            event("e2", "b", EventType.PATIENT_IN_ROOM, 95),
        )
        val report = EndOfDayReportBuilder.build(
            cases,
            listOf(delay("d1", "b", min = null)),
            metrics(cases, events),
        )
        assertEquals(25, report.lostMinutes)
        assertTrue(report.attributions.single().measured)
    }

    @Test
    fun `a delay on a case with no spans at all contributes zero, not a guess`() {
        val cases = listOf(case("a", "1", 0))
        val report = EndOfDayReportBuilder.build(
            cases,
            listOf(delay("d1", "a", min = null)),
            metrics(cases, emptyList()),
        )
        assertEquals(0, report.lostMinutes)
    }

    @Test
    fun `totals group by code and by department, largest first`() {
        val cases = listOf(case("a", "1", 0))
        val delays = listOf(
            delay("d1", "a", code = DelayCode.STERILE_SET_UNAVAILABLE, dept = "CSSD", min = 40),
            delay("d2", "a", code = DelayCode.SURGEON_LATE, dept = "Surgery", min = 10),
            delay("d3", "a", code = DelayCode.STERILE_SET_UNAVAILABLE, dept = "CSSD", min = 5),
        )
        val report = EndOfDayReportBuilder.build(cases, delays, metrics(cases, emptyList()))

        assertEquals(55, report.lostMinutes)
        assertEquals(DelayCode.STERILE_SET_UNAVAILABLE, report.byCode.first().code)
        assertEquals(45, report.byCode.first().minutes)
        assertEquals(2, report.byCode.first().occurrences)
        assertEquals("CSSD", report.byDept.first().dept)
        assertEquals(45, report.byDept.first().minutes)
    }

    @Test
    fun `avoidable minutes count only the ones judged avoidable`() {
        val cases = listOf(case("a", "1", 0))
        val delays = listOf(
            delay("d1", "a", min = 40, avoidable = Avoidability.AVOIDABLE),
            delay("d2", "a", min = 30, avoidable = Avoidability.UNAVOIDABLE),
            delay("d3", "a", min = 20, avoidable = Avoidability.UNCLEAR),
        )
        val report = EndOfDayReportBuilder.build(cases, delays, metrics(cases, emptyList()))
        assertEquals(90, report.lostMinutes)
        assertEquals(40, report.avoidableMinutes)
    }

    @Test
    fun `a delay whose case no longer exists still reports, with an unknown case number`() {
        val cases = listOf(case("a", "1", 0))
        val report = EndOfDayReportBuilder.build(
            cases,
            listOf(delay("d1", "ghost", min = 15)),
            metrics(cases, emptyList()),
        )
        assertEquals(15, report.lostMinutes)
        assertEquals("?", report.attributions.single().caseNumber)
    }

    @Test
    fun `an empty day reports zeroes and says so in the headline`() {
        val report = EndOfDayReportBuilder.build(emptyList(), emptyList(), DayMetrics.Empty)
        assertEquals(0, report.lostMinutes)
        assertEquals("—", report.theatreId)
        assertTrue(EndOfDayReportBuilder.headline(report).contains("no attributed delay"))
    }

    @Test
    fun `the headline names the worst department`() {
        val cases = listOf(case("a", "1", 0))
        val report = EndOfDayReportBuilder.build(
            cases,
            listOf(delay("d1", "a", dept = "CSSD", min = 31)),
            metrics(cases, emptyList()),
        )
        val headline = EndOfDayReportBuilder.headline(report)
        assertTrue(headline, headline.contains("OT-2 lost 31 minutes"))
        assertTrue(headline, headline.contains("31 of them were CSSD"))
    }

    @Test
    fun `scheduled and completed counts come from the cases and the marks`() {
        val cases = listOf(case("a", "1", 0, durationMin = 60), case("b", "2", 1, durationMin = 45))
        val events = listOf(
            event("e1", "a", EventType.PATIENT_IN_ROOM, 0),
            event("e2", "a", EventType.PATIENT_OUT, 70),
        )
        val report = EndOfDayReportBuilder.build(cases, emptyList(), metrics(cases, events))
        assertEquals(2, report.casesScheduled)
        assertEquals(1, report.casesCompleted)
        assertEquals(105, report.scheduledMinutes)
    }
}
