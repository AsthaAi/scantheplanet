import type { ScanFinding } from './Finding.js';

/**
 * Scan status
 */
export type ScanStatus = 'pass' | 'fail' | 'unknown';

/**
 * Summary of a scan operation
 */
export interface ScanSummary {
  /** Overall scan status */
  status: ScanStatus;
  /** Human-readable summary */
  summary: string;
  /** List of findings */
  findings: ScanFinding[];
  /** Timestamp of scan (ISO 8601 UTC) */
  scannedAtUtc: string;
  /** Number of files scanned */
  filesScanned: number;
  /** Number of chunks analyzed */
  chunksAnalyzed: number;
  /** Number of chunks that failed analysis */
  chunksFailed: number;
  /** Models used during the scan */
  modelSupport: string[];
}

/**
 * Progress update during a scan
 */
export interface ScanProgress {
  /** Current technique ID */
  techniqueId: string;
  /** Current technique name */
  techniqueName: string;
  /** Current technique index (1-based) */
  techniqueIndex: number;
  /** Total number of techniques */
  totalTechniques: number;
  /** Current file being scanned */
  currentFile: string;
  /** Current chunk ID (if applicable) */
  currentChunkId: string | null;
  /** Number of files processed */
  processedFiles: number;
  /** Total number of files */
  totalFiles: number;
  /** Number of chunks analyzed */
  chunksAnalyzed: number;
  /** Number of chunks that failed */
  chunksFailed: number;
  /** Current scan phase */
  phase: ScanPhase;
  /** Current chunk status */
  chunkStatus: ChunkStatus;
}

/**
 * Scan phase
 */
export type ScanPhase = 'preparing' | 'scanning' | 'completing';

/**
 * Chunk analysis status
 */
export type ChunkStatus = 'scanning' | 'cached' | 'failed';

/**
 * Internal batch result
 */
export interface ScanBatch {
  findings: ScanFinding[];
  filesScanned: number;
  chunksAnalyzed: number;
  chunksFailed: number;
  gatingStats: GatingStats | null;
}

/**
 * Gating statistics for heuristic pre-filtering
 */
export interface GatingStats {
  totalChunks: number;
  gatedChunks: number;
  llmChunks: number;
  shadowChunks: number;
  shadowFindings: number;
}
