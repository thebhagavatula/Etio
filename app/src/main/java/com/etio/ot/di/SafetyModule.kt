package com.etio.ot.di

import com.etio.ot.data.repository.ChecklistRepository

/**
 * OWNER: safety branch.
 *
 * Deliberately thin. The end-of-day report reads through [CoreModule.caseRepository]
 * and [AiModule.delayRepository] rather than holding its own, so there is exactly
 * one instance of each repository in the app.
 */
object SafetyModule {

    val checklistRepository: ChecklistRepository by lazy {
        ChecklistRepository(
            dao = CoreModule.database.checklistRunDao(),
            config = CoreModule.config,
            clock = ServiceLocator.clock,
        )
    }
}
