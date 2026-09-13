package com.etio.ot.ui.tutorial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.core.newId
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.core.Clock
import com.etio.ot.data.local.dao.CaseDao
import com.etio.ot.data.settings.AppSettings
import com.etio.ot.di.CoreModule
import com.etio.ot.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Six steps over the real app, in a theatre that does not exist.
 *
 * The sandbox is one dummy case in an empty database — every tap the user makes is a
 * real write to real tables, which is the point, but none of it touches the seeded
 * OT2 day: on the first run that day has not been created yet, and finishing replaces
 * whatever the sandbox contains with a fresh seed.
 *
 * The completion flag is the important part. It is set on finish AND on skip, lives
 * in DataStore rather than Room, and is therefore untouched by the demo reset — the
 * tutorial cannot reappear on stage.
 */
class TutorialViewModel(
    private val cases: CaseRepository = CoreModule.caseRepository,
    private val settings: AppSettings = CoreModule.settingsStore,
    /**
     * The sandbox writes a case directly, so the DAO is a dependency rather than
     * something reached for mid-method — otherwise the tutorial's step machine cannot
     * be exercised without a real database behind it.
     */
    private val caseDao: CaseDao = CoreModule.database.caseDao(),
    private val clock: Clock = ServiceLocator.clock,
) : ViewModel() {

    private val _step = MutableStateFlow(TutorialStep.WELCOME)
    val step: StateFlow<TutorialStep> = _step.asStateFlow()

    private val _sandboxCaseId = MutableStateFlow<String?>(null)
    val sandboxCaseId: StateFlow<String?> = _sandboxCaseId.asStateFlow()

    init {
        // Steps 2 and 3 turn over on the real write, not on a tap we intercept: an
        // event appearing, and then one being superseded by a correction.
        viewModelScope.launch {
            cases.events.collect { rows ->
                when (_step.value) {
                    TutorialStep.MARK_EVENT ->
                        if (rows.isNotEmpty()) goTo(TutorialStep.CORRECT_TIME)
                    TutorialStep.CORRECT_TIME ->
                        if (rows.any { it.correctedFromEventId != null }) goTo(TutorialStep.SPEAK_DELAY)
                    else -> Unit
                }
            }
        }
    }

    /** Replaces whatever is in the database with the single demo case. */
    fun startSandbox() {
        if (_sandboxCaseId.value != null) return
        viewModelScope.launch {
            val dao = caseDao
            dao.clear()
            val id = newId()
            dao.upsert(
                CaseEntity(
                    id = id,
                    caseNumber = "Demo",
                    theatreId = "OT1",
                    procedureName = "Demo Case",
                    surgeon = "Dr Placeholder",
                    scheduledStartMs = clock.nowMs(),
                    scheduledDurationMin = 45,
                    orderIndex = 0,
                ),
            )
            _sandboxCaseId.value = id
        }
    }

    fun advance() {
        _step.value = TutorialStep.entries.getOrElse(_step.value.ordinal + 1) { TutorialStep.FINALE }
    }

    fun goTo(step: TutorialStep) {
        if (step.ordinal > _step.value.ordinal) _step.value = step
    }

    /** Skip and finish are the same promise: the flag is set, and the seeded day is loaded. */
    fun skip(onDone: () -> Unit) = finish(onDone)

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setTutorialCompleted(true)
            cases.resetDay()
            onDone()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TutorialViewModel() as T
        }
    }
}

/** Six, in order. One sentence each. */
enum class TutorialStep(val body: String) {
    WELCOME(
        "Etio logs your theatre day and turns what you say into structured records. " +
            "Everything runs on this phone, offline.",
    ),
    MARK_EVENT("Tap here to mark what just happened. Etio always knows what comes next."),
    CORRECT_TIME("Long-press any event to correct its time."),
    SPEAK_DELAY("When something holds the list up, tap once to start, tap again to stop. Just say what happened."),
    TRANSCRIPT("Your exact words are always kept underneath. Tap any field to correct it."),
    FINALE(
        "Etio drafts the four messages you'd otherwise type — ward, family, surgeon, anaesthesia.",
    ),
    ;

    val isLast: Boolean get() = this == FINALE
}
