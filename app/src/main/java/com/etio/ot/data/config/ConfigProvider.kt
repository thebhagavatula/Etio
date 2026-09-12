package com.etio.ot.data.config

import android.content.Context
import android.util.Log
import com.etio.ot.ai.PromptSource
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Red Light architecture (PRD §11).
 *
 * On first launch every file in assets/config/ is copied to <files>/config/.
 * Thereafter the app reads ONLY from <files>/config/, so the JSON can be edited on
 * the phone between relaunches. [reload] re-reads from disk without a reinstall.
 *
 * Every accessor falls back to the bundled asset if the on-disk copy is malformed,
 * so a bad edit at 3am cannot brick the demo.
 */
class ConfigProvider(private val context: Context) : PromptSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val configDir: File by lazy {
        File(context.filesDir, "config").apply { mkdirs() }
    }

    @Volatile private var prompts: PromptConfig? = null
    @Volatile private var taxonomy: TaxonomyConfig? = null
    @Volatile private var checklist: ChecklistConfig? = null
    @Volatile private var seed: SeedConfig? = null

    /** Call once from Application.onCreate before anything reads config. */
    fun installIfNeeded(force: Boolean = false) {
        FILES.forEach { name ->
            val target = File(configDir, name)
            if (force || !target.exists()) {
                runCatching {
                    context.assets.open("config/$name").use { input ->
                        target.outputStream().use(input::copyTo)
                    }
                }.onFailure { Log.e(TAG, "Could not install $name", it) }
            }
        }
        reload()
    }

    fun reload() {
        prompts = load(FILE_PROMPTS)
        taxonomy = load(FILE_TAXONOMY)
        checklist = load(FILE_CHECKLIST)
        seed = load(FILE_SEED)
    }

    override fun prompts(): PromptConfig = prompts ?: load<PromptConfig>(FILE_PROMPTS, fromAssetOnly = true)!!
    override fun taxonomy(): TaxonomyConfig = taxonomy ?: load<TaxonomyConfig>(FILE_TAXONOMY, fromAssetOnly = true)!!
    fun checklist(): ChecklistConfig = checklist ?: load<ChecklistConfig>(FILE_CHECKLIST, fromAssetOnly = true)!!
    fun seed(): SeedConfig = seed ?: load<SeedConfig>(FILE_SEED, fromAssetOnly = true)!!

    /** Absolute path shown in the debug drawer so you know what to edit on the phone. */
    fun configDirPath(): String = configDir.absolutePath

    private inline fun <reified T> load(name: String, fromAssetOnly: Boolean = false): T? {
        if (!fromAssetOnly) {
            val onDisk = File(configDir, name)
            if (onDisk.exists()) {
                runCatching { json.decodeFromString<T>(onDisk.readText()) }
                    .onSuccess { return it }
                    .onFailure { Log.e(TAG, "Bad on-disk $name, falling back to asset", it) }
            }
        }
        return runCatching {
            context.assets.open("config/$name").bufferedReader().use { it.readText() }
                .let { json.decodeFromString<T>(it) }
        }.onFailure { Log.e(TAG, "Bad bundled asset $name", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "ConfigProvider"
        const val FILE_PROMPTS = "prompts.json"
        const val FILE_TAXONOMY = "taxonomy.json"
        const val FILE_CHECKLIST = "checklist.json"
        const val FILE_SEED = "seed_cases.json"
        private val FILES = listOf(FILE_PROMPTS, FILE_TAXONOMY, FILE_CHECKLIST, FILE_SEED)
    }
}
