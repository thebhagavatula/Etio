package com.etio.ot.data.repository

import android.util.Log
import com.etio.ot.core.Clock
import com.etio.ot.core.newId
import com.etio.ot.data.config.ChecklistItem
import com.etio.ot.data.config.ChecklistSource
import com.etio.ot.data.local.dao.ChecklistRunDao
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.EventType
import com.etio.ot.domain.checklist.ChecklistStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

class ChecklistRepository(
    private val dao: ChecklistRunDao,
    private val config: ChecklistSource,
    private val clock: Clock = Clock.System,
) {
    private val tag = "ChecklistRepository"

    fun runsForCase(caseId: String): Flow<List<ChecklistRunEntity>> = 
        dao.observeForCase(caseId).catch { e ->
            Log.e(tag, "Error observing runs for case $caseId", e)
            emit(emptyList())
        }

    fun items(phase: ChecklistPhase): List<ChecklistItem> =
        config.checklist().phases[phase.name].orEmpty()

    fun itemsByPhase(): Map<ChecklistPhase, List<ChecklistItem>> =
        ChecklistPhase.entries.associateWith { items(it) }

    suspend fun get(caseId: String, phase: ChecklistPhase): ChecklistRunEntity? = 
        runCatching { dao.get(caseId, phase) }.getOrNull()

    suspend fun allRuns(): List<ChecklistRunEntity> = 
        runCatching { dao.getAll() }.getOrDefault(emptyList())

    /** Called when the trigger event is marked. Idempotent. */
    suspend fun openPhase(caseId: String, phase: ChecklistPhase): ChecklistRunEntity {
        return runCatching {
            dao.get(caseId, phase)?.let { return@runCatching it }
            val run = ChecklistRunEntity(id = newId(), caseId = caseId, phase = phase)
            dao.upsert(run)
            run
        }.getOrElse { e ->
            Log.e(tag, "Failed to open phase $phase for case $caseId", e)
            // Return a transient entity to avoid crashing UI, though it won't be saved
            ChecklistRunEntity(id = newId(), caseId = caseId, phase = phase)
        }
    }

    suspend fun toggleItem(caseId: String, phase: ChecklistPhase, itemId: String) {
        runCatching {
            val run = openPhase(caseId, phase)
            val next = if (itemId in run.itemsConfirmed) {
                run.itemsConfirmed - itemId
            } else {
                run.itemsConfirmed + itemId
            }
            dao.upsert(run.copy(itemsConfirmed = next, completedAtMs = null))
        }.onFailure { e ->
            Log.e(tag, "Failed to toggle item $itemId", e)
        }
    }

    /** Only succeeds when every critical item is confirmed. */
    suspend fun complete(caseId: String, phase: ChecklistPhase): Boolean {
        return runCatching {
            val run = openPhase(caseId, phase)
            val items = items(phase)
            val criticalIds = items.filter { it.critical }.map { it.id }.toSet()
            
            if (!run.itemsConfirmed.containsAll(criticalIds)) return@runCatching false
            
            dao.upsert(run.copy(completedAtMs = clock.nowMs(), skipped = false, skipReason = null))
            true
        }.getOrElse { e ->
            Log.e(tag, "Failed to complete phase $phase", e)
            false
        }
    }

    /** Skipping is allowed but never silent — the reason is stored and shows in the report. */
    suspend fun skip(caseId: String, phase: ChecklistPhase, reason: String) {
        runCatching {
            val run = openPhase(caseId, phase)
            dao.upsert(run.copy(skipped = true, skipReason = reason.trim(), completedAtMs = clock.nowMs()))
        }.onFailure { e ->
            Log.e(tag, "Failed to skip phase $phase", e)
        }
    }

    /** Null when [next] may be marked; otherwise the phase that must be dealt with first. */
    suspend fun gateFor(
        caseId: String,
        next: EventType,
        markedEvents: Set<EventType>,
    ): ChecklistPhase? {
        return runCatching {
            val runs = ChecklistPhase.entries.associateWith { dao.get(caseId, it) }
            ChecklistStateMachine.gate(next, markedEvents, runs, itemsByPhase())
        }.getOrElse { e ->
            Log.e(tag, "Error determining gate for next event $next", e)
            null
        }
    }
}
