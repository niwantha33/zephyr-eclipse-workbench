// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { HttpsTelemetryTransport, VerificationCoordinator } from '../dist/index.js';

test('collector transport rejects non-HTTPS, unapproved origins and redirects', async () => {
  const allow = ['https://telemetry.example.org'];
  assert.throws(() => new HttpsTelemetryTransport('http://telemetry.example.org/v1/metrics/build', allow));
  assert.throws(() => new HttpsTelemetryTransport('https://other.example.org/v1/metrics/build', allow));
  let request;
  const transport = new HttpsTelemetryTransport(
    'https://telemetry.example.org/v1/metrics/build', allow,
    async (url, options) => {
      request = { url, options };
      return { ok: true, status: 200 };
    });
  await transport.transmit({schemaVersion:1,counts:[]});
  assert.equal(request.options.method, 'POST');
  assert.equal(request.options.redirect, 'error');
  assert.equal(request.options.credentials, 'omit');
  assert.equal(new URL(request.url).origin, allow[0]);
});
test('collector HTTP failures propagate to preserve pending aggregate', async () => {
  const transport = new HttpsTelemetryTransport('https://a.test/v1/metrics/build',
    ['https://a.test'], async () => ({ ok: false, status: 503 }));
  await assert.rejects(transport.transmit({schemaVersion:1, counts:[]}));
});
test('board verification never executes without explicit approval', async () => {
  let calls = 0;
  const coordinator = new VerificationCoordinator({run:async () => {
    calls++; return {exitCode:0,stdout:'ok',stderr:''};
  }});
  const cmds = [{executable:'west',args:['build'],cwd:'/ws',purpose:'Build firmware'}];
  const denied = await coordinator.execute(cmds,false);
  assert.equal(denied[0].outcome,'not-run');
  assert.equal(calls,0);
  const allowed = await coordinator.execute(cmds,true);
  assert.equal(allowed[0].outcome,'passed');
  assert.equal(calls,1);
});
test('verification does not present failed commands as passing', async () => {
  const coordinator = new VerificationCoordinator({run:async()=>({
    exitCode:1,stdout:'',stderr:'error: invalid devicetree'
  })});
  const rows = await coordinator.execute([{executable:'west',args:[],cwd:'/w',purpose:'build'}],true);
  assert.equal(rows[0].outcome,'failed');
  assert.match(rows[0].diagnostic,/devicetree/);
});
