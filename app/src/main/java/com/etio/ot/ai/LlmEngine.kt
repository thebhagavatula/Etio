package com.etio.ot.ai

import kotlinx.coroutines.flow.Flow

/**
 * The only surface the rest of the app knows about. Swapping MediaPipe for anything
 * else — or for [FakeLlmEngine] during Red Light UI work — touches nothing else.
 */
interface LlmEngine {

    val state: Flow<EngineState>

    /** Idempotent. Safe to call from Application.onCreate; returns once the session is warm. */
    suspend fun warmUp(): Result<Unit>

    /** One-shot completion. [maxTokens] is advisory; the engine may cap lower. */
    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): Result<String>

    /** Token stream for the drafting job, so the UI never looks frozen. */
    fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): Flow<String>

    fun close()

    sealed interface EngineState {
        data object NotLoaded : EngineState
        data class Loading(val message: String) : EngineState
        data class Ready(val loadMs: Long, val backend: String) : EngineState
        data class Failed(val message: String) : EngineState
    }
}

/**
 * Where the model file must live. Not bundled in the APK — sideloaded (see README),
 * so a 550 MB asset never enters the build or git.
 */
object ModelLocator {
    const val FILE_NAME = "gemma3-1b-it-int4.task"

    /** Preferred: app-private storage, survives reinstall of nothing but is fastest to read. */
    fun appPrivatePath(filesDirPath: String): String = "$filesDirPath/models/$FILE_NAME"

    /** Sideload target used by `adb push` during the build — see README. */
    const val SIDELOAD_PATH = "/data/local/tmp/llm/$FILE_NAME"
}
