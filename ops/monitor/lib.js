'use strict';
// Pure logic of the monitor (no I/O), so it can be unit-tested: ops/monitor/test.js.

/**
 * Debounced state of one check. A check must fail `failAfter` times in a row before it is "down" (one lost probe
 * is not an incident) and pass `recoverAfter` times in a row before it is "ok" again (no flapping alerts).
 * Returns the new state and the event to notify: null | 'down' | 'recovered' | 'reminder'.
 */
function step(prev, ok, now, cfg) {
  const s = Object.assign({ status: 'ok', failStreak: 0, okStreak: 0, since: null, firstFailAt: null, lastAlertAt: null }, prev);
  let event = null;
  if (ok) {
    s.okStreak += 1;
    s.failStreak = 0;
    if (s.status === 'down') {
      if (s.okStreak >= cfg.recoverAfter) {
        event = 'recovered';
        s.downSince = s.since;
        s.status = 'ok';
        s.since = now;
        s.lastAlertAt = null;
      }
    } else {
      s.firstFailAt = null;
    }
  } else {
    if (s.failStreak === 0) s.firstFailAt = s.status === 'down' ? s.firstFailAt : now;
    s.failStreak += 1;
    s.okStreak = 0;
    if (s.status === 'ok') {
      if (s.failStreak >= cfg.failAfter) {
        event = 'down';
        s.status = 'down';
        s.since = s.firstFailAt || now;   // the incident started at the first failed probe, not at the alert
        s.lastAlertAt = now;
      }
    } else if (cfg.reminderMs > 0 && s.lastAlertAt != null && now - s.lastAlertAt >= cfg.reminderMs) {
      event = 'reminder';
      s.lastAlertAt = now;
    }
  }
  return { state: s, event };
}

/** "2026-11" for a timestamp, in the process time zone. */
function monthKey(ts) {
  const d = new Date(ts);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}
function dayKey(ts) {
  const d = new Date(ts);
  return monthKey(ts) + '-' + String(d.getDate()).padStart(2, '0');
}

/** Adds one probe cycle to the per-month and per-day counters ({total, ok}) of the service. */
function record(agg, ts, ok) {
  const out = agg || { months: {}, days: {} };
  for (const [bucket, key] of [[out.months, monthKey(ts)], [out.days, dayKey(ts)]]) {
    const b = bucket[key] || (bucket[key] = { total: 0, ok: 0 });
    b.total += 1;
    if (ok) b.ok += 1;
  }
  return out;
}

/** Keeps the last `months` months and `days` days. */
function prune(agg, months, days) {
  for (const [bucket, keep] of [[agg.months, months], [agg.days, days]]) {
    const keys = Object.keys(bucket).sort();
    for (const k of keys.slice(0, Math.max(0, keys.length - keep))) delete bucket[k];
  }
  return agg;
}

/** Availability in percent with 3 decimals, or null with no data. Probes not run (monitor down) are not counted. */
function availability(bucket) {
  if (!bucket || !bucket.total) return null;
  return Math.round((bucket.ok / bucket.total) * 100000) / 1000;
}

/** Minutes of downtime a monthly availability target allows (30-day month). */
function allowedDowntimeMinutes(targetPercent, days) {
  return Math.round((1 - targetPercent / 100) * (days || 30) * 24 * 60 * 10) / 10;
}

/**
 * Sudden drop of devices that reported recently = a communication incident (the fleet cannot reach the server),
 * even when every health probe is green. `history` holds the recent online counts (about one hour); the baseline is
 * their maximum. Fails when the fleet is big enough to judge and the count fell by `dropPercent` or more.
 */
function fleetDrop(history, online, total, cfg) {
  const baseline = history.length ? Math.max.apply(null, history) : online;
  if (total < cfg.minFleet || baseline < cfg.minFleet) {
    return { ok: true, baseline, judged: false };
  }
  const floor = baseline * (1 - cfg.dropPercent / 100);
  return { ok: online >= floor, baseline, judged: true };
}

function duration(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return s + ' s';
  const m = Math.floor(s / 60);
  if (m < 60) return m + ' min ' + (s % 60) + ' s';
  return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
}

function escapeHtml(v) {
  return String(v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** RFC 5322 message for an alert (UTF-8, base64 body). */
function mailMessage(from, to, subject, text, now) {
  const b64 = Buffer.from(text, 'utf8').toString('base64').replace(/(.{76})/g, '$1\r\n');
  return [
    'From: ' + from,
    'To: ' + to.join(', '),
    'Subject: =?UTF-8?B?' + Buffer.from(subject, 'utf8').toString('base64') + '?=',
    'Date: ' + new Date(now).toUTCString().replace('GMT', '+0000'),
    'Message-ID: <' + now + '.' + Math.random().toString(36).slice(2) + '@dallycontrol-monitor>',
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset=UTF-8',
    'Content-Transfer-Encoding: base64',
    '',
    b64,
  ].join('\r\n');
}

module.exports = { step, monthKey, dayKey, record, prune, availability, allowedDowntimeMinutes, fleetDrop, duration, escapeHtml, mailMessage };
