import { readFileSync, existsSync } from 'node:fs';
import { parse as parseYaml } from 'yaml';

/**
 * Scanner configuration options
 */
export interface ScannerConfig {
  /** Allow remote LLM providers (openai, anthropic, gemini) */
  allowRemoteProviders: boolean;
  /** List of allowed provider names */
  allowedProviders?: string[];
  /** Preferred model names */
  modelNames?: string[];
  /** OpenAI API key */
  openaiApiKey?: string;
  /** Anthropic API key */
  anthropicApiKey?: string;
  /** Google Gemini API key */
  geminiApiKey?: string;
  /** Ollama endpoint URL */
  ollamaEndpoint?: string;
  /** Maximum retries for API calls */
  retryMaxRetries?: number;
  /** Delay between retries in ms */
  retryDelayMs?: number;
  /** Request timeout in ms */
  timeoutMs?: number;
  /** File extensions to include */
  includeExtensions?: string[];
  /** File extensions to exclude */
  excludeExtensions?: string[];
  /** Glob patterns to include */
  includeGlobs?: string[];
  /** Glob patterns to exclude */
  excludeGlobs?: string[];
  /** Maximum file size in bytes */
  maxFileBytes?: number;
  /** Patterns to exclude */
  excludePatterns?: string[];
  /** Patterns that override exclusions */
  includeOverride?: string[];
  /** Exclude documentation files */
  excludeDocs?: boolean;
  /** Exclude test files */
  excludeTests?: boolean;
  /** Enable LLM review of findings */
  llmReview?: boolean;
  /** Enable adaptive chunking based on token limits */
  adaptiveChunking?: boolean;
  /** Maximum prompt tokens */
  maxPromptTokens?: number;
  /** Reserve tokens for output */
  reserveOutputTokens?: number;
  /** Enable heuristic gating */
  gatingEnabled?: boolean;
  /** Shadow sample rate for gated chunks */
  shadowSampleRate?: number;
}

/**
 * Default configuration values
 */
export const ScannerConfigDefaults = {
  DEFAULT_MAX_FILE_BYTES: 5 * 1024 * 1024,
  DEFAULT_MAX_PROMPT_TOKENS: 6000,
  DEFAULT_RESERVE_OUTPUT_TOKENS: 1024,
  DEFAULT_OLLAMA_ENDPOINT: 'http://localhost:11434',
  GPT5_MAX_PROMPT_TOKENS: 200000,
  GPT5_RESERVE_OUTPUT_TOKENS: 8192,
  DEFAULT_RETRY_MAX_RETRIES: 3,
  DEFAULT_RETRY_DELAY_MS: 1000,
  DEFAULT_TIMEOUT_MS: 60000,
} as const;

/**
 * Load scanner configuration from a file path
 */
export function loadScannerConfig(path?: string): ScannerConfig {
  let base: ScannerConfig = { allowRemoteProviders: false };

  if (path && existsSync(path)) {
    const content = readFileSync(path, 'utf-8');
    if (path.endsWith('.json')) {
      base = JSON.parse(content) as ScannerConfig;
    } else {
      base = parseYaml(content) as ScannerConfig;
    }
  }

  return applyEnvOverrides(base);
}

/**
 * Apply environment variable overrides to configuration
 */
