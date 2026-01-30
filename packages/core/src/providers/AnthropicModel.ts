import type { CodeModel } from './CodeModel.js';
import { CodeModelException } from './CodeModel.js';
import type { ModelFinding } from '../types/Finding.js';
import type { PromptPayload, BatchPromptPayload } from '../prompt/PromptModels.js';
import type { ScannerConfig } from '../scanner/ScannerConfig.js';
import {
  SYSTEM_PROMPT,
  BATCH_SYSTEM_PROMPT,
  JSON_PREFILL,
  buildUserPrompt,
  buildUserPromptBatch,
} from '../prompt/PromptBuilder.js';
import {
  parseFindingsWithPrefill,
  parseFindingsBatchWithPrefill,
} from './ResponseParser.js';
import { withRetry, getRetryConfig } from './Retry.js';

const ANTHROPIC_API_URL = 'https://api.anthropic.com/v1/messages';
const ANTHROPIC_VERSION = '2023-06-01';

/**
 * Anthropic Claude model implementation
 */
export class AnthropicModel implements CodeModel {
  readonly name: string;
  private readonly timeoutMs: number;

  constructor(
    modelName: string,
    private readonly apiKey: string,
    private readonly config: ScannerConfig
  ) {
    this.name = modelName;
    this.timeoutMs = config.timeoutMs ?? 60000;
  }

  async analyzeChunk(prompt: PromptPayload): Promise<ModelFinding[]> {
    const userContent = buildUserPrompt(prompt);
    const body = {
      model: this.name,
      temperature: 0.0,
      max_tokens: 4096,
      system: SYSTEM_PROMPT,
      messages: [
        { role: 'user', content: userContent },
        { role: 'assistant', content: JSON_PREFILL },
      ],
    };

    const retryConfig = getRetryConfig('ANTHROPIC', this.config);

    return withRetry(retryConfig, async () => {
      const response = await this.makeRequest(body);
      const content = this.extractContent(response);
      return parseFindingsWithPrefill(content, JSON_PREFILL, prompt, this.name);
    });
  }

  async analyzeChunkBatch(prompt: BatchPromptPayload): Promise<ModelFinding[]> {
    const userContent = buildUserPromptBatch(prompt);
    const body = {
      model: this.name,
      temperature: 0.0,
      max_tokens: 4096,
      system: BATCH_SYSTEM_PROMPT,
      messages: [
        { role: 'user', content: userContent },
        { role: 'assistant', content: JSON_PREFILL },
      ],
    };

    const retryConfig = getRetryConfig('ANTHROPIC', this.config);

    return withRetry(retryConfig, async () => {
      const response = await this.makeRequest(body);
      const content = this.extractContent(response);
      return parseFindingsBatchWithPrefill(
        content,
        JSON_PREFILL,
        prompt,
        this.name
      );
    });
  }

  private async makeRequest(body: object): Promise<unknown> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const response = await fetch(ANTHROPIC_API_URL, {
        method: 'POST',
        headers: {
          'x-api-key': this.apiKey,
          'anthropic-version': ANTHROPIC_VERSION,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!response.ok) {
        const text = await response.text();
        const snippet = text.slice(0, 400);
        throw new CodeModelException(
          `Anthropic call failed with status ${response.status}: ${snippet}`
        );
      }

      return await response.json();
    } finally {
      clearTimeout(timeout);
    }
  }

  private extractContent(response: unknown): string {
    const data = response as {
      content?: Array<{ text?: string }>;
    };

    if (!Array.isArray(data.content) || data.content.length === 0) {
      throw new CodeModelException('Anthropic response missing content');
    }

    const text = data.content[0]?.text;
    if (!text) {
      throw new CodeModelException('Anthropic response missing text');
    }

    return text;
  }
}
