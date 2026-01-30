# Contracts

Canonical, shared artifacts used by both the VS Code extension (TypeScript) and IntelliJ plugin (Kotlin).

## Contents

- `prompts/` — canonical system prompts and JSON prefill
- `schemas/` — shared JSON schemas for config and scan results
- `fixtures/` — shared test fixtures (responses, wrapped output)

## Workflow

Run `pnpm sync:contracts` after editing these files to update generated prompt constants in both codebases.
