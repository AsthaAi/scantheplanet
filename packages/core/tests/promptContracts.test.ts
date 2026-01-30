import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  SYSTEM_PROMPT,
  BATCH_SYSTEM_PROMPT,
  JSON_PREFILL,
} from '../src/prompt/PromptBuilder.js';

function readContract(name: string): string {
  const contractsDir = resolve(__dirname, '..', '..', 'contracts', 'prompts');
  return readFileSync(resolve(contractsDir, name), 'utf8').trimEnd();
}

test('system prompt matches contracts', () => {
  expect(SYSTEM_PROMPT).toBe(readContract('system.prompt.txt'));
});

test('batch prompt matches contracts', () => {
  expect(BATCH_SYSTEM_PROMPT).toBe(readContract('batch.prompt.txt'));
});

test('json prefill matches contracts', () => {
  expect(JSON_PREFILL).toBe(readContract('json.prefill.txt'));
});
