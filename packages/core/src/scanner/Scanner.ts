import { createHash } from 'node:crypto';
import { relative } from 'node:path';
import type { TechniqueSpec } from '@scantheplanet/safe-mcp';
import { SafeMcpRepository } from '@scantheplanet/safe-mcp';
import type {
  ScanFinding,
  ScanSummary,
  ScanProgress,
  ChunkStatus,
  ScanBatch,
  GatingStats,
} from '../types/ScanResult.js';
import type { ScannerConfig } from './ScannerConfig.js';
import { ScannerConfigDefaults } from './ScannerConfig.js';
import type { ScanScope, PathFilters } from './Scope.js';
import type { CodeChunk } from './Chunks.js';
import type { CodeModel } from '../providers/CodeModel.js';
import {
  collectFiles,
  createGlobMatchers,
  gitChangedFiles,
  gitDiffAddedLines,
  isIncluded,
  readTextIfValid,
} from './ScannerUtils.js';
import {
  buildChunksAdaptive,
  buildChunksFixed,
  selectAdaptiveConfig,
  selectMaxLinesPerChunk,
} from './Chunking.js';
import { PromptTokenEstimator } from './PromptTokenEstimator.js';
import { applyRuleHints, computeHints } from './RuleHints.js';
import { buildModel } from '../providers/ModelFactory.js';
import { toScanFinding } from '../types/Finding.js';
import type { ScanCache } from './ScanCache.js';
import type { MitigationPrompt } from '../prompt/PromptModels.js';
import type { PromptPayload, BatchPromptPayload } from '../prompt/PromptModels.js';
import { PROMPT_VERSION } from '../prompt/PromptBuilder.js';

export interface ScannerOptions {
  maxLinesPerChunk?: number;
  progress?: (progress: ScanProgress) => void;
  batchEnabled?: boolean;
  batchSize?: number;
  cache?: ScanCache;
}

export interface ScanParams extends ScannerOptions {
  repoPath: string;
  techniqueIds: string[];
  scope: ScanScope;
  provider: string;
  modelName?: string;
  apiKey?: string;
}

export class Scanner {
  constructor(
    private readonly repository: SafeMcpRepository,
    private readonly config: ScannerConfig,
    private readonly cache?: ScanCache
  ) {}

