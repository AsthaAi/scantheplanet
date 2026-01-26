package ai.astha.scantheplanet.idea.scanner.model

import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload

interface CodeModel {
    val name: String
    fun analyzeChunk(prompt: PromptPayload): List<ModelFinding>
    fun analyzeChunkBatch(prompt: ai.astha.scantheplanet.idea.scanner.prompt.BatchPromptPayload): List<ModelFinding>
}

data class ModelFinding(
    val techniqueId: String? = null,
    val chunkId: String,
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val severity: String,
    val confidence: Double?,
    val observation: String,
    val evidence: String,
    val reasoning: String?,
    val unknownMitigations: List<String>,
    val modelName: String
)

class CodeModelException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
