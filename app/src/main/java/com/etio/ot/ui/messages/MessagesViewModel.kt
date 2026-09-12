package com.etio.ot.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.GeneratedMessageEntity
import com.etio.ot.data.model.Audience
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.DelayRepository
import com.etio.ot.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Job 2 driver. Messages are generated sequentially and appear as each completes,
 * with the remaining audiences shown as pending — never a blank screen.
 */
class MessagesViewModel(
    private val delayId: String,
    private val delays: DelayRepository = ServiceLocator.delayRepository,
    private val cases: CaseRepository = ServiceLocator.caseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val record = delays.get(delayId)
            val case = record?.let { cases.getCase(it.caseId) }
            _state.value = _state.value.copy(record = record, case = case)
            record ?: return@launch

            // Reuse anything already generated (e.g. reopened from the history strip).
            val existing = delays.messagesFor(delayId)
            viewModelScope.launch {
                existing.collect { rows ->
                    if (rows.isNotEmpty()) {
                        _state.value = _state.value.copy(
                            messages = rows.associateBy { it.audience },
                        )
                    }
                }
            }
            generate()
        }
    }

    fun generate() {
        val record = _state.value.record ?: return
        if (_state.value.generating) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                generating = true,
                pending = Audience.demoOrder.toSet(),
                messages = emptyMap(),
            )
            delays.draftMessages(record).collect { row ->
                _state.value = _state.value.copy(
                    messages = _state.value.messages + (row.audience to row),
                    pending = _state.value.pending - row.audience,
                )
            }
            _state.value = _state.value.copy(generating = false, pending = emptySet())
        }
    }

    fun regenerate(audience: Audience) {
        val record = _state.value.record ?: return
        val existing = _state.value.messages[audience] ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(pending = _state.value.pending + audience)
            delays.regenerate(record, existing)
            _state.value = _state.value.copy(pending = _state.value.pending - audience)
        }
    }

    fun markCopied(messageId: String) {
        viewModelScope.launch { delays.markCopied(messageId) }
    }

    companion object {
        fun factory(delayId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagesViewModel(delayId) as T
        }
    }
}

data class MessagesUiState(
    val record: DelayRecordEntity? = null,
    val case: CaseEntity? = null,
    val messages: Map<Audience, GeneratedMessageEntity> = emptyMap(),
    val pending: Set<Audience> = emptySet(),
    val generating: Boolean = false,
)