  async scan(params: ScanParams): Promise<ScanSummary> {
    const model = buildModel(
      params.provider,
      params.modelName,
      params.apiKey,
      this.config
    );
    const maxLinesPerChunk = params.maxLinesPerChunk ?? 200;
    const progress = params.progress;
    const batchEnabled = params.batchEnabled ?? true;
    const batchSize = Math.max(params.batchSize ?? 3, 1);
    const cache = params.cache ?? this.cache;

    const diffAddedLines =
      params.scope.kind === 'gitDiff'
        ? gitDiffAddedLines(params.repoPath, params.scope.baseRef)
        : new Map<string, Set<number>>();

    const techniqueIds = params.techniqueIds.map((id) => normalizeTechniqueId(id));

    const findings: ScanFinding[] = [];
    const summaries: string[] = [];
    const gatingSummaries: string[] = [];
    const techniqueCounts = new Map<string, number>();
    const gatingByTechnique = new Map<string, GatingStats>();
    const techniqueNames = new Map<string, string>();

    let overallStatus: 'pass' | 'fail' | 'unknown' = 'pass';
    let filesScannedTotal = 0;
    let chunksAnalyzedTotal = 0;
    let chunksFailedTotal = 0;

    if (batchEnabled) {
      const batches = chunkArray(techniqueIds, batchSize);
      const totalBatches = batches.length;
      for (const [batchIndex, batchIds] of batches.entries()) {
        const techniques = batchIds
          .map((id) => this.repository.loadTechnique(id))
          .filter(Boolean) as TechniqueSpec[];
        if (techniques.length === 0) continue;
        for (const tech of techniques) {
          if (!techniqueCounts.has(tech.id)) {
            techniqueCounts.set(tech.id, 0);
          }
          if (!gatingByTechnique.has(tech.id)) {
            gatingByTechnique.set(tech.id, emptyGatingStats());
          }
          if (!techniqueNames.has(tech.id)) {
            techniqueNames.set(tech.id, tech.name);
          }
        }

        const filters = buildFilters(this.config);
        const matchers = createGlobMatchers(filters);
        const files = resolveFiles(params.repoPath, params.scope, filters, matchers);
        const batchSignature = hashString(JSON.stringify(techniques));
        const modelLineCap = selectMaxLinesPerChunk(model.name, maxLinesPerChunk);
        const batchLabel = `Batch ${batchIndex + 1} of ${totalBatches}`;
        const batchName = techniques.map((t) => t.name).join(', ').slice(0, 200);

        const result = await scanFilesBatch({
          files,
          repoPath: params.repoPath,
          techniques,
          batchSignature,
          providerName: params.provider,
          scope: params.scope,
          maxLinesPerChunk: modelLineCap,
          filters,
          matchers,
          model,
          diffAddedLines,
          progress,
          gatingByTechnique,
          batchLabel,
          batchName,
          batchIndex: batchIndex + 1,
          totalBatches,
          config: this.config,
          repository: this.repository,
          cache,
        });

        filesScannedTotal += result.filesScanned;
        chunksAnalyzedTotal += result.chunksAnalyzed;
        chunksFailedTotal += result.chunksFailed;
        if (result.findings.length > 0) {
          overallStatus = 'fail';
        }
        for (const finding of result.findings) {
          const id = finding.techniqueId;
          techniqueCounts.set(id, (techniqueCounts.get(id) ?? 0) + 1);
          if (!techniqueNames.has(id)) {
            techniqueNames.set(id, id);
          }
        }
        findings.push(...result.findings);
      }

      for (const [tech, count] of techniqueCounts.entries()) {
        summaries.push(`${tech}: ${count} findings`);
      }
      for (const [tech, stats] of gatingByTechnique.entries()) {
        gatingSummaries.push(
          `${tech}: gated ${stats.gatedChunks}/${stats.totalChunks} chunks; shadow ${stats.shadowChunks} sampled, ${stats.shadowFindings} findings`
        );
      }
    } else {
      const totalTechniques = techniqueIds.length;
      for (const [index, techniqueId] of techniqueIds.entries()) {
        const technique = this.repository.loadTechnique(techniqueId);
        if (!technique) {
          summaries.push(`${techniqueId}: technique not found`);
          overallStatus = 'unknown';
          continue;
        }

        const filters = buildFilters(this.config);
        const matchers = createGlobMatchers(filters);
        const files = resolveFiles(params.repoPath, params.scope, filters, matchers);
        const techniqueSignature = hashString(JSON.stringify(technique));
        const modelLineCap = selectMaxLinesPerChunk(model.name, maxLinesPerChunk);

        const result = await scanFiles({
          files,
          repoPath: params.repoPath,
          technique,
          techniqueSignature,
          providerName: params.provider,
          scope: params.scope,
          maxLinesPerChunk: modelLineCap,
          filters,
          matchers,
          model,
          diffAddedLines,
          progress,
          techniqueId: technique.id,
          techniqueName: technique.name,
          techniqueIndex: index + 1,
          totalTechniques,
          config: this.config,
          repository: this.repository,
          cache,
        });

        filesScannedTotal += result.filesScanned;
        chunksAnalyzedTotal += result.chunksAnalyzed;
        chunksFailedTotal += result.chunksFailed;
        if (result.findings.length > 0) {
          overallStatus = 'fail';
        }
        findings.push(...result.findings);
        summaries.push(`${technique.id}: ${result.findings.length} findings`);
        if (result.gatingStats) {
          gatingSummaries.push(
            `${technique.id}: gated ${result.gatingStats.gatedChunks}/${result.gatingStats.totalChunks} chunks; shadow ${result.gatingStats.shadowChunks} sampled, ${result.gatingStats.shadowFindings} findings`
          );
        }
      }
    }

    const summaryText =
      `Techniques scanned: ${techniqueIds.length}. Findings: ${findings.length}.` +
      (summaries.length > 0 ? `\n${summaries.join('\n')}` : '');
    const gatingText =
      gatingSummaries.length > 0
        ? `\nGating summary:\n${gatingSummaries.join('\n')}`
        : '';

    const summary: ScanSummary = {
      status: overallStatus,
      summary: summaryText + gatingText,
      findings,
      scannedAtUtc: new Date().toISOString(),
      filesScanned: filesScannedTotal,
      chunksAnalyzed: chunksAnalyzedTotal,
      chunksFailed: chunksFailedTotal,
      modelSupport: [model.name],
    };

    cache?.flush?.();

    return summary;
  }
}

