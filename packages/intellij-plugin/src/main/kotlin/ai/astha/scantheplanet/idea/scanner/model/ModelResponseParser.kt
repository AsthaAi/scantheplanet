package ai.astha.scantheplanet.idea.scanner.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ai.astha.scantheplanet.idea.scanner.evidence.EvidenceParser
import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload
import ai.astha.scantheplanet.idea.scanner.prompt.BatchPromptPayload
import ai.astha.scantheplanet.idea.scanner.prompt.CodeChunkPrompt

object ModelResponseParser {
    private val mapper = ObjectMapper()

    fun parseFindings(content: String, prompt: PromptPayload, modelName: String): List<ModelFinding> {
        val jsonText = EvidenceParser.extractJsonObject(content) ?: content.trim()
        val node = mapper.readTree(jsonText)
        val findingsNode = node.get("findings")
            ?: throw CodeModelException("Invalid LLM response: missing findings array")
        if (!findingsNode.isArray) {
            throw CodeModelException("Invalid LLM response: findings is not an array")
        }

        val findings = mutableListOf<ModelFinding>()
        for (findingNode in findingsNode) {
            val evidenceRaw = findingNode.get("evidence")?.asText() ?: ""
            val normalized = EvidenceParser.normalizeFromPrompt(evidenceRaw, prompt, true)
            findings.add(
                ModelFinding(
                    techniqueId = null,
                    chunkId = prompt.codeChunk.id,
                    file = normalized.path,
                    startLine = normalized.start,
                    endLine = normalized.end,
                    severity = findingNode.get("severity")?.asText() ?: "unknown",
                    confidence = findingNode.get("confidence")?.asDouble(),
                    observation = findingNode.get("observation")?.asText() ?: "",
                    evidence = normalized.text,
                    reasoning = findingNode.get("reasoning")?.asText(),
                    unknownMitigations = parseUnknownMitigations(findingNode),
                    modelName = modelName
                )
            )
        }
        return findings
    }

    fun parseFindingsWithPrefill(content: String, prefill: String, prompt: PromptPayload, modelName: String): List<ModelFinding> {
        val combined = prefill + content
        return parseFindings(combined, prompt, modelName)
    }

    fun parseFindingsBatch(content: String, prompt: BatchPromptPayload, modelName: String): List<ModelFinding> {
        val jsonText = EvidenceParser.extractJsonObject(content) ?: content.trim()
        val node = mapper.readTree(jsonText)
        val findingsNode = node.get("findings")
            ?: throw CodeModelException("Invalid LLM response: missing findings array")
        if (!findingsNode.isArray) {
            throw CodeModelException("Invalid LLM response: findings is not an array")
        }

        val chunkPrompt = PromptPayload(
            techniqueId = prompt.techniques.firstOrNull()?.id ?: "unknown",
            techniqueName = prompt.techniques.firstOrNull()?.name ?: "unknown",
            severity = prompt.techniques.firstOrNull()?.severity ?: "unknown",
            summary = prompt.techniques.firstOrNull()?.summary ?: "",
            description = prompt.techniques.firstOrNull()?.description ?: "",
            mitigations = prompt.techniques.firstOrNull()?.mitigations ?: emptyList(),
            codeChunk = prompt.codeChunk,
            readmeExcerpt = prompt.readmeExcerpt
        )
        val allowed = prompt.techniques.map { it.id }.toSet()

        val findings = mutableListOf<ModelFinding>()
        for (findingNode in findingsNode) {
            val evidenceRaw = findingNode.get("evidence")?.asText() ?: ""
            val normalized = EvidenceParser.normalizeFromPrompt(evidenceRaw, chunkPrompt, true)
            val techniqueId = findingNode.get("technique_id")?.asText()?.takeIf { it in allowed }
                ?: prompt.techniques.firstOrNull()?.id
            findings.add(
                ModelFinding(
                    techniqueId = techniqueId,
                    chunkId = prompt.codeChunk.id,
                    file = normalized.path,
                    startLine = normalized.start,
                    endLine = normalized.end,
                    severity = findingNode.get("severity")?.asText() ?: "unknown",
                    confidence = findingNode.get("confidence")?.asDouble(),
                    observation = findingNode.get("observation")?.asText() ?: "",
                    evidence = normalized.text,
                    reasoning = findingNode.get("reasoning")?.asText(),
                    unknownMitigations = parseUnknownMitigations(findingNode),
                    modelName = modelName
                )
            )
        }
        return findings
    }

    private fun parseUnknownMitigations(node: JsonNode): List<String> {
        val array = node.get("unknown_mitigations") ?: return emptyList()
        if (!array.isArray) return emptyList()
        return array.mapNotNull { it.asText()?.takeIf { text -> text.isNotBlank() } }
    }
}
