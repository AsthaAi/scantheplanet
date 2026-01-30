export type ScanScope =
  | { kind: 'full' }
  | { kind: 'file'; file: string }
  | { kind: 'selection'; file: string; startLine: number; endLine: number }
  | { kind: 'gitDiff'; baseRef: string; includeUntracked: boolean };

export interface PathFilters {
  includeExtensions: string[];
  excludeExtensions: string[];
  includeGlobs: string[];
  excludeGlobs: string[];
  maxFileBytes?: number;
  excludeDocs: boolean;
  excludeTests: boolean;
  includeOverride: string[];
  excludePatterns: string[];
  onlyFiles: Set<string> | null;
}