interface ScanFilesParams {
  files: string[];
  repoPath: string;
  technique: TechniqueSpec;
  techniqueSignature: string;
  providerName: string;
  scope: ScanScope;
  maxLinesPerChunk: number;
  filters: PathFilters;
  matchers: ReturnType<typeof createGlobMatchers>;
  model: CodeModel;
  diffAddedLines: Map<string, Set<number>>;
  progress?: (progress: ScanProgress) => void;
  techniqueId: string;
  techniqueName: string;
  techniqueIndex: number;
  totalTechniques: number;
  config: ScannerConfig;
  repository: SafeMcpRepository;
  cache?: ScanCache;
}

async function scanFiles(params: ScanFilesParams): Promise<ScanBatch> {
  const {
    files,
    repoPath,
    technique,
    techniqueSignature,
    providerName,
    scope,
    maxLinesPerChunk,
    filters,
    matchers,
    model,
    diffAddedLines,
    progress,
    techniqueId,
    techniqueName,
    techniqueIndex,
    totalTechniques,
    config,
    repository,
    cache,
  } = params;

  const findings: ScanFinding[] = [];
  const filesScanned = new Set<string>();
  let chunksAnalyzed = 0;
  let chunksFailed = 0;
  const estimator = new PromptTokenEstimator(providerName, model.name);
  const gatingEnabled = config.gatingEnabled ?? true;
  const shadowSampleRate = config.shadowSampleRate ?? 0;
  const adaptiveChunking = config.adaptiveChunking ?? true;
  const gatingStats =
    gatingEnabled || shadowSampleRate > 0 ? emptyGatingStats() : null;
  const totalFiles = files.length;
  let processedFiles = 0;
  let lastProgressAt = 0;

  for (const file of files) {
    processedFiles += 1;
    const relativePath = safeRelative(repoPath, file);
    if (!isIncluded(relativePath, file, filters, matchers)) {
      if (
        maybeReportProgress(
          progress,
          techniqueId,
          techniqueName,
          techniqueIndex,
          totalTechniques,
          relativePath,
          null,
          processedFiles,
          totalFiles,
          chunksAnalyzed,
          chunksFailed,
          lastProgressAt
        )
      ) {
        lastProgressAt = processedFiles;
      }
      continue;
    }
    if (!shouldScanFileForTechnique(relativePath, technique)) {
      if (
        maybeReportProgress(
          progress,
          techniqueId,
          techniqueName,
          techniqueIndex,
          totalTechniques,
          relativePath,
          null,
          processedFiles,
          totalFiles,
          chunksAnalyzed,
          chunksFailed,
          lastProgressAt
        )
      ) {
        lastProgressAt = processedFiles;
      }
      continue;
    }

    const lines = readTextIfValid(file);
    if (!lines) continue;

    const lineRange = resolveLineRange(scope, lines.length);
    const includeLines =
      scope.kind === 'gitDiff' ? diffAddedLines.get(file) ?? null : null;

    const readmeExcerpt = repository.readTechniqueReadme(technique.id);
    const mitigationPrompts = buildMitigationPrompts(repository, technique);

    const adaptiveConfig = selectAdaptiveConfig(model.name, config);
    const chunks = adaptiveChunking
      ? buildChunksAdaptive(
          relativePath,
          lines,
          lineRange,
          includeLines,
          technique,
          readmeExcerpt ? truncateReadme(readmeExcerpt) : null,
          mitigationPrompts,
          estimator,
          maxLinesPerChunk,
          adaptiveConfig.maxPromptTokens,
          adaptiveConfig.reserveOutputTokens
        )
      : buildChunksFixed(
          relativePath,
          lines,
          lineRange,
          maxLinesPerChunk,
          includeLines
        );

    applyRuleHints(technique, chunks);

    for (const chunk of chunks) {
      gatingStats && (gatingStats.totalChunks += 1);
      let shadowed = false;
      if (chunk.ruleHints.length === 0 && !technique.llm_required) {
        const sampleRate = clamp(shadowSampleRate, 0, 1);
        if (gatingEnabled) {
          if (sampleRate > 0 && Math.random() <= sampleRate) {
            shadowed = true;
            gatingStats && (gatingStats.shadowChunks += 1);
          } else {
            gatingStats && (gatingStats.gatedChunks += 1);
            continue;
          }
        }
      }

      chunksAnalyzed += 1;
      gatingStats && (gatingStats.llmChunks += 1);

      if (
        maybeReportProgress(
          progress,
          techniqueId,
          techniqueName,
          techniqueIndex,
          totalTechniques,
          relativePath,
          chunk.id,
          processedFiles,
          totalFiles,
          chunksAnalyzed,
          chunksFailed,
          lastProgressAt
        )
      ) {
        lastProgressAt = processedFiles;
      }

      const cacheKey = buildCacheKey(
        technique.id,
        techniqueSignature,
        providerName,
        model.name,
        chunk
      );
      const cached = cache?.get(cacheKey);
      if (cached) {
        reportChunkStatus(
          progress,
          techniqueId,
          techniqueName,
          techniqueIndex,
          totalTechniques,
          relativePath,
          chunk.id,
          processedFiles,
          totalFiles,
          chunksAnalyzed,
          chunksFailed,
          'cached'
        );
        for (const finding of cached) {
          findings.push(toScanFinding(finding, technique.id, technique.name));
        }
        continue;
      }

      const payload: PromptPayload = {
        techniqueId: technique.id,
        techniqueName: technique.name,
        severity: technique.severity,
        summary: technique.summary,
        description: technique.description,
        mitigations: mitigationPrompts,
        codeChunk: {
          id: chunk.id,
          file: relativePath,
          startLine: chunk.startLine,
          endLine: chunk.endLine,
          code: chunk.code,
          ruleHints: chunk.ruleHints,
        },
        readmeExcerpt: readmeExcerpt ? truncateReadme(readmeExcerpt) : undefined,
      };

      try {
        const modelFindings = await model.analyzeChunk(payload);
        if (shadowed && modelFindings.length > 0) {
          gatingStats && (gatingStats.shadowFindings += modelFindings.length);
        }
        cache?.put(cacheKey, modelFindings);
        cache?.flushMaybe();
        for (const finding of modelFindings) {
          findings.push(toScanFinding(finding, technique.id, technique.name));
        }
      } catch (error) {
        chunksFailed += 1;
        throw error;
      }
    }

    if (chunks.length > 0) {
      filesScanned.add(relativePath);
    }

    if (
      maybeReportProgress(
        progress,
        techniqueId,
        techniqueName,
        techniqueIndex,
        totalTechniques,
        relativePath,
        null,
        processedFiles,
        totalFiles,
        chunksAnalyzed,
        chunksFailed,
        lastProgressAt
      )
    ) {
      lastProgressAt = processedFiles;
    }
  }

  return {
    findings,
    filesScanned: filesScanned.size,
    chunksAnalyzed,
    chunksFailed,
    gatingStats,
  };
}

