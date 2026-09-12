package com.etio.ot.ai

/**
 * Gemma's instruction-tuned models are trained around turns delimited by these
 * control tokens. Skipping them isn't a style choice — without them the model has no
 * signal that it's being asked to follow an instruction and free-continues the raw
 * text instead, which is why real on-device runs never returned a parseable JSON
 * object until this was added (confirmed on an int4 Gemma 3 1B .task bundle: 0/2
 * classify calls produced JSON before, format-followed after).
 *
 * Gemma has no separate "system" role — system-style instructions live inside the
 * user turn, per Google's published Gemma chat template.
 */
internal object GemmaChatTemplate {

    private const val START_OF_TURN = "<start_of_turn>"
    private const val END_OF_TURN = "<end_of_turn>\n"

    /**
     * Wraps [userContent] as a single user turn and opens the model's turn, primed
     * with [modelPrefix] (e.g. "JSON:") to bias what the continuation starts with.
     * [modelPrefix] is never echoed back by [LlmEngine.generate] — it only steers
     * generation — so callers keep parsing the raw response exactly as before.
     */
    fun wrap(userContent: String, modelPrefix: String = ""): String = buildString {
        append(START_OF_TURN).append("user\n")
        append(userContent)
        append(END_OF_TURN)
        append(START_OF_TURN).append("model\n")
        append(modelPrefix)
    }
}
