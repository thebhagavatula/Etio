package com.etio.ot.data.model

/**
 * Every enum in the app lives here so the taxonomy is readable in one screen.
 *
 * NOTE ON THE CONTRACT WITH THE MODEL:
 * [DelayCode] is the closed set the LLM must emit verbatim. It is validated in code
 * ([com.etio.ot.ai.DelayJsonValidator]) — the model is never trusted to stay inside it.
 */

enum class CaseStatus {
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
}

/** Ordered progress events. Ordinal order == expected clinical order. */
enum class EventType(val label: String, val shortLabel: String) {
    PATIENT_SENT_FOR("Sent for", "Sent for"),
    PATIENT_IN_ROOM("Patient in room", "In room"),
    ANAESTHESIA_START("Anaesthesia start", "Anaes"),
    KNIFE_TO_SKIN("Knife to skin", "Knife"),
    CLOSURE_COMPLETE("Closure complete", "Closed"),
    PATIENT_OUT("Patient out", "Out"),
    ROOM_CLEAN_START("Room clean start", "Clean"),
    ROOM_READY("Room ready", "Ready"),
    ;

    companion object {
        val ordered: List<EventType> = entries.toList()
    }
}

/**
 * How an event row got its timestamp. [INFERRED] rows are written by the app when a
 * later event is marked and an earlier one is missing: never silently, always shown
 * as assumed, and correctable like any other row.
 */
enum class EventSource { TAP, VOICE, INFERRED }

/**
 * Fixed 11-value taxonomy (PRD §9, Job 1). Do not add values without also
 * updating assets/config/taxonomy.json — the prompt reads from there.
 */
enum class DelayCode(val display: String) {
    SURGEON_LATE("Surgeon late"),
    ANAESTHESIA_DELAY("Anaesthesia delay"),
    STERILE_SET_UNAVAILABLE("Sterile set unavailable"),
    PATIENT_NOT_READY("Patient not ready"),
    CONSENT_INCOMPLETE("Consent incomplete"),
    PREVIOUS_CASE_OVERRUN("Previous case overrun"),
    PORTER_TRANSPORT("Porter / transport"),
    BLOOD_PRODUCTS("Blood products"),
    EQUIPMENT_FAILURE("Equipment failure"),
    ICU_BED_UNAVAILABLE("ICU bed unavailable"),
    OTHER("Other"),
    ;

    companion object {
        /** Strict, case-insensitive lookup. Anything unrecognised becomes [OTHER]. */
        fun fromModelOutput(raw: String?): DelayCode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OTHER
    }
}

/** Tri-state: the model must be allowed to say it does not know. */
enum class Avoidability { AVOIDABLE, UNAVOIDABLE, UNCLEAR;
    companion object {
        fun fromModelOutput(raw: Any?): Avoidability = when {
            raw is Boolean && raw -> AVOIDABLE
            raw is Boolean && !raw -> UNAVOIDABLE
            raw is String && raw.equals("true", true) -> AVOIDABLE
            raw is String && raw.equals("false", true) -> UNAVOIDABLE
            else -> UNCLEAR
        }
    }
}

/** The four recipients of a single delay event (PRD §9, Job 2). */
enum class Audience(val display: String) {
    WARD("Ward"),
    FAMILY("Patient's family"),
    SURGEON("Surgeon"),
    ANAESTHESIA("Anaesthesia"),
    ;

    companion object {
        /** Demo order: surgeon and family adjacent so the contrast lands. */
        val demoOrder: List<Audience> = listOf(SURGEON, FAMILY, WARD, ANAESTHESIA)
    }
}

/** WHO Surgical Safety Checklist phases (PRD §7, F6). */
enum class ChecklistPhase(val display: String, val triggerEvent: EventType) {
    SIGN_IN("Sign In", EventType.PATIENT_IN_ROOM),
    TIME_OUT("Time Out", EventType.ANAESTHESIA_START),
    SIGN_OUT("Sign Out", EventType.CLOSURE_COMPLETE),
    ;

    companion object {
        fun forEvent(event: EventType): ChecklistPhase? =
            entries.firstOrNull { it.triggerEvent == event }
    }
}