interface ScanFilesBatchParams {
  files: string[];
  repoPath: string;
  techniques: TechniqueSpec[];
  batchSignature: string;
  providerName: string;
  scope: ScanScope;
  maxLinesPerChunk: number;
  filters: PathFilters;
  matchers: ReturnType<typeof createGlobMatchers>;
  model: CodeModel;
  diffAddedLines: Map<string, Set<number>>;
  progress?: (progress: ScanProgress) => void;
  gatingByTechnique: Map<string, GatingStats>;
  batchLabel: string;
  batchName: string;
  batchIndex: number;
  totalBatches: number;
  config: ScannerConfig;
  repository: SafeMcpRepository;
  cache?: ScanCache;
}

async function scanFilesBatch(params: ScanFilesBatchParams): Promise<ScanBatch> {
  const {
    files,
    repoPath,
    techniques,
    batchSignature,
    providerName,
    scope,
    maxLinesPerChunk,
    filters,
    matchers,
    model,
    diffAddedLines,
    progress,
    gatingByTechnique,
    batchLabel,
    batchName,
    batchIndex,
    totalBatches,
    config,
    repository,
    cache,
  } = params;

  const findings: ScanFinding[] = [];
  const filesScanned = new Set<string>();
  let chunksAnalyzed = 0;
  let chunksFailed = 0;
  const estimator = new PromptTokenEstimator(providerName, model.name);
  const totalFiles = files.length;
  let processedFiles = 0;
  let lastProgressAt = 0;

  for (const file of files) {
    processedFiles += 1;
    const relativePath = safeRelative(repoPath, file);
    if (!isIncluded(relativePath, file, filters, matchers)) {
      if (
        maybeReportProgress(
          progress,
          batchLabel,
          batchName,
          batchIndex,
          totalBatches,
          relativePath,
          null,
          processedFiles,
          totalFiles,
          chunksAnalyzed,
          chunksFailed,
          lastProgressAt
        )
      ) {
        lastProgressAt = processedFiles;
      }
      continue;
    }

    const eligible = techniques.filter((tech) =>
      shouldScanFileForTechnique(relativePath, tech)
    );
    if (eligible.length === 0) {
      if (
        maybeReportProgress(
          progress,
          batchLabel,
          batchName,
          batchIndex,
          totalBatches,
          relativePath,
          null,
          processedFiles,
          totalFiles,
          chunksAnalyzed,
          chunksFailed,
          lastProgressAt
        )
      ) {
        lastProgressAt = processedFiles;
      }
      continue;
    }

    const lines = readTextIfValid(file);
    if (!lines) continue;

    const lineRange = resolveLineRange(scope, lines.length);
    const includeLines =
      scope.kind === 'gitDiff' ? diffAddedLines.get(file) ?? null : null;

    const adaptiveConfig = selectAdaptiveConfig(model.name, config);
    const adaptiveChunking = config.adaptiveChunking ?? true;
    const gatingEnabled = config.gatingEnabled ?? true;
    const shadowSampleRate = config.shadowSampleRate ?? 0;
    const chunks = adaptiveChunking
      ? buildChunksAdaptive(
          relativePath,
          lines,
          lineRange,
          includeLines,
          eligible[0],
          null,
          [],
          estimator,
          maxLinesPerChunk,
          adaptiveConfig.maxPromptTokens,
          adaptiveConfig.reserveOutputTokens
        )
      : buildChunksFixed(
          relativePath,
          lines,
          lineRange,
          maxLinesPerChunk,
          includeLines
        );

    for (const chunk of chunks) {
      const hintsForChunk = new Map<string, ReturnType<typeof computeHints>>();
      for (const tech of eligible) {
        hintsForChunk.set(tech.id, computeHints(tech, chunk));
        const stats = gatingByTechnique.get(tech.id) ?? emptyGatingStats();
        stats.totalChunks += 1;
        gatingByTechnique.set(tech.id, stats);
      }

      let gated = true;
      for (const tech of eligible) {
        const hints = hintsForChunk.get(tech.id) ?? [];
        if (hints.length > 0 || tech.llm_required || !gatingEnabled) {
          gated = false;
        }
      }

      let shadowed = false;
      const sampleRate = clamp(shadowSampleRate, 0, 1);
      if (gatingEnabled && gated && sampleRate > 0 && Math.random() <= sampleRate) {
        shadowed = true;
        gated = false;
      }

      if (gatingEnabled && gated) {
        for (const tech of eligible) {
          const stats = gatingByTechnique.get(tech.id) ?? emptyGatingStats();
          stats.gatedChunks += 1;
          gatingByTechnique.set(tech.id, stats);
        }
        continue;
      }

      const activeTechniques: BatchPromptPayload['techniques'] = [];
      for (const tech of eligible) {
        const hints = hintsForChunk.get(tech.id) ?? [];
        if (gatingEnabled && !tech.llm_required && hints.length === 0 && !shadowed) {
          continue;
        }
        if (shadowed && hints.length === 0 && !tech.llm_required) {
          const stats = gatingByTechnique.get(tech.id) ?? emptyGatingStats();
          stats.shadowChunks += 1;
          gatingByTechnique.set(tech.id, stats);
        }
        const mitigations = buildMitigationPrompts(repository, tech);
        activeTechniques.push({
          id: tech.id,
          name: tech.name,
          severity: tech.severity,
          summary: tech.summary,
          description: tech.description,
          mitigations,
          ruleHints: hints,
        });
      }

      if (activeTechniques.length === 0) {
        continue;
      }

      chunksAnalyzed += 1;
      if (
        maybeReportProgress(
          progress,
          batchLabel,
          batchName,
          batchIndex,
          totalBatches,
          relativePath,
          chunk.id,
          processedFiles,
          totalFiles,
          chunksAnalyzed,
          chunksFailed,
          lastProgressAt
        )
      ) {
        lastProgressAt = processedFiles;
      }
      for (const tech of activeTechniques) {
        const stats = gatingByTechnique.get(tech.id) ?? emptyGatingStats();
        stats.llmChunks += 1;
        gatingByTechnique.set(tech.id, stats);
      }

      const cacheKey = buildCacheKeyBatch(
        batchSignature,
        providerName,
        model.name,
        chunk,
        activeTechniques.map((t) => t.id)
      );
      const techniqueNameMap = new Map(
        activeTechniques.map((tech) => [tech.id, tech.name])
      );
      const defaultTechniqueId =
        activeTechniques.length === 1 ? activeTechniques[0].id : 'unknown';
      const defaultTechniqueName =
        activeTechniques.length === 1 ? activeTechniques[0].name : '';

      const cached = cache?.get(cacheKey);
      if (cached) {
        reportChunkStatus(
          progress,
          batchLabel,
          batchName,
          batchIndex,
          totalBatches,
          relativePath,
          chunk.id,
          processedFiles,
          totalFiles,
          chunksAnalyzed,
          chunksFailed,
          'cached'
        );
        for (const finding of cached) {
          const id = finding.techniqueId ?? defaultTechniqueId;
          const name = techniqueNameMap.get(finding.techniqueId ?? '') ?? defaultTechniqueName;
          findings.push(toScanFinding(finding, id, name));
        }
        continue;
      }

      const payload: BatchPromptPayload = {
        techniques: activeTechniques,
        codeChunk: {
          id: chunk.id,
          file: relativePath,
          startLine: chunk.startLine,
          endLine: chunk.endLine,
          code: chunk.code,
          ruleHints: [],
        },
        readmeExcerpt: undefined,
      };

      try {
        const result = await model.analyzeChunkBatch(payload);
        for (const finding of result) {
          if (shadowed) {
            const stats = gatingByTechnique.get(finding.techniqueId ?? 'unknown') ?? emptyGatingStats();
            stats.shadowFindings += 1;
            gatingByTechnique.set(finding.techniqueId ?? 'unknown', stats);
          }
        }
        cache?.put(cacheKey, result);
        cache?.flushMaybe();
        for (const finding of result) {
          const id = finding.techniqueId ?? defaultTechniqueId;
          const name = techniqueNameMap.get(finding.techniqueId ?? '') ?? defaultTechniqueName;
          findings.push(toScanFinding(finding, id, name));
        }
      } catch (error) {
        chunksFailed += 1;
        throw error;
      }
    }

    if (chunks.length > 0) {
      filesScanned.add(relativePath);
    }

    if (
      maybeReportProgress(
        progress,
        batchLabel,
        batchName,
        batchIndex,
        totalBatches,
        relativePath,
        null,
        processedFiles,
        totalFiles,
        chunksAnalyzed,
        chunksFailed,
        lastProgressAt
      )
    ) {
      lastProgressAt = processedFiles;
    }
  }

  return {
    findings,
    filesScanned: filesScanned.size,
    chunksAnalyzed,
    chunksFailed,
    gatingStats: null,
  };
}

