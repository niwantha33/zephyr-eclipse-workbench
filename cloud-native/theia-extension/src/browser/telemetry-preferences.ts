// SPDX-License-Identifier: Apache-2.0
// Integration file for a future Theia/CDT.cloud application, not wired into Eclipse RCP.
// Theia 1.74+ already provides 'telemetry.telemetryLevel' = 'off' by default.
import { PreferenceScope, type PreferenceSchema } from '@theia/core/lib/common/preferences';

export const zewCloudPreferenceSchema: PreferenceSchema = {
  properties: {
    'zew.cloud.telemetry.buildMetricsEnabled': {
      type: 'boolean', default: false, scope: PreferenceScope.User,
      description: 'Allow locally aggregated Zephyr build metrics to be prepared for explicit review. Off by default; enabling does not transmit data.'
    },
    'zew.cloud.registry.endpoint': {
      type: 'string', default: '', scope: PreferenceScope.User,
      description: 'An organisation-approved HTTPS registry endpoint. Empty disables package discovery.'
    },
    'zew.cloud.remoteLab.enabled': {
      type: 'boolean', default: false, scope: PreferenceScope.User,
      description: 'Allow remote hardware lab discovery; no remote connection until a board is explicitly selected.'
    },
    'zew.cloud.ai.remoteRetrievalEnabled': {
      type: 'boolean', default: false, scope: PreferenceScope.User,
      description: 'Permit remote documentation retrieval on explicit request; project source is never uploaded implicitly.'
    }
  }
};
