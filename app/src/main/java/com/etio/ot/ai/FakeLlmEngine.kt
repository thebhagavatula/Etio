package com.etio.ot.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Lets the entire UI, timer and checklist path be built and demoed before the model
 * file is on the device. Flip [com.etio.ot.di.AiModule.USE_FAKE_LLM] to use it.
 *
 * Latency is simulated at the PRD §10 targets so the UX is designed against realistic
 * timing rather than instant responses.
 */
class FakeLlmEngine : LlmEngine {

    private val _state = MutableStateFlow<LlmEngine.EngineState>(
        LlmEngine.EngineState.Ready(0, "FAKE"),
    )
    override val state = _state.asStateFlow()

    override suspend fun warmUp(): Result<Unit> = Result.success(Unit)

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): Result<String> {
        delay(1800)
        return Result.success(canned(prompt))
    }

    override fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): Flow<String> = flow {
        val full = canned(prompt)
        val sb = StringBuilder()
        full.split(" ").forEach { word ->
            delay(45)
            sb.append(word).append(' ')
            emit(sb.toString())
        }
    }

    override fun close() = Unit

    private fun canned(prompt: String): String = when {
        prompt.contains("\"code\"") || prompt.contains("STERILE_SET_UNAVAILABLE") ->
            """{"code":"STERILE_SET_UNAVAILABLE","attributed_dept":"CSSD","avoidable":true,"estimated_min":40,"note":"Set returned wet; CSSD reprocessing","confidence":0.91}"""
        prompt.contains("FAMILY", ignoreCase = true) ->
            "A quick update on your family member's operation. They are safe and still on today's list. The theatre team needs a little more time to get everything ready, so we now expect them to go in about forty minutes later than planned. We will let you know as soon as they do."
        prompt.contains("SURGEON", ignoreCase = true) ->
            "Case 3 knife approx 40 min late. Set wet, CSSD reprocessing. Will confirm."
        prompt.contains("ANAESTHESIA", ignoreCase = true) ->
            "Case 3 induction pushed approx 40 min — sterile set being reprocessed by CSSD. Running order unchanged; will confirm once the set is back."
        else ->
            "Case 3 on hold — sterile set being reprocessed, CSSD says 40 min. Hold the send-for; new time to follow."
    }
}