function resolveFiles(
  repoPath: string,
  scope: ScanScope,
  filters: PathFilters,
  matchers: ReturnType<typeof createGlobMatchers>
): string[] {
  switch (scope.kind) {
    case 'full':
      return collectFiles(repoPath, filters, matchers);
    case 'file':
      return [scope.file];
    case 'selection':
      return [scope.file];
    case 'gitDiff':
      return Array.from(gitChangedFiles(repoPath, scope.baseRef, scope.includeUntracked));
    default:
      return collectFiles(repoPath, filters, matchers);
  }
}

function resolveLineRange(scope: ScanScope, totalLines: number): { start: number; end: number } {
  if (scope.kind === 'selection') {
    return {
      start: Math.max(1, scope.startLine),
      end: Math.min(totalLines, scope.endLine),
    };
  }
  return { start: 1, end: totalLines };
}

function buildMitigationPrompts(
  repository: SafeMcpRepository,
  technique: TechniqueSpec
): MitigationPrompt[] {
  return (technique.mitigations ?? []).map((mitigationId) => {
    const description =
      repository
        .readMitigationReadme(mitigationId)
        ?.split(/\r?\n/)
        .slice(1)
        .join('\n')
        .trim() || `See mitigation ${mitigationId}`;
    return { id: mitigationId, description };
  });
}

