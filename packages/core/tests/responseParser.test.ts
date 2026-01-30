import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parseFindings, parseFindingsBatch } from '../src/providers/ResponseParser.js';
import type { PromptPayload, BatchPromptPayload } from '../src/prompt/PromptModels.js';

function readFixture(name: string): string {
  const fixturesDir = resolve(__dirname, '..', '..', 'contracts', 'fixtures');
  return readFileSync(resolve(fixturesDir, name), 'utf8');
}

function buildPrompt(): PromptPayload {
  return {
    techniqueId: 'SAFE-T0001',
    techniqueName: 'Test',
    severity: 'high',
    summary: 'summary',
    description: 'description',
    mitigations: [],
    codeChunk: {
      id: 'chunk1',
      file: 'src/app.ts',
      startLine: 1,
      endLine: 20,
      code: 'line1\nline2\nline3',
      ruleHints: [],
    },
  };
}

test('parseFindings uses evidence line numbers', () => {
  const content = readFixture('response-single.json');
  const findings = parseFindings(content, buildPrompt(), 'test-model');
  expect(findings).toHaveLength(1);
  expect(findings[0]?.file).toBe('src/app.ts');
  expect(findings[0]?.startLine).toBe(10);
  expect(findings[0]?.endLine).toBe(12);
});

test('parseFindings extracts json from wrapped response', () => {
  const content = readFixture('response-wrapped.txt');
  const findings = parseFindings(content, buildPrompt(), 'test-model');
  expect(findings).toHaveLength(1);
  expect(findings[0]?.file).toBe('src/main.go');
});

test('parseFindings falls back to chunk when evidence missing', () => {
  const content = '{"findings":[{"severity":"low","observation":"Missing evidence"}]}';
  const findings = parseFindings(content, buildPrompt(), 'test-model');
  expect(findings[0]?.startLine).toBe(1);
  expect(findings[0]?.endLine).toBe(20);
});

test('parseFindingsBatch parses technique ids', () => {
  const content = readFixture('response-batch.json');
  const prompt: BatchPromptPayload = {
    techniques: [
      {
        id: 'SAFE-T1001',
        name: 'Technique',
        severity: 'medium',
        summary: 'summary',
        description: 'description',
        mitigations: [],
        ruleHints: [],
      },
    ],
    codeChunk: {
      id: 'chunk2',
      file: 'lib/util.py',
      startLine: 1,
      endLine: 10,
      code: 'print("hello")',
      ruleHints: [],
    },
  };
  const findings = parseFindingsBatch(content, prompt, 'test-model');
  expect(findings).toHaveLength(1);
  expect(findings[0]?.techniqueId).toBe('SAFE-T1001');
  expect(findings[0]?.file).toBe('lib/util.py');
  expect(findings[0]?.startLine).toBe(5);
});
