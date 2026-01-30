import * as vscode from 'vscode';

const outputChannel = vscode.window.createOutputChannel('Scan The Planet');

/**
 * Logger utility for the extension
 */
export const Logger = {
  info(message: string): void {
    const timestamp = new Date().toISOString();
    outputChannel.appendLine(`[${timestamp}] INFO: ${message}`);
  },

  warn(message: string): void {
    const timestamp = new Date().toISOString();
    outputChannel.appendLine(`[${timestamp}] WARN: ${message}`);
  },

  error(message: string, error?: Error): void {
    const timestamp = new Date().toISOString();
    outputChannel.appendLine(`[${timestamp}] ERROR: ${message}`);
    if (error?.stack) {
      outputChannel.appendLine(error.stack);
    }
  },

  show(): void {
    outputChannel.show();
  },
};
