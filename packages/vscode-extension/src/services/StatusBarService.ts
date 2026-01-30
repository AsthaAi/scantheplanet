import * as vscode from 'vscode';
import type { ScanProgress } from '@scantheplanet/core';

/**
 * Service for managing the status bar
 */
export class StatusBarService implements vscode.Disposable {
  private readonly statusBarItem: vscode.StatusBarItem;

  constructor() {
    this.statusBarItem = vscode.window.createStatusBarItem(
      vscode.StatusBarAlignment.Left,
      100
    );
    this.statusBarItem.command = 'scantheplanet.scanProject';
    this.showReady();
    this.statusBarItem.show();
  }

  /**
   * Show ready state
   */
  showReady(): void {
    this.statusBarItem.text = '$(shield) Scan The Planet';
    this.statusBarItem.tooltip = 'Click to scan project';
    this.statusBarItem.backgroundColor = undefined;
  }

  /**
   * Show scanning progress
   */
  showProgress(progress: ScanProgress): void {
    const percent =
      progress.totalFiles > 0
        ? Math.round((progress.processedFiles / progress.totalFiles) * 100)
        : 0;
    const percentText = progress.totalFiles > 0 ? ` ${percent}%` : '';
    this.statusBarItem.text = `$(sync~spin) Scanning ${progress.techniqueName}...${percentText}`;
    this.statusBarItem.tooltip = `Scanning ${progress.currentFile}\nTechnique ${progress.techniqueIndex}/${progress.totalTechniques}`;
  }

  /**
   * Show scan complete
   */
  showScanComplete(findingsCount: number): void {
    if (findingsCount === 0) {
      this.statusBarItem.text = '$(pass) No issues found';
      this.statusBarItem.backgroundColor = undefined;
    } else {
      this.statusBarItem.text = `$(warning) ${findingsCount} issue${findingsCount === 1 ? '' : 's'} found`;
      this.statusBarItem.backgroundColor = new vscode.ThemeColor(
        'statusBarItem.warningBackground'
      );
    }
    this.statusBarItem.tooltip = 'Click to scan again';

    // Reset to ready state after 5 seconds
    setTimeout(() => this.showReady(), 5000);
  }

  /**
   * Show error state
   */
  showError(message: string): void {
    this.statusBarItem.text = '$(error) Scan failed';
    this.statusBarItem.tooltip = message;
    this.statusBarItem.backgroundColor = new vscode.ThemeColor(
      'statusBarItem.errorBackground'
    );

    // Reset to ready state after 5 seconds
    setTimeout(() => this.showReady(), 5000);
  }

  dispose(): void {
    this.statusBarItem.dispose();
  }
}
