import type { ScannerConfig } from '../scanner/ScannerConfig.js';

/**
 * Retry configuration
 */
export interface RetryConfig {
  maxRetries: number;
  delayMs: number;
}

/**
 * Get retry config from environment or config
 */
export function getRetryConfig(
  provider: string,
  config: ScannerConfig
): RetryConfig {
  const prefix = provider.toUpperCase();

  const maxRetries =
    envInt(`${prefix}_RETRY_MAX_RETRIES`) ??
    envInt('RETRY_MAX_RETRIES') ??
    config.retryMaxRetries ??
    3;

  const delayMs =
    envInt(`${prefix}_RETRY_DELAY_MS`) ??
    envInt('RETRY_DELAY_MS') ??
    config.retryDelayMs ??
    1000;

  return { maxRetries, delayMs };
}

function envInt(key: string): number | undefined {
  const value = process.env[key];
  if (!value) return undefined;
  const parsed = parseInt(value, 10);
  return isNaN(parsed) ? undefined : parsed;
}

/**
 * Execute a function with retry logic
 */
export async function withRetry<T>(
  config: RetryConfig,
  fn: () => Promise<T>,
  onRetry?: (attempt: number, error: Error) => void
): Promise<T> {
  let lastError: Error | undefined;
  const maxRetries = Math.max(0, config.maxRetries);

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error instanceof Error ? error : new Error(String(error));

      if (attempt < maxRetries) {
        onRetry?.(attempt + 1, lastError);
        await sleep(config.delayMs * Math.pow(2, attempt));
      }
    }
  }

  throw lastError;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
