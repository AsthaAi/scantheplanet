package ai.astha.scantheplanet.idea.scanner.prompt

import ai.astha.scantheplanet.idea.scanner.RuleHint

data class PromptPayload(
    val techniqueId: String,
    val techniqueName: String,
    val severity: String,
    val summary: String,
    val description: String,
    val mitigations: List<MitigationPrompt>,
    val codeChunk: CodeChunkPrompt,
    val readmeExcerpt: String?
)

data class BatchPromptPayload(
    val techniques: List<TechniquePrompt>,
    val codeChunk: CodeChunkPrompt,
    val readmeExcerpt: String?
)

data class TechniquePrompt(
    val id: String,
    val name: String,
    val severity: String,
    val summary: String,
    val description: String,
    val mitigations: List<MitigationPrompt>,
    val ruleHints: List<ai.astha.scantheplanet.idea.scanner.RuleHint>
)

data class MitigationPrompt(
    val id: String,
    val description: String
)

data class CodeChunkPrompt(
    val id: String,
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val code: String,
    val ruleHints: List<RuleHint>
)