function buildFilters(config: ScannerConfig): PathFilters {
  return {
    includeExtensions: config.includeExtensions?.map((ext) => ext.toLowerCase()) ?? [],
    excludeExtensions: config.excludeExtensions?.map((ext) => ext.toLowerCase()) ?? [],
    includeGlobs: config.includeGlobs ?? [],
    excludeGlobs: config.excludeGlobs ?? [],
    maxFileBytes: config.maxFileBytes ?? ScannerConfigDefaults.DEFAULT_MAX_FILE_BYTES,
    excludeDocs: config.excludeDocs ?? true,
    excludeTests: config.excludeTests ?? true,
    includeOverride: config.includeOverride ?? [],
    excludePatterns: config.excludePatterns ?? [],
    onlyFiles: null,
  };
}

function maybeReportProgress(
  progress: ((progress: ScanProgress) => void) | undefined,
  techniqueId: string,
  techniqueName: string,
  techniqueIndex: number,
  totalTechniques: number,
  currentFile: string,
  currentChunkId: string | null,
  processedFiles: number,
  totalFiles: number,
  chunksAnalyzed: number,
  chunksFailed: number,
  lastReportedAt: number
): boolean {
  if (!progress || totalFiles === 0) return false;
  const shouldReport = processedFiles === totalFiles || processedFiles - lastReportedAt >= 5;
  if (!shouldReport) return false;
  progress({
    techniqueId,
    techniqueName,
    techniqueIndex,
    totalTechniques,
    currentFile,
    currentChunkId,
    processedFiles,
    totalFiles,
    chunksAnalyzed,
    chunksFailed,
    phase: 'scanning',
    chunkStatus: 'scanning',
  });
  return true;
}

