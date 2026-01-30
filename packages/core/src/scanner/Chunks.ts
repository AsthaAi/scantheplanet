export type ChunkKind = 'slidingWindow' | 'wholeFile' | 'selection';

export interface RuleHint {
  signalId: string;
  line: number;
  snippet: string;
}

export interface CodeChunk {
  id: string;
  file: string;
  startLine: number;
  endLine: number;
  kind: ChunkKind;
  code: string;
  ruleHints: RuleHint[];
}
