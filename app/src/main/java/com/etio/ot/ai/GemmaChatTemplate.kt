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
 *
 * The turn is built in two halves so the engine can cache the first one. [head] is
 * everything that is the same on every call of a job, up to and including the opening
 * control token; [tail] is the part that changes, plus the tokens that close the user
 * turn and open the model's. Concatenated they are exactly what [wrap] produces —
 * the split is about where the KV cache can be reused, not about content.
 */
internal object GemmaChatTemplate {

    private const val START_OF_TURN = "<start_of_turn>"
    private const val END_OF_TURN = "<end_of_turn>\n"

    /** Opens the user turn. Must be byte-identical across every call of a profile. */
    fun head(stableContent: String): String =
        START_OF_TURN + "user\n" + stableContent

    /**
     * Closes the user turn and opens the model's, primed with [modelPrefix]
     * (e.g. "JSON:") to bias what the continuation starts with. [modelPrefix] is never
     * echoed back by [LlmEngine.generate] — it only steers generation — so callers keep
     * parsing the raw response exactly as before.
     */
    fun tail(variableContent: String, modelPrefix: String = ""): String =
        variableContent + END_OF_TURN + START_OF_TURN + "model\n" + modelPrefix

    /** The whole turn, for callers that have no reason to split it. */
    fun wrap(userContent: String, modelPrefix: String = ""): String =
        head("") + tail(userContent, modelPrefix)
}
