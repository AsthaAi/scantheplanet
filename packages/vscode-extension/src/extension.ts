import * as vscode from 'vscode';
import * as path from 'node:path';
import { existsSync } from 'node:fs';
import { ScannerService } from './services/ScannerService';
import { ConfigService } from './services/ConfigService';
import { StatusBarService } from './services/StatusBarService';
import { FindingsTreeProvider } from './providers/FindingsTreeProvider';
import { TechniqueTreeProvider } from './providers/TechniqueTreeProvider';
import { registerCommands } from './commands';
import { Logger } from './utils/logger';
import { SafeMcpRepository } from '@scantheplanet/core';

let scannerService: ScannerService;
let configService: ConfigService;
let statusBarService: StatusBarService;
let findingsProvider: FindingsTreeProvider;
let techniqueProvider: TechniqueTreeProvider;

function resolveSafeMcpBasePath(extensionPath: string): string | undefined {
  const candidates = [
    path.join(extensionPath, 'node_modules', '@scantheplanet', 'safe-mcp'),
    path.join(extensionPath, '..', '..', 'node_modules', '@scantheplanet', 'safe-mcp'),
    path.join(extensionPath, '..', 'node_modules', '@scantheplanet', 'safe-mcp'),
  ];
  return candidates.find((candidate) => existsSync(candidate));
}

export function activate(context: vscode.ExtensionContext): void {
  Logger.info('Scan The Planet extension activating...');

  // Initialize services
  configService = new ConfigService(context);
  statusBarService = new StatusBarService();
  const safeMcpBasePath = resolveSafeMcpBasePath(context.extensionPath);
  const safeMcpRepository = safeMcpBasePath
    ? new SafeMcpRepository(safeMcpBasePath)
    : new SafeMcpRepository();
  scannerService = new ScannerService(
    configService,
    safeMcpRepository,
    context.globalStorageUri.fsPath,
    statusBarService
  );

  // Initialize tree providers
  findingsProvider = new FindingsTreeProvider();
  techniqueProvider = new TechniqueTreeProvider(safeMcpRepository);

  // Register tree views
  const findingsTreeView = vscode.window.createTreeView('scantheplanet.findings', {
    treeDataProvider: findingsProvider,
    showCollapseAll: true,
  });

  const techniqueTreeView = vscode.window.createTreeView('scantheplanet.techniques', {
    treeDataProvider: techniqueProvider,
    showCollapseAll: true,
  });

  // Register commands
  registerCommands(
    context,
    scannerService,
    configService,
    findingsProvider,
    techniqueProvider
  );

  // Subscribe to scan events
  scannerService.onScanComplete((summary) => {
    findingsProvider.setFindings(summary.findings);
    statusBarService.showScanComplete(summary.findings.length);
  });

  scannerService.onScanProgress((progress) => {
    statusBarService.showProgress(progress);
  });

  scannerService.onScanError((error) => {
    statusBarService.showError(error.message);
    vscode.window.showErrorMessage(`Scan failed: ${error.message}`);
  });

  // Add to subscriptions
  context.subscriptions.push(
    findingsTreeView,
    techniqueTreeView,
    statusBarService
  );

  Logger.info('Scan The Planet extension activated');
}

export function deactivate(): void {
  Logger.info('Scan The Planet extension deactivating...');
  statusBarService?.dispose();
}
