# Contributing to Scan The Planet

Thank you for your interest in contributing to Scan The Planet! This document provides guidelines and instructions for development.

## Table of Contents

- [Development Setup](#development-setup)
- [Monorepo Structure](#monorepo-structure)
- [Development Workflow](#development-workflow)
- [Package-Specific Guidelines](#package-specific-guidelines)
- [Testing](#testing)
- [Code Style](#code-style)
- [Pull Request Process](#pull-request-process)

## Development Setup

### Prerequisites

- **Node.js 20+** - JavaScript runtime
- **pnpm 9+** - Package manager (`npm install -g pnpm`)
- **JDK 21** - For IntelliJ plugin development
- **VS Code** - For extension development
- **IntelliJ IDEA** - For plugin development (optional)

### Initial Setup

```bash
# Clone the repository
git clone https://github.com/AsthaAI/scantheplanet.git
cd scantheplanet

# Install all dependencies
pnpm install

# Build all packages (respects dependency order)
pnpm build

# Verify everything works
pnpm test
pnpm typecheck
```

## Monorepo Structure

This project uses **pnpm workspaces** with **Turborepo** for build orchestration.

```
scantheplanet/
├── packages/
│   ├── safe-mcp/           # SAFE-MCP technique specifications
│   │   ├── src/            # TypeScript loader
│   │   ├── techniques/     # YAML technique definitions
│   │   ├── mitigations/    # Mitigation documentation
│   │   └── schemas/        # JSON schemas for validation
│   │
│   ├── core/               # TypeScript scanner core
│   │   ├── src/
│   │   │   ├── scanner/    # NativeScanner, utils, config
│   │   │   ├── providers/  # LLM provider implementations
│   │   │   ├── prompt/     # Prompt building
│   │   │   ├── cache/      # Scan result caching
│   │   │   ├── git/        # Git diff parsing
│   │   │   └── types/      # TypeScript interfaces
│   │   └── tests/          # Vitest tests
│   │
│   ├── vscode-extension/   # VS Code extension
│   │   ├── src/
│   │   │   ├── commands/   # VS Code commands
│   │   │   ├── providers/  # Tree view providers
│   │   │   ├── views/      # Webview panels
│   │   │   └── services/   # Extension services
│   │   └── media/          # Icons and styles
│   │
│   └── intellij-plugin/    # IntelliJ IDEA plugin (Kotlin)
│       ├── src/main/kotlin/
│       └── src/main/resources/
│
├── pnpm-workspace.yaml     # Workspace configuration
├── turbo.json              # Build pipeline
└── tsconfig.base.json      # Shared TypeScript config
```

### Package Dependencies

```
@scantheplanet/safe-mcp  (no dependencies)
         ▲
         │
@scantheplanet/core      (depends on safe-mcp)
         ▲
         │
vscode-extension         (depends on core)

intellij-plugin          (independent - uses Kotlin)
```

## Development Workflow

### Common Commands

```bash
# Build everything
pnpm build

# Build specific package
pnpm --filter @scantheplanet/core build

# Run tests
pnpm test

# Type checking
pnpm typecheck

# Linting
pnpm lint

# Fix lint issues
pnpm lint:fix
```

### Working on Specific Packages

#### VS Code Extension

```bash
# Start development mode (watches for changes)
pnpm dev:vscode

# Or manually:
cd packages/vscode-extension
pnpm dev

# In VS Code: Press F5 to launch Extension Development Host
```

#### Core Library

```bash
cd packages/core

# Run tests in watch mode
pnpm test:watch

# Build and watch for changes
pnpm dev
```

#### IntelliJ Plugin

```bash
cd packages/intellij-plugin

# Run plugin in development IDE
./gradlew runIde

# Build plugin distribution
./gradlew buildPlugin
```

### Branch Naming

Use descriptive branch names:

- `feat/add-new-provider` - New features
- `fix/scan-cache-issue` - Bug fixes
- `docs/update-readme` - Documentation
- `refactor/scanner-utils` - Refactoring

## Package-Specific Guidelines

### @scantheplanet/safe-mcp

**Purpose**: SAFE-MCP technique specifications and TypeScript loader.

**Adding a New Technique**:

1. Create a folder: `techniques/SAFE-TXXXX/`
2. Add `technique.yaml`:

```yaml
id: SAFE-T1234
name: Technique Name
severity: high  # critical, high, medium, low
summary: Brief description
description: |
  Detailed explanation of the security issue,
  how to identify it, and why it matters.
mitigations:
  - SAFE-M-2
code_signals:
  - id: signal-name
    description: What this signal detects
    heuristics:
      - pattern: "suspicious_pattern"
      - regex: "\\bregex_pattern\\b"
languages:
  - typescript
  - python
output_schema:
  requires_mitigations: true
  allowed_status_values:
    - vulnerable
    - safe
    - needs_review
```

3. Add `README.md` with detailed guidance

**Validation**: Run `pnpm --filter @scantheplanet/safe-mcp test` to validate schemas.

### @scantheplanet/core

**Purpose**: Platform-agnostic scanner implementation.

**Key Interfaces**:

```typescript
// Provider interface - implement for new LLM providers
interface CodeModel {
  readonly name: string;
  analyzeChunk(prompt: PromptPayload): Promise<ModelFinding[]>;
  analyzeChunkBatch(prompt: BatchPromptPayload): Promise<ModelFinding[]>;
}

// Scanner configuration
interface ScannerConfig {
  provider: string;
  modelName?: string;
  apiKey?: string;
  maxLinesPerChunk?: number;
  // ... more options
}
```

**Adding a New Provider**:

1. Create `src/providers/NewProvider.ts`
2. Implement `CodeModel` interface
3. Register in `ModelFactory.ts`
4. Add tests in `tests/providers/`

### vscode-extension

**Purpose**: VS Code integration with UI components.

**Structure**:

- `commands/` - Command implementations (scan, configure, etc.)
- `providers/` - Tree view data providers
- `views/` - Webview panels (reports)
- `services/` - Background services (scanner, config)

**Testing**:

```bash
# Run extension tests
pnpm --filter vscode-extension test

# Test in VS Code
# Press F5 with extension open
```

### intellij-plugin

**Purpose**: IntelliJ IDEA integration.

The IntelliJ plugin uses its own Kotlin implementation (not the TypeScript core) for native JVM performance.

**Build Commands**:

```bash
./gradlew build          # Compile and test
./gradlew runIde         # Launch development IDE
./gradlew buildPlugin    # Create distribution
```

## Testing

### TypeScript Packages

We use **Vitest** for testing TypeScript packages:

```bash
# Run all tests
pnpm test

# Run specific package tests
pnpm --filter @scantheplanet/core test

# Watch mode
pnpm --filter @scantheplanet/core test:watch

# Coverage report
pnpm --filter @scantheplanet/core test:coverage
```

### Writing Tests

```typescript
// tests/scanner/NativeScanner.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { NativeScanner } from '../../src/scanner/NativeScanner';

describe('NativeScanner', () => {
  let scanner: NativeScanner;

  beforeEach(() => {
    scanner = new NativeScanner(/* ... */);
  });

  it('should detect vulnerabilities', async () => {
    const result = await scanner.scan(/* ... */);
    expect(result.findings).toHaveLength(1);
    expect(result.findings[0].severity).toBe('high');
  });
});
```

### IntelliJ Plugin

Uses JUnit for testing:

```bash
cd packages/intellij-plugin
./gradlew test
```

## Code Style

### TypeScript

- **ESLint** with recommended rules
- **Prettier** for formatting
- **Strict TypeScript** (`strict: true`)

```bash
# Check style
pnpm lint

# Fix auto-fixable issues
pnpm lint:fix

# Format code
pnpm format
```

### Kotlin

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Qodana for static analysis

### Guidelines

1. **Prefer explicit types** over `any`
2. **Use readonly** where possible
3. **Avoid magic numbers** - use named constants
4. **Document public APIs** with JSDoc/KDoc
5. **Keep functions small** and single-purpose
6. **Write tests** for new functionality

## Pull Request Process

1. **Fork and branch** from `main`
2. **Write tests** for your changes
3. **Update documentation** if needed
4. **Run validation**:
   ```bash
   pnpm typecheck
   pnpm lint
   pnpm test
   pnpm build
   ```
5. **Create PR** with:
   - Clear title describing the change
   - Description of what and why
   - Link to related issues

### PR Title Format

```
feat(core): add Groq provider support
fix(vscode): handle empty scan results
docs(readme): update installation instructions
refactor(safe-mcp): simplify technique loader
```

### Review Process

1. All PRs require at least one approval
2. CI checks must pass
3. Maintain or improve code coverage
4. Address reviewer feedback

## Getting Help

- **Issues**: [GitHub Issues](https://github.com/AsthaAI/scantheplanet/issues)
- **Discussions**: [GitHub Discussions](https://github.com/AsthaAI/scantheplanet/discussions)

Thank you for contributing!
