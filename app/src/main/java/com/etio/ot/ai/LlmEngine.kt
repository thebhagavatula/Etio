package com.etio.ot.ai

import kotlinx.coroutines.flow.Flow

/**
 * Sampling settings for one kind of work.
 *
 * MediaPipe fixes temperature and topK when a session is created, not when it is
 * asked to generate, so a profile is really "which held session answers this call".
 * There are exactly two in this app — see [Profiles] — and each owns a session that
 * lives for the life of the process.
 */
data class DecodeProfile(
    /** Appears in logs and on the diagnostics screen. */
    val name: String,
    val temperature: Float,
    val topK: Int,
) {
    companion object Profiles {
        /**
         * Job 1. topK = 1 is greedy: no sampling, so the same utterance classifies the
         * same way twice running. Classification is not a creative task, and run-to-run
         * drift is not something to discover on stage.
         */
        const val CLASSIFY = "classify"

        /** Same prefix, sampled decode. Primed separately so both stay warm. */
        const val CLASSIFY_VOTE = "classify_vote"

        /** Job 2. Higher, because four messages that read as templated are worse than four that vary. */
        const val DRAFT = "draft"
    }
}

/**
 * What to prime at start-up.
 *
 * [stablePrefix] must be byte-identical to the prefix every real call of that profile
 * will pass, or the priming is wasted — the engine logs it loudly if it ever differs.
 * [throwawaySuffix] is a fake utterance used to force the first (slow) decode to happen
 * behind the splash rather than in front of the coordinator.
 */
data class WarmUpSpec(
    val profile: DecodeProfile,
    val stablePrefix: String,
    val throwawaySuffix: String? = null,
    val throwawayMaxTokens: Int = 24,
)

/**
 * The only surface the rest of the app knows about. Swapping MediaPipe for anything
 * else — or for [FakeLlmEngine] during Red Light UI work — touches nothing else.
 *
 * Prompts are passed in two pieces on purpose. The prefix is the part that does not
 * change between calls (system text, instructions, the code list, the examples); the
 * suffix is the part that does (this utterance, this audience, this case). Keeping
 * them apart is what lets the engine hold one primed session per profile and pay the
 * prefix's decode cost once, at start-up, instead of on every classification.
 */
interface LlmEngine {

    val state: Flow<EngineState>

    /**
     * Idempotent. Loads the model, opens one session per profile in [warmUpSpecs],
     * primes each with its prefix, and runs any throwaway decode. Safe to call from
     * Application.onCreate; returns once the engine is warm.
     */
    suspend fun warmUp(): Result<Unit>

    /**
     * One-shot completion against the held session for [profile].
     *
     * [maxTokens] is advisory: MediaPipe sets the token budget on the engine, not the
     * session, so this is a logged intent rather than an enforced cap.
     */
    suspend fun generate(
        profile: DecodeProfile,
        stablePrefix: String,
        variableSuffix: String,
        maxTokens: Int,
    ): Result<String>

    /** Token stream for the drafting job, so the UI never looks frozen. */
    fun generateStreaming(
        profile: DecodeProfile,
        stablePrefix: String,
        variableSuffix: String,
        maxTokens: Int,
    ): Flow<String>

    fun close()

    /**
     * Debug only: ignore any primed session and send the whole prompt every call.
     *
     * Exists so the A/B column in the eval harness is a real measurement rather than
     * an assertion — the two paths can be run back to back on the same device, in the
     * same minute, against the same thirty utterances. No-op by default.
     */
    fun setForceFullPrefill(force: Boolean) = Unit

    sealed interface EngineState {
        data object NotLoaded : EngineState
        data class Loading(val message: String) : EngineState
        data class Ready(val loadMs: Long, val backend: String) : EngineState
        data class Failed(val message: String) : EngineState
    }
}

/**
 * Where the model file must live. Not bundled in the APK (see README), so a 550 MB
 * asset never enters the build or git.
 */
object ModelLocator {
    const val FILE_NAME = "gemma3-1b-it-int4.task"

    /** Preferred: app-private storage, survives reinstall of nothing but is fastest to read. */
    fun appPrivatePath(filesDirPath: String): String = "$filesDirPath/models/$FILE_NAME"

    /** Sideload target used by `adb push` during the build — see README. */
    const val SIDELOAD_PATH = "/data/local/tmp/llm/$FILE_NAME"
}
