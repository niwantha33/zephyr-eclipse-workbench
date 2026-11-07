// SPDX-License-Identifier: Apache-2.0
import type { BuildObservation, ConsentStore, TelemetrySummary, TelemetryTransport } from './contracts.js';

const families = new Set(['mspm0', 'stm32', 'nrf', 'esp32', 'nxp', 'other']);
const toolchains = new Set(['zephyr-sdk', 'gnuarmemb', 'llvm', 'other']);
const outcomes = new Set(['success', 'failure']);
function coarseVersion(input: string): string {
  // Accept numeric public versions only. Reject paths, user names, hostnames and build metadata.
  const match = /^(\d{1,3})\.(\d{1,3})(?:\.\d{1,3})?$/.exec(input);
  if (!match) throw new Error('Non-public or malformed version rejected');
  return match[1] + '.' + match[2];
}
export class PrivateBuildTelemetry {
  private enabled = false;
  private buckets = new Map<string, number>();
  constructor(private readonly consent: ConsentStore,
              private readonly sender: TelemetryTransport,
              private readonly threshold = 20) {
    if (!Number.isSafeInteger(threshold) || threshold < 20) {
      throw new Error('Privacy threshold must be >= 20');
    }
  }
  async initialize(): Promise<void> {
    // Fails closed while consent storage is loading or unavailable.
    this.enabled = false;
    this.buckets.clear();
    try { this.enabled = await this.consent.read() === true; } catch { this.enabled = false; }
  }
  async setEnabled(next: boolean): Promise<void> {
    // Write consent before enabling a session. Opt-out clears immediately.
    this.enabled = false;
    this.buckets.clear();
    await this.consent.write(next);
    this.enabled = next === true;
  }
  get isEnabled(): boolean { return this.enabled; }
  record(obs: BuildObservation): void {
    if (!this.enabled) return;
    if (!families.has(obs.family) || !toolchains.has(obs.toolchain) || !outcomes.has(obs.outcome)) {
      throw new Error('Unrecognised build category rejected');
    }
    const parts = [obs.family, coarseVersion(obs.sdkVersion),
                   obs.toolchain, coarseVersion(obs.compilerVersion), obs.outcome];
    const key = parts.join('|');
    this.buckets.set(key, (this.buckets.get(key) ?? 0) + 1);
  }
  preview(): TelemetrySummary {
    const counts = [...this.buckets].sort(([a], [b]) => a.localeCompare(b)).map(([key, count]) => {
      const [family, sdkVersion, toolchain, compilerVersion, outcome] = key.split('|');
      return { family: family as BuildObservation['family'], sdkVersion,
               toolchain: toolchain as BuildObservation['toolchain'], compilerVersion,
               outcome: outcome as BuildObservation['outcome'], count };
    });
    return { schemaVersion: 1, counts };
  }
  async flushAfterUserApproval(): Promise<boolean> {
    if (!this.enabled) return false;
    const payload = this.preview();
    const total = payload.counts.reduce((n, row) => n + row.count, 0);
    // No small-sample telemetry leaves the client, regardless of the number of buckets.
    if (total < this.threshold || payload.counts.some(row => row.count < this.threshold)) return false;
    await this.sender.transmit(payload); // On failure, retain data for inspection and retry.
    this.buckets.clear();
    return true;
  }
}
