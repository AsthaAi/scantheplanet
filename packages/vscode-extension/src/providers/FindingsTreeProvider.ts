import * as vscode from 'vscode';
import * as path from 'node:path';
import type { ScanFinding } from '@scantheplanet/core';

/**
 * Tree item representing a finding or severity group
 */
class FindingTreeItem extends vscode.TreeItem {
  constructor(
    public readonly label: string,
    public readonly collapsibleState: vscode.TreeItemCollapsibleState,
    public readonly finding?: ScanFinding,
    public readonly severity?: string,
    public readonly count?: number
  ) {
    super(label, collapsibleState);

    if (finding) {
      // Individual finding
      this.description = `${finding.file}:${finding.startLine}`;
      this.tooltip = finding.observation;
      this.contextValue = 'finding';
      this.iconPath = this.getIconForSeverity(finding.severity);
      const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
      const filePath =
        workspaceRoot && !path.isAbsolute(finding.file)
          ? path.join(workspaceRoot, finding.file)
          : finding.file;
      this.command = {
        command: 'vscode.open',
        title: 'Open File',
        arguments: [
          vscode.Uri.file(filePath),
          {
            selection: new vscode.Range(
              finding.startLine - 1,
              0,
              finding.endLine - 1,
              0
            ),
          },
        ],
      };
    } else if (severity) {
      // Severity group
      this.description = `${count} issue${count === 1 ? '' : 's'}`;
      this.contextValue = 'severity';
      this.iconPath = this.getIconForSeverity(severity);
    }
  }

  private getIconForSeverity(severity: string): vscode.ThemeIcon {
    switch (severity.toLowerCase()) {
      case 'critical':
        return new vscode.ThemeIcon(
          'error',
          new vscode.ThemeColor('errorForeground')
        );
      case 'high':
        return new vscode.ThemeIcon(
          'warning',
          new vscode.ThemeColor('editorWarning.foreground')
        );
      case 'medium':
        return new vscode.ThemeIcon(
          'info',
          new vscode.ThemeColor('editorInfo.foreground')
        );
      case 'low':
      case 'info':
        return new vscode.ThemeIcon('circle-outline');
      default:
        return new vscode.ThemeIcon('question');
    }
  }
}

/**
 * Tree data provider for findings
 */
export class FindingsTreeProvider
  implements vscode.TreeDataProvider<FindingTreeItem>
{
  private _onDidChangeTreeData = new vscode.EventEmitter<
    FindingTreeItem | undefined | null | void
  >();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private findings: ScanFinding[] = [];
  private findingsBySeverity: Map<string, ScanFinding[]> = new Map();

  /**
   * Set the findings and refresh the tree
   */
  setFindings(findings: ScanFinding[]): void {
    this.findings = findings;
    this.groupBySeverity();
    this._onDidChangeTreeData.fire();
  }

  /**
   * Clear all findings
   */
  clearFindings(): void {
    this.findings = [];
    this.findingsBySeverity.clear();
    this._onDidChangeTreeData.fire();
  }

  private groupBySeverity(): void {
    this.findingsBySeverity.clear();
    const severityOrder = ['critical', 'high', 'medium', 'low', 'info'];

    for (const finding of this.findings) {
      const severity = finding.severity.toLowerCase();
      if (!this.findingsBySeverity.has(severity)) {
        this.findingsBySeverity.set(severity, []);
      }
      this.findingsBySeverity.get(severity)!.push(finding);
    }

    // Ensure order
    const ordered = new Map<string, ScanFinding[]>();
    for (const sev of severityOrder) {
      if (this.findingsBySeverity.has(sev)) {
        ordered.set(sev, this.findingsBySeverity.get(sev)!);
      }
    }
    this.findingsBySeverity = ordered;
  }

  getTreeItem(element: FindingTreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(element?: FindingTreeItem): Thenable<FindingTreeItem[]> {
    if (!element) {
      // Root level: show severity groups
      const items: FindingTreeItem[] = [];
      for (const [severity, findings] of this.findingsBySeverity) {
        const label = this.formatSeverityLabel(severity);
        items.push(
          new FindingTreeItem(
            label,
            vscode.TreeItemCollapsibleState.Expanded,
            undefined,
            severity,
            findings.length
          )
        );
      }
      return Promise.resolve(items);
    }

    if (element.severity) {
      // Severity group: show findings
      const findings = this.findingsBySeverity.get(element.severity) ?? [];
      const items = findings.map(
        (finding) =>
          new FindingTreeItem(
            finding.techniqueName || finding.techniqueId,
            vscode.TreeItemCollapsibleState.None,
            finding
          )
      );
      return Promise.resolve(items);
    }

    return Promise.resolve([]);
  }

  private formatSeverityLabel(severity: string): string {
    const emoji = this.getSeverityEmoji(severity);
    return `${emoji} ${severity.charAt(0).toUpperCase() + severity.slice(1)}`;
  }

  private getSeverityEmoji(severity: string): string {
    switch (severity.toLowerCase()) {
      case 'critical':
        return '🔴';
      case 'high':
        return '🟠';
      case 'medium':
        return '🟡';
      case 'low':
        return '🟢';
      case 'info':
        return '🔵';
      default:
        return '⚪';
    }
  }
}
