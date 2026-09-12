package com.etio.ot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.local.entity.GeneratedMessageEntity
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.ChecklistPhase
import kotlinx.coroutines.flow.Flow

@Dao
interface CaseDao {
    @Query("SELECT * FROM cases ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<CaseEntity>>

    @Query("SELECT * FROM cases WHERE id = :id")
    fun observeById(id: String): Flow<CaseEntity?>

    @Query("SELECT * FROM cases WHERE id = :id")
    suspend fun getById(id: String): CaseEntity?

    @Query("SELECT * FROM cases ORDER BY orderIndex ASC")
    suspend fun getAll(): List<CaseEntity>

    @Query("SELECT COUNT(*) FROM cases")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(cases: List<CaseEntity>)

    @Upsert
    suspend fun upsert(case: CaseEntity)

    @Query("UPDATE cases SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: CaseStatus)

    @Query("DELETE FROM cases")
    suspend fun clear()
}

@Dao
interface EventDao {
    /** Live events only — superseded rows are kept for audit but hidden from timers. */
    @Query("SELECT * FROM events WHERE supersededByEventId IS NULL ORDER BY timestampMs ASC")
    fun observeLive(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE caseId = :caseId AND supersededByEventId IS NULL ORDER BY timestampMs ASC")
    fun observeForCase(caseId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE supersededByEventId IS NULL ORDER BY timestampMs ASC")
    suspend fun getLive(): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY timestampMs ASC")
    suspend fun getAllIncludingSuperseded(): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: EventEntity)

    @Query("UPDATE events SET supersededByEventId = :newId WHERE id = :oldId")
    suspend fun markSuperseded(oldId: String, newId: String)

    /** Append-only correction: never mutate the original row's timestamp. */
    @Transaction
    suspend fun correct(old: EventEntity, corrected: EventEntity) {
        insert(corrected)
        markSuperseded(old.id, corrected.id)
    }
}

@Dao
interface DelayRecordDao {
    @Query("SELECT * FROM delay_records ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<DelayRecordEntity>>

    @Query("SELECT * FROM delay_records WHERE caseId = :caseId ORDER BY createdAtMs DESC")
    fun observeForCase(caseId: String): Flow<List<DelayRecordEntity>>

    @Query("SELECT * FROM delay_records WHERE id = :id")
    suspend fun getById(id: String): DelayRecordEntity?

    @Query("SELECT * FROM delay_records ORDER BY createdAtMs ASC")
    suspend fun getAll(): List<DelayRecordEntity>

    @Upsert
    suspend fun upsert(record: DelayRecordEntity)

    @Query("DELETE FROM delay_records WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChecklistRunDao {
    @Query("SELECT * FROM checklist_runs WHERE caseId = :caseId")
    fun observeForCase(caseId: String): Flow<List<ChecklistRunEntity>>

    @Query("SELECT * FROM checklist_runs WHERE caseId = :caseId AND phase = :phase LIMIT 1")
    suspend fun get(caseId: String, phase: ChecklistPhase): ChecklistRunEntity?

    @Query("SELECT * FROM checklist_runs")
    suspend fun getAll(): List<ChecklistRunEntity>

    @Upsert
    suspend fun upsert(run: ChecklistRunEntity)
}

@Dao
interface GeneratedMessageDao {
    @Query("SELECT * FROM generated_messages WHERE delayRecordId = :delayRecordId ORDER BY generatedAtMs ASC")
    fun observeForDelay(delayRecordId: String): Flow<List<GeneratedMessageEntity>>

    @Query("SELECT * FROM generated_messages WHERE delayRecordId = :delayRecordId")
    suspend fun getForDelay(delayRecordId: String): List<GeneratedMessageEntity>

    @Upsert
    suspend fun upsert(message: GeneratedMessageEntity)

    @Update
    suspend fun update(message: GeneratedMessageEntity)

    @Query("UPDATE generated_messages SET copied = 1 WHERE id = :id")
    suspend fun markCopied(id: String)

    @Query("DELETE FROM generated_messages WHERE delayRecordId = :delayRecordId")
    suspend fun clearForDelay(delayRecordId: String)
}
