import * as vscode from 'vscode';
import { SafeMcpRepository, type TechniqueSpec } from '@scantheplanet/core';

/**
 * Tree item representing a technique or category
 */
class TechniqueTreeItem extends vscode.TreeItem {
  constructor(
    public readonly label: string,
    public readonly collapsibleState: vscode.TreeItemCollapsibleState,
    public readonly technique?: TechniqueSpec,
    public readonly category?: string,
    public readonly count?: number
  ) {
    super(label, collapsibleState);

    if (technique) {
      // Individual technique
      this.description = technique.severity;
      this.tooltip = technique.summary;
      this.contextValue = 'technique';
      this.iconPath = this.getIconForSeverity(technique.severity);
    } else if (category) {
      // Category group
      this.description = `${count} technique${count === 1 ? '' : 's'}`;
      this.contextValue = 'category';
      this.iconPath = new vscode.ThemeIcon('folder');
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
 * Tree data provider for techniques
 */
export class TechniqueTreeProvider
  implements vscode.TreeDataProvider<TechniqueTreeItem>
{
  private _onDidChangeTreeData = new vscode.EventEmitter<
    TechniqueTreeItem | undefined | null | void
  >();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private readonly repository: SafeMcpRepository;
  private techniques: TechniqueSpec[] = [];
  private techniquesByCategory: Map<string, TechniqueSpec[]> = new Map();

  constructor(repository: SafeMcpRepository) {
    this.repository = repository;
    this.loadTechniques();
  }

  /**
   * Refresh the technique list
   */
  refresh(): void {
    this.loadTechniques();
    this._onDidChangeTreeData.fire();
  }

  private loadTechniques(): void {
    this.techniques = this.repository.loadAllTechniques();
    this.groupByCategory();
  }

  private groupByCategory(): void {
    this.techniquesByCategory.clear();

    for (const technique of this.techniques) {
      // Extract category from ID (e.g., SAFE-T13XX -> T13 = category 13)
      const match = technique.id.match(/SAFE-T(\d{2})/);
      const category = match ? `T${match[1]}XX` : 'Other';

      if (!this.techniquesByCategory.has(category)) {
        this.techniquesByCategory.set(category, []);
      }
      this.techniquesByCategory.get(category)!.push(technique);
    }

    // Sort categories
    const sorted = new Map(
      [...this.techniquesByCategory.entries()].sort(([a], [b]) =>
        a.localeCompare(b)
      )
    );
    this.techniquesByCategory = sorted;
  }

  getTreeItem(element: TechniqueTreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(element?: TechniqueTreeItem): Thenable<TechniqueTreeItem[]> {
    if (!element) {
      // Root level: show categories
      const items: TechniqueTreeItem[] = [];
      for (const [category, techniques] of this.techniquesByCategory) {
        const label = this.getCategoryLabel(category);
        items.push(
          new TechniqueTreeItem(
            label,
            vscode.TreeItemCollapsibleState.Collapsed,
            undefined,
            category,
            techniques.length
          )
        );
      }
      return Promise.resolve(items);
    }

    if (element.category) {
      // Category: show techniques
      const techniques = this.techniquesByCategory.get(element.category) ?? [];
      const items = techniques.map(
        (technique) =>
          new TechniqueTreeItem(
            technique.id,
            vscode.TreeItemCollapsibleState.None,
            technique
          )
      );
      return Promise.resolve(items);
    }

    return Promise.resolve([]);
  }

  private getCategoryLabel(category: string): string {
    const categoryLabels: Record<string, string> = {
      T10XX: 'Supply Chain',
      T11XX: 'Input Validation',
      T12XX: 'Authentication',
      T13XX: 'Privilege Escalation',
      T14XX: 'Data Exfiltration',
      T15XX: 'Denial of Service',
      T16XX: 'Code Execution',
      T17XX: 'Information Disclosure',
      T18XX: 'Integrity',
      T19XX: 'Logging & Monitoring',
      T20XX: 'Reserved',
      T21XX: 'MCP Specific',
      T30XX: 'Miscellaneous',
    };

    return categoryLabels[category] ?? category;
  }
}
