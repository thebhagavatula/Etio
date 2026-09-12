package com.etio.ot.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PRD §10: load once, keep the session warm, never block the main thread.
 *
 * Two objects matter here:
 *  - [LlmInference] is the expensive one. Created once, held for the app lifetime.
 *  - [LlmInferenceSession] carries the KV cache and sampling params. We recreate it
 *    per call because temperature/topK differ between Job 1 and Job 2, but the
 *    underlying engine — and therefore the multi-second load — is never repeated.
 *
 * A [Mutex] serialises calls: MediaPipe sessions are not safe for concurrent use, and
 * two overlapping inferences on a loaner phone is exactly how you OOM at hour 26.
 */
class MediaPipeLlmEngine(
    private val context: Context,
    private val modelPath: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val preferGpu: Boolean = true,
) : LlmEngine {

    private val _state = MutableStateFlow<LlmEngine.EngineState>(LlmEngine.EngineState.NotLoaded)
    override val state = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile private var inference: LlmInference? = null

    override suspend fun warmUp(): Result<Unit> = mutex.withLock {
        inference?.let { return@withLock Result.success(Unit) }

        withContext(dispatcher) {
            val file = File(modelPath)
            if (!file.exists()) {
                val msg = "Model not found at $modelPath"
                _state.value = LlmEngine.EngineState.Failed(msg)
                return@withContext Result.failure<Unit>(IllegalStateException(msg))
            }

            _state.value = LlmEngine.EngineState.Loading("Loading model (${file.length() / 1_000_000} MB)…")
            val start = System.currentTimeMillis()

            runCatching {
                val backend = if (preferGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(MAX_CONTEXT_TOKENS)
                    .setPreferredBackend(backend)
                    .build()
                LlmInference.createFromOptions(context, options) to backend.name
            }.fold(
                onSuccess = { (engine, backendName) ->
                    inference = engine
                    val ms = System.currentTimeMillis() - start
                    Log.i(TAG, "Model warm in ${ms}ms on $backendName")
                    _state.value = LlmEngine.EngineState.Ready(ms, backendName)
                    Result.success(Unit)
                },
                onFailure = { t ->
                    // GPU can fail on a device whose driver we do not control. Fall back once.
                    if (preferGpu) {
                        Log.w(TAG, "GPU backend failed, retrying on CPU", t)
                        _state.value = LlmEngine.EngineState.Loading("GPU unavailable, loading on CPU…")
                        runCatching {
                            val options = LlmInference.LlmInferenceOptions.builder()
                                .setModelPath(modelPath)
                                .setMaxTokens(MAX_CONTEXT_TOKENS)
                                .setPreferredBackend(LlmInference.Backend.CPU)
                                .build()
                            LlmInference.createFromOptions(context, options)
                        }.fold(
                            onSuccess = {
                                inference = it
                                val ms = System.currentTimeMillis() - start
                                _state.value = LlmEngine.EngineState.Ready(ms, "CPU")
                                Result.success(Unit)
                            },
                            onFailure = { t2 ->
                                _state.value = LlmEngine.EngineState.Failed(t2.message ?: "Model load failed")
                                Result.failure(t2)
                            },
                        )
                    } else {
                        _state.value = LlmEngine.EngineState.Failed(t.message ?: "Model load failed")
                        Result.failure(t)
                    }
                },
            )
        }
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): Result<String> {
        warmUp().onFailure { return Result.failure(it) }

        return mutex.withLock {
            withContext(dispatcher) {
                val engine = inference
                    ?: return@withContext Result.failure<String>(IllegalStateException("Engine not ready"))
                runCatching {
                    newSession(engine, temperature, topK, maxTokens).use { session ->
                        session.addQueryChunk(prompt)
                        session.generateResponse()
                    }
                }
            }
        }
    }

    override fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): Flow<String> = callbackFlow {
        warmUp().onFailure { close(it); return@callbackFlow }

        val engine = inference
        if (engine == null) {
            close(IllegalStateException("Engine not ready"))
            return@callbackFlow
        }

        // Serialise against other inferences for the whole stream.
        val owner = Any()
        mutex.lock(owner)
        val session = runCatching { newSession(engine, temperature, topK, maxTokens) }
            .getOrElse { mutex.unlock(owner); close(it); return@callbackFlow }

        runCatching {
            session.addQueryChunk(prompt)
            session.generateResponseAsync { partial, done ->
                trySend(partial)
                if (done) close()
            }
        }.onFailure { close(it) }

        awaitClose {
            runCatching { session.close() }
            runCatching { mutex.unlock(owner) }
        }
    }.flowOn(dispatcher)

    private fun newSession(
        engine: LlmInference,
        temperature: Float,
        topK: Int,
        maxTokens: Int,
    ): LlmInferenceSession = LlmInferenceSession.createFromOptions(
        engine,
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(temperature)
            .setTopK(topK)
            .setTopP(TOP_P)
            .build(),
    ).also { Log.d(TAG, "session t=$temperature k=$topK budget=$maxTokens") }

    override fun close() {
        runCatching { inference?.close() }
        inference = null
        _state.value = LlmEngine.EngineState.NotLoaded
    }

    companion object {
        private const val TAG = "MediaPipeLlmEngine"
        /** Keep the context small: short prompts, short outputs, smaller KV cache. */
        private const val MAX_CONTEXT_TOKENS = 1280
        private const val TOP_P = 0.9f
    }
}
