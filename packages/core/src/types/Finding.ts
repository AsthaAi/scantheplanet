/**
 * A security finding from the scanner
 */
export interface ScanFinding {
  /** Technique ID (e.g., SAFE-T1301) */
  techniqueId: string;
  /** Human-readable technique name */
  techniqueName: string;
  /** Severity level */
  severity: string;
  /** File path where the finding was detected */
  file: string;
  /** Starting line number (1-indexed) */
  startLine: number;
  /** Ending line number (1-indexed) */
  endLine: number;
  /** Description of the finding */
  observation: string;
}

/**
 * A finding returned by the model before mapping to ScanFinding
 */
export interface ModelFinding {
  /** Technique ID (for batch mode) */
  techniqueId?: string;
  /** Chunk ID where the finding was detected */
  chunkId: string;
  /** File path */
  file: string;
  /** Starting line number */
  startLine: number;
  /** Ending line number */
  endLine: number;
  /** Severity level */
  severity: string;
  /** Confidence score (0-1) */
  confidence: number | null;
  /** Description of the finding */
  observation: string;
  /** Evidence string with code snippet */
  evidence: string;
  /** Reasoning for the finding */
  reasoning: string | null;
  /** Unknown mitigations mentioned */
  unknownMitigations: string[];
  /** Model name that produced the finding */
  modelName: string;
}

/**
 * Convert a ModelFinding to a ScanFinding
 */
export function toScanFinding(
  finding: ModelFinding,
  techniqueId: string,
  techniqueName: string
): ScanFinding {
  const cleanedName = cleanTechniqueName(techniqueId, techniqueName);
  return {
    techniqueId: finding.techniqueId ?? techniqueId,
    techniqueName: cleanedName,
    severity: finding.severity,
    file: finding.file,
    startLine: finding.startLine,
    endLine: finding.endLine,
    observation: finding.observation,
  };
}

/**
 * Clean technique name by removing redundant ID prefix
 */
function cleanTechniqueName(techniqueId: string, name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return '';
  if (trimmed.toLowerCase() === techniqueId.toLowerCase()) return '';
  const prefix = /^SAFE-T\d+\s*[:\-]\s*/i;
  return trimmed.replace(prefix, '').trim();
}
