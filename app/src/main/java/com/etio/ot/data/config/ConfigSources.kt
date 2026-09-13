package com.etio.ot.data.config

/**
 * The narrow slices of [ConfigProvider] that the repositories actually read.
 *
 * Same reasoning as [com.etio.ot.ai.PromptSource] in the ai lane: [ConfigProvider] is
 * a concrete class that needs an Android Context and an AssetManager, so depending on
 * it directly puts the seeding rules and the checklist rules permanently out of reach
 * of a JVM unit test. Neither repository wants the whole provider — one reads
 * `seed()`, the other reads `checklist()` — so each takes only that.
 *
 * Additive: [ConfigProvider] itself is untouched (spine-owned), and the adapters below
 * are what the DI modules hand over.
 */

/** What [com.etio.ot.data.repository.CaseRepository] needs: the day's seeded list. */
fun interface SeedSource {
    fun seed(): SeedConfig
}

/** What [com.etio.ot.data.repository.ChecklistRepository] needs: the WHO item text. */
fun interface ChecklistSource {
    fun checklist(): ChecklistConfig
}

fun ConfigProvider.asSeedSource(): SeedSource = SeedSource { seed() }

fun ConfigProvider.asChecklistSource(): ChecklistSource = ChecklistSource { checklist() }
