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
 * Three objects, and the distinction between them is the whole point of this file:
 *
 *  - [LlmInference] is the expensive one — the multi-second model load. Created once,
 *    held for the app lifetime.
 *  - A **base session** per [DecodeProfile], also created once and held. Each is primed
 *    at start-up with that profile's byte-identical prefix, so the prefix is decoded
 *    into the KV cache exactly once in the life of the process.
 *  - A **clone** per call. [LlmInferenceSession.cloneSession] copies the primed cache
 *    rather than rebuilding it, so a classification only ever decodes the handful of
 *    tokens in the utterance. The clone is closed when the call ends; the base is not.
 *
 * Nothing here is created per call except that clone, and a clone is a cache copy, not
 * a session build. If cloning is refused by the backend we fall back to a fresh session
 * from options — correct, just slower — rather than failing the call.
 *
 * A [Mutex] serialises calls: MediaPipe sessions are not safe for concurrent use, and
 * two overlapping inferences on a loaner phone is exactly how you OOM at hour 26.
 */
class MediaPipeLlmEngine(
    private val context: Context,
    private val modelPath: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val preferGpu: Boolean = true,
    /** Profiles to open and prime at start-up. Supplied by AiModule from config. */
    private val warmUpSpecs: () -> List<WarmUpSpec> = ::emptyList,
) : LlmEngine {

    private val _state = MutableStateFlow<LlmEngine.EngineState>(LlmEngine.EngineState.NotLoaded)
    override val state = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile private var inference: LlmInference? = null

    /** One held, primed session per profile name. Never closed until [close]. */
    private val bases = mutableMapOf<String, Base>()

    private class Base(
        val session: LlmInferenceSession,
        val profile: DecodeProfile,
        val prefix: String,
    )

    override suspend fun warmUp(): Result<Unit> = mutex.withLock {
        if (inference != null) return@withLock Result.success(Unit)

        withContext(dispatcher) {
            val file = File(modelPath)
            if (!file.exists()) {
                val msg = "Model not found at $modelPath"
                _state.value = LlmEngine.EngineState.Failed(msg)
                return@withContext Result.failure<Unit>(IllegalStateException(msg))
            }

            _state.value = LlmEngine.EngineState.Loading("Loading model (${file.length() / 1_000_000} MB)…")
            val start = System.currentTimeMillis()

            val loaded = loadEngine(preferGpu)
                ?: loadEngine(false).also { if (it != null) Log.w(TAG, "GPU refused; running on CPU") }

            if (loaded == null) {
                val msg = "Model load failed on every backend"
                _state.value = LlmEngine.EngineState.Failed(msg)
                return@withContext Result.failure<Unit>(IllegalStateException(msg))
            }

            val (engine, backendName) = loaded
            inference = engine
            val loadMs = System.currentTimeMillis() - start
            Log.i(TAG, "Model warm in ${loadMs}ms on $backendName")
            InferenceTelemetry.engineLoaded(backendName, loadMs)

            primeAll(engine)

            _state.value = LlmEngine.EngineState.Ready(loadMs, backendName)
            Result.success(Unit)
        }
    }

    private fun loadEngine(gpu: Boolean): Pair<LlmInference, String>? = runCatching {
        val backend = if (gpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_CONTEXT_TOKENS)
            // Must cover the largest topK any profile asks for, or session creation
            // is rejected at runtime for the drafting profile.
            .setMaxTopK(MAX_TOP_K)
            .setPreferredBackend(backend)
            .build()
        LlmInference.createFromOptions(context, options) to backend.name
    }.onFailure {
        Log.w(TAG, "Backend ${if (gpu) "GPU" else "CPU"} failed to load", it)
    }.getOrNull()

    /**
     * Opens and primes one session per profile, then burns a throwaway decode so the
     * first real classification is not the one that pays for cold kernels.
     */
    private fun primeAll(engine: LlmInference) {
        val specs = runCatching(warmUpSpecs).getOrElse {
            Log.w(TAG, "Could not read warm-up specs; skipping prime", it)
            return
        }
        if (specs.isEmpty()) return

        val start = System.currentTimeMillis()
        val tokens = mutableMapOf<String, Int>()

        specs.forEach { spec ->
            runCatching {
                val base = openBase(engine, spec.profile, spec.stablePrefix)
                bases[spec.profile.name] = base
                tokens[spec.profile.name] = runCatching { base.session.sizeInTokens(spec.stablePrefix) }
                    .getOrDefault(-1)

                spec.throwawaySuffix?.let { suffix ->
                    val t0 = System.currentTimeMillis()
                    runCatching { answer(base, suffix) }
                        .onSuccess {
                            Log.i(TAG, "Warm-up decode (${spec.profile.name}) in ${System.currentTimeMillis() - t0}ms")
                        }
                        .onFailure { Log.w(TAG, "Warm-up decode failed for ${spec.profile.name}", it) }
                }
            }.onFailure { Log.w(TAG, "Priming failed for ${spec.profile.name}", it) }
        }

        val primeMs = System.currentTimeMillis() - start
        Log.i(TAG, "Primed ${bases.size} session(s) in ${primeMs}ms; prefix tokens=$tokens")
        InferenceTelemetry.primed(primeMs, tokens)
    }

    private fun openBase(engine: LlmInference, profile: DecodeProfile, prefix: String): Base {
        val session = LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(profile.temperature)
                .setTopK(profile.topK)
                .setTopP(TOP_P)
                // Fixed, so a greedy profile is reproducible run to run and a sampled
                // one at least starts from the same place on every rehearsal.
                .setRandomSeed(RANDOM_SEED)
                .build(),
        )
        session.addQueryChunk(prefix)
        return Base(session, profile, prefix)
    }

    /**
     * The held base for [profile], opening it on demand if warm-up could not.
     *
     * If the prefix ever differs from what the base was primed with, that is a bug
     * worth shouting about — it means something per-call leaked into the prefix and
     * the cache is being thrown away on every call — so it is logged and the base is
     * rebuilt rather than silently returning wrong context.
     */
    private fun baseFor(engine: LlmInference, profile: DecodeProfile, prefix: String): Base {
        val existing = bases[profile.name]
        if (existing != null && existing.prefix == prefix) return existing

        if (existing != null) {
            Log.w(
                TAG,
                "Prefix for ${profile.name} changed (${existing.prefix.length} -> ${prefix.length} chars); " +
                    "KV cache discarded. Per-call text belongs in the suffix.",
            )
            runCatching { existing.session.close() }
        }
        return openBase(engine, profile, prefix).also { bases[profile.name] = it }
    }

    /** Clone the primed base, add only the variable part, decode, drop the clone. */
    private fun answer(base: Base, variableSuffix: String): String {
        val call = runCatching { base.session.cloneSession() }.getOrNull()
        if (call != null) {
            return call.use { session ->
                session.addQueryChunk(variableSuffix)
                session.generateResponse()
            }
        }
        // Backend refused to clone. Correct but slower: rebuild the whole context.
        Log.w(TAG, "cloneSession unavailable for ${base.profile.name}; rebuilding context")
        val engine = inference ?: error("Engine not ready")
        return LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(base.profile.temperature)
                .setTopK(base.profile.topK)
                .setTopP(TOP_P)
                .setRandomSeed(RANDOM_SEED)
                .build(),
        ).use { session ->
            session.addQueryChunk(base.prefix + variableSuffix)
            session.generateResponse()
        }
    }

    override suspend fun generate(
        profile: DecodeProfile,
        stablePrefix: String,
        variableSuffix: String,
        maxTokens: Int,
    ): Result<String> {
        warmUp().onFailure { return Result.failure(it) }

        return mutex.withLock {
            withContext(dispatcher) {
                val engine = inference
                    ?: return@withContext Result.failure<String>(IllegalStateException("Engine not ready"))

                val started = System.currentTimeMillis()
                val result = runCatching {
                    answer(baseFor(engine, profile, stablePrefix), variableSuffix)
                }
                val elapsed = System.currentTimeMillis() - started

                InferenceTelemetry.call(
                    InferenceTelemetry.Call(
                        profile = profile.name,
                        elapsedMs = elapsed,
                        promptChars = stablePrefix.length + variableSuffix.length,
                        ok = result.isSuccess,
                    ),
                )
                Log.i(TAG, "${profile.name} in ${elapsed}ms (budget ${maxTokens}tok, suffix ${variableSuffix.length}ch)")
                result
            }
        }
    }

    override fun generateStreaming(
        profile: DecodeProfile,
        stablePrefix: String,
        variableSuffix: String,
        maxTokens: Int,
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

        val session = runCatching { baseFor(engine, profile, stablePrefix).session.cloneSession() }
            .getOrElse { mutex.unlock(owner); close(it); return@callbackFlow }

        runCatching {
            session.addQueryChunk(variableSuffix)
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

    override fun close() {
        bases.values.forEach { runCatching { it.session.close() } }
        bases.clear()
        runCatching { inference?.close() }
        inference = null
        _state.value = LlmEngine.EngineState.NotLoaded
    }

    companion object {
        private const val TAG = "MediaPipeLlmEngine"
        /** Keep the context small: short prompts, short outputs, smaller KV cache. */
        private const val MAX_CONTEXT_TOKENS = 1280
        private const val TOP_P = 0.9f
        /** Ceiling across every profile; the drafting profile is the one that needs it. */
        private const val MAX_TOP_K = 64
        private const val RANDOM_SEED = 42
    }
}
