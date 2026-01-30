/**
 * SAFE-MCP Technique and related type definitions
 */

/**
 * Severity level for a technique
 */
export type Severity = 'critical' | 'high' | 'medium' | 'low';

/**
 * A heuristic clause used in all_of conditions
 */
export interface HeuristicClause {
  pattern?: string;
  regex?: string;
}

/**
 * A heuristic for detecting code signals
 */
export interface Heuristic {
  /** Glob-style pattern for matching */
  pattern?: string;
  /** Regular expression for matching */
  regex?: string;
  /** Regex flags (e.g., 'i' for case-insensitive) */
  flags?: string;
  /** All conditions must match (AND logic) */
  all_of?: HeuristicClause[];
  /** Window size in lines for multi-line patterns */
  window?: number;
  /** Reference to a pattern in common.yaml */
  pattern_ref?: string;
  /** Minimum entropy threshold for secret detection */
  entropy_min?: number;
  /** Minimum length for secret detection */
  min_length?: number;
}

/**
 * A code signal that may indicate a vulnerability
 */
export interface CodeSignal {
  id: string;
  description: string;
  heuristics: Heuristic[];
}

/**
 * Output schema configuration for a technique
 */
export interface OutputSchema {
  /** Whether findings must include mitigations */
  requires_mitigations: boolean;
  /** Allowed values for finding status */
  allowed_status_values: string[];
}

/**
 * A SAFE-MCP technique specification
 */
export interface TechniqueSpec {
  /** Unique identifier (e.g., SAFE-T1301) */
  id: string;
  /** Human-readable name */
  name: string;
  /** Severity level */
  severity: Severity | string;
  /** Brief summary */
  summary: string;
  /** Detailed description */
  description: string;
  /** List of mitigation IDs */
  mitigations: string[];
  /** Code signals for heuristic detection */
  code_signals: CodeSignal[];
  /** Languages this technique applies to */
  languages: string[];
  /** Output schema configuration */
  output_schema: OutputSchema;
  /** Whether LLM analysis is required (not just heuristics) */
  llm_required?: boolean;
}

/**
 * Pattern library entry for shared patterns
 */
export interface PatternEntry {
  id: string;
  heuristics: Heuristic[];
}

/**
 * Pattern library loaded from common.yaml
 */
export interface PatternLibrary {
  patterns: PatternEntry[];
}

/**
 * Mitigation specification
 */
export interface MitigationSpec {
  id: string;
  description: string;
  readme?: string;
}
