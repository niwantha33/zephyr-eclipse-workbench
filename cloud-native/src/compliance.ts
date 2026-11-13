// SPDX-License-Identifier: Apache-2.0
import type { CommandSpec } from './contracts.js';

export interface BoardCheckInput {
  zephyrRoot: string;
  workspaceRoot: string;
  applicationRoot: string;
  board: string;
  buildDirectory: string;
  commitRange?: string;
}
// Generates explicit commands only. Backend must authorize and execute them with no shell.
export class ZephyrVerificationPlanner {
  plan(input: BoardCheckInput): readonly CommandSpec[] {
    if (!/^[a-zA-Z0-9_-]+(?:\/[a-zA-Z0-9_-]+){0,3}$/.test(input.board)) {
      throw new Error('Unsafe board identifier');
    }
    for (const path of [input.zephyrRoot, input.workspaceRoot,
                        input.applicationRoot, input.buildDirectory]) {
      if (!path || path.includes('\0') || path.includes('\n')) {
        throw new Error('Invalid path');
      }
    }
    const tasks: CommandSpec[] = [{
      executable: 'west',
      args: ['build', '--board', input.board, '--build-dir', input.buildDirectory,
             '--pristine=always', input.applicationRoot],
      cwd: input.workspaceRoot,
      purpose: 'Real Zephyr configure/build; inspect generated zephyr.dts, .config and build logs'
    }];
    if (input.commitRange !== undefined) {
      if (!/^[a-f0-9]{7,40}\.\.[a-f0-9]{7,40}$/.test(input.commitRange)) {
        throw new Error('Compliance commit range must contain resolved immutable SHAs');
      }
      tasks.push({
        executable: 'python3',
        args: [input.zephyrRoot + '/scripts/ci/check_compliance.py',
               '-p', '1', '-c', input.commitRange],
        cwd: input.zephyrRoot,
        purpose: 'Run upstream Zephyr compliance checks on an explicit commit range'
      });
    }
    return tasks;
  }
}