function reportChunkStatus(
  progress: ((progress: ScanProgress) => void) | undefined,
  techniqueId: string,
  techniqueName: string,
  techniqueIndex: number,
  totalTechniques: number,
  currentFile: string,
  currentChunkId: string | null,
  processedFiles: number,
  totalFiles: number,
  chunksAnalyzed: number,
  chunksFailed: number,
  status: ChunkStatus
): void {
  if (!progress || totalFiles === 0) return;
  progress({
    techniqueId,
    techniqueName,
    techniqueIndex,
    totalTechniques,
    currentFile,
    currentChunkId,
    processedFiles,
    totalFiles,
    chunksAnalyzed,
    chunksFailed,
    phase: 'scanning',
    chunkStatus: status,
  });
}

function emptyGatingStats(): GatingStats {
  return {
    totalChunks: 0,
    gatedChunks: 0,
    llmChunks: 0,
    shadowChunks: 0,
    shadowFindings: 0,
  };
}

function safeRelative(repoPath: string, filePath: string): string {
  try {
    const rel = relative(repoPath, filePath);
    return rel || filePath;
  } catch {
    return filePath;
  }
}

function normalizeTechniqueId(id: string): string {
  return id.startsWith('SAFE-') ? id : `SAFE-${id}`;
}

function buildCacheKey(
  techniqueId: string,
  techniqueSignature: string,
  providerName: string,
  modelName: string,
  chunk: CodeChunk
): string {
  const input = [
    techniqueId,
    techniqueSignature,
    providerName,
    modelName,
    PROMPT_VERSION,
    chunk.file,
    chunk.startLine.toString(),
    chunk.endLine.toString(),
    chunk.code,
  ].join('|');
  return hashString(input);
}

