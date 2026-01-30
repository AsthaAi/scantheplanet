# Scan The Planet

A comprehensive security scanner for MCP (Model Context Protocol) servers using SAFE-MCP techniques. Available as both an IntelliJ IDEA plugin and VS Code extension.

[![Build](https://github.com/asthaai/scantheplanet/workflows/Build/badge.svg)](https://github.com/asthaai/scantheplanet/actions)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## Overview

Scan The Planet analyzes codebases for security vulnerabilities using the [SAFE-MCP framework](https://github.com/AsthaAI/SAFE-MCP) - a structured catalog of techniques for identifying security issues in AI/ML applications, particularly those using the Model Context Protocol.

### Features

- **Multi-provider LLM support**: OpenAI, Anthropic, Google Gemini, Ollama, or local pattern matching
- **70+ security techniques**: Covering prompt injection, data exfiltration, supply chain attacks, and more
- **Intelligent chunking**: Adaptive code chunking based on token limits
- **Caching**: Avoid re-analyzing unchanged code
- **Git diff scanning**: Only scan changed files for faster CI/CD integration
- **Gating**: Use heuristic pre-filtering to reduce LLM calls

## Monorepo Structure

```
scantheplanet/
├── packages/
│   ├── contracts/               # Shared prompts + schemas
│   ├── core/                    # TypeScript scanner core library
│   ├── safe-mcp/                # SAFE-MCP technique specifications
│   ├── vscode-extension/        # VS Code extension
│   └── intellij-plugin/         # IntelliJ IDEA plugin (Kotlin)
├── pnpm-workspace.yaml          # pnpm workspaces config
├── turbo.json                   # Turborepo build orchestration
└── tsconfig.base.json           # Shared TypeScript config
```

### Package Overview

| Package | Description | Language |
|---------|-------------|----------|
| `@scantheplanet/contracts` | Shared prompts and JSON schemas | Text/JSON |
| `@scantheplanet/core` | Scanner engine, LLM providers, prompt building | TypeScript |
| `@scantheplanet/safe-mcp` | SAFE-MCP technique YAML specs and loader | TypeScript |
| `vscode-extension` | VS Code extension with tree view and webview | TypeScript |
| `intellij-plugin` | IntelliJ IDEA plugin | Kotlin |

## Quick Start

### Prerequisites

- **Node.js** 20+ and **pnpm** 9+
- **JDK 21** (for IntelliJ plugin)
- **VS Code** 1.85+ (for VS Code extension)

### Installation

```bash
# Clone the repository
git clone https://github.com/AsthaAI/scantheplanet.git
cd scantheplanet

# Install dependencies
pnpm install

# Build all packages
pnpm build

# Sync shared prompts/schemas
pnpm sync:contracts

# Sync SAFE-MCP resources into IntelliJ plugin
pnpm sync:safe-mcp
```

### Development

```bash
# Run all tests
pnpm test

# Type check all packages
pnpm typecheck

# Lint all packages
pnpm lint

# Build a specific package
pnpm --filter @scantheplanet/core build

# Run VS Code extension in development mode
pnpm dev:vscode

# Build IntelliJ plugin
pnpm build:intellij
```

## VS Code Extension

### Installation

1. **From VSIX** (recommended for development):
   ```bash
   pnpm --filter vscode-extension package
   code --install-extension packages/vscode-extension/scantheplanet-*.vsix
   ```

2. **From Marketplace** (coming soon):
   Search for "Scan The Planet" in VS Code Extensions

### Usage

1. Open Command Palette (`Cmd+Shift+P` / `Ctrl+Shift+P`)
2. Run "Scan The Planet: Scan Project"
3. View results in the Scan The Planet sidebar

### Configuration

Settings are available under `scantheplanet.*`:

| Setting | Description | Default |
|---------|-------------|---------|
| `scantheplanet.provider` | LLM provider (local, openai, anthropic, gemini, ollama) | `local` |
| `scantheplanet.techniques` | Technique IDs to scan (empty = all) | `[]` |
| `scantheplanet.scope` | Scan scope (full, gitDiff) | `full` |
| `scantheplanet.ollamaEndpoint` | Ollama API endpoint | `http://localhost:11434` |

API keys are stored securely in VS Code's secrets storage.

## IntelliJ Plugin

### Installation

1. **From Disk**:
   ```bash
   cd packages/intellij-plugin
   ./gradlew buildPlugin
   ```
   Then install from `build/distributions/*.zip`

2. **From Marketplace** (coming soon):
   Search for "Scan The Planet" in JetBrains Marketplace

### Usage

1. Open Tools menu → Scan The Planet
2. Configure provider and API keys in Settings → Tools → Scan The Planet
3. Run scan from the tool window

## SAFE-MCP Techniques

The scanner uses SAFE-MCP technique specifications stored in `packages/safe-mcp/techniques/`. Each technique defines:

- **ID**: Unique identifier (e.g., `SAFE-T1301`)
- **Severity**: Critical, High, Medium, Low
- **Code signals**: Heuristic patterns for pre-filtering
- **LLM prompt**: Detailed description for LLM analysis

### Example Technique

```yaml
id: SAFE-T1301
name: Prompt Injection via User Input
severity: high
summary: Detects user input that flows into LLM prompts without sanitization
code_signals:
  - id: user-input-to-prompt
    heuristics:
      - pattern: "prompt.*user"
      - regex: "\\buser_input\\b.*\\bprompt\\b"
```

### Adding Custom Techniques

1. Create a new folder in `packages/safe-mcp/techniques/SAFE-TXXXX/`
2. Add `technique.yaml` following the schema
3. Optionally add `README.md` for detailed guidance

## LLM Providers

### Local Mode (Default)

Uses pattern matching only - no API calls. Fast but limited to heuristic detection.

### OpenAI

```bash
# Set API key
export OPENAI_API_KEY=your-key-here
```

Supported models: `gpt-4o`, `gpt-4o-mini`, `gpt-4-turbo`

### Anthropic

```bash
export ANTHROPIC_API_KEY=your-key-here
```

Supported models: `claude-3-5-sonnet`, `claude-3-opus`, `claude-3-haiku`

### Google Gemini

```bash
export GEMINI_API_KEY=your-key-here
```

Supported models: `gemini-1.5-pro`, `gemini-1.5-flash`

### Ollama (Self-hosted)

```bash
# Start Ollama with a model
ollama serve
ollama pull llama3.1
```

Configure endpoint in settings if not using default `http://localhost:11434`.

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Security Scan
on: [push, pull_request]

jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # For git diff scanning

      - uses: pnpm/action-setup@v2
        with:
          version: 9

      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: 'pnpm'

      - run: pnpm install
      - run: pnpm build

      - name: Run scan
        run: |
          cd packages/core
          pnpm scan --scope gitDiff --output sarif > results.sarif

      - uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: packages/core/results.sarif
```

## Architecture

```
┌─────────────────┐     ┌─────────────────┐
│   safe-mcp      │     │     core        │
│  (YAML specs)   │◄────│ (Scanner logic) │
└─────────────────┘     └────────┬────────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
                    ▼                         ▼
         ┌──────────────────┐     ┌──────────────────┐
         │ vscode-extension │     │ intellij-plugin  │
         │   (TypeScript)   │     │    (Kotlin)      │
         └──────────────────┘     └──────────────────┘
```

The TypeScript core is shared between the VS Code extension. The IntelliJ plugin uses its own Kotlin implementation for native JVM performance.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

## Security

For security issues, see [SECURITY.md](SECURITY.md).

## License

MIT License - see [LICENSE](LICENSE) for details.

---

<!-- Plugin description -->
**Scan The Planet** is a security scanner for MCP (Model Context Protocol) servers. It analyzes your codebase for vulnerabilities using the SAFE-MCP framework, with support for multiple LLM providers including OpenAI, Anthropic, Google Gemini, and Ollama.

Key features:
- 70+ security techniques covering prompt injection, data exfiltration, and supply chain attacks
- Smart chunking and caching for efficient scanning
- Git diff mode for CI/CD integration
- Local pattern matching mode (no API required)
<!-- Plugin description end -->
