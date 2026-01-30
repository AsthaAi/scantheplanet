import { join } from 'node:path';

export function parseUnifiedDiff(
  repoPath: string,
  diffText: string
): Map<string, Set<number>> {
  const addedByFile = new Map<string, Set<number>>();
  let currentFile: string | null = null;
  let currentNewLine = 0;
  let inHunk = false;

  const hunkHeader = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@.*/;

  for (const line of diffText.split(/\r?\n/)) {
    if (line.startsWith('diff --git ')) {
      currentFile = null;
      inHunk = false;
      continue;
    }
    if (line.startsWith('+++ ')) {
      const pathPart = line.replace(/^\+\+\+ /, '').trim();
      if (pathPart === '/dev/null') {
        currentFile = null;
      } else {
        const cleaned = pathPart.replace(/^b\//, '');
        currentFile = join(repoPath, cleaned);
        if (!addedByFile.has(currentFile)) {
          addedByFile.set(currentFile, new Set());
        }
      }
      continue;
    }
    if (line.startsWith('@@')) {
      const match = hunkHeader.exec(line);
      if (match) {
        const start = Number.parseInt(match[1] ?? '0', 10);
        currentNewLine = Number.isNaN(start) ? 0 : start;
        inHunk = true;
      } else {
        inHunk = false;
      }
      continue;
    }
    if (!inHunk || !currentFile) {
      continue;
    }
    if (line.startsWith('+') && !line.startsWith('+++')) {
      addedByFile.get(currentFile)?.add(currentNewLine);
      currentNewLine += 1;
      continue;
    }
    if (line.startsWith('-') && !line.startsWith('---')) {
      continue;
    }
    if (line.startsWith('\ No newline')) {
      continue;
    }
    currentNewLine += 1;
  }

  return addedByFile;
}
