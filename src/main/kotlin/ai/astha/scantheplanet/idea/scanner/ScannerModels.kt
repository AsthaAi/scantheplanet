package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class TechniqueSpec(
    val id: String = "",
    val name: String = "",
    val severity: String = "",
    val summary: String = "",
    val description: String = "",
    val mitigations: List<String> = emptyList(),
    @field:JsonProperty("code_signals")
    val codeSignals: List<CodeSignal> = emptyList(),
    val languages: List<String> = emptyList(),
    @field:JsonProperty("output_schema")
    val outputSchema: OutputSchema = OutputSchema(),
    @field:JsonProperty("llm_required")
    val llmRequired: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CodeSignal(
    val id: String = "",
    val description: String = "",
    val heuristics: List<Heuristic> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Heuristic(
    val pattern: String? = null,
    val regex: String? = null,
    val flags: String? = null,
    @field:JsonProperty("all_of")
    val allOf: List<HeuristicClause> = emptyList(),
    val window: Int? = null,
    @field:JsonProperty("pattern_ref")
    val patternRef: String? = null,
    @field:JsonProperty("entropy_min")
    val entropyMin: Double? = null,
    @field:JsonProperty("min_length")
    val minLength: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HeuristicClause(
    val pattern: String? = null,
    val regex: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OutputSchema(
    @field:JsonProperty("requires_mitigations")
    val requiresMitigations: Boolean = false,
    @field:JsonProperty("allowed_status_values")
    val allowedStatusValues: List<String> = emptyList()
)

data class ScanFinding(
    val techniqueId: String,
    val techniqueName: String = "",
    val severity: String,
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val observation: String
)

data class ScanSummary(
    val status: String,
    val summary: String,
    val findings: List<ScanFinding>,
    val scannedAtUtc: String = "",
    val filesScanned: Int = 0,
    val chunksAnalyzed: Int = 0,
    val chunksFailed: Int = 0,
    val modelSupport: List<String> = emptyList()
)

enum class ScanStatus {
    PASS,
    FAIL,
    UNKNOWN
}
