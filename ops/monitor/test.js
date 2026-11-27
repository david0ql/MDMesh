'use strict';
// Unit tests of the monitor's logic. Run: node ops/monitor/test.js
const assert = require('assert');
const lib = require('./lib');

const cfg = { failAfter: 2, recoverAfter: 2, reminderMs: 60 * 60000 };
let n = 0;
function test(name, fn) { fn(); n += 1; console.log('ok - ' + name); }

test('one lost probe is not an incident', () => {
  let r = lib.step(undefined, false, 1000, cfg);
  assert.strictEqual(r.event, null);
  r = lib.step(r.state, true, 2000, cfg);
  assert.strictEqual(r.event, null);
  assert.strictEqual(r.state.status, 'ok');
});

test('down after N failures, dated at the first failed probe; one alert only', () => {
  let r = lib.step(undefined, false, 1000, cfg);
  r = lib.step(r.state, false, 2000, cfg);
  assert.strictEqual(r.event, 'down');
  assert.strictEqual(r.state.since, 1000);
  r = lib.step(r.state, false, 3000, cfg);
  assert.strictEqual(r.event, null);
});

test('recovery needs N passes and is announced once', () => {
  let r = lib.step(undefined, false, 1000, cfg);
  r = lib.step(r.state, false, 2000, cfg);
  r = lib.step(r.state, true, 3000, cfg);
  assert.strictEqual(r.event, null);
  r = lib.step(r.state, false, 4000, cfg);   // flap: still the same incident, no new alert
  assert.strictEqual(r.event, null);
  assert.strictEqual(r.state.status, 'down');
  r = lib.step(r.state, true, 5000, cfg);
  r = lib.step(r.state, true, 6000, cfg);
  assert.strictEqual(r.event, 'recovered');
  assert.strictEqual(r.state.downSince, 1000);
  r = lib.step(r.state, true, 7000, cfg);
  assert.strictEqual(r.event, null);
});

test('reminder while it stays down', () => {
  let r = lib.step(undefined, false, 0, cfg);
  r = lib.step(r.state, false, 1000, cfg);
  r = lib.step(r.state, false, 1000 + 59 * 60000, cfg);
  assert.strictEqual(r.event, null);
  r = lib.step(r.state, false, 1000 + 60 * 60000, cfg);
  assert.strictEqual(r.event, 'reminder');
});

test('monthly availability from the stored probes', () => {
  let agg;
  const t0 = new Date(2026, 10, 1, 0, 0, 0).getTime();
  for (let i = 0; i < 43200; i++) agg = lib.record(agg, t0 + i * 60000, i >= 43);   // 43 minutes down in a 30-day month
  assert.strictEqual(agg.months['2026-11'].total, 43200);
  assert.strictEqual(lib.availability(agg.months['2026-11']), 99.9);
  assert.strictEqual(lib.availability(agg.days['2026-11-02']), 100);
  assert.strictEqual(lib.availability(undefined), null);
  assert.strictEqual(lib.allowedDowntimeMinutes(99.9), 43.2);
  assert.strictEqual(lib.allowedDowntimeMinutes(99.5), 216);
});

test('prune keeps the newest buckets', () => {
  let agg;
  for (let m = 0; m < 15; m++) agg = lib.record(agg, new Date(2025, m, 5).getTime(), true);
  lib.prune(agg, 13, 5);
  assert.strictEqual(Object.keys(agg.months).length, 13);
  assert.strictEqual(Object.keys(agg.days).length, 5);
  assert.ok(agg.months['2026-03']);
});

test('fleet drop: sudden fall is an incident, small fleets are not judged', () => {
  const f = { minFleet: 5, dropPercent: 40 };
  assert.strictEqual(lib.fleetDrop([100, 98, 99], 97, 120, f).ok, true);
  assert.strictEqual(lib.fleetDrop([100, 98, 99], 59, 120, f).ok, false);
  assert.strictEqual(lib.fleetDrop([100, 98, 99], 60, 120, f).ok, true);
  assert.strictEqual(lib.fleetDrop([3, 3], 0, 3, f).judged, false);
  assert.strictEqual(lib.fleetDrop([], 0, 0, f).ok, true);
});

test('mail message is valid for non-ASCII text', () => {
  const m = lib.mailMessage('a@x', ['b@x', 'c@x'], 'CAÍDA: señal', 'línea\nsegunda', 0);
  assert.ok(m.includes('To: b@x, c@x'));
  assert.ok(m.includes('Subject: =?UTF-8?B?'));
  assert.ok(!/[^\x00-\x7f]/.test(m));
  assert.strictEqual(lib.duration(125000), '2 min 5 s');
  assert.strictEqual(lib.escapeHtml('<a>&'), '&lt;a&gt;&amp;');
});

console.log(n + ' tests passed');
