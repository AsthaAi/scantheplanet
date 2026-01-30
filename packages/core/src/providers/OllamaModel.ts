import type { CodeModel } from './CodeModel.js';
import { CodeModelException } from './CodeModel.js';
import type { ModelFinding } from '../types/Finding.js';
import type { PromptPayload, BatchPromptPayload } from '../prompt/PromptModels.js';
import type { ScannerConfig } from '../scanner/ScannerConfig.js';
import { ScannerConfigDefaults } from '../scanner/ScannerConfig.js';
import {
  SYSTEM_PROMPT,
  BATCH_SYSTEM_PROMPT,
  buildUserPrompt,
  buildUserPromptBatch,
} from '../prompt/PromptBuilder.js';
import { parseFindings, parseFindingsBatch } from './ResponseParser.js';
import { withRetry, getRetryConfig } from './Retry.js';

/**
 * Ollama model implementation (self-hosted LLM)
 */
export class OllamaModel implements CodeModel {
  readonly name: string;
  private readonly endpoint: string;
  private readonly timeoutMs: number;

  constructor(
    modelName: string,
    private readonly config: ScannerConfig,
    endpoint?: string
  ) {
    this.name = modelName;
    this.endpoint =
      endpoint ??
      config.ollamaEndpoint ??
      ScannerConfigDefaults.DEFAULT_OLLAMA_ENDPOINT;
    this.timeoutMs = config.timeoutMs ?? 120000; // Longer timeout for local models
  }

  async analyzeChunk(prompt: PromptPayload): Promise<ModelFinding[]> {
    const userContent = buildUserPrompt(prompt);
    const body = this.buildRequestBody(SYSTEM_PROMPT, userContent);

    const retryConfig = getRetryConfig('OLLAMA', this.config);

    return withRetry(retryConfig, async () => {
      const response = await this.makeRequest(body);
      const content = this.extractContent(response);
      return parseFindings(content, prompt, this.name);
    });
  }

  async analyzeChunkBatch(prompt: BatchPromptPayload): Promise<ModelFinding[]> {
    const userContent = buildUserPromptBatch(prompt);
    const body = this.buildRequestBody(BATCH_SYSTEM_PROMPT, userContent);

    const retryConfig = getRetryConfig('OLLAMA', this.config);

    return withRetry(retryConfig, async () => {
      const response = await this.makeRequest(body);
      const content = this.extractContent(response);
      return parseFindingsBatch(content, prompt, this.name);
    });
  }

  private buildRequestBody(
    systemPrompt: string,
    userContent: string
  ): object {
    return {
      model: this.name,
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userContent },
      ],
      format: 'json',
      stream: false,
      options: {
        temperature: 0.0,
        num_predict: 4096,
      },
    };
  }

  private async makeRequest(body: object): Promise<unknown> {
    const url = `${this.endpoint}/api/chat`;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!response.ok) {
        const text = await response.text();
        const snippet = text.slice(0, 400);
        throw new CodeModelException(
          `Ollama call failed with status ${response.status}: ${snippet}`
        );
      }

      return await response.json();
    } finally {
      clearTimeout(timeout);
    }
  }

  private extractContent(response: unknown): string {
    const data = response as {
      message?: { content?: string };
    };

    const content = data.message?.content;
    if (!content) {
      throw new CodeModelException('Ollama response missing content');
    }

    return content;
  }
}
