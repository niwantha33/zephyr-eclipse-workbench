// SPDX-License-Identifier: Apache-2.0
import type { InstallPlan, RegistryItem } from './contracts.js';

const safeId = /^[a-z0-9][a-z0-9._-]{1,127}$/;
const pinnedVersion = /^[0-9]+\.[0-9]+\.[0-9]+(?:[-.][a-zA-Z0-9.-]+)?$/;
const digest = /^[a-f0-9]{64}$/;
export class MarketplacePlanner {
  plan(item: RegistryItem, installedIds: readonly string[]): InstallPlan {
    if (!safeId.test(item.id) || !pinnedVersion.test(item.version)) {
      throw new Error('Invalid or unpinned package identity');
    }
    if (!item.license.trim()) throw new Error('Licence review required');
    const url = new URL(item.sourceUri);
    if (url.protocol !== 'https:' || url.username || url.password) {
      throw new Error('Registry source must use HTTPS without embedded credentials');
    }
    if (!item.sha256 || !digest.test(item.sha256)) {
      throw new Error('Immutable SHA-256 artifact digest required');
    }
    for (const dependency of item.dependencies) {
      if (!safeId.test(dependency)) throw new Error('Unsafe dependency identifier');
    }
    if (item.kind === 'theia-extension' &&
        (item.kconfigFragments?.length || item.cmakeAdditions?.length)) {
      throw new Error('Editor extensions cannot modify Zephyr build inputs');
    }
    // These are review notes, not automatic writes. West module resolution is a separate adapter.
    const missing = item.dependencies.filter(id => !installedIds.includes(id));
    const actions = [
      'Review publisher, licence, hash, dependency graph and workspace trust',
      'Resolve ' + missing.length + ' missing dependencies from the allowlisted registry',
      'Download to quarantine and verify signature/hash before any install',
      'Preview changes to West manifest, prj.conf and CMakeLists.txt',
      'Require explicit confirmation; use atomic writes with rollback'
    ];
    return { item, actions, status: 'requires-review' };
  }
}
