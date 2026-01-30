import type { CodeModel } from './CodeModel.js';
import { CodeModelException } from './CodeModel.js';
import type { ModelFinding } from '../types/Finding.js';
import type { PromptPayload, BatchPromptPayload } from '../prompt/PromptModels.js';
import type { ScannerConfig } from '../scanner/ScannerConfig.js';
import {
  SYSTEM_PROMPT,
  BATCH_SYSTEM_PROMPT,
  buildUserPrompt,
  buildUserPromptBatch,
} from '../prompt/PromptBuilder.js';
import { parseFindings, parseFindingsBatch } from './ResponseParser.js';
import { withRetry, getRetryConfig } from './Retry.js';

const OPENAI_API_URL = 'https://api.openai.com/v1/chat/completions';

/**
 * OpenAI model implementation
 */
export class OpenAIModel implements CodeModel {
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
    const body = this.buildRequestBody(SYSTEM_PROMPT, userContent);

    const retryConfig = getRetryConfig('OPENAI', this.config);

    return withRetry(retryConfig, async () => {
      const response = await this.makeRequest(body);
      const content = this.extractContent(response);
      return parseFindings(content, prompt, this.name);
    });
  }

  async analyzeChunkBatch(prompt: BatchPromptPayload): Promise<ModelFinding[]> {
    const userContent = buildUserPromptBatch(prompt);
    const body = this.buildRequestBody(BATCH_SYSTEM_PROMPT, userContent);

    const retryConfig = getRetryConfig('OPENAI', this.config);

    return withRetry(retryConfig, async () => {
      const response = await this.makeRequest(body);
      const content = this.extractContent(response);
      return parseFindingsBatch(content, prompt, this.name);
    });
  }

  private buildRequestBody(
    systemPrompt: string,
    userContent: string
  ): Record<string, unknown> {
    const body: Record<string, unknown> = {
      model: this.name,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userContent },
      ],
    };

    // GPT-5 models use different parameter names
    const isGpt5 = this.name.toLowerCase().startsWith('gpt-5');
    const maxTokensKey = isGpt5 ? 'max_completion_tokens' : 'max_tokens';
    body[maxTokensKey] = 4096;

    // Some GPT-5 variants don't support temperature
    if (this.supportsTemperature()) {
      body['temperature'] = 0.0;
    }

    return body;
  }

  private supportsTemperature(): boolean {
    const lower = this.name.toLowerCase();
    return !lower.startsWith('gpt-5-mini') && !lower.startsWith('gpt-5-nano');
  }

  private async makeRequest(body: object): Promise<unknown> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const response = await fetch(OPENAI_API_URL, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${this.apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!response.ok) {
        const text = await response.text();
        const snippet = text.slice(0, 400);
        throw new CodeModelException(
          `OpenAI call failed with status ${response.status}: ${snippet}`
        );
      }

      return await response.json();
    } finally {
      clearTimeout(timeout);
    }
  }

  private extractContent(response: unknown): string {
    const data = response as {
      choices?: Array<{ message?: { content?: string } }>;
    };

    if (!Array.isArray(data.choices) || data.choices.length === 0) {
      throw new CodeModelException('OpenAI response missing choices');
    }

    const content = data.choices[0]?.message?.content;
    if (!content) {
      throw new CodeModelException('OpenAI response missing content');
    }

    return content;
  }
}
