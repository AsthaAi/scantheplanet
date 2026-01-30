import type { CodeModel } from './CodeModel.js';
import type { ScannerConfig } from '../scanner/ScannerConfig.js';
import { ScannerConfigDefaults } from '../scanner/ScannerConfig.js';
import { LocalModel } from './LocalModel.js';
import { AnthropicModel } from './AnthropicModel.js';
import { OpenAIModel } from './OpenAIModel.js';
import { GeminiModel } from './GeminiModel.js';
import { OllamaModel } from './OllamaModel.js';

/**
 * Default model names for each provider
 */
const DEFAULT_MODELS: Record<string, string> = {
  openai: 'gpt-4o-mini',
  anthropic: 'claude-3-5-sonnet-20240620',
  gemini: 'gemini-1.5-flash',
  ollama: 'llama3.1',
  local: 'local',
};

/**
 * Supported remote providers
 */
const REMOTE_PROVIDERS = ['openai', 'anthropic', 'gemini'];

/**
 * Build a code model for the specified provider
 */
export function buildModel(
  provider: string,
  modelName: string | undefined,
  apiKey: string | undefined,
  config: ScannerConfig
): CodeModel {
  const normalized = provider.toLowerCase();

  // Local model doesn't need API key
  if (normalized === 'local') {
    return new LocalModel();
  }

  // Ollama is self-hosted, doesn't need API key
  if (normalized === 'ollama') {
    enforceProviderAllowlist(config, normalized);
    const resolvedModel =
      modelName ?? config.modelNames?.[0] ?? DEFAULT_MODELS[normalized]!;
    const endpoint =
      config.ollamaEndpoint ?? ScannerConfigDefaults.DEFAULT_OLLAMA_ENDPOINT;
    return new OllamaModel(resolvedModel, config, endpoint);
  }

  // Remote providers require API key
  if (!REMOTE_PROVIDERS.includes(normalized)) {
    throw new Error(`Unsupported provider: ${normalized}`);
  }

  enforceProviderAllowlist(config, normalized);

  const key = apiKey ?? getApiKeyFromConfig(normalized, config);
  if (!key) {
    throw new Error(`API key missing for provider ${normalized}`);
  }

  const resolvedModel =
    modelName ?? config.modelNames?.[0] ?? DEFAULT_MODELS[normalized]!;

  switch (normalized) {
    case 'openai':
      return new OpenAIModel(resolvedModel, key, config);
    case 'anthropic':
      return new AnthropicModel(resolvedModel, key, config);
    case 'gemini':
      return new GeminiModel(resolvedModel, key, config);
    default:
      return new LocalModel();
  }
}

/**
 * Get API key from config or environment for a provider
 */
function getApiKeyFromConfig(
  provider: string,
  config: ScannerConfig
): string | undefined {
  switch (provider) {
    case 'openai':
      return config.openaiApiKey;
    case 'anthropic':
      return config.anthropicApiKey;
    case 'gemini':
      return config.geminiApiKey;
    default:
      return undefined;
  }
}

/**
 * Enforce provider allowlist configuration
 */
function enforceProviderAllowlist(
  config: ScannerConfig,
  provider: string
): void {
  // Local and Ollama don't require remote provider access
  if (provider === 'local' || provider === 'ollama') {
    return;
  }

  if (!config.allowRemoteProviders) {
    throw new Error(
      `Provider ${provider} disallowed: allow_remote_providers=false`
    );
  }

  if (config.allowedProviders) {
    const allowed = config.allowedProviders.map((p) => p.toLowerCase());
    if (!allowed.includes(provider)) {
      throw new Error(`Provider ${provider} not in allowed_providers`);
    }
  }
}

/**
 * Get the default model name for a provider
 */
export function getDefaultModel(provider: string): string {
  return DEFAULT_MODELS[provider.toLowerCase()] ?? 'local';
}
