package com.etio.ot.ai

import com.etio.ot.data.config.ConfigProvider
import com.etio.ot.data.config.PromptConfig
import com.etio.ot.data.config.TaxonomyConfig

/**
 * The subset of [ConfigProvider] that Job 1/Job 2 prompt building needs.
 * [DelayClassifier] and [MessageDrafter] depend on this instead of the concrete
 * provider so tests can supply a plain fixture instead of a real Android
 * [android.content.Context] + AssetManager.
 *
 * Deliberately does NOT touch ConfigProvider.kt itself — that file is spine-owned
 * (see CODEOWNERS/BRANCHES.md). [asPromptSource] adapts the existing instance from
 * the ai lane's side instead.
 */
interface PromptSource {
    fun prompts(): PromptConfig
    fun taxonomy(): TaxonomyConfig
}

fun ConfigProvider.asPromptSource(): PromptSource = object : PromptSource {
    override fun prompts(): PromptConfig = this@asPromptSource.prompts()
    override fun taxonomy(): TaxonomyConfig = this@asPromptSource.taxonomy()
}
