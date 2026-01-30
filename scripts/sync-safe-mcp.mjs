import { rmSync, mkdirSync, readdirSync, copyFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const sourceRoot = join(root, 'packages', 'safe-mcp');
const targetRoot = join(root, 'packages', 'intellij-plugin', 'src', 'main', 'resources', 'safe-mcp');

const itemsToCopy = ['techniques', 'mitigations', 'schemas', 'patterns', 'README.md'];

try {
  rmSync(targetRoot, { recursive: true, force: true });
} catch {
  // ignore
}
mkdirSync(targetRoot, { recursive: true });

for (const item of itemsToCopy) {
  const src = join(sourceRoot, item);
  const dst = join(targetRoot, item);
  copyRecursive(src, dst);
}

console.log('Synced SAFE-MCP resources to IntelliJ plugin.');

function copyRecursive(src, dst) {
  const stats = statSync(src);
  if (stats.isDirectory()) {
    mkdirSync(dst, { recursive: true });
    const entries = readdirSync(src);
    for (const entry of entries) {
      copyRecursive(join(src, entry), join(dst, entry));
    }
  } else {
    mkdirSync(dirname(dst), { recursive: true });
    copyFileSync(src, dst);
  }
}
