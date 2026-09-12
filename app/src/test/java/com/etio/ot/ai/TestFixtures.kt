package com.etio.ot.ai

import com.etio.ot.data.config.AudienceRule
import com.etio.ot.data.config.ClassificationPrompt
import com.etio.ot.data.config.DraftingPrompt
import com.etio.ot.data.config.FewShotExample
import com.etio.ot.data.config.PromptConfig
import com.etio.ot.data.config.TaxonomyConfig
import com.etio.ot.data.config.TaxonomyEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared fixtures for [DelayClassifierTest] and [MessageDrafterTest]. Mirrors the
 * shape of assets/config/prompts.json and taxonomy.json closely enough that a test
 * failure here means the real prompt-building logic broke, not that the fixture
 * drifted from the schema.
 */

fun fakeTaxonomy(): TaxonomyConfig = TaxonomyConfig(
    codes = listOf(
        TaxonomyEntry(
            code = "STERILE_SET_UNAVAILABLE",
            display = "Sterile set unavailable",
            description = "Instrument tray missing, short, wet, unsterile, or still being reprocessed by CSSD.",
            typicalDept = "CSSD",
        ),
        TaxonomyEntry(
            code = "SURGEON_LATE",
            display = "Surgeon late",
            description = "Operating surgeon not present or not scrubbed when the room was ready.",
            typicalDept = "Surgery",
        ),
        TaxonomyEntry(
            code = "OTHER",
            display = "Other",
            description = "Anything that does not clearly fit the codes above.",
            typicalDept = "Unattributed",
        ),
    ),
    departmentHints = listOf("CSSD", "Surgery", "Ward", "Unattributed"),
)

fun fakePrompts(): PromptConfig = PromptConfig(
    systemPrefix = "You are a theatre operations assistant.",
    classification = ClassificationPrompt(
        instruction = "Read the coordinator's utterance and return ONE JSON object and nothing else.",
        fewShot = listOf(
            FewShotExample(
                transcript = "we're stuck on three, the set came back wet, CSSD says forty minutes",
                json = """{"code":"STERILE_SET_UNAVAILABLE","attributed_dept":"CSSD","avoidable":true,"estimated_min":40,"note":"Set returned wet; CSSD reprocessing","confidence":0.9}""",
            ),
        ),
        maxTokens = 128,
        temperature = 0.1f,
        topK = 20,
    ),
    drafting = DraftingPrompt(
        instruction = "You are drafting ONE short message about a theatre delay, for ONE named audience.",
        audiences = mapOf(
            "SURGEON" to AudienceRule(
                register = "Extremely terse. ETA first.",
                include = listOf("new knife-to-skin estimate", "the cause in two or three words"),
                exclude = listOf("reassurance", "explanation"),
                example = "Case 3 knife approx 40 min late. Set wet, CSSD reprocessing. Will confirm.",
            ),
            "FAMILY" to AudienceRule(
                register = "Warm, calm, plain language.",
                include = listOf("reassurance that the patient is safe and still on the list"),
                exclude = listOf("the clinical cause", "which department is responsible"),
                example = null,
            ),
            // WARD deliberately omitted so the "no rule for this audience" path is covered.
        ),
        maxTokens = 200,
        temperature = 0.4f,
        topK = 40,
    ),
)

fun fakePromptSource(
    prompts: PromptConfig = fakePrompts(),
    taxonomy: TaxonomyConfig = fakeTaxonomy(),
): PromptSource = object : PromptSource {
    override fun prompts(): PromptConfig = prompts
    override fun taxonomy(): TaxonomyConfig = taxonomy
}

/** What one [RecordingLlmEngine.generate] call received. */
data class CapturedCall(
    val prompt: String,
    val maxTokens: Int,
    val temperature: Float,
    val topK: Int,
)

/**
 * Records every [generate] call and answers from a fixed queue of results, in order.
 * Running off the queue is a test bug (one too few responses queued), not a silent
 * fallback, so it throws rather than returning something that could mask that.
 */
class RecordingLlmEngine(
    private val responses: MutableList<Result<String>> = mutableListOf(),
) : LlmEngine {

    override val state = MutableStateFlow<LlmEngine.EngineState>(LlmEngine.EngineState.Ready(0, "TEST"))

    val calls = mutableListOf<CapturedCall>()

    fun queue(result: Result<String>) = responses.add(result)
    fun queueSuccess(body: String) = queue(Result.success(body))
    fun queueFailure(t: Throwable = RuntimeException("boom")) = queue(Result.failure(t))

    override suspend fun warmUp(): Result<Unit> = Result.success(Unit)

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): Result<String> {
        calls += CapturedCall(prompt, maxTokens, temperature, topK)
        check(responses.isNotEmpty()) { "RecordingLlmEngine.generate called with no response queued" }
        return responses.removeAt(0)
    }

    override fun generateStreaming(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): Flow<String> = flowOf(prompt)

    override fun close() = Unit
}
