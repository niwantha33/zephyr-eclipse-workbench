// SPDX-License-Identifier: Apache-2.0
import type { CommandSpec } from './contracts.js';

export interface CommandResult {
  exitCode: number;
  stdout: string;
  stderr: string;
}
export interface ExecutionPort {
  // Must enforce absolute allowed paths, bounded output, resource limits,
  // cancellation, trusted executable lookup, no shell and sandboxed cwd.
  run(spec: CommandSpec, signal?: AbortSignal): Promise<CommandResult>;
}
export interface VerificationResult {
  purpose: string;
  command: readonly string[];
  outcome: 'passed' | 'failed' | 'not-run';
  exitCode: number | null;
  diagnostic: string;
}
export class VerificationCoordinator {
  constructor(private readonly executor: ExecutionPort) {}
  async execute(specs: readonly CommandSpec[], approved: boolean,
                signal?: AbortSignal): Promise<VerificationResult[]> {
    if (!approved) {
      return specs.map(spec => ({
        purpose: spec.purpose, command: [spec.executable, ...spec.args],
        outcome: 'not-run' as const, exitCode: null,
        diagnostic: 'Not run: explicit verification approval required'
      }));
    }
    const results: VerificationResult[] = [];
    for (const spec of specs) {
      if (signal?.aborted) {
        results.push({ purpose: spec.purpose, command: [spec.executable, ...spec.args],
          outcome: 'not-run', exitCode: null, diagnostic: 'Cancelled' });
        continue;
      }
      try {
        const run = await this.executor.run(spec, signal);
        results.push({
          purpose: spec.purpose, command: [spec.executable, ...spec.args],
          outcome: run.exitCode === 0 ? 'passed' : 'failed', exitCode: run.exitCode,
          diagnostic: (run.stderr || run.stdout).slice(0, 8_192)
        });
      } catch {
        results.push({
          purpose: spec.purpose, command: [spec.executable, ...spec.args],
          outcome: 'failed', exitCode: null, diagnostic: 'Execution failed; see protected job log'
        });
      }
    }
    return results;
  }
}
