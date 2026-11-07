// SPDX-License-Identifier: Apache-2.0
// Transport-independent contracts. No dependency on Eclipse, Theia or a hardware probe.
export type Outcome = 'success' | 'failure';
export type McuFamily = 'mspm0' | 'stm32' | 'nrf' | 'esp32' | 'nxp' | 'other';
export type Toolchain = 'zephyr-sdk' | 'gnuarmemb' | 'llvm' | 'other';
export interface BuildObservation {
  family: McuFamily;
  sdkVersion: string;
  toolchain: Toolchain;
  compilerVersion: string;
  outcome: Outcome;
}
export interface TelemetrySummary {
  schemaVersion: 1;
  counts: readonly { family: McuFamily; sdkVersion: string; toolchain: Toolchain;
    compilerVersion: string; outcome: Outcome; count: number }[];
}
export interface ConsentStore {
  read(): Promise<boolean>;
  write(enabled: boolean): Promise<void>;
}
export interface TelemetryTransport {
  // Must enforce TLS, endpoint allowlist and a no-IP-retention backend policy.
  transmit(payload: TelemetrySummary): Promise<void>;
}
export interface RegistryItem {
  id: string;
  kind: 'theia-extension' | 'zephyr-module';
  version: string;
  license: string;
  sourceUri: string;
  sha256?: string;
  dependencies: readonly string[];
  kconfigFragments?: readonly string[];
  cmakeAdditions?: readonly string[];
}
export interface InstallPlan {
  item: RegistryItem;
  actions: readonly string[];
  status: 'requires-review';
}
export interface BoardDescriptor {
  boardId: string;
  family: McuFamily;
  runner: string;
  available: boolean;
}
export interface BoardLease {
  leaseId: string;
  boardId: string;
  userId: string;
  expiresAtMs: number;
}
export interface ReferenceDocument {
  id: string;
  title: string;
  vendor: string;
  part: string;
  sourceUri: string;
  revision: string;
  approved: boolean;
  vector: readonly number[];
  excerpt: string;
}
export interface RetrievalHit {
  document: ReferenceDocument;
  score: number;
}
export interface CommandSpec {
  executable: string;
  args: readonly string[];
  cwd: string;
  purpose: string;
}
