package com.etio.ot.data.repository

import com.etio.ot.core.Clock
import com.etio.ot.core.newId
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.config.ConfigProvider
import com.etio.ot.data.local.dao.ChecklistRunDao
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.checklist.ChecklistStateMachine
import kotlinx.coroutines.flow.Flow

class ChecklistRepository(
    private val dao: ChecklistRunDao,
    private val config: ConfigProvider,
    private val clock: Clock = Clock.System,
) {

    fun runsForCase(caseId: String): Flow<List<ChecklistRunEntity>> = dao.observeForCase(caseId)

    fun items(phase: ChecklistPhase): List<ChecklistItem> =
        config.checklist().phases[phase.name].orEmpty()

    fun itemsByPhase(): Map<ChecklistPhase, List<ChecklistItem>> =
        ChecklistPhase.entries.associateWith { items(it) }

    suspend fun get(caseId: String, phase: ChecklistPhase): ChecklistRunEntity? = dao.get(caseId, phase)

    suspend fun allRuns(): List<ChecklistRunEntity> = dao.getAll()

    /** Called when the trigger event is marked. Idempotent. */
    suspend fun openPhase(caseId: String, phase: ChecklistPhase): ChecklistRunEntity {
        dao.get(caseId, phase)?.let { return it }
        val run = ChecklistRunEntity(id = newId(), caseId = caseId, phase = phase)
        dao.upsert(run)
        return run
    }

    suspend fun toggleItem(caseId: String, phase: ChecklistPhase, itemId: String) {
        val run = openPhase(caseId, phase)
        val next = if (itemId in run.itemsConfirmed) {
            run.itemsConfirmed - itemId
        } else {
            run.itemsConfirmed + itemId
        }
        dao.upsert(run.copy(itemsConfirmed = next, completedAtMs = null))
    }

    /** Only succeeds when every critical item is confirmed. */
    suspend fun complete(caseId: String, phase: ChecklistPhase): Boolean {
        val run = openPhase(caseId, phase)
        val items = items(phase)
        val criticalIds = items.filter { it.critical }.map { it.id }.toSet()
        if (!run.itemsConfirmed.containsAll(criticalIds)) return false
        dao.upsert(run.copy(completedAtMs = clock.nowMs(), skipped = false, skipReason = null))
        return true
    }

    /** Skipping is allowed but never silent — the reason is stored and shows in the report. */
    suspend fun skip(caseId: String, phase: ChecklistPhase, reason: String) {
        val run = openPhase(caseId, phase)
        dao.upsert(run.copy(skipped = true, skipReason = reason.trim(), completedAtMs = clock.nowMs()))
    }

    /** Null when [next] may be marked; otherwise the phase that must be dealt with first. */
    suspend fun gateFor(
        caseId: String,
        next: EventType,
        markedEvents: Set<EventType>,
    ): ChecklistPhase? {
        val runs = ChecklistPhase.entries.associateWith { dao.get(caseId, it) }
        return ChecklistStateMachine.gate(next, markedEvents, runs, itemsByPhase())
    }
}
