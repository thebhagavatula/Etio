package com.etio.ot.di

import com.etio.ot.ai.AndroidSpeechCapture
import com.etio.ot.ai.DelayClassifier
import com.etio.ot.ai.FakeLlmEngine
import com.etio.ot.ai.LlmEngine
import com.etio.ot.ai.MediaPipeLlmEngine
import com.etio.ot.ai.MessageDraftCoordinator
import com.etio.ot.ai.MessageDrafter
import com.etio.ot.ai.ModelLocator
import com.etio.ot.ai.SpeechCapture
import com.etio.ot.ai.WarmUpSpec
import com.etio.ot.ai.asPromptSource
import com.etio.ot.data.repository.DelayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
                warmUpSpecs = ::warmUpSpecs,
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

    /**
     * Job 2 runs here rather than in a screen's ViewModel, so drafting survives the
     * coordinator walking away from the capture screen the instant she confirms.
     */
    val draftCoordinator: MessageDraftCoordinator by lazy {
        MessageDraftCoordinator(
            delays = delayRepository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    /**
     * One session per job, primed at start-up with that job's real prefix.
     *
     * The classify profile also burns a throwaway utterance, so the first decode the
     * coordinator actually waits on is not the one paying for cold kernels. Drafting
     * is primed but not burned: Job 2 already runs off the critical path, and a second
     * warm-up decode is start-up time spent for no visible gain.
     */
    private fun warmUpSpecs(): List<WarmUpSpec> {
        return listOf(
            WarmUpSpec(
                profile = classifier.profile(),
                stablePrefix = classifier.stablePrefix(),
                throwawaySuffix = classifier.variableSuffix("warm up, ignore this"),
            ),
            WarmUpSpec(
                profile = drafter.profile(),
                stablePrefix = drafter.stablePrefix(),
            ),
        )
    }

    /** App-private copy first (fastest read), then the adb sideload path. */
    fun resolveModelPath(): String {
        val private = ModelLocator.appPrivatePath(ServiceLocator.appContext.filesDir.absolutePath)
        return if (File(private).exists()) private else ModelLocator.SIDELOAD_PATH
    }

    fun modelPresent(): Boolean = File(resolveModelPath()).exists()
}
