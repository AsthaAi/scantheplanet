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

const GEMINI_API_URL =
  'https://generativelanguage.googleapis.com/v1beta/models';

/**
 * Google Gemini model implementation
 */
export class GeminiModel implements CodeModel {
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

    const retryConfig = getRetryConfig('GEMINI', this.config);

    return withRetry(retryConfig, async () => {
      const response = await this.makeRequest(body);
      const content = this.extractContent(response);
      return parseFindings(content, prompt, this.name);
    });
  }

  async analyzeChunkBatch(prompt: BatchPromptPayload): Promise<ModelFinding[]> {
    const userContent = buildUserPromptBatch(prompt);
    const body = this.buildRequestBody(BATCH_SYSTEM_PROMPT, userContent);

    const retryConfig = getRetryConfig('GEMINI', this.config);

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
      contents: [
        {
          role: 'user',
          parts: [{ text: `${systemPrompt}\n\n${userContent}` }],
        },
      ],
      generationConfig: {
        temperature: 0.0,
        maxOutputTokens: 4096,
        responseMimeType: 'application/json',
      },
    };
  }

  private async makeRequest(body: object): Promise<unknown> {
    const url = `${GEMINI_API_URL}/${this.name}:generateContent`;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-goog-api-key': this.apiKey,
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });

      if (!response.ok) {
        const text = await response.text();
        const snippet = text.slice(0, 400);
        throw new CodeModelException(
          `Gemini call failed with status ${response.status}: ${snippet}`
        );
      }

      return await response.json();
    } finally {
      clearTimeout(timeout);
    }
  }

  private extractContent(response: unknown): string {
    const data = response as {
      candidates?: Array<{
        content?: { parts?: Array<{ text?: string }> };
      }>;
    };

    if (!Array.isArray(data.candidates) || data.candidates.length === 0) {
      throw new CodeModelException('Gemini response missing candidates');
    }

    const text = data.candidates[0]?.content?.parts?.[0]?.text;
    if (!text) {
      throw new CodeModelException('Gemini response missing text');
    }

    return text;
  }
}
