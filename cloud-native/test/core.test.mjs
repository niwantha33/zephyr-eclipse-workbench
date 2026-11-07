// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { PrivateBuildTelemetry, MarketplacePlanner, LabLeaseRegistry,
         ApprovedDocumentRetriever, ZephyrVerificationPlanner } from '../dist/index.js';

const observation = {family:'mspm0',sdkVersion:'0.17.3',toolchain:'zephyr-sdk',
                     compilerVersion:'12.2.0',outcome:'success'};
test('telemetry requires consent and uses coarse, allowlisted fields only', async () => {
  let saved = false, sent;
  const engine = new PrivateBuildTelemetry(
    {read:async()=>saved,write:async v=>{saved=v;}},
    {transmit:async v=>{sent=v;}});
  await engine.initialize();
  engine.record(observation);
  assert.equal(engine.preview().counts.length, 0);
  await engine.setEnabled(true);
  for (let i=0;i<19;i++) engine.record(observation);
  assert.equal(await engine.flushAfterUserApproval(),false);
  engine.record(observation);
  assert.equal(await engine.flushAfterUserApproval(),true);
  assert.deepEqual(Object.keys(sent),['schemaVersion','counts']);
  assert.equal(sent.counts[0].sdkVersion,'0.17');
  assert.equal(sent.counts[0].compilerVersion,'12.2');
  await engine.setEnabled(false);
  assert.equal(engine.preview().counts.length,0);
  assert.equal(await engine.flushAfterUserApproval(),false);
});
test('telemetry fails closed on invalid free-text versions', async () => {
  const engine = new PrivateBuildTelemetry({read:async()=>true,write:async()=>{}},
                                            {transmit:async()=>{}});
  await engine.initialize();
  assert.throws(()=>engine.record({...observation,sdkVersion:'/home/alice/secret'}));
});
test('marketplace is plan-only and requires HTTPS and hashes', () => {
  const item={id:'modbus',kind:'zephyr-module',version:'1.2.3',license:'Apache-2.0',
    sourceUri:'https://example.org/modbus',sha256:'a'.repeat(64),dependencies:[]};
  const planner=new MarketplacePlanner();
  assert.equal(planner.plan(item,[]).status,'requires-review');
  assert.throws(()=>planner.plan({...item,sourceUri:'http://example.org'},[]));
  assert.throws(()=>planner.plan({...item,sha256:undefined},[]));
});
test('board leases require authorization, enforce exclusivity and expire', async () => {
  let now=1000;
  const leases=new LabLeaseRegistry({canUse:async user=>user==='allowed'},()=>now,()=>'lease-1');
  const board={boardId:'lab-1',family:'mspm0',runner:'openocd',available:true};
  await assert.rejects(leases.acquire('other',board,2000));
  await leases.acquire('allowed',board,2000);
  await assert.rejects(leases.acquire('allowed',board,2000));
  leases.verify('allowed','lab-1','lease-1');
  now=4000;
  assert.throws(()=>leases.verify('allowed','lab-1','lease-1'));
});
test('RAG only retrieves approved exact silicon reference documentation', () => {
  const base={id:'doc',title:'Manual',vendor:'TI',part:'MSPM0G3507',
    sourceUri:'https://vendor.example/manual',revision:'1.0',
    approved:true,vector:[1,0],excerpt:'ADC driver overview'};
  const rag=new ApprovedDocumentRetriever([base,{...base,id:'unapproved',approved:false}]);
  assert.deepEqual(rag.search([1,0],'TI','MSPM0G3507').map(x=>x.document.id),['doc']);
  assert.deepEqual(rag.search([1,0],'TI','STM32F4'),[]);
});
test('compliance plans upstream commands without executing or editing', () => {
  const planner=new ZephyrVerificationPlanner();
  const input={zephyrRoot:'/zephyr',workspaceRoot:'/ws',applicationRoot:'/ws/app',
    board:'lp_mspm0g3507',buildDirectory:'/tmp/build'};
  assert.equal(planner.plan(input).length,1);
  assert.deepEqual(planner.plan({...input,commitRange:'abcdef0..abcdef1'})[1].args.slice(-4),
    ['-p','1','-c','abcdef0..abcdef1']);
  assert.throws(()=>planner.plan({...input,board:'../evil'}));
  assert.throws(()=>planner.plan({...input,commitRange:'HEAD~1..HEAD'}));
});
