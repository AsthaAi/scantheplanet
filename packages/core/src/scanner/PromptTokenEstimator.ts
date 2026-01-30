import type { TechniqueSpec } from '@scantheplanet/safe-mcp';
import type { MitigationPrompt } from '../prompt/PromptModels.js';
import { buildUserPrompt, SYSTEM_PROMPT, JSON_PREFILL } from '../prompt/PromptBuilder.js';
import type { CodeChunkPrompt, PromptPayload } from '../prompt/PromptModels.js';

export class PromptTokenEstimator {
  constructor(private readonly providerName: string, private readonly modelName?: string) {}

  baseTokens(
    technique: TechniqueSpec,
    readmeExcerpt: string | null,
    mitigations: MitigationPrompt[],
    filePath: string
  ): number {
    const payload: PromptPayload = {
      techniqueId: technique.id,
      techniqueName: technique.name,
      severity: technique.severity,
      summary: technique.summary,
      description: technique.description,
      mitigations,
      codeChunk: {
        id: 'base',
        file: filePath,
        startLine: 0,
        endLine: 0,
        code: '',
        ruleHints: [],
      } as CodeChunkPrompt,
      readmeExcerpt: readmeExcerpt ?? undefined,
    };
    const userPrompt = buildUserPrompt(payload);
    let tokens = this.countTokens(userPrompt);
    tokens += this.countTokens(SYSTEM_PROMPT);
    if (this.providerName.toLowerCase() === 'anthropic') {
      tokens += this.countTokens(JSON_PREFILL);
    }
    tokens += messageOverheadTokens(this.providerName);
    return tokens;
  }

  countTokens(text: string): number {
    // Lightweight approximation; avoids heavy tokenizer dependency.
    const normalized = text.replace(/\s+/g, ' ').trim();
    if (!normalized) return 0;
    return Math.max(1, Math.ceil(normalized.length / 4));
  }
}

function messageOverheadTokens(provider: string): number {
  const lower = provider.toLowerCase();
  switch (lower) {
    case 'openai':
      return 12;
    case 'anthropic':
      return 12;
    case 'gemini':
      return 8;
    default:
      return 10;
  }
}
