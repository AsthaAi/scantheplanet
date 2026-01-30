import type { TechniqueSpec, Heuristic } from '@scantheplanet/safe-mcp';
import type { CodeChunk, RuleHint } from './Chunks.js';

export function applyRuleHints(
  technique: TechniqueSpec,
  chunks: CodeChunk[]
): void {
  for (const chunk of chunks) {
    chunk.ruleHints = computeHints(technique, chunk);
  }
}

export function computeHints(
  technique: TechniqueSpec,
  chunk: CodeChunk
): RuleHint[] {
  const hints: RuleHint[] = [];
  const hintKeys = new Set<string>();
  const lines = chunk.code.split(/\r?\n/);
  if (lines.length === 0) return [];

  for (const signal of technique.code_signals ?? []) {
    for (const heuristic of signal.heuristics ?? []) {
      const flags = heuristic.flags ?? '';

      if (heuristic.entropy_min != null) {
        const minLen = Math.max(heuristic.min_length ?? 20, 8);
        for (let idx = 0; idx < lines.length; idx += 1) {
          const line = lines[idx];
          if (line.length < minLen) continue;
          const score = shannonEntropy(line);
          if (score >= heuristic.entropy_min) {
            const lineNo = chunk.startLine + idx;
            const key = `${signal.id}:${lineNo}`;
            if (!hintKeys.has(key)) {
              hintKeys.add(key);
              hints.push({ signalId: signal.id, line: lineNo, snippet: line });
            }
          }
        }
      }

      const matchers: HeuristicMatcher[] = [];
      if (heuristic.all_of && heuristic.all_of.length > 0) {
        for (const clause of heuristic.all_of) {
          const matcher = buildMatcher(clause.pattern, clause.regex, flags);
          if (matcher) matchers.push(matcher);
        }
      } else {
        const matcher = buildMatcher(heuristic.pattern, heuristic.regex, flags);
        if (matcher) matchers.push(matcher);
      }

      if (matchers.length === 0) continue;

      if (matchers.length === 1) {
        for (let idx = 0; idx < lines.length; idx += 1) {
          const line = lines[idx];
          if (matchers[0].matches(line)) {
            const lineNo = chunk.startLine + idx;
            const key = `${signal.id}:${lineNo}`;
            if (!hintKeys.has(key)) {
              hintKeys.add(key);
              hints.push({ signalId: signal.id, line: lineNo, snippet: line });
            }
          }
        }
        continue;
      }

      const window = heuristic.window ?? 0;
      const matchesPerClause: number[][] = [];
      for (const matcher of matchers) {
        const matches: number[] = [];
        for (let idx = 0; idx < lines.length; idx += 1) {
          if (matcher.matches(lines[idx])) {
            matches.push(idx);
          }
        }
        if (matches.length === 0) {
          matchesPerClause.length = 0;
          break;
        }
        matchesPerClause.push(matches);
      }
      if (matchesPerClause.length === 0) continue;

      for (const idx of matchesPerClause[0]) {
        const start = Math.max(0, idx - window);
        const end = Math.min(lines.length - 1, idx + window);
        const allWithin = matchesPerClause.slice(1).every((candidates) =>
          candidates.some((candidate) => candidate >= start && candidate <= end)
        );
        if (allWithin) {
          const lineNo = chunk.startLine + idx;
          const key = `${signal.id}:${lineNo}`;
          if (!hintKeys.has(key)) {
            hintKeys.add(key);
            hints.push({ signalId: signal.id, line: lineNo, snippet: lines[idx] });
          }
        }
      }
    }
  }

  return hints;
}

function shannonEntropy(input: string): number {
  if (!input) return 0;
  const counts = new Map<string, number>();
  let total = 0;
  for (const ch of input) {
    if (ch.trim() === '') continue;
    counts.set(ch, (counts.get(ch) ?? 0) + 1);
    total += 1;
  }
  if (total === 0) return 0;
  let entropy = 0;
  for (const count of counts.values()) {
    const p = count / total;
    entropy -= p * Math.log2(p);
  }
  return entropy;
}

function buildMatcher(
  pattern: string | undefined,
  regex: string | undefined,
  flags: string
): HeuristicMatcher | null {
  const caseInsensitive = flags.includes('i');
  if (pattern && pattern.trim()) {
    if (pattern.includes('*') || pattern.includes('?') || caseInsensitive) {
      const regexPattern = pattern.includes('*') || pattern.includes('?')
        ? globToRegex(pattern)
        : escapeRegex(pattern);
      return buildRegexMatcher(regexPattern, caseInsensitive);
    }
    return new SubstringMatcher(pattern);
  }
  if (!regex || !regex.trim()) return null;
  return buildRegexMatcher(regex, caseInsensitive);
}

function buildRegexMatcher(pattern: string, caseInsensitive: boolean): HeuristicMatcher | null {
  try {
    const compiled = new RegExp(pattern, caseInsensitive ? 'i' : undefined);
    return new RegexMatcher(compiled);
  } catch {
    return null;
  }
}

function globToRegex(pattern: string): string {
  let out = '';
  for (let i = 0; i < pattern.length; i += 1) {
    const ch = pattern[i];
    if (ch === '*') {
      out += '.*';
      continue;
    }
    if (ch === '?') {
      out += '.';
      continue;
    }
    if ('\\^$+?.()|{}[]'.includes(ch)) {
      out += `\\${ch}`;
      continue;
    }
    out += ch;
  }
  return out;
}

function escapeRegex(value: string): string {
  return value.replace(/[\\^$+?.()|{}[\]]/g, '\\$&');
}

interface HeuristicMatcher {
  matches(line: string): boolean;
}

class SubstringMatcher implements HeuristicMatcher {
  constructor(private readonly value: string) {}

  matches(line: string): boolean {
    return line.includes(this.value);
  }
}

class RegexMatcher implements HeuristicMatcher {
  constructor(private readonly pattern: RegExp) {}

  matches(line: string): boolean {
    return this.pattern.test(line);
  }
}
