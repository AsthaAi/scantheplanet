import type { CodeModel } from './CodeModel.js';
import type { ModelFinding } from '../types/Finding.js';
import type { PromptPayload, BatchPromptPayload } from '../prompt/PromptModels.js';
import { formatRuleHintEvidence } from './EvidenceParser.js';

/**
 * Local model that uses heuristic matching only (no LLM calls)
 */
export class LocalModel implements CodeModel {
  readonly name = 'local';

  constructor(private readonly severityOverride?: string) {}

  async analyzeChunk(prompt: PromptPayload): Promise<ModelFinding[]> {
    if (prompt.codeChunk.ruleHints.length === 0) {
      return [];
    }

    const severity = this.severityOverride?.toLowerCase() ?? 'info';

    return prompt.codeChunk.ruleHints.map((hint) => ({
      techniqueId: undefined,
      chunkId: prompt.codeChunk.id,
      file: prompt.codeChunk.file,
      startLine: hint.line,
      endLine: hint.line,
      severity,
      confidence: null,
      observation: `Rule hint matched: ${hint.signalId}`,
      evidence: formatRuleHintEvidence(
        prompt.codeChunk.file,
        hint.line,
        hint.snippet
      ),
      reasoning: null,
      unknownMitigations: [],
      modelName: this.name,
    }));
  }

  async analyzeChunkBatch(prompt: BatchPromptPayload): Promise<ModelFinding[]> {
    const severity = this.severityOverride?.toLowerCase() ?? 'info';
    const findings: ModelFinding[] = [];

    for (const technique of prompt.techniques) {
      if (technique.ruleHints.length === 0) continue;

      for (const hint of technique.ruleHints) {
        findings.push({
          techniqueId: technique.id,
          chunkId: prompt.codeChunk.id,
          file: prompt.codeChunk.file,
          startLine: hint.line,
          endLine: hint.line,
          severity,
          confidence: null,
          observation: `Rule hint matched: ${hint.signalId}`,
          evidence: formatRuleHintEvidence(
            prompt.codeChunk.file,
            hint.line,
            hint.snippet
          ),
          reasoning: null,
          unknownMitigations: [],
          modelName: this.name,
        });
      }
    }

    return findings;
  }
}
