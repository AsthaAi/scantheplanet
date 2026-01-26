# Scan The Planet

![Build](https://github.com/asthaai/scantheplanet/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/29924.svg)](https://plugins.jetbrains.com/plugin/29924-scan-the-planet)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/29924.svg)](https://plugins.jetbrains.com/plugin/29924-scan-the-planet)

<!-- Plugin description -->
Scan The Planet is an IntelliJ plugin that scans MCP (Model Context Protocol) servers and related code for security risks using the SAFE-MCP technique catalog. It can run against the current project, highlight findings, and optionally clean/merge overlapping findings with an LLM.

Key capabilities:
- SAFE-MCP technique scanning (rules + LLM analysis)
- Git diff or full-repo scan scope
- Provider support: OpenAI or Ollama
- Optional LLM-based findings cleaning and scan caching
<!-- Plugin description end -->

## Quick start
1. Open **Settings/Preferences → Scan The Planet**.
2. Choose a provider (or **local** for rules-only).
3. Set **Model name** and **LLM token** if using a remote provider.
4. (Optional) Enable **Clean findings with LLM** to reduce overlaps.
5. Run **Scan** from the IDE action or toolbar.

## Configuration
- **Provider**: `local`, `openai`, `anthropic`, `gemini`, `ollama`.
- **Model name**: Passed to the selected provider.
- **Config path**: Optional YAML/JSON scanner config.
- **LLM endpoint**: Used for Ollama; ignored for other providers.
- **Batch mode**: Enable multi-technique prompts.
- **Scan cache**: Speeds up repeated scans; clear cache if prompts change.

## Installation
- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Scan The Planet"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29924-scan-the-planet) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/29924-scan-the-planet/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/asthaai/scantheplanet/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
