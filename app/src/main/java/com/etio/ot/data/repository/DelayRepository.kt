package com.etio.ot.data.repository

import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.core.Clock
import com.etio.ot.core.newId
import com.etio.ot.data.local.dao.DelayRecordDao
import com.etio.ot.data.local.dao.GeneratedMessageDao
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.GeneratedMessageEntity
import com.etio.ot.domain.timing.ScheduleProjector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DelayRepository(
    private val delayDao: DelayRecordDao,
    private val messageDao: GeneratedMessageDao,
    private val classifier: DelayClassifier,
    private val drafter: MessageDrafter,
    private val caseRepository: CaseRepository,
    private val clock: Clock = Clock.System,
) {

    val delays: Flow<List<DelayRecordEntity>> = delayDao.observeAll()

    fun delaysForCase(caseId: String): Flow<List<DelayRecordEntity>> = delayDao.observeForCase(caseId)

    fun messagesFor(delayId: String): Flow<List<GeneratedMessageEntity>> =
        messageDao.observeForDelay(delayId)

    /**
     * Job 1. Always returns a record — a failed or unparseable inference becomes an
     * OTHER with the transcript as the note, flagged for the coordinator to correct.
     * The record is NOT persisted here; the review card persists it on confirm, so a
     * discarded capture leaves no row.
     */
    suspend fun classify(caseId: String, transcript: String): DelayRecordEntity {
        // One sample unless config asks for more. The agreement ratio is only stored
        // when it was actually measured — a 1.0 from a single sample would be a
        // confidence figure invented by arithmetic.
        val samples = classifier.voteSamples()
        val outcome = classifier.classifyVoted(transcript, samples)
        val parsed = outcome.merged
        return DelayRecordEntity(
            id = newId(),
            caseId = caseId,
            createdAtMs = clock.nowMs(),
            transcriptRaw = transcript,
            code = parsed.code,
            attributedDept = parsed.attributedDept,
            avoidable = parsed.avoidable,
            estimatedMin = parsed.estimatedMin,
            note = parsed.note,
            modelConfidence = parsed.confidence,
            userEdited = false,
            fellBackToOther = parsed.fellBack,
            noteGrounded = parsed.noteGrounded,
            estimatedMinGrounded = parsed.estimatedMinGrounded,
            deptGrounded = parsed.deptGrounded,
            agreementRatio = if (samples > 1) outcome.agreementRatio else null,
        )
    }

    suspend fun save(record: DelayRecordEntity) = delayDao.upsert(record)

    suspend fun get(id: String): DelayRecordEntity? = delayDao.getById(id)

    suspend fun allDelays(): List<DelayRecordEntity> = delayDao.getAll()

    suspend fun delete(id: String) = delayDao.delete(id)

    /**
     * Job 2. Emits each message as it finishes so the UI fills in progressively.
     * Messages are persisted as they arrive — closing the screen mid-generation still
     * keeps what was produced.
     */
    fun draftMessages(record: DelayRecordEntity): Flow<GeneratedMessageEntity> = flow {
        messageDao.clearForDelay(record.id)
        val case = caseRepository.getCase(record.caseId)
        drafter.draftAll(record, case, shiftFor(record)).collect { draft ->
            val row = GeneratedMessageEntity(
                id = newId(),
                delayRecordId = record.id,
                audience = draft.audience,
                body = draft.body,
                generatedAtMs = clock.nowMs(),
            )
            messageDao.upsert(row)
            emit(row)
        }
    }

    /**
     * The revised schedule handed to Job 2. Computed here, in Kotlin, from the stated
     * duration only — null when nothing was said, so no message can carry a made-up time.
     */
    suspend fun shiftFor(record: DelayRecordEntity): ScheduleProjector.Shift? =
        ScheduleProjector.project(
            cases = caseRepository.allCases(),
            delayedCaseId = record.caseId,
            estimatedMin = record.estimatedMin,
            nowMs = clock.nowMs(),
        )

    suspend fun regenerate(record: DelayRecordEntity, existing: GeneratedMessageEntity) {
        val case = caseRepository.getCase(record.caseId)
        val body = drafter.draftOne(existing.audience, record, case, shiftFor(record))
        messageDao.update(existing.copy(body = body, generatedAtMs = clock.nowMs(), copied = false))
    }

    suspend fun markCopied(messageId: String) = messageDao.markCopied(messageId)
}
