/**
 * A mitigation prompt entry
 */
export interface MitigationPrompt {
  id: string;
  description: string;
}

/**
 * A rule hint from heuristic matching
 */
export interface RuleHint {
  signalId: string;
  line: number;
  snippet: string;
}

/**
 * A code chunk for analysis
 */
export interface CodeChunkPrompt {
  id: string;
  file: string;
  startLine: number;
  endLine: number;
  code: string;
  ruleHints: RuleHint[];
}

/**
 * A technique prompt for batch mode
 */
export interface TechniquePrompt {
  id: string;
  name: string;
  severity: string;
  summary: string;
  description: string;
  mitigations: MitigationPrompt[];
  ruleHints: RuleHint[];
}

/**
 * Single technique prompt payload
 */
export interface PromptPayload {
  techniqueId: string;
  techniqueName: string;
  severity: string;
  summary: string;
  description: string;
  mitigations: MitigationPrompt[];
  codeChunk: CodeChunkPrompt;
  readmeExcerpt?: string;
}

/**
 * Batch prompt payload for multiple techniques
 */
export interface BatchPromptPayload {
  techniques: TechniquePrompt[];
  codeChunk: CodeChunkPrompt;
  readmeExcerpt?: string;
}
