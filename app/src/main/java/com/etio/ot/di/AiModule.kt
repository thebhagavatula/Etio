package com.etio.ot.di

import com.etio.ot.ai.AndroidSpeechCapture
import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.FakeLlmEngine
import com.etio.ot.ai.LlmEngine
import com.etio.ot.ai.MediaPipeLlmEngine
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.ai.ModelLocator
import com.etio.ot.ai.SpeechCapture
import com.etio.ot.ai.asPromptSource
import com.etio.ot.data.repository.DelayRepository
import java.io.File

/**
 * OWNER: AI branch.
 *
 * Nobody else edits this file. The spine and safety branches can flip
 * [USE_FAKE_LLM] locally to work without a model on the device, but must not
 * commit that flip — see the merge checklist in BRANCHES.md.
 */
object AiModule {

    /**
     * true → [FakeLlmEngine], canned responses at realistic latency, no model file
     * needed. The whole UI, timer and checklist path works against it.
     *
     * ⚠️ MUST BE false ON main. The pre-merge checklist checks this.
     */
    const val USE_FAKE_LLM = false

    /** GPU first; [MediaPipeLlmEngine] falls back to CPU on its own if the driver refuses. */
    const val PREFER_GPU = true

    val llmEngine: LlmEngine by lazy {
        if (USE_FAKE_LLM) {
            FakeLlmEngine()
        } else {
            MediaPipeLlmEngine(
                context = ServiceLocator.appContext,
                modelPath = resolveModelPath(),
                preferGpu = PREFER_GPU,
            )
        }
    }

    val speechCapture: SpeechCapture by lazy { AndroidSpeechCapture(ServiceLocator.appContext) }

    private val classifier: DelayClassifier by lazy {
        DelayClassifier(llmEngine, CoreModule.config.asPromptSource())
    }

    private val drafter: MessageDrafter by lazy {
        MessageDrafter(llmEngine, CoreModule.config.asPromptSource())
    }

    val delayRepository: DelayRepository by lazy {
        DelayRepository(
            delayDao = CoreModule.database.delayRecordDao(),
            messageDao = CoreModule.database.generatedMessageDao(),
            classifier = classifier,
            drafter = drafter,
            caseRepository = CoreModule.caseRepository,
            clock = ServiceLocator.clock,
        )
    }

    /** App-private copy first (fastest read), then the adb sideload path. */
    fun resolveModelPath(): String {
        val private = ModelLocator.appPrivatePath(ServiceLocator.appContext.filesDir.absolutePath)
        return if (File(private).exists()) private else ModelLocator.SIDELOAD_PATH
    }

    fun modelPresent(): Boolean = File(resolveModelPath()).exists()
}
