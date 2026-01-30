import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, normalize, relative } from 'node:path';
import { spawnSync } from 'node:child_process';
import type { PathFilters } from './Scope.js';
import { parseUnifiedDiff } from './GitDiff.js';

export interface GlobMatchers {
  include: RegExp[];
  exclude: RegExp[];
  excludePatterns: RegExp[];
  includeOverride: RegExp[];
}

export function collectFiles(
  repoPath: string,
  filters: PathFilters,
  matchers: GlobMatchers = createGlobMatchers(filters)
): string[] {
  const root = normalize(repoPath);
  const files: string[] = [];
  walk(root, (filePath) => {
    const rel = relative(root, filePath);
    if (!isIncluded(rel, filePath, filters, matchers)) {
      return;
    }
    files.push(filePath);
  });
  return files;
}

function walk(root: string, onFile: (path: string) => void): void {
  let entries: ReturnType<typeof readdirSync>;
  try {
    entries = readdirSync(root, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const fullPath = join(root, entry.name);
    if (entry.isDirectory()) {
      walk(fullPath, onFile);
    } else if (entry.isFile()) {
      onFile(fullPath);
    }
  }
}

export function createGlobMatchers(filters: PathFilters): GlobMatchers {
  return {
    include: filters.includeGlobs.map((pattern) => globToRegex(pattern)),
    exclude: filters.excludeGlobs.map((pattern) => globToRegex(pattern)),
    excludePatterns: filters.excludePatterns.map((pattern) => globToRegex(pattern)),
    includeOverride: filters.includeOverride.map((pattern) => globToRegex(pattern)),
  };
}

export function isIncluded(
  relativePath: string,
  absolutePath: string,
  filters: PathFilters,
  matchers: GlobMatchers
): boolean {
  const fileName = basename(relativePath);
  const ext = extension(fileName).toLowerCase();
  if (isGitPath(relativePath)) return false;
  if (isLockfile(fileName)) return false;
  if (isIdeMetadataPath(relativePath)) return false;
  if (isIgnoredDotfile(fileName)) return false;
  if (filters.onlyFiles && !filters.onlyFiles.has(normalizePath(relativePath))) {
    return false;
  }

  if (filters.excludeDocs && isDocFile(fileName)) return false;
  if (filters.excludeTests && isTestFile(relativePath)) return false;

  if (filters.excludeExtensions.length > 0 && ext) {
    if (filters.excludeExtensions.includes(ext)) return false;
  }
  if (filters.includeExtensions.length > 0 && ext) {
    if (!filters.includeExtensions.includes(ext)) return false;
  }

  const normalized = normalizePath(relativePath);
  if (
    matchAny(matchers.exclude, normalized) ||
    matchAny(matchers.excludePatterns, normalized)
  ) {
    if (matchAny(matchers.includeOverride, normalized)) {
      return true;
    }
    return false;
  }
  if (matchers.include.length > 0 && !matchAny(matchers.include, normalized)) {
    return false;
  }

  if (filters.maxFileBytes != null) {
    const size = safeFileSize(absolutePath);
    if (size > filters.maxFileBytes) return false;
  }

  return true;
}

export function readTextIfValid(
  path: string,
  maxLineChars = maxLineCharsEnv()
): string[] | null {
  let buf: Buffer;
  try {
    buf = readFileSync(path);
  } catch {
    return null;
  }
  if (isBinary(buf)) return null;
  let text: string;
  try {
    text = buf.toString('utf8');
  } catch {
    return null;
  }
  const lines = text.split(/\r?\n/);
  if (lines.length === 0) return null;
  if (lines.some((line) => line.length > maxLineChars)) return null;
  return lines;
}

export function isBinary(buffer: Buffer): boolean {
  return buffer.includes(0);
}

function maxLineCharsEnv(): number {
  const raw = process.env.MAX_LINE_CHARS;
  const parsed = raw ? Number.parseInt(raw, 10) : NaN;
  if (!Number.isNaN(parsed)) {
    return Math.min(Math.max(parsed, 1000), 200000);
  }
  return 10000;
}

export function isDocFile(fileName: string): boolean {
  const lower = fileName.toLowerCase();
  if (lower === 'readme' || lower.startsWith('readme.')) return true;
  if (lower === 'license' || lower.startsWith('license.')) return true;
  const ext = extension(lower);
  return ['md', 'rst', 'txt', 'adoc'].includes(ext);
}

export function isTestFile(path: string): boolean {
  const lower = normalizePath(path).toLowerCase();
  return (
    lower.includes('/test/') ||
    lower.includes('/tests/') ||
    lower.includes('__tests__') ||
    lower.includes('.test.') ||
    lower.includes('.spec.')
  );
}

export function gitChangedFiles(
  repoPath: string,
  baseRef: string,
  includeUntracked: boolean
): Set<string> {
  const files = new Set<string>();
  for (const file of runGit(repoPath, ['diff', '--name-only', baseRef, 'HEAD'])) {
    files.add(join(repoPath, file));
  }
  if (includeUntracked) {
    for (const file of runGit(repoPath, [
      'ls-files',
      '--others',
      '--exclude-standard',
    ])) {
      files.add(join(repoPath, file));
    }
  }
  return files;
}

export function gitDiffAddedLines(
  repoPath: string,
  baseRef: string
): Map<string, Set<number>> {
  const diffText = runGitText(repoPath, [
    'diff',
    '--unified=0',
    baseRef,
    'HEAD',
  ]);
  if (!diffText.trim()) return new Map();
  return parseUnifiedDiff(repoPath, diffText);
}

function runGit(repoPath: string, args: string[]): string[] {
  try {
    const result = spawnSync('git', ['-C', repoPath, ...args], {
      encoding: 'utf8',
    });
    if (result.status !== 0) return [];
    return result.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);
  } catch {
    return [];
  }
}

