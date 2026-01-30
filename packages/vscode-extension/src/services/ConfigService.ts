import * as vscode from 'vscode';
import type { ScannerConfig } from '@scantheplanet/core';

const CONFIG_SECTION = 'scantheplanet';
const API_KEY_PREFIX = 'scantheplanet.apiKey.';

/**
 * Service for managing extension configuration
 */
export class ConfigService {
  constructor(private readonly context: vscode.ExtensionContext) {}

  /**
   * Get the current scanner configuration
   */
  getConfig(): ScannerConfig {
    const config = vscode.workspace.getConfiguration(CONFIG_SECTION);

    return {
      allowRemoteProviders: true,
      ollamaEndpoint: config.get('ollamaEndpoint', 'http://localhost:11434'),
      excludePatterns: config.get('excludePatterns', []),
      maxFileBytes: config.get('maxFileSize', 5 * 1024 * 1024),
      gatingEnabled: config.get('enableGating', true),
    };
  }

  /**
   * Get the current provider
   */
  getProvider(): string {
    return vscode.workspace
      .getConfiguration(CONFIG_SECTION)
      .get('provider', 'local');
  }

  /**
   * Get the model name override
   */
  getModelName(): string | undefined {
    const name = vscode.workspace
      .getConfiguration(CONFIG_SECTION)
      .get('modelName', '');
    return name || undefined;
  }

  /**
   * Get selected technique IDs
   */
  getTechniques(): string[] {
    return vscode.workspace
      .getConfiguration(CONFIG_SECTION)
      .get('techniques', []);
  }

  /**
   * Get the scan scope
   */
  getScope(): 'full' | 'gitDiff' {
    return vscode.workspace
      .getConfiguration(CONFIG_SECTION)
      .get('scope', 'full') as 'full' | 'gitDiff';
  }

  /**
   * Get API key for a provider from secrets storage
   */
  async getApiKey(provider: string): Promise<string | undefined> {
    return this.context.secrets.get(`${API_KEY_PREFIX}${provider}`);
  }

  /**
   * Store API key for a provider in secrets storage
   */
  async setApiKey(provider: string, apiKey: string): Promise<void> {
    await this.context.secrets.store(`${API_KEY_PREFIX}${provider}`, apiKey);
  }

  /**
   * Delete API key for a provider
   */
  async deleteApiKey(provider: string): Promise<void> {
    await this.context.secrets.delete(`${API_KEY_PREFIX}${provider}`);
  }

  /**
   * Check if API key is configured for a provider
   */
  async hasApiKey(provider: string): Promise<boolean> {
    const key = await this.getApiKey(provider);
    return !!key;
  }

  /**
   * Prompt user for API key
   */
  async promptForApiKey(provider: string): Promise<string | undefined> {
    const apiKey = await vscode.window.showInputBox({
      prompt: `Enter your ${provider} API key`,
      password: true,
      placeHolder: 'sk-...',
      ignoreFocusOut: true,
    });

    if (apiKey) {
      await this.setApiKey(provider, apiKey);
    }

    return apiKey;
  }

  /**
   * Open settings UI
   */
  openSettings(): void {
    vscode.commands.executeCommand(
      'workbench.action.openSettings',
      `@ext:astha-ai.scantheplanet`
    );
  }
}
