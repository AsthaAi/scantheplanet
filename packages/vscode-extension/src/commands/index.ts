import * as vscode from 'vscode';
import type { ScannerService } from '../services/ScannerService';
import type { ConfigService } from '../services/ConfigService';
import type { FindingsTreeProvider } from '../providers/FindingsTreeProvider';
import type { TechniqueTreeProvider } from '../providers/TechniqueTreeProvider';
import { Logger } from '../utils/logger';

/**
 * Register all extension commands
 */
export function registerCommands(
  context: vscode.ExtensionContext,
  scannerService: ScannerService,
  configService: ConfigService,
  findingsProvider: FindingsTreeProvider,
  techniqueProvider: TechniqueTreeProvider
): void {
  // Scan Project command
  context.subscriptions.push(
    vscode.commands.registerCommand('scantheplanet.scanProject', async () => {
      Logger.info('Command: scanProject');
      await scannerService.scanProject();
    })
  );

  // Scan File command
  context.subscriptions.push(
    vscode.commands.registerCommand(
      'scantheplanet.scanFile',
      async (uri?: vscode.Uri) => {
        Logger.info('Command: scanFile');

        let filePath: string;
        if (uri) {
          filePath = uri.fsPath;
        } else if (vscode.window.activeTextEditor) {
          filePath = vscode.window.activeTextEditor.document.uri.fsPath;
        } else {
          vscode.window.showErrorMessage('No file selected');
          return;
        }

        await scannerService.scanFile(filePath);
      }
    )
  );

  // Configure command
  context.subscriptions.push(
    vscode.commands.registerCommand('scantheplanet.configure', async () => {
      Logger.info('Command: configure');

      const options = [
        { label: '$(gear) Open Settings', action: 'settings' },
        { label: '$(key) Set API Key', action: 'apikey' },
        { label: '$(list-unordered) Select Techniques', action: 'techniques' },
      ];

      const selected = await vscode.window.showQuickPick(options, {
        placeHolder: 'Configure Scan The Planet',
      });

      if (!selected) return;

      switch (selected.action) {
        case 'settings':
          configService.openSettings();
          break;
        case 'apikey':
          await configureApiKey(configService);
          break;
        case 'techniques':
          await selectTechniques(configService, scannerService);
          break;
      }
    })
  );

  // Clear Findings command
  context.subscriptions.push(
    vscode.commands.registerCommand('scantheplanet.clearFindings', () => {
      Logger.info('Command: clearFindings');
      findingsProvider.clearFindings();
    })
  );

  // Refresh Techniques command
  context.subscriptions.push(
    vscode.commands.registerCommand('scantheplanet.refresh', () => {
      Logger.info('Command: refresh');
      techniqueProvider.refresh();
    })
  );
}

/**
 * Configure API key for a provider
 */
async function configureApiKey(configService: ConfigService): Promise<void> {
  const providers = [
    { label: 'OpenAI', value: 'openai' },
    { label: 'Anthropic', value: 'anthropic' },
    { label: 'Google Gemini', value: 'gemini' },
  ];

  const selected = await vscode.window.showQuickPick(providers, {
    placeHolder: 'Select provider to configure',
  });

  if (!selected) return;

  const hasKey = await configService.hasApiKey(selected.value);
  if (hasKey) {
    const confirm = await vscode.window.showQuickPick(
      ['Replace existing key', 'Delete key', 'Cancel'],
      { placeHolder: `API key already configured for ${selected.label}` }
    );

    if (confirm === 'Delete key') {
      await configService.deleteApiKey(selected.value);
      vscode.window.showInformationMessage(
        `${selected.label} API key deleted`
      );
      return;
    } else if (confirm !== 'Replace existing key') {
      return;
    }
  }

  await configService.promptForApiKey(selected.value);
  vscode.window.showInformationMessage(`${selected.label} API key saved`);
}

/**
 * Select techniques to scan
 */
async function selectTechniques(
  configService: ConfigService,
  scannerService: ScannerService
): Promise<void> {
  const allTechniques = scannerService.getTechniques();
  const currentTechniques = configService.getTechniques();

  const items = allTechniques.map((id) => ({
    label: id,
    picked: currentTechniques.length === 0 || currentTechniques.includes(id),
    description: scannerService.loadTechnique(id)?.name ?? '',
  }));

  const selected = await vscode.window.showQuickPick(items, {
    canPickMany: true,
    placeHolder: 'Select techniques to scan (empty = all)',
  });

  if (selected === undefined) return;

  const selectedIds = selected.map((item) => item.label);

  // Update configuration
  await vscode.workspace
    .getConfiguration('scantheplanet')
    .update(
      'techniques',
      selectedIds.length === allTechniques.length ? [] : selectedIds,
      vscode.ConfigurationTarget.Workspace
    );

  vscode.window.showInformationMessage(
    `Selected ${selectedIds.length} technique${selectedIds.length === 1 ? '' : 's'}`
  );
}
