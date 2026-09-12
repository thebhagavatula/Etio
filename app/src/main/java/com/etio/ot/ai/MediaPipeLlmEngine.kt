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
 *  - A **base session** per [DecodeProfile], primed at start-up with that profile's
 *    byte-identical prefix, so the prefix is decoded into the KV cache once in the
 *    life of the process.
 *  - A **clone** per call. [LlmInferenceSession.cloneSession] copies the primed cache
 *    rather than rebuilding it, so a classification only decodes the tokens in the
 *    utterance. The clone is closed when the call ends; the base is not.
 *
 * That second and third point only hold where the backend implements cloning, and
 * plenty do not — the OpenCL GPU executor on current Adreno parts answers
 * UNIMPLEMENTED. Cloning is therefore probed exactly once, at prime time. Where it is
 * missing the held session is closed rather than kept as a cache nothing can read,
 * and every call sends the whole prompt: correct, no slower than having never tried,
 * and reported honestly on the diagnostics screen instead of claiming a cache hit.
 *
 * The token budget is the other hard edge. Going over it does not raise a Kotlin
 * exception — MediaPipe leaves one pending and the JNI layer aborts the process — so
 * every prompt is measured against [MAX_CONTEXT_TOKENS] before it is ever sent.
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

    /**
     * One held, primed session per profile — but only on a backend that can clone.
     * Empty elsewhere, because a cache nothing is able to read is just memory.
     */
    private val bases = mutableMapOf<String, Base>()

    /** Measured prefix size per profile, kept whether or not a base is held. */
    private val prefixTokens = mutableMapOf<String, Int>()

    @Volatile private var cloneSupported: Boolean = true

    /** Set by the eval harness to measure the two paths against each other. */
    @Volatile private var forceFullPrefill: Boolean = false

    override fun setForceFullPrefill(force: Boolean) {
        forceFullPrefill = force
    }

    private class Base(
        val session: LlmInferenceSession,
        val profile: DecodeProfile,
        val prefix: String,
        /** Measured once, at prime time. -1 when the backend would not count it. */
        val prefixTokens: Int,
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
                tokens[spec.profile.name] = base.prefixTokens
                prefixTokens[spec.profile.name] = base.prefixTokens

                // Probe cloning once, here, rather than rediscovering it per call.
                // Where it is unsupported a primed base is dead weight: it holds a KV
                // cache nothing can ever read, so release it and send the whole prompt.
                if (canClone(base)) {
                    bases[spec.profile.name] = base
                } else {
                    cloneSupported = false
                    runCatching { base.session.close() }
                    Log.w(
                        TAG,
                        "Backend cannot clone, so the ${spec.profile.name} prefix " +
                            "(${base.prefixTokens} tokens) is re-sent on every call. " +
                            "Held session released rather than kept as unreadable cache.",
                    )
                }

                spec.throwawaySuffix?.let { suffix ->
                    val t0 = System.currentTimeMillis()
                    runCatching { answer(spec.profile, spec.stablePrefix, suffix) }
                        .onSuccess {
                            Log.i(TAG, "Warm-up decode (${spec.profile.name}) in ${System.currentTimeMillis() - t0}ms")
                        }
                        .onFailure { Log.w(TAG, "Warm-up decode failed for ${spec.profile.name}", it) }
                }
            }.onFailure { Log.w(TAG, "Priming failed for ${spec.profile.name}", it) }
        }

        val primeMs = System.currentTimeMillis() - start
        Log.i(
            TAG,
            "Primed ${bases.size} cached session(s) in ${primeMs}ms; prefix tokens=$tokens; " +
                "prefix caching ${if (cloneSupported) "active" else "UNAVAILABLE on this backend"}",
        )
        InferenceTelemetry.primed(primeMs, tokens, cloneSupported)
    }

    /** One probe, at start-up, so the per-call path never has to ask. */
    private fun canClone(base: Base): Boolean = runCatching {
        base.session.cloneSession().close()
        true
    }.getOrElse {
        Log.w(TAG, "cloneSession refused: ${it.message?.lineSequence()?.firstOrNull()}")
        false
    }

    private fun openBase(engine: LlmInference, profile: DecodeProfile, prefix: String): Base {
        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions(profile))

        // Measure BEFORE committing anything to the session. Overflowing the budget
        // does not raise a Kotlin exception — MediaPipe leaves an IllegalStateException
        // pending and the JNI layer then aborts the process, so there is nothing to
        // catch and nothing to recover. The only defence is to refuse the call.
        val tokens = runCatching { session.sizeInTokens(prefix) }.getOrDefault(-1)
        if (tokens > PREFIX_CEILING_TOKENS) {
            runCatching { session.close() }
            error(
                "Prefix for ${profile.name} is $tokens tokens; ceiling is $PREFIX_CEILING_TOKENS " +
                    "(budget $MAX_CONTEXT_TOKENS less room for the utterance and the reply). " +
                    "Shorten prompts.json — fewer or shorter few-shot examples.",
            )
        }

        session.addQueryChunk(prefix)
        return Base(session, profile, prefix, tokens)
    }

    private fun sessionOptions(profile: DecodeProfile) =
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(profile.temperature)
            .setTopK(profile.topK)
            .setTopP(TOP_P)
            // Fixed, so a greedy profile is reproducible run to run and a sampled
            // one at least starts from the same place on every rehearsal.
            .setRandomSeed(RANDOM_SEED)
            .build()

    /**
     * Makes sure this profile has been measured, and re-primes it if the prefix ever
     * changes underneath us.
     *
     * A changed prefix is worth shouting about: it means something per-call has leaked
     * into the cached half, which on a cloning backend throws the cache away on every
     * call and on any backend invalidates the measured token count the budget guard
     * depends on.
     */
    private fun ensurePrimed(engine: LlmInference, profile: DecodeProfile, prefix: String) {
        val existing = bases[profile.name]
        if (existing != null && existing.prefix != prefix) {
            Log.w(
                TAG,
                "Prefix for ${profile.name} changed (${existing.prefix.length} -> ${prefix.length} chars); " +
                    "re-priming. Per-call text belongs in the suffix.",
            )
            runCatching { existing.session.close() }
            bases.remove(profile.name)
            prefixTokens.remove(profile.name)
        }
        if (profile.name in prefixTokens) return

        // Not primed at start-up — a config reload, or a profile warm-up never saw.
        val base = openBase(engine, profile, prefix)
        prefixTokens[profile.name] = base.prefixTokens
        if (cloneSupported && canClone(base)) bases[profile.name] = base
        else runCatching { base.session.close() }
    }

    /**
     * One completion, by whichever route this backend actually supports.
     *
     * Where cloning works the primed cache is copied and only the utterance is
     * decoded. Where it does not, the whole prompt goes in every time — correct, just
     * not cheaper — and no held session is kept pretending otherwise.
     */
    private fun answer(profile: DecodeProfile, stablePrefix: String, variableSuffix: String): String {
        val engine = inference ?: error("Engine not ready")
        val base = if (forceFullPrefill) null else bases[profile.name]

        guardBudget(profile, base?.session, variableSuffix)

        if (base != null) {
            return base.session.cloneSession().use { session ->
                session.addQueryChunk(variableSuffix)
                session.generateResponse()
            }
        }

        return LlmInferenceSession.createFromOptions(engine, sessionOptions(profile)).use { session ->
            session.addQueryChunk(stablePrefix + variableSuffix)
            session.generateResponse()
        }
    }

    /**
     * The last point at which an over-long prompt is still recoverable.
     *
     * Past here it stops being a Kotlin problem. MediaPipe leaves an exception pending
     * and the JNI layer aborts the process, so there is nothing to catch, nothing to
     * log and no way to degrade. A refused call the caller turns into an OTHER is a
     * far better outcome than the app disappearing.
     */
    private fun guardBudget(profile: DecodeProfile, sizer: LlmInferenceSession?, variableSuffix: String) {
        val prefixTok = prefixTokens[profile.name] ?: return
        if (prefixTok < 0) return
        val suffixTok = sizer?.let { runCatching { it.sizeInTokens(variableSuffix) }.getOrNull() }
            ?: (variableSuffix.length / APPROX_CHARS_PER_TOKEN)
        val total = prefixTok + suffixTok + RESPONSE_HEADROOM_TOKENS
        if (total > MAX_CONTEXT_TOKENS) {
            error(
                "Prompt is ~$total tokens against a $MAX_CONTEXT_TOKENS budget " +
                    "(prefix $prefixTok, utterance $suffixTok). Refusing to call the model: " +
                    "overflowing this aborts the process, it does not throw.",
            )
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
                    ensurePrimed(engine, profile, stablePrefix)
                    answer(profile, stablePrefix, variableSuffix)
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

        // Same two routes as the one-shot path: clone the primed cache where the
        // backend allows it, otherwise send the whole prompt into a fresh session.
        val session = runCatching {
            ensurePrimed(engine, profile, stablePrefix)
            guardBudget(profile, bases[profile.name]?.session, variableSuffix)
            bases[profile.name]?.session?.cloneSession()
                ?: LlmInferenceSession.createFromOptions(engine, sessionOptions(profile))
        }.getOrElse { mutex.unlock(owner); close(it); return@callbackFlow }

        val prompt = if (bases.containsKey(profile.name)) variableSuffix else stablePrefix + variableSuffix

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

    override fun close() {
        bases.values.forEach { runCatching { it.session.close() } }
        bases.clear()
        prefixTokens.clear()
        runCatching { inference?.close() }
        inference = null
        _state.value = LlmEngine.EngineState.NotLoaded
    }

    companion object {
        private const val TAG = "MediaPipeLlmEngine"
        /**
         * Input plus output, for the whole session. Pinned to the GPU's ceiling.
         *
         * This number is bounded on both sides and the window is narrow:
         *
         *  - Too low and the prompt overflows. At 1280 the seven-example classification
         *    prefix (957 tokens, measured) plus an utterance plus a reply did not fit,
         *    and overflowing aborts the process from native code rather than raising
         *    anything catchable.
         *  - Too high and the GPU refuses to load at all. The OpenCL executor on this
         *    class of device caps the KV cache at 2048 — asking for 3072 produced
         *    "Max number of tokens is larger than the maximum cache size supported"
         *    and a silent fall back to CPU, where a warm-up decode took ten seconds
         *    instead of two.
         *
         * 2048 is that ceiling. Raising it does not buy a longer prompt, it buys the
         * CPU backend.
         */
        private const val MAX_CONTEXT_TOKENS = 2048

        /** Reserved for the model's own reply, never lent to the prompt. */
        private const val RESPONSE_HEADROOM_TOKENS = 256

        /** Room for the longest utterance a 30s capture can produce. */
        private const val SUFFIX_ALLOWANCE_TOKENS = 256

        /** A prefix bigger than this cannot be primed, and says so at start-up. */
        private const val PREFIX_CEILING_TOKENS =
            MAX_CONTEXT_TOKENS - RESPONSE_HEADROOM_TOKENS - SUFFIX_ALLOWANCE_TOKENS
        private const val TOP_P = 0.9f
        /** Ceiling across every profile; the drafting profile is the one that needs it. */
        private const val MAX_TOP_K = 64
        private const val RANDOM_SEED = 42

        /** Only used to size a suffix when no session is available to count it. */
        private const val APPROX_CHARS_PER_TOKEN = 3
    }
}
