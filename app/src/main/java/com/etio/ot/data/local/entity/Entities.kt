package com.etio.ot.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.etio.ot.data.model.Audience
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType

/** PRD §8 data model, one file. All IDs are app-generated UUID strings. */

@Entity(tableName = "cases", indices = [Index("orderIndex")])
data class CaseEntity(
    @PrimaryKey val id: String,
    val caseNumber: String,
    val theatreId: String,
    val procedureName: String,
    val surgeon: String,
    /** Epoch millis of the scheduled start. */
    val scheduledStartMs: Long,
    val scheduledDurationMin: Int,
    val status: CaseStatus = CaseStatus.SCHEDULED,
    val orderIndex: Int,
)

/**
 * Append-only. A correction writes a NEW row and points [correctedFromEventId] at
 * the row it supersedes; nothing is ever updated in place. The audit trail is the
 * answer to "how do we know the timestamps are real".
 */
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(
        entity = CaseEntity::class,
        parentColumns = ["id"],
        childColumns = ["caseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("caseId"), Index("timestampMs")],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val caseId: String,
    val type: EventType,
    val timestampMs: Long,
    val correctedFromEventId: String? = null,
    val supersededByEventId: String? = null,
    val source: EventSource = EventSource.TAP,
)

/**
 * The one field a timer cannot produce.
 * [transcriptRaw] is never discarded — it is the grounding evidence shown beside
 * every model-assigned field.
 */
@Entity(
    tableName = "delay_records",
    foreignKeys = [ForeignKey(
        entity = CaseEntity::class,
        parentColumns = ["id"],
        childColumns = ["caseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("caseId"), Index("createdAtMs")],
)
data class DelayRecordEntity(
    @PrimaryKey val id: String,
    val caseId: String,
    val createdAtMs: Long,
    val transcriptRaw: String,
    val code: DelayCode,
    val attributedDept: String,
    val avoidable: Avoidability,
    /** Null unless a duration was explicitly spoken. The model must never estimate. */
    val estimatedMin: Int? = null,
    val note: String,
    val modelConfidence: Float = 0f,
    val userEdited: Boolean = false,
    /** True when the model output failed validation and we fell back to OTHER. */
    val fellBackToOther: Boolean = false,
    /**
     * Extractive-grounding results, from [com.etio.ot.ai.GroundingVerifier]. Each says
     * the field was found in the transcript rather than introduced by the model.
     */
    val noteGrounded: Boolean = true,
    val estimatedMinGrounded: Boolean = true,
    val deptGrounded: Boolean = true,
    /**
     * Share of self-consistency samples that agreed on [code]. Measured behaviour, not
     * self-report — kept ALONGSIDE [modelConfidence] rather than replacing it, because
     * the gap between the two is itself worth looking at.
     *
     * Null when the record was classified with a single sample.
     */
    val agreementRatio: Float? = null,
)

@Entity(
    tableName = "checklist_runs",
    foreignKeys = [ForeignKey(
        entity = CaseEntity::class,
        parentColumns = ["id"],
        childColumns = ["caseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["caseId", "phase"], unique = true)],
)
data class ChecklistRunEntity(
    @PrimaryKey val id: String,
    val caseId: String,
    val phase: ChecklistPhase,
    /** Item ids confirmed, as stored in assets/config/checklist.json. */
    val itemsConfirmed: List<String> = emptyList(),
    val skipped: Boolean = false,
    val skipReason: String? = null,
    val completedAtMs: Long? = null,
)

@Entity(
    tableName = "generated_messages",
    foreignKeys = [ForeignKey(
        entity = DelayRecordEntity::class,
        parentColumns = ["id"],
        childColumns = ["delayRecordId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("delayRecordId")],
)
data class GeneratedMessageEntity(
    @PrimaryKey val id: String,
    val delayRecordId: String,
    val audience: Audience,
    val body: String,
    val generatedAtMs: Long,
    val copied: Boolean = false,
)
