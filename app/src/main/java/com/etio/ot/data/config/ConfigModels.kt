package com.etio.ot.data.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything tunable during Red Light hours. These map 1:1 onto the JSON files in
 * assets/config/, which are copied to app files/config/ on first launch and read
 * from there afterwards — so a phone text editor + relaunch is a full tuning cycle,
 * no compiler required (PRD §11).
 */

@Serializable
data class PromptConfig(
    @SerialName("system_prefix") val systemPrefix: String,
    @SerialName("classification") val classification: ClassificationPrompt,
    @SerialName("drafting") val drafting: DraftingPrompt,
    /**
     * Department -> words that count as having said it out loud. Read only by the
     * grounding verifier; the model never sees this table.
     */
    @SerialName("department_aliases") val departmentAliases: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class ClassificationPrompt(
    val instruction: String,
    @SerialName("few_shot") val fewShot: List<FewShotExample> = emptyList(),
    @SerialName("max_tokens") val maxTokens: Int = 128,
    val temperature: Float = 0.1f,
    @SerialName("top_k") val topK: Int = 20,
    /**
     * How many samples self-consistency voting draws. 1 disables voting.
     *
     * Config rather than a constant because the ceiling on it is a latency budget
     * that has to be measured on the handset the demo runs on, and the one moment
     * you want to change it is the one moment you cannot rebuild.
     */
    @SerialName("vote_samples") val voteSamples: Int = 1,
    /** Temperature for voting draws. Variance is the signal; 0.1 has none. */
    @SerialName("vote_temperature") val voteTemperature: Float = 0.7f,
    @SerialName("vote_top_k") val voteTopK: Int = 40,
)

@Serializable
data class FewShotExample(
    val transcript: String,
    val json: String,
)

@Serializable
data class DraftingPrompt(
    val instruction: String,
    val audiences: Map<String, AudienceRule>,
    @SerialName("max_tokens") val maxTokens: Int = 200,
    val temperature: Float = 0.4f,
    @SerialName("top_k") val topK: Int = 40,
)

@Serializable
data class AudienceRule(
    val register: String,
    val include: List<String>,
    val exclude: List<String>,
    @SerialName("example") val example: String? = null,
)

@Serializable
data class TaxonomyConfig(
    val codes: List<TaxonomyEntry>,
    @SerialName("department_hints") val departmentHints: List<String> = emptyList(),
)

@Serializable
data class TaxonomyEntry(
    val code: String,
    val display: String,
    val description: String,
    @SerialName("typical_dept") val typicalDept: String,
)

@Serializable
data class ChecklistConfig(
    val phases: Map<String, List<ChecklistItem>>,
)

@Serializable
data class ChecklistItem(
    val id: String,
    val text: String,
    val critical: Boolean = false,
)

@Serializable
data class SeedCase(
    @SerialName("case_number") val caseNumber: String,
    @SerialName("theatre_id") val theatreId: String,
    @SerialName("procedure_name") val procedureName: String,
    val surgeon: String,
    /** "HH:mm" local time today. Resolved to epoch millis at seed time. */
    @SerialName("scheduled_start") val scheduledStart: String,
    @SerialName("scheduled_duration_min") val scheduledDurationMin: Int,
)

@Serializable
data class SeedConfig(
    val cases: List<SeedCase>,
)
