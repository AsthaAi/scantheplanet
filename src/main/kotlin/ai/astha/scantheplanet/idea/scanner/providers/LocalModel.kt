package ai.astha.scantheplanet.idea.scanner.providers

import ai.astha.scantheplanet.idea.scanner.evidence.EvidenceParser
import ai.astha.scantheplanet.idea.scanner.model.CodeModel
import ai.astha.scantheplanet.idea.scanner.model.ModelFinding
import ai.astha.scantheplanet.idea.scanner.prompt.BatchPromptPayload
import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload

class LocalModel(private val severityOverride: String? = null) : CodeModel {
    override val name: String = "local"

    override fun analyzeChunk(prompt: PromptPayload): List<ModelFinding> {
        if (prompt.codeChunk.ruleHints.isEmpty()) return emptyList()
        val severity = severityOverride?.lowercase() ?: "info"
        return prompt.codeChunk.ruleHints.map { hint ->
            ModelFinding(
                techniqueId = null,
                chunkId = prompt.codeChunk.id,
                file = prompt.codeChunk.file,
                startLine = hint.line,
                endLine = hint.line,
                severity = severity,
                confidence = null,
                observation = "Rule hint matched: ${hint.signalId}",
                evidence = EvidenceParser.formatRuleHintEvidence(prompt.codeChunk.file, hint.line, hint.snippet),
                reasoning = null,
                unknownMitigations = emptyList(),
                modelName = name
            )
        }
    }

    override fun analyzeChunkBatch(prompt: BatchPromptPayload): List<ModelFinding> {
        val severity = severityOverride?.lowercase() ?: "info"
        val findings = mutableListOf<ModelFinding>()
        for (technique in prompt.techniques) {
            if (technique.ruleHints.isEmpty()) continue
            for (hint in technique.ruleHints) {
                findings.add(
                    ModelFinding(
                        techniqueId = technique.id,
                        chunkId = prompt.codeChunk.id,
                        file = prompt.codeChunk.file,
                        startLine = hint.line,
                        endLine = hint.line,
                        severity = severity,
                        confidence = null,
                        observation = "Rule hint matched: ${hint.signalId}",
                        evidence = EvidenceParser.formatRuleHintEvidence(prompt.codeChunk.file, hint.line, hint.snippet),
                        reasoning = null,
                        unknownMitigations = emptyList(),
                        modelName = name
                    )
                )
            }
        }
        return findings
    }
}
