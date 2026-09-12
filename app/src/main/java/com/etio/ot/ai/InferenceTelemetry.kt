package com.etio.ot.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Real numbers, measured rather than claimed.
 *
 * Two separate things live here and they are not the same claim:
 *
 *  - Timings say the thing is fast enough. They are wall-clock, taken around the
 *    engine call itself, and they include nothing else.
 *  - The correction rate says the thing is right often enough. It counts how many
 *    confirmed delay records the coordinator had to edit before confirming. That is
 *    the only number in the app that answers "how do you know it works", so it is
 *    counted honestly: an edit is an edit, and nothing here can lower the count.
 *
 * Process-scoped on purpose. It is a session figure, not a historical one, and a
 * demo reset should not flatter it by clearing the edits while keeping the records.
 */
object InferenceTelemetry {

    data class Call(
        val profile: String,
        val elapsedMs: Long,
        val promptChars: Int,
        val ok: Boolean,
        /** True when this call was the silent retry after a parse failure. */
        val wasRetry: Boolean = false,
        val atMs: Long = System.currentTimeMillis(),
    )

    data class Snapshot(
        val backend: String? = null,
        val engineLoadMs: Long? = null,
        /** Priming one session per profile, after the engine itself is up. */
        val primeMs: Long? = null,
        val prefixTokens: Map<String, Int> = emptyMap(),
        val calls: List<Call> = emptyList(),
        val recordsConfirmed: Int = 0,
        val recordsEdited: Int = 0,
        val parseRetries: Int = 0,
        val parseFallbacks: Int = 0,
    ) {
        fun callsFor(profile: String): List<Call> = calls.filter { it.profile == profile }

        fun medianMs(profile: String): Long? = callsFor(profile)
            .map { it.elapsedMs }
            .sorted()
            .takeIf { it.isNotEmpty() }
            ?.let { it[it.size / 2] }

        fun lastMs(profile: String): Long? = callsFor(profile).lastOrNull()?.elapsedMs

        /**
         * Share of confirmed records the coordinator corrected first. Null until at
         * least one record exists — a rate over zero records is not a zero rate, and
         * showing 0% there would be the one dishonest number on the screen.
         */
        val correctionRate: Float?
            get() = if (recordsConfirmed == 0) null else recordsEdited.toFloat() / recordsConfirmed
    }

    private const val MAX_CALLS = 60

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun engineLoaded(backend: String, loadMs: Long) = _snapshot.update {
        it.copy(backend = backend, engineLoadMs = loadMs)
    }

    fun primed(primeMs: Long, prefixTokens: Map<String, Int>) = _snapshot.update {
        it.copy(primeMs = primeMs, prefixTokens = prefixTokens)
    }

    fun call(call: Call) = _snapshot.update {
        it.copy(calls = (it.calls + call).takeLast(MAX_CALLS))
    }

    fun parseRetry() = _snapshot.update { it.copy(parseRetries = it.parseRetries + 1) }

    fun parseFallback() = _snapshot.update { it.copy(parseFallbacks = it.parseFallbacks + 1) }

    /** Called once per confirmed DelayRecord, with whether the coordinator edited it. */
    fun recordConfirmed(edited: Boolean) = _snapshot.update {
        it.copy(
            recordsConfirmed = it.recordsConfirmed + 1,
            recordsEdited = it.recordsEdited + if (edited) 1 else 0,
        )
    }

    /** Diagnostics screen only. Never called on the demo path. */
    fun reset() { _snapshot.value = Snapshot() }
}
