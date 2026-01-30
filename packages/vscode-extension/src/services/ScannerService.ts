import * as vscode from 'vscode';
import {
  SafeMcpRepository,
  Scanner,
  ScanCache,
  type ScanScope,
  type ScanSummary,
  type ScanProgress,
} from '@scantheplanet/core';
import * as path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import type { ConfigService } from './ConfigService';
import type { StatusBarService } from './StatusBarService';
import { Logger } from '../utils/logger';

type ScanCompleteHandler = (summary: ScanSummary) => void;
type ScanProgressHandler = (progress: ScanProgress) => void;
type ScanErrorHandler = (error: Error) => void;

/**
 * Service for running scans
 */
export class ScannerService {
  private readonly repository: SafeMcpRepository;
  private readonly cacheDir?: string;
  private isScanning = false;
  private scanCompleteHandlers: ScanCompleteHandler[] = [];
  private scanProgressHandlers: ScanProgressHandler[] = [];
  private scanErrorHandlers: ScanErrorHandler[] = [];

  constructor(
    private readonly configService: ConfigService,
    repository: SafeMcpRepository,
    cacheDir: string | undefined,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    _statusBarService: StatusBarService
  ) {
    this.repository = repository;
    this.cacheDir = cacheDir;
  }

  /**
   * Register handler for scan completion
   */
  onScanComplete(handler: ScanCompleteHandler): void {
    this.scanCompleteHandlers.push(handler);
  }

  /**
   * Register handler for scan progress
   */
  onScanProgress(handler: ScanProgressHandler): void {
    this.scanProgressHandlers.push(handler);
  }

  /**
   * Register handler for scan errors
   */
  onScanError(handler: ScanErrorHandler): void {
    this.scanErrorHandlers.push(handler);
  }

  /**
   * Check if a scan is currently running
   */
  get scanning(): boolean {
    return this.isScanning;
  }

  /**
   * Scan the entire project
   */
  async scanProject(): Promise<void> {
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
      vscode.window.showErrorMessage('No workspace folder open');
      return;
    }

    await this.runScan(workspaceFolder.uri.fsPath);
  }

  /**
   * Scan a specific file
   */
  async scanFile(filePath: string): Promise<void> {
    await this.runScan(filePath, true);
  }

  /**
   * Run a scan
   */
  private async runScan(
    path: string,
    singleFile = false
  ): Promise<void> {
    if (this.isScanning) {
      vscode.window.showWarningMessage('A scan is already in progress');
      return;
    }

    this.isScanning = true;
    Logger.info(`Starting scan: ${path}`);

    try {
      const config = this.configService.getConfig();
      const provider = this.configService.getProvider();
      const modelName = this.configService.getModelName();
      const techniqueIds = this.configService.getTechniques();
      const scopeSetting = this.configService.getScope();

      // Get API key if needed
      let apiKey: string | undefined;
      if (provider !== 'local' && provider !== 'ollama') {
        apiKey = await this.configService.getApiKey(provider);
        if (!apiKey) {
          apiKey = await this.configService.promptForApiKey(provider);
          if (!apiKey) {
            this.isScanning = false;
            return;
          }
        }
      }

      // Get techniques to scan
      const allTechniqueIds = this.repository.listTechniqueIds();
      const techniquesToScan =
        techniqueIds.length > 0 ? techniqueIds : allTechniqueIds;

      const scanScope = this.resolveScope(path, singleFile, scopeSetting);
      const cache = this.buildCache(scanScope.repoPath);
      const scanner = new Scanner(this.repository, config, cache);

      Logger.info(
        `Scanning with provider: ${provider}, model: ${modelName || 'default'}, techniques: ${techniquesToScan.length}`
      );

      // Run the scan
      const summary = await scanner.scan({
        repoPath: scanScope.repoPath,
        techniqueIds: techniquesToScan,
        scope: scanScope.scope,
        provider,
        modelName,
        apiKey,
        progress: (progress) => {
          for (const handler of this.scanProgressHandlers) {
            handler(progress);
          }
        },
      });

      // Notify handlers
      for (const handler of this.scanCompleteHandlers) {
        handler(summary);
      }

      Logger.info(
        `Scan complete: ${summary.findings.length} findings, ${summary.filesScanned} files`
      );
    } catch (error) {
      const err = error instanceof Error ? error : new Error(String(error));
      Logger.error('Scan failed', err);
      for (const handler of this.scanErrorHandlers) {
        handler(err);
      }
    } finally {
      this.isScanning = false;
    }
  }

  private buildCache(repoPath: string): ScanCache | undefined {
    if (!this.cacheDir) return undefined;
    const hash = hashString(repoPath);
    const cachePath = path.join(this.cacheDir, 'scan-cache', hash, 'cache.json');
    return new ScanCache(cachePath);
  }

  private resolveScope(
    targetPath: string,
    singleFile: boolean,
    scopeSetting: 'full' | 'gitDiff'
  ): { repoPath: string; scope: ScanScope } {
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    const repoPath = workspaceFolder ?? path.dirname(targetPath);

    if (singleFile) {
      return { repoPath, scope: { kind: 'file', file: targetPath } };
    }

    if (scopeSetting === 'gitDiff') {
      const baseRef = resolveGitBaseRef(repoPath);
      if (baseRef) {
        return {
          repoPath,
          scope: { kind: 'gitDiff', baseRef, includeUntracked: true },
        };
      }
    }

    return { repoPath, scope: { kind: 'full' } };
  }

  /**
   * Get all available techniques
   */
  getTechniques(): string[] {
    return this.repository.listTechniqueIds();
  }

  /**
   * Load a technique by ID
   */
  loadTechnique(id: string) {
    return this.repository.loadTechnique(id);
  }
}

function hashString(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

function resolveGitBaseRef(repoPath: string): string | undefined {
  const candidates = [
    'origin/main',
    'origin/master',
    'main',
    'master',
    'HEAD~1',
    'HEAD',
  ];
  for (const candidate of candidates) {
    if (gitRefExists(repoPath, candidate)) {
      return candidate;
    }
  }
  return undefined;
}

function gitRefExists(repoPath: string, ref: string): boolean {
  try {
    const result = spawnSync('git', ['-C', repoPath, 'rev-parse', '--verify', ref], {
      encoding: 'utf8',
    });
    return result.status === 0;
  } catch {
    return false;
  }
}