function runGitText(repoPath: string, args: string[]): string {
  try {
    const result = spawnSync('git', ['-C', repoPath, ...args], {
      encoding: 'utf8',
    });
    if (result.status !== 0) return '';
    return result.stdout || '';
  } catch {
    return '';
  }
}

function safeFileSize(path: string): number {
  try {
    return statSync(path).size;
  } catch {
    return 0;
  }
}

function basename(path: string): string {
  const normalized = normalizePath(path);
  const idx = normalized.lastIndexOf('/');
  return idx >= 0 ? normalized.slice(idx + 1) : normalized;
}

function extension(fileName: string): string {
  const idx = fileName.lastIndexOf('.');
  return idx >= 0 ? fileName.slice(idx + 1) : '';
}

function isGitPath(path: string): boolean {
  return normalizePath(path).split('/').some((part) => part === '.git');
}

function isIdeMetadataPath(path: string): boolean {
  return normalizePath(path).split('/').some((part) => part === '.idea' || part === '.vscode');
}

function isIgnoredDotfile(fileName: string): boolean {
  const lower = fileName.toLowerCase();
  if (!lower.startsWith('.')) return false;
  return (
    [
      '.gitignore',
      '.gitattributes',
      '.gitmodules',
      '.python-version',
      '.editorconfig',
    ].includes(lower) || lower.endsWith('.iml')
  );
}

function isLockfile(fileName: string): boolean {
  const lower = fileName.toLowerCase();
  if (lower.endsWith('.lock')) return true;
  return [
    'package-lock.json',
    'pnpm-lock.yaml',
    'yarn.lock',
    'poetry.lock',
    'pipfile.lock',
    'uv.lock',
    'composer.lock',
    'cargo.lock',
    'go.sum',
    'mix.lock',
    'gemfile.lock',
    'podfile.lock',
  ].includes(lower);
}

function normalizePath(path: string): string {
  return path.replace(/\\/g, '/');
}

function matchAny(patterns: RegExp[], value: string): boolean {
  return patterns.some((pattern) => pattern.test(value));
}

function globToRegex(glob: string): RegExp {
  let pattern = normalizePath(glob);
  let out = '^';
  for (let i = 0; i < pattern.length; i += 1) {
    const ch = pattern[i];
    if (ch === '*') {
      const next = pattern[i + 1];
      if (next === '*') {
        out += '.*';
        i += 1;
      } else {
        out += '[^/]*';
      }
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
  out += '$';
  return new RegExp(out);
}
