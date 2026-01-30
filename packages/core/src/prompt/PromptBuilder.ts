import type { PromptPayload, BatchPromptPayload } from './PromptModels.js';
import {
  SYSTEM_PROMPT,
  BATCH_SYSTEM_PROMPT,
  JSON_PREFILL,
} from './PromptStrings.js';

export { SYSTEM_PROMPT, BATCH_SYSTEM_PROMPT, JSON_PREFILL };

/**
 * Prompt version for cache invalidation
 */
export const PROMPT_VERSION = 'v1';

/**
 * Build a user prompt for single-technique analysis
 */
export function buildUserPrompt(prompt: PromptPayload): string {
  const ext = fileExtension(prompt.codeChunk.file);
  const mitigations = prompt.mitigations
    .map((m) => `${m.id}: ${m.description}`)
    .join('; ');
  const ruleHints = prompt.codeChunk.ruleHints.map((h) => h.snippet);
  const readme = prompt.readmeExcerpt ?? '';

  return `Technique: ${prompt.techniqueId} (severity ${prompt.severity})
Summary: ${prompt.summary}
Mitigations: ${mitigations}
File: ${prompt.codeChunk.file} (ext: ${ext || 'none'}, lines ${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine})
Code: ${prompt.codeChunk.code}
Rule hints: ${JSON.stringify(ruleHints)}
README: ${readme}`;
}

/**
 * Build a user prompt for batch analysis (multiple techniques)
 */
export function buildUserPromptBatch(prompt: BatchPromptPayload): string {
  const techniqueBlocks = prompt.techniques
    .map((technique) => {
      const mitigations = technique.mitigations
        .map((m) => `${m.id}: ${m.description}`)
        .join('; ');
      const hints = technique.ruleHints.map((h) => h.snippet);
      return `Technique: ${technique.id} (severity ${technique.severity})
Name: ${technique.name}
Summary: ${technique.summary}
Description: ${technique.description}
Mitigations: ${mitigations}
Rule hints: ${JSON.stringify(hints)}`;
    })
    .join('\n\n');

  const ext = fileExtension(prompt.codeChunk.file);
  return `Analyze the following code chunk against the techniques listed.

${techniqueBlocks}

File: ${prompt.codeChunk.file} (ext: ${ext || 'none'}, lines ${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine})
Code: ${prompt.codeChunk.code}
README: ${prompt.readmeExcerpt ?? ''}`;
}

/**
 * Extract file extension from a path
 */
export function fileExtension(path: string): string {
  const name = path.split('/').pop()?.split('\\').pop() ?? '';
  const idx = name.lastIndexOf('.');
  return idx > 0 && idx < name.length - 1 ? name.substring(idx + 1) : '';
}
