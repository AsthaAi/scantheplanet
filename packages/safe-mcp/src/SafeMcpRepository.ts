import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse as parseYaml } from 'yaml';
import type {
  TechniqueSpec,
  PatternLibrary,
  Heuristic,
} from './types.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

/**
 * Repository for loading SAFE-MCP technique specifications
 */
export class SafeMcpRepository {
  private readonly basePath: string;
  private techniqueCache = new Map<string, TechniqueSpec>();
  private patternLibrary: PatternLibrary | null = null;

  /**
   * Create a new SafeMcpRepository
   * @param basePath Base path to the safe-mcp content. Defaults to the package's bundled content.
   */
  constructor(basePath?: string) {
    // Default to the package's bundled techniques directory
    this.basePath = basePath ?? join(__dirname, '..');
  }

  /**
   * Read a file from the repository
   */
  private readFile(relativePath: string): string | null {
    const fullPath = join(this.basePath, relativePath);
    if (!existsSync(fullPath)) {
      return null;
    }
    return readFileSync(fullPath, 'utf-8');
  }

  /**
   * List all available technique IDs
   */
  listTechniqueIds(): string[] {
    const prioritizedPath = join(
      this.basePath,
      'techniques',
      'prioritized-techniques.md'
    );

    if (existsSync(prioritizedPath)) {
      const content = readFileSync(prioritizedPath, 'utf-8');
      const ids = content
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#'))
        .map((id) => (id.startsWith('SAFE-') ? id : `SAFE-${id}`))
        .filter((value, index, self) => self.indexOf(value) === index)
        .sort();

      if (ids.length > 0) {
        return ids;
      }
    }

    // Fallback: scan the techniques directory
    const techniquesPath = join(this.basePath, 'techniques');
    if (!existsSync(techniquesPath)) {
      return ['SAFE-T0001'];
    }

    const entries = readdirSync(techniquesPath, { withFileTypes: true });
    const ids: string[] = [];

    for (const entry of entries) {
      if (entry.isDirectory() && entry.name.startsWith('SAFE-T')) {
        ids.push(entry.name);
      } else if (entry.isFile() && entry.name.match(/^SAFE-T\d+\.yaml$/)) {
        ids.push(entry.name.replace('.yaml', ''));
      }
    }

    return ids.length > 0 ? ids.sort() : ['SAFE-T0001'];
  }

  /**
   * Normalize a technique ID to include SAFE- prefix
   */
  private normalizeTechniqueId(id: string): string {
    return id.startsWith('SAFE-') ? id : `SAFE-${id}`;
  }

  /**
   * Read the raw YAML content for a technique
   */
  readTechniqueSpec(id: string): string | null {
    const normalized = this.normalizeTechniqueId(id);
    const fileName = normalized.replace('SAFE-', '');

    // Try various file locations
    const paths = [
      `techniques/${normalized}/technique.yaml`,
      `techniques/${normalized}/technique.yml`,
      `techniques/${normalized}.yaml`,
      `techniques/${fileName}.yaml`,
      `techniques/${normalized}.json`,
      `techniques/${fileName}.json`,
    ];

    for (const path of paths) {
      const content = this.readFile(path);
      if (content) {
        return content;
      }
    }

    return null;
  }

  /**
   * Load and parse a technique specification
   */
  loadTechnique(id: string): TechniqueSpec | null {
    const normalized = this.normalizeTechniqueId(id);

    // Check cache first
    const cached = this.techniqueCache.get(normalized);
    if (cached) {
      return cached;
    }

    const raw = this.readTechniqueSpec(normalized);
    if (!raw) {
      return null;
    }

    const technique = parseYaml(raw) as TechniqueSpec;

    // Resolve pattern references
    const resolvedTechnique = this.resolvePatternRefs(technique);

    this.techniqueCache.set(normalized, resolvedTechnique);
    return resolvedTechnique;
  }

  /**
   * Load all techniques
   */
  loadAllTechniques(): TechniqueSpec[] {
    const ids = this.listTechniqueIds();
    const techniques: TechniqueSpec[] = [];

    for (const id of ids) {
      const technique = this.loadTechnique(id);
      if (technique) {
        techniques.push(technique);
      }
    }

    return techniques;
  }

  /**
   * Read the README for a technique
   */
  readTechniqueReadme(id: string): string | null {
    const normalized = this.normalizeTechniqueId(id);
    return (
      this.readFile(`techniques/${normalized}/README.md`) ??
      this.readFile(`techniques/${normalized}/README.txt`)
    );
  }

  /**
   * Read the README for a mitigation
   */
  readMitigationReadme(id: string): string | null {
    const normalized = this.normalizeTechniqueId(id);
    return this.readFile(`mitigations/${normalized}/README.md`);
  }

  /**
   * Load the pattern library from common.yaml
   */
  loadPatternLibrary(): PatternLibrary {
    if (this.patternLibrary) {
      return this.patternLibrary;
    }

    const content = this.readFile('patterns/common.yaml');
    if (!content) {
      this.patternLibrary = { patterns: [] };
      return this.patternLibrary;
    }

    this.patternLibrary = parseYaml(content) as PatternLibrary;
    return this.patternLibrary;
  }

  /**
   * Resolve pattern references in a technique's code signals
   */
  private resolvePatternRefs(technique: TechniqueSpec): TechniqueSpec {
    const library = this.loadPatternLibrary();
    const patternMap = new Map<string, Heuristic[]>();

    for (const pattern of library.patterns) {
      patternMap.set(pattern.id, pattern.heuristics);
    }

    const resolvedSignals = technique.code_signals.map((signal) => {
      const resolvedHeuristics = signal.heuristics.flatMap((heuristic) => {
        if (heuristic.pattern_ref) {
          const resolved = patternMap.get(heuristic.pattern_ref);
          return resolved ?? [];
        }
        return [heuristic];
      });

      return {
        ...signal,
        heuristics: resolvedHeuristics,
      };
    });

    return {
      ...technique,
      code_signals: resolvedSignals,
    };
  }

  /**
   * Resolve a specific pattern reference
   */
  resolvePattern(patternRef: string): Heuristic[] | null {
    const library = this.loadPatternLibrary();
    const pattern = library.patterns.find((p) => p.id === patternRef);
    return pattern?.heuristics ?? null;
  }

  /**
   * Clear all caches
   */
  clearCache(): void {
    this.techniqueCache.clear();
    this.patternLibrary = null;
  }
}
