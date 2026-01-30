import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import type { ModelFinding } from '../types/Finding.js';

interface CacheState {
  entries: Record<string, CacheEntry>;
}

interface CacheEntry {
  cachedAtUtc: string;
  findings: ModelFindingCache[];
}

interface ModelFindingCache {
  techniqueId?: string;
  chunkId: string;
  file: string;
  startLine: number;
  endLine: number;
  severity: string;
  confidence: number | null;
  observation: string;
  evidence: string;
  reasoning: string | null;
  unknownMitigations: string[];
  modelName: string;
}

export class ScanCache {
  private state: CacheState;
  private pendingWrites = 0;
  private lastFlushMs = Date.now();

  constructor(private readonly cacheFile: string) {
    this.state = this.load();
  }

  get(key: string): ModelFinding[] | null {
    const entry = this.state.entries[key];
    if (!entry) return null;
    return entry.findings.map((finding) => toModelFinding(finding));
  }

  put(key: string, findings: ModelFinding[]): void {
    this.state.entries[key] = {
      cachedAtUtc: new Date().toISOString(),
      findings: findings.map((finding) => fromModelFinding(finding)),
    };
    this.pendingWrites += 1;
  }

  flush(): void {
    this.flushLocked();
  }

  flushMaybe(): void {
    if (this.pendingWrites === 0) return;
    const now = Date.now();
    const shouldFlush =
      this.pendingWrites >= 20 || now - this.lastFlushMs >= 5000;
    if (shouldFlush) {
      this.flushLocked();
    }
  }

  private flushLocked(): void {
    try {
      mkdirSync(dirname(this.cacheFile), { recursive: true });
      writeFileSync(this.cacheFile, JSON.stringify(this.state), 'utf8');
      this.pendingWrites = 0;
      this.lastFlushMs = Date.now();
    } catch {
      // best-effort cache
    }
  }

  private load(): CacheState {
    try {
      if (!existsSync(this.cacheFile)) {
        return { entries: {} };
      }
      const raw = readFileSync(this.cacheFile, 'utf8');
      const parsed = JSON.parse(raw) as CacheState;
      if (!parsed.entries) {
        return { entries: {} };
      }
      return parsed;
    } catch {
      return { entries: {} };
    }
  }
}

function toModelFinding(cache: ModelFindingCache): ModelFinding {
  return {
    techniqueId: cache.techniqueId,
    chunkId: cache.chunkId,
    file: cache.file,
    startLine: cache.startLine,
    endLine: cache.endLine,
    severity: cache.severity,
    confidence: cache.confidence,
    observation: cache.observation,
    evidence: cache.evidence,
    reasoning: cache.reasoning,
    unknownMitigations: cache.unknownMitigations,
    modelName: cache.modelName,
  };
}

function fromModelFinding(finding: ModelFinding): ModelFindingCache {
  return {
    techniqueId: finding.techniqueId,
    chunkId: finding.chunkId,
    file: finding.file,
    startLine: finding.startLine,
    endLine: finding.endLine,
    severity: finding.severity,
    confidence: finding.confidence,
    observation: finding.observation,
    evidence: finding.evidence,
    reasoning: finding.reasoning,
    unknownMitigations: finding.unknownMitigations,
    modelName: finding.modelName,
  };
}
