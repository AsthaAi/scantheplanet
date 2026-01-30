import type { ModelFinding } from '../types/Finding.js';
import type { PromptPayload, BatchPromptPayload } from '../prompt/PromptModels.js';

/**
 * Interface for code analysis models (LLM providers)
 */
export interface CodeModel {
  /** Model name/identifier */
  readonly name: string;

  /**
   * Analyze a code chunk for a single technique
   */
  analyzeChunk(prompt: PromptPayload): Promise<ModelFinding[]>;

  /**
   * Analyze a code chunk for multiple techniques (batch mode)
   */
  analyzeChunkBatch(prompt: BatchPromptPayload): Promise<ModelFinding[]>;
}

/**
 * Exception thrown by code models
 */
export class CodeModelException extends Error {
  constructor(
    message: string,
    public readonly cause?: Error
  ) {
    super(message);
    this.name = 'CodeModelException';
  }
}
