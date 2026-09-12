package com.etio.ot.di

import android.content.Context
import com.etio.ot.ai.AndroidSpeechCapture
import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.FakeLlmEngine
import com.etio.ot.ai.LlmEngine
import com.etio.ot.ai.MediaPipeLlmEngine
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.ai.ModelLocator
import com.etio.ot.ai.SpeechCapture
import com.etio.ot.core.Clock
import com.etio.ot.data.config.ConfigProvider
import com.etio.ot.data.local.EtioDatabase
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.ChecklistRepository
import com.etio.ot.data.repository.DelayRepository
import java.io.File

/**
 * Manual DI. No Hilt on purpose: in an 8-hour build, KAPT/KSP annotation rounds and
 * a component graph cost more than they save for a single-module app.
 */
object ServiceLocator {

    /**
     * Flip to true to build and demo the entire UI, timer and checklist path before
     * the model file is on the device.
     */
    const val USE_FAKE_LLM = false

    /** GPU first, CPU fallback is automatic inside the engine (PRD §10). */
    const val PREFER_GPU = true

    private lateinit var appContext: Context

    val clock: Clock = Clock.System

    val config: ConfigProvider by lazy { ConfigProvider(appContext) }

    private val database: EtioDatabase by lazy { EtioDatabase.build(appContext) }

    val llmEngine: LlmEngine by lazy {
        if (USE_FAKE_LLM) {
            FakeLlmEngine()
        } else {
            MediaPipeLlmEngine(
                context = appContext,
                modelPath = resolveModelPath(),
                preferGpu = PREFER_GPU,
            )
        }
    }

    val speechCapture: SpeechCapture by lazy { AndroidSpeechCapture(appContext) }

    private val classifier: DelayClassifier by lazy { DelayClassifier(llmEngine, config) }
    private val drafter: MessageDrafter by lazy { MessageDrafter(llmEngine, config) }

    val caseRepository: CaseRepository by lazy {
        CaseRepository(database.caseDao(), database.eventDao(), config, clock)
    }

    val delayRepository: DelayRepository by lazy {
        DelayRepository(
            delayDao = database.delayRecordDao(),
            messageDao = database.generatedMessageDao(),
            classifier = classifier,
            drafter = drafter,
            caseRepository = caseRepository,
            clock = clock,
        )
    }

    val checklistRepository: ChecklistRepository by lazy {
        ChecklistRepository(database.checklistRunDao(), config, clock)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * App-private copy first (fastest read, survives an adb-tmp wipe); otherwise the
     * sideload path. See README for both `adb push` commands.
     */
    fun resolveModelPath(): String {
        val private = ModelLocator.appPrivatePath(appContext.filesDir.absolutePath)
        return if (File(private).exists()) private else ModelLocator.SIDELOAD_PATH
    }

    fun modelPresent(): Boolean = File(resolveModelPath()).exists()
}