function applyEnvOverrides(config: ScannerConfig): ScannerConfig {
  const cfg = { ...config };

  cfg.allowRemoteProviders = envBool(
    'ALLOW_REMOTE_PROVIDERS',
    cfg.allowRemoteProviders
  );

  const allowedProviders = envList('ALLOWED_PROVIDERS');
  if (allowedProviders) cfg.allowedProviders = allowedProviders;

  const modelNames = envList('MODEL_NAMES');
  if (modelNames) cfg.modelNames = modelNames;

  const openaiKey = envString('OPENAI_API_KEY');
  if (openaiKey) cfg.openaiApiKey = openaiKey;

  const anthropicKey = envString('ANTHROPIC_API_KEY');
  if (anthropicKey) cfg.anthropicApiKey = anthropicKey;

  const geminiKey =
    envString('GEMINI_API_KEY') ??
    envString('GOOGLE_GENERATIVE_AI_API_KEY') ??
    envString('GOOGLE_API_KEY');
  if (geminiKey) cfg.geminiApiKey = geminiKey;

  const ollamaEndpoint = envString('OLLAMA_ENDPOINT');
  if (ollamaEndpoint) cfg.ollamaEndpoint = ollamaEndpoint;

  const retryMaxRetries = envInt('RETRY_MAX_RETRIES');
  if (retryMaxRetries !== undefined) cfg.retryMaxRetries = retryMaxRetries;

  const retryDelayMs = envInt('RETRY_DELAY_MS');
  if (retryDelayMs !== undefined) cfg.retryDelayMs = retryDelayMs;

  const timeoutMs = envInt('TIMEOUT_MS');
  if (timeoutMs !== undefined) cfg.timeoutMs = timeoutMs;

  const includeExtensions = envList('INCLUDE_EXTENSIONS');
  if (includeExtensions) cfg.includeExtensions = includeExtensions;

  const excludeExtensions = envList('EXCLUDE_EXTENSIONS');
  if (excludeExtensions) cfg.excludeExtensions = excludeExtensions;

  const includeGlobs = envList('INCLUDE_GLOBS');
  if (includeGlobs) cfg.includeGlobs = includeGlobs;

  const excludeGlobs = envList('EXCLUDE_GLOBS');
  if (excludeGlobs) cfg.excludeGlobs = excludeGlobs;

  const maxFileBytes = envInt('MAX_FILE_BYTES');
  if (maxFileBytes !== undefined) cfg.maxFileBytes = maxFileBytes;

  const excludePatterns = envList('SCANNER_EXCLUDE_PATTERNS');
  if (excludePatterns) cfg.excludePatterns = excludePatterns;

  const includeOverride = envList('SCANNER_INCLUDE_OVERRIDE');
  if (includeOverride) cfg.includeOverride = includeOverride;

  if ('SCANNER_EXCLUDE_DOCS' in process.env) {
    cfg.excludeDocs = envBool('SCANNER_EXCLUDE_DOCS', true);
  }

  if ('SCANNER_EXCLUDE_TESTS' in process.env) {
    cfg.excludeTests = envBool('SCANNER_EXCLUDE_TESTS', true);
  }

  if ('LLM_REVIEW' in process.env) {
    cfg.llmReview = envBool('LLM_REVIEW', false);
  }

  if ('ADAPTIVE_CHUNKING' in process.env) {
    cfg.adaptiveChunking = envBool('ADAPTIVE_CHUNKING', true);
  }

  const maxPromptTokens = envInt('MAX_PROMPT_TOKENS');
  if (maxPromptTokens !== undefined) cfg.maxPromptTokens = maxPromptTokens;

  const reserveOutputTokens = envInt('RESERVE_OUTPUT_TOKENS');
  if (reserveOutputTokens !== undefined)
    cfg.reserveOutputTokens = reserveOutputTokens;

  if ('GATING_ENABLED' in process.env) {
    cfg.gatingEnabled = envBool('GATING_ENABLED', true);
  }

  const shadowSampleRate = envFloat('SHADOW_SAMPLE_RATE');
  if (shadowSampleRate !== undefined) cfg.shadowSampleRate = shadowSampleRate;

  // Apply defaults
  cfg.maxFileBytes ??= ScannerConfigDefaults.DEFAULT_MAX_FILE_BYTES;
  cfg.adaptiveChunking ??= true;
  cfg.maxPromptTokens ??= ScannerConfigDefaults.DEFAULT_MAX_PROMPT_TOKENS;
  cfg.reserveOutputTokens ??=
    ScannerConfigDefaults.DEFAULT_RESERVE_OUTPUT_TOKENS;
  cfg.gatingEnabled ??= true;
  cfg.shadowSampleRate ??= 0.0;

  return cfg;
}

function envString(key: string): string | undefined {
  const value = process.env[key];
  return value && value.trim() ? value.trim() : undefined;
}

function envList(key: string): string[] | undefined {
  const raw = envString(key);
  if (!raw) return undefined;
  const list = raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s);
  return list.length > 0 ? list : undefined;
}

function envBool(key: string, defaultValue: boolean): boolean {
  const value = process.env[key]?.trim().toLowerCase();
  if (value === '1' || value === 'true' || value === 'yes') return true;
  if (value === '0' || value === 'false' || value === 'no') return false;
  return defaultValue;
}

function envInt(key: string): number | undefined {
  const value = process.env[key];
  if (!value) return undefined;
  const parsed = parseInt(value, 10);
  return isNaN(parsed) ? undefined : parsed;
}

function envFloat(key: string): number | undefined {
  const value = process.env[key];
  if (!value) return undefined;
  const parsed = parseFloat(value);
  return isNaN(parsed) ? undefined : parsed;
}
