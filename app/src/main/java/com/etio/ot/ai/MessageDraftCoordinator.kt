package com.etio.ot.ai

import android.util.Log
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Audience
import com.etio.ot.data.repository.DelayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Job 2, started the moment the DelayRecord is committed rather than when someone
 * opens the messages screen.
 *
 * The four drafts take a handful of seconds each; waiting until the coordinator taps
 * Notify spends those seconds in front of her. Running them here, on an app-scoped
 * scope, means the work survives leaving the capture screen and Notify becomes a
 * reveal rather than a wait.
 *
 * Still exactly the existing Job 2 — same [DelayRepository.draftMessages], same
 * per-audience sequence, no additional model calls.
 */
class MessageDraftCoordinator(
    private val delays: DelayRepository,
    private val scope: CoroutineScope,
) {

    /** Audiences still outstanding, per delay record id. Absent means nothing running. */
    private val _pending = MutableStateFlow<Map<String, Set<Audience>>>(emptyMap())
    val pending = _pending.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** Idempotent: a record already drafting is left alone. */
    @Synchronized
    fun start(record: DelayRecordEntity) {
        if (jobs[record.id]?.isActive == true) return

        _pending.value = _pending.value + (record.id to Audience.demoOrder.toSet())
        jobs[record.id] = scope.launch {
            runCatching {
                delays.draftMessages(record).collect { row ->
                    _pending.value = _pending.value +
                        (record.id to _pending.value[record.id].orEmpty().minus(row.audience))
                }
            }.onFailure { Log.e(TAG, "Background drafting failed for ${record.id}", it) }

            synchronized(this@MessageDraftCoordinator) {
                _pending.value = _pending.value - record.id
                jobs.remove(record.id)
            }
        }
    }

    @Synchronized
    fun isRunning(delayId: String): Boolean = jobs[delayId]?.isActive == true

    /** Outstanding audiences for one record, for per-message progress in the UI. */
    fun pendingFor(delayId: String): Flow<Set<Audience>> =
        pending.map { it[delayId].orEmpty() }

    private companion object { const val TAG = "MessageDraftCoordinator" }
}
