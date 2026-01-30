/**
 * @scantheplanet/core
 *
 * Core scanner engine for Scan The Planet
 */

// Types
export type {
  ScanFinding,
  ModelFinding,
  ScanStatus,
  ScanSummary,
  ScanProgress,
  ScanPhase,
  ChunkStatus,
  ScanBatch,
  GatingStats,
} from './types/index.js';
export { toScanFinding } from './types/index.js';

// Scanner configuration
export type { ScannerConfig } from './scanner/index.js';
export {
  ScannerConfigDefaults,
  loadScannerConfig,
  Scanner,
  ScanCache,
} from './scanner/index.js';
export type { ScanScope, PathFilters, CodeChunk, RuleHint, ChunkKind } from './scanner/index.js';

// Prompt building
export type {
  MitigationPrompt,
  CodeChunkPrompt,
  TechniquePrompt,
  PromptPayload,
  BatchPromptPayload,
} from './prompt/index.js';
export {
  PROMPT_VERSION,
  SYSTEM_PROMPT,
  BATCH_SYSTEM_PROMPT,
  JSON_PREFILL,
  buildUserPrompt,
  buildUserPromptBatch,
  fileExtension,
} from './prompt/index.js';

// Providers
export type { CodeModel, RetryConfig } from './providers/index.js';
export {
  CodeModelException,
  LocalModel,
  AnthropicModel,
  OpenAIModel,
  GeminiModel,
  OllamaModel,
  buildModel,
  getDefaultModel,
  withRetry,
  getRetryConfig,
} from './providers/index.js';

// Re-export safe-mcp types for convenience
export type {
  TechniqueSpec,
  CodeSignal,
  Heuristic,
  HeuristicClause,
  OutputSchema,
  Severity,
  PatternLibrary,
  PatternEntry,
  MitigationSpec,
} from '@scantheplanet/safe-mcp';
export { SafeMcpRepository } from '@scantheplanet/safe-mcp';
