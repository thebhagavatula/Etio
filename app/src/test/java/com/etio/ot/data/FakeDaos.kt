package com.etio.ot.data

import com.etio.ot.data.local.dao.CaseDao
import com.etio.ot.data.local.dao.ChecklistRunDao
import com.etio.ot.data.local.dao.DelayRecordDao
import com.etio.ot.data.local.dao.EventDao
import com.etio.ot.data.local.dao.GeneratedMessageDao
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.ChecklistPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-ins for the Room DAOs.
 *
 * They implement the same contracts the generated code does — including the ones that
 * are easy to forget, like events being ordered by timestamp and live rows excluding
 * anything superseded — so a repository test failing here means the repository is
 * wrong, not that the fake is lying.
 */

class FakeCaseDao(seed: List<CaseEntity> = emptyList()) : CaseDao {
    private val rows = MutableStateFlow(seed)

    override fun observeAll(): Flow<List<CaseEntity>> = rows.map { it.sortedBy { c -> c.orderIndex } }
    override fun observeById(id: String): Flow<CaseEntity?> = rows.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun getById(id: String): CaseEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getAll(): List<CaseEntity> = rows.value.sortedBy { it.orderIndex }
    override suspend fun count(): Int = rows.value.size

    override suspend fun upsertAll(cases: List<CaseEntity>) {
        cases.forEach { upsert(it) }
    }

    override suspend fun upsert(case: CaseEntity) {
        rows.value = rows.value.filterNot { it.id == case.id } + case
    }

    override suspend fun updateStatus(id: String, status: CaseStatus) {
        rows.value = rows.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    /** Room cascades to children; the fakes are wired together in [FakeStore]. */
    override suspend fun clear() {
        rows.value = emptyList()
        onClear()
    }

    var onClear: () -> Unit = {}
}

class FakeEventDao : EventDao {
    val rows = MutableStateFlow<List<EventEntity>>(emptyList())

    private fun live() = rows.value.filter { it.supersededByEventId == null }.sortedBy { it.timestampMs }

    override fun observeLive(): Flow<List<EventEntity>> =
        rows.map { list -> list.filter { it.supersededByEventId == null }.sortedBy { it.timestampMs } }

    override fun observeForCase(caseId: String): Flow<List<EventEntity>> =
        rows.map { list ->
            list.filter { it.caseId == caseId && it.supersededByEventId == null }.sortedBy { it.timestampMs }
        }

    override suspend fun getLive(): List<EventEntity> = live()

    override suspend fun getAllIncludingSuperseded(): List<EventEntity> = rows.value.sortedBy { it.timestampMs }

    override suspend fun insert(event: EventEntity) {
        require(rows.value.none { it.id == event.id }) { "duplicate event id ${event.id}" }
        rows.value = rows.value + event
    }

    override suspend fun markSuperseded(oldId: String, newId: String) {
        rows.value = rows.value.map { if (it.id == oldId) it.copy(supersededByEventId = newId) else it }
    }
}

class FakeDelayDao : DelayRecordDao {
    val rows = MutableStateFlow<List<DelayRecordEntity>>(emptyList())

    override fun observeAll(): Flow<List<DelayRecordEntity>> =
        rows.map { list -> list.sortedByDescending { it.createdAtMs } }

    override fun observeForCase(caseId: String): Flow<List<DelayRecordEntity>> =
        rows.map { list -> list.filter { it.caseId == caseId }.sortedByDescending { it.createdAtMs } }

    override suspend fun getById(id: String): DelayRecordEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun getAll(): List<DelayRecordEntity> = rows.value.sortedBy { it.createdAtMs }

    override suspend fun upsert(record: DelayRecordEntity) {
        rows.value = rows.value.filterNot { it.id == record.id } + record
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

class FakeChecklistDao : ChecklistRunDao {
    val rows = MutableStateFlow<List<ChecklistRunEntity>>(emptyList())

    override fun observeForCase(caseId: String): Flow<List<ChecklistRunEntity>> =
        rows.map { list -> list.filter { it.caseId == caseId } }

    override suspend fun get(caseId: String, phase: ChecklistPhase): ChecklistRunEntity? =
        rows.value.firstOrNull { it.caseId == caseId && it.phase == phase }

    override suspend fun getAll(): List<ChecklistRunEntity> = rows.value

    override suspend fun upsert(run: ChecklistRunEntity) {
        rows.value = rows.value.filterNot { it.id == run.id } + run
    }
}

class FakeMessageDao : GeneratedMessageDao {
    val rows = MutableStateFlow<List<com.etio.ot.data.local.entity.GeneratedMessageEntity>>(emptyList())

    override fun observeForDelay(delayRecordId: String): Flow<List<com.etio.ot.data.local.entity.GeneratedMessageEntity>> =
        rows.map { list -> list.filter { it.delayRecordId == delayRecordId }.sortedBy { it.generatedAtMs } }

    override suspend fun getForDelay(delayRecordId: String) =
        rows.value.filter { it.delayRecordId == delayRecordId }

    override suspend fun upsert(message: com.etio.ot.data.local.entity.GeneratedMessageEntity) {
        rows.value = rows.value.filterNot { it.id == message.id } + message
    }

    override suspend fun update(message: com.etio.ot.data.local.entity.GeneratedMessageEntity) {
        rows.value = rows.value.map { if (it.id == message.id) message else it }
    }

    override suspend fun markCopied(id: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(copied = true) else it }
    }

    override suspend fun clearForDelay(delayRecordId: String) {
        rows.value = rows.value.filterNot { it.delayRecordId == delayRecordId }
    }
}

/** The five fakes wired together, with the cascade Room would give us. */
class FakeStore {
    val cases = FakeCaseDao()
    val events = FakeEventDao()
    val delays = FakeDelayDao()
    val checklists = FakeChecklistDao()
    val messages = FakeMessageDao()

    init {
        cases.onClear = {
            events.rows.value = emptyList()
            delays.rows.value = emptyList()
            checklists.rows.value = emptyList()
            messages.rows.value = emptyList()
        }
    }
}
