package com.etio.ot.di

import com.etio.ot.data.config.ConfigProvider
import com.etio.ot.data.config.asSeedSource
import com.etio.ot.data.local.EtioDatabase
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.settings.SettingsStore

/**
 * OWNER: spine branch.
 *
 * The database and config live here because both other slices need them, and a
 * single owner for the Room instance is the only way `version = 1` stays honest.
 *
 * If you are on the AI or safety branch and need a new DAO exposed, ask the spine
 * owner to add it rather than editing this file — that is the one change that
 * would conflict in all three branches at once.
 */
object CoreModule {

    val config: ConfigProvider by lazy { ConfigProvider(ServiceLocator.appContext) }

    /** Theme choice and the tutorial flag — the two things a demo reset must not clear. */
    val settingsStore: SettingsStore by lazy { SettingsStore(ServiceLocator.appContext) }

    val database: EtioDatabase by lazy { EtioDatabase.build(ServiceLocator.appContext) }

    val caseRepository: CaseRepository by lazy {
        CaseRepository(
            caseDao = database.caseDao(),
            eventDao = database.eventDao(),
            config = config.asSeedSource(),
            clock = ServiceLocator.clock,
        )
    }
}
