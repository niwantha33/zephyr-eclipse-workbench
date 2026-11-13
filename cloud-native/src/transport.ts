// SPDX-License-Identifier: Apache-2.0
import type { TelemetrySummary, TelemetryTransport } from './contracts.js';

// Deliberately not instantiated by default. Requires explicit administrator
// endpoint configuration, consent and a separate user-approved flush call.
export class HttpsTelemetryTransport implements TelemetryTransport {
  readonly endpoint: string;
  constructor(endpoint: string,
              private readonly allowedOrigins: readonly string[],
              private readonly request: typeof fetch = fetch) {
    const url = new URL(endpoint);
    if (url.protocol !== 'https:' || url.username || url.password ||
        !allowedOrigins.includes(url.origin) || url.hash || url.search ||
        url.pathname !== '/v1/metrics/build') {
      throw new Error('Telemetry endpoint must be a pinned HTTPS collector origin and route');
    }
    this.endpoint = url.toString();
  }
  async transmit(payload: TelemetrySummary): Promise<void> {
    if (payload.schemaVersion !== 1 || !Array.isArray(payload.counts)) {
      throw new Error('Unsupported telemetry schema');
    }
    const body = JSON.stringify(payload);
    if (body.length > 16_384) throw new Error('Telemetry batch exceeds size limit');
    const response = await this.request(this.endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'accept': 'application/json' },
      body,
      redirect: 'error',
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      signal: AbortSignal.timeout(10_000)
    });
    if (!response.ok) throw new Error('Collector rejected telemetry HTTP ' + response.status);
  }
}