function buildCacheKeyBatch(
  batchSignature: string,
  providerName: string,
  modelName: string,
  chunk: CodeChunk,
  techniqueIds: string[]
): string {
  const ids = [...techniqueIds].sort().join(',');
  const input = [
    batchSignature,
    providerName,
    modelName,
    PROMPT_VERSION,
    ids,
    chunk.file,
    chunk.startLine.toString(),
    chunk.endLine.toString(),
    chunk.code,
  ].join('|');
  return hashString(input);
}

function hashString(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

function truncateReadme(readme: string): string {
  const max = readmeMaxChars();
  if (readme.length <= max) return readme;
  return readme.slice(0, max);
}

function readmeMaxChars(): number {
  const raw = process.env.MAX_README_CHARS;
  const parsed = raw ? Number.parseInt(raw, 10) : NaN;
  if (!Number.isNaN(parsed)) {
    return Math.min(Math.max(parsed, 500), 100000);
  }
  return 8000;
}

function shouldScanFileForTechnique(relativePath: string, technique: TechniqueSpec): boolean {
  const name = relativePath.split('/').pop() ?? relativePath;
  if (name.toLowerCase() !== 'pyproject.toml') {
    return true;
  }
  return isTechniqueRelevantForPyproject(technique);
}

function isTechniqueRelevantForPyproject(technique: TechniqueSpec): boolean {
  const explicit = new Set(['SAFE-T1002', 'SAFE-T1004']);
  if (explicit.has(technique.id)) return true;
  const text = `${technique.name} ${technique.summary} ${technique.description}`.toLowerCase();
  const keywords = [
    'dependency',
    'dependencies',
    'package',
    'packaging',
    'version',
    'supply chain',
    'supply-chain',
    'registry',
    'typosquat',
    'typosquatting',
    'name-collision',
    'lockfile',
    'manifest',
    'pypi',
    'poetry',
    'python',
  ];
  return keywords.some((keyword) => text.includes(keyword));
}

function chunkArray<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    out.push(items.slice(i, i + size));
  }
  return out;
}
function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}
