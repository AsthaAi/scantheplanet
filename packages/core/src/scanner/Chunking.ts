import type { TechniqueSpec } from '@scantheplanet/safe-mcp';
import type { MitigationPrompt } from '../prompt/PromptModels.js';
import { PromptTokenEstimator } from './PromptTokenEstimator.js';
import type { CodeChunk, ChunkKind } from './Chunks.js';
import { ScannerConfigDefaults } from './ScannerConfig.js';

export interface LineRange {
  start: number;
  end: number;
}

export interface AdaptiveChunkConfig {
  maxPromptTokens: number;
  reserveOutputTokens: number;
}

export function buildChunksFixed(
  relativePath: string,
  lines: string[],
  lineRange: LineRange,
  maxLinesPerChunk: number,
  includeLines?: Set<number> | null
): CodeChunk[] {
  const chunks: CodeChunk[] = [];
  let start = lineRange.start;
  const end = lineRange.end;
  while (start <= end) {
    const chunkEnd = Math.min(start + maxLinesPerChunk - 1, end);
    if (!includeLines || anyIncluded(includeLines, start, chunkEnd)) {
      const code = lines.slice(start - 1, chunkEnd).join('\n');
      const kind: ChunkKind =
        chunkEnd - start + 1 >= maxLinesPerChunk ? 'slidingWindow' : 'wholeFile';
      chunks.push({
        id: `${relativePath}:${start}-${chunkEnd}`,
        file: relativePath,
        startLine: start,
        endLine: chunkEnd,
        kind,
        code,
        ruleHints: [],
      });
    }
    start = chunkEnd + 1;
  }
  return chunks;
}

export function buildChunksAdaptive(
  relativePath: string,
  lines: string[],
  lineRange: LineRange,
  includeLines: Set<number> | null | undefined,
  technique: TechniqueSpec,
  readmeExcerpt: string | null,
  mitigations: MitigationPrompt[],
  estimator: PromptTokenEstimator,
  maxLinesPerChunk: number,
  maxPromptTokens: number,
  reserveOutputTokens: number
): CodeChunk[] {
  const chunks: CodeChunk[] = [];
  const baseTokens = estimator.baseTokens(
    technique,
    readmeExcerpt,
    mitigations,
    relativePath
  );
  const budget = Math.max(maxPromptTokens - reserveOutputTokens, 500);
  const effectiveBudget = Math.floor(budget * 0.85);
  const lineTokens = lines.map((line) => estimator.countTokens(`${line}\n`));

  let start = lineRange.start;
  const end = lineRange.end;
  while (start <= end) {
    let tokenSum = baseTokens;
    let cursor = start;
    let lastGood = start - 1;
    while (cursor <= end && cursor - start + 1 <= maxLinesPerChunk) {
      tokenSum += lineTokens[cursor - 1] ?? 0;
      if (tokenSum <= effectiveBudget) {
        lastGood = cursor;
        cursor += 1;
      } else {
        break;
      }
    }
    if (lastGood < start) {
      lastGood = start;
    }
    if (!includeLines || anyIncluded(includeLines, start, lastGood)) {
      const code = lines.slice(start - 1, lastGood).join('\n');
      const kind: ChunkKind =
        lastGood - start + 1 >= maxLinesPerChunk ? 'slidingWindow' : 'wholeFile';
      chunks.push({
        id: `${relativePath}:${start}-${lastGood}`,
        file: relativePath,
        startLine: start,
        endLine: lastGood,
        kind,
        code,
        ruleHints: [],
      });
    }
    start = lastGood + 1;
  }

  return chunks;
}

export function selectAdaptiveConfig(
  modelName: string,
  config: { maxPromptTokens?: number; reserveOutputTokens?: number }
): AdaptiveChunkConfig {
  const lower = modelName.toLowerCase();
  if (lower.startsWith('gpt-5')) {
    return {
      maxPromptTokens: ScannerConfigDefaults.GPT5_MAX_PROMPT_TOKENS,
      reserveOutputTokens: ScannerConfigDefaults.GPT5_RESERVE_OUTPUT_TOKENS,
    };
  }
  return {
    maxPromptTokens:
      config.maxPromptTokens ?? ScannerConfigDefaults.DEFAULT_MAX_PROMPT_TOKENS,
    reserveOutputTokens:
      config.reserveOutputTokens ??
      ScannerConfigDefaults.DEFAULT_RESERVE_OUTPUT_TOKENS,
  };
}

export function selectMaxLinesPerChunk(
  modelName: string,
  base: number
): number {
  const lower = modelName.toLowerCase();
  if (lower.startsWith('gpt-5')) {
    return Math.max(base, 2000);
  }
  return base;
}

function anyIncluded(lines: Set<number>, start: number, end: number): boolean {
  for (const line of lines) {
    if (line >= start && line <= end) return true;
  }
  return false;
}
