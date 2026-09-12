package com.etio.ot.data.repository

import com.etio.ot.core.Clock
import com.etio.ot.core.newId
import com.etio.ot.data.config.ConfigProvider
import com.etio.ot.data.local.dao.CaseDao
import com.etio.ot.data.local.dao.EventDao
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.timing.DayMetrics
import com.etio.ot.domain.timing.TimerEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Calendar

class CaseRepository(
    private val caseDao: CaseDao,
    private val eventDao: EventDao,
    private val config: ConfigProvider,
    private val clock: Clock = Clock.System,
) {

    val cases: Flow<List<CaseEntity>> = caseDao.observeAll()
    val events: Flow<List<EventEntity>> = eventDao.observeLive()

    /**
     * Metrics recompute whenever cases or events change. The UI additionally ticks a
     * 1s timer to refresh live spans — see CaseListViewModel.
     */
    val metrics: Flow<DayMetrics> = combine(cases, events) { c, e -> TimerEngine.compute(c, e, clock) }

    suspend fun seedIfEmpty() {
        if (caseDao.count() > 0) return
        val seeds = config.seed().cases
        val rows = seeds.mapIndexed { index, s ->
            CaseEntity(
                id = newId(),
                caseNumber = s.caseNumber,
                theatreId = s.theatreId,
                procedureName = s.procedureName,
                surgeon = s.surgeon,
                scheduledStartMs = todayAt(s.scheduledStart),
                scheduledDurationMin = s.scheduledDurationMin,
                status = CaseStatus.SCHEDULED,
                orderIndex = index,
            )
        }
        caseDao.upsertAll(rows)
    }

    /** Wipes everything and re-seeds. The button you press between rehearsals. */
    suspend fun resetDay() {
        caseDao.clear() // cascades to events, delays, checklist runs, messages
        seedIfEmpty()
    }

    suspend fun markEvent(
        caseId: String,
        type: EventType,
        atMs: Long = clock.nowMs(),
        source: EventSource = EventSource.TAP,
    ): EventEntity {
        val event = EventEntity(
            id = newId(),
            caseId = caseId,
            type = type,
            timestampMs = atMs,
            source = source,
        )
        eventDao.insert(event)
        when (type) {
            EventType.PATIENT_IN_ROOM -> caseDao.updateStatus(caseId, CaseStatus.IN_PROGRESS)
            EventType.PATIENT_OUT -> caseDao.updateStatus(caseId, CaseStatus.COMPLETED)
            else -> Unit
        }
        return event
    }

    /** Append-only correction (long-press on the stage). */
    suspend fun correctEvent(old: EventEntity, newTimestampMs: Long) {
        val corrected = old.copy(
            id = newId(),
            timestampMs = newTimestampMs,
            correctedFromEventId = old.id,
            supersededByEventId = null,
        )
        eventDao.correct(old, corrected)
    }

    suspend fun getCase(id: String): CaseEntity? = caseDao.getById(id)
    suspend fun allCases(): List<CaseEntity> = caseDao.getAll()
    suspend fun liveEvents(): List<EventEntity> = eventDao.getLive()

    private fun todayAt(hhmm: String): Long {
        val (h, m) = hhmm.split(":").map { it.trim().toIntOrNull() ?: 0 }
        return Calendar.getInstance().apply {
            timeInMillis = clock.nowMs()
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
