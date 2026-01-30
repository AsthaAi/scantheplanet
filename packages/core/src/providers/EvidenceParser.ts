import type { PromptPayload } from '../prompt/PromptModels.js';

const codeExtensions = new Set([
  'rs',
  'py',
  'ts',
  'tsx',
  'js',
  'jsx',
  'go',
  'rb',
  'php',
  'java',
  'kt',
  'c',
  'cpp',
  'cc',
  'h',
  'hpp',
  'cs',
  'swift',
  'm',
  'mm',
  'scala',
  'lua',
  'vue',
  'svelte',
  'dart',
  'ex',
  'exs',
  'erl',
  'sh',
  'ps1',
  'bash',
  'zsh',
  'toml',
  'yaml',
  'yml',
  'json',
  'sql',
  'r',
  'pl',
  'pm',
]);

export interface NormalizedEvidence {
  path: string;
  start: number;
  end: number;
  text: string;
}

export function extractJsonObject(raw: string): string | null {
  for (let start = 0; start < raw.length; start += 1) {
    if (raw[start] !== '{') continue;
    const end = findJsonObjectEnd(raw, start);
    if (end == null) continue;
    const candidate = raw.slice(start, end + 1);
    if (isJsonObject(candidate)) return candidate;
  }
  return null;
}

export function normalizeFromPrompt(
  raw: string,
  prompt: PromptPayload,
  enforceCodePath: boolean
): NormalizedEvidence {
  const parsed = parseEvidence(raw);
  let path = parsed?.path ?? prompt.codeChunk.file;
  let start = parsed?.start ?? prompt.codeChunk.startLine;
  let end = parsed?.end ?? prompt.codeChunk.endLine;
  let snippet = parsed?.snippet ?? raw.trim();

  if (start <= 0 || end <= 0 || end < start) {
    path = prompt.codeChunk.file;
    start = prompt.codeChunk.startLine;
    end = prompt.codeChunk.endLine;
    snippet = defaultSnippet(prompt.codeChunk.code);
  }

  if (start < prompt.codeChunk.startLine || end > prompt.codeChunk.endLine) {
    path = prompt.codeChunk.file;
    start = prompt.codeChunk.startLine;
    end = prompt.codeChunk.endLine;
    snippet = defaultSnippet(prompt.codeChunk.code);
  }

  if (enforceCodePath && !isCodeFile(path)) {
    path = prompt.codeChunk.file;
    start = prompt.codeChunk.startLine;
    end = prompt.codeChunk.endLine;
    snippet = defaultSnippet(prompt.codeChunk.code);
  }

  if (!snippet.trim()) {
    snippet = defaultSnippet(prompt.codeChunk.code);
  }

  const text = `${path}:${start}-${end} ${snippet}`;
  return { path, start, end, text };
}

export function formatRuleHintEvidence(
  file: string,
  line: number,
  snippet: string
): string {
  const safeSnippet = snippet.trim() ? snippet : 'rule hint matched';
  return `${file}:${line}-${line} ${safeSnippet}`;
}

interface ParsedEvidence {
  path: string;
  start: number;
  end: number;
  snippet: string;
}

function parseEvidence(raw: string): ParsedEvidence | null {
  const trimmed = raw.trim();
  const match = evidenceRegex.exec(trimmed);
  if (!match?.groups) return null;
  const path = match.groups.path;
  const start = Number.parseInt(match.groups.start, 10);
  const end = Number.parseInt(match.groups.end, 10);
  if (!path || Number.isNaN(start) || Number.isNaN(end)) return null;
  return {
    path,
    start,
    end,
    snippet: match.groups.snippet ?? '',
  };
}

function defaultSnippet(code: string): string {
  return code.split(/\r?\n/).slice(0, 3).join('\n');
}

function isCodeFile(path: string): boolean {
  const fileName = path
    .split('/')
    .pop()
    ?.split('\\')
    .pop()
    ?.toLowerCase() ?? '';
  if (fileName.startsWith('readme') || fileName === 'license') return false;
  const ext = fileName.includes('.') ? fileName.split('.').pop() ?? '' : '';
  return ext ? codeExtensions.has(ext) : false;
}

function findJsonObjectEnd(raw: string, start: number): number | null {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let idx = start; idx < raw.length; idx += 1) {
    const ch = raw[idx];
    if (inString) {
      if (escaped) {
        escaped = false;
        continue;
      }
      if (ch === '\\') {
        escaped = true;
        continue;
      }
      if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
      continue;
    }
    if (ch === '{') {
      depth += 1;
      continue;
    }
    if (ch === '}') {
      if (depth === 0) continue;
      depth -= 1;
      if (depth === 0) return idx;
    }
  }
  return null;
}

function isJsonObject(candidate: string): boolean {
  const trimmed = candidate.trim();
  return trimmed.startsWith('{') && trimmed.endsWith('}');
}

const evidenceRegex =
  /^(?<path>.+):(?<start>\d+)-(?<end>\d+)\s*(?<snippet>.*)$/;
