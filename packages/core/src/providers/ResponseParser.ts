import type { ModelFinding } from '../types/Finding.js';
import type { PromptPayload, BatchPromptPayload } from '../prompt/PromptModels.js';
import { extractJsonObject, normalizeFromPrompt } from './EvidenceParser.js';

/**
 * Raw finding from model response
 */
interface RawFinding {
  technique_id?: string;
  severity?: string;
  confidence?: number;
  observation?: string;
  evidence?: string;
  reasoning?: string;
  unknown_mitigations?: string[];
}

/**
 * Parse model response JSON into findings
 */
export function parseFindings(
  content: string,
  prompt: PromptPayload,
  modelName: string
): ModelFinding[] {
  const jsonText = extractJsonObject(content) ?? content.trim();
  const parsed = JSON.parse(jsonText) as { findings?: RawFinding[] };
  const findings = parsed.findings ?? [];

  return findings.map((raw) => {
    const normalized = normalizeFromPrompt(raw.evidence ?? '', prompt, true);
    return {
      techniqueId: undefined,
      chunkId: prompt.codeChunk.id,
      file: normalized.path,
      startLine: normalized.start,
      endLine: normalized.end,
      severity: raw.severity ?? 'info',
      confidence: raw.confidence ?? null,
      observation: raw.observation ?? '',
      evidence: normalized.text,
      reasoning: raw.reasoning ?? null,
      unknownMitigations: raw.unknown_mitigations ?? [],
      modelName,
    };
  });
}

/**
 * Parse model response with JSON prefill (Anthropic)
 */
export function parseFindingsWithPrefill(
  content: string,
  prefill: string,
  prompt: PromptPayload,
  modelName: string
): ModelFinding[] {
  // Anthropic responses continue from the prefill
  const fullJson = prefill + content;
  return parseFindings(fullJson, prompt, modelName);
}

/**
 * Parse batch model response into findings
 */
export function parseFindingsBatch(
  content: string,
  prompt: BatchPromptPayload,
  modelName: string
): ModelFinding[] {
  const jsonText = extractJsonObject(content) ?? content.trim();
  const parsed = JSON.parse(jsonText) as { findings?: RawFinding[] };
  const findings = parsed.findings ?? [];
  const primary = prompt.techniques[0];
  const chunkPrompt: PromptPayload = {
    techniqueId: primary?.id ?? 'unknown',
    techniqueName: primary?.name ?? 'unknown',
    severity: primary?.severity ?? 'unknown',
    summary: primary?.summary ?? '',
    description: primary?.description ?? '',
    mitigations: primary?.mitigations ?? [],
    codeChunk: prompt.codeChunk,
    readmeExcerpt: prompt.readmeExcerpt,
  };

  return findings.map((raw) => {
    const normalized = normalizeFromPrompt(raw.evidence ?? '', chunkPrompt, true);
    return {
      techniqueId: raw.technique_id,
      chunkId: prompt.codeChunk.id,
      file: normalized.path,
      startLine: normalized.start,
      endLine: normalized.end,
      severity: raw.severity ?? 'info',
      confidence: raw.confidence ?? null,
      observation: raw.observation ?? '',
      evidence: normalized.text,
      reasoning: raw.reasoning ?? null,
      unknownMitigations: raw.unknown_mitigations ?? [],
      modelName,
    };
  });
}

/**
 * Parse batch response with JSON prefill (Anthropic)
 */
export function parseFindingsBatchWithPrefill(
  content: string,
  prefill: string,
  prompt: BatchPromptPayload,
  modelName: string
): ModelFinding[] {
  const fullJson = prefill + content;
  return parseFindingsBatch(fullJson, prompt, modelName);
}
