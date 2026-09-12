package com.etio.ot.data.local

import androidx.room.TypeConverter
import com.etio.ot.data.model.Audience
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.CaseStatus
import com.etio.ot.data.model.ChecklistPhase
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.model.EventSource
import com.etio.ot.data.model.EventType

/**
 * Enums are stored as their names, not ordinals — reordering an enum must never
 * silently reinterpret existing rows.
 */
class Converters {
    @TypeConverter fun caseStatusTo(v: CaseStatus): String = v.name
    @TypeConverter fun caseStatusFrom(v: String): CaseStatus = CaseStatus.valueOf(v)

    @TypeConverter fun eventTypeTo(v: EventType): String = v.name
    @TypeConverter fun eventTypeFrom(v: String): EventType = EventType.valueOf(v)

    @TypeConverter fun eventSourceTo(v: EventSource): String = v.name
    @TypeConverter fun eventSourceFrom(v: String): EventSource = EventSource.valueOf(v)

    @TypeConverter fun delayCodeTo(v: DelayCode): String = v.name
    @TypeConverter fun delayCodeFrom(v: String): DelayCode = DelayCode.fromModelOutput(v)

    @TypeConverter fun avoidabilityTo(v: Avoidability): String = v.name
    @TypeConverter fun avoidabilityFrom(v: String): Avoidability =
        runCatching { Avoidability.valueOf(v) }.getOrDefault(Avoidability.UNCLEAR)

    @TypeConverter fun audienceTo(v: Audience): String = v.name
    @TypeConverter fun audienceFrom(v: String): Audience = Audience.valueOf(v)

    @TypeConverter fun phaseTo(v: ChecklistPhase): String = v.name
    @TypeConverter fun phaseFrom(v: String): ChecklistPhase = ChecklistPhase.valueOf(v)

    @TypeConverter fun stringListTo(v: List<String>): String = v.joinToString("")
    @TypeConverter fun stringListFrom(v: String): List<String> =
        if (v.isEmpty()) emptyList() else v.split("")
}
