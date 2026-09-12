package com.etio.ot.ai

import com.etio.ot.data.config.PromptConfig
import com.etio.ot.data.config.TaxonomyConfig

/**
 * The subset of [com.etio.ot.data.config.ConfigProvider] that Job 1/Job 2 prompt
 * building needs. [DelayClassifier] and [MessageDrafter] depend on this instead of
 * the concrete provider so tests can supply a plain fixture instead of a real
 * Android [android.content.Context] + AssetManager.
 */
interface PromptSource {
    fun prompts(): PromptConfig
    fun taxonomy(): TaxonomyConfig
}
