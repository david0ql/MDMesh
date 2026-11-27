'use strict';
// DallyControl monitor: probes the service every minute, alerts (e-mail and/or webhook/Telegram) with debounce and a
// recovery notice, stores every probe, and serves the status page (/estado, /estado.json) with the monthly
// availability computed from the stored probes. No npm dependencies. Configuration: environment (docker-compose.yml,
// service `monitor`); documentation: docs/ops/MONITOREO.md.
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const net = require('net');
const tls = require('tls');
const { execFile } = require('child_process');
const lib = require('./lib');

const env = process.env;
const num = (v, d) => (v === undefined || v === '' || isNaN(Number(v)) ? d : Number(v));
const list = (v) => (v || '').split(',').map((x) => x.trim()).filter(Boolean);

const CFG = {
  name: env.MONITOR_SERVICE_NAME || 'DallyControl',
  intervalMs: Math.max(5, num(env.MONITOR_INTERVAL_SECONDS, 60)) * 1000,
  publicUrl: env.MONITOR_PUBLIC_URL || '',
  edgeUrl: env.MONITOR_EDGE_URL || '',
  serverUrl: env.MONITOR_SERVER_URL || '',
  supervisorUrl: env.MONITOR_SUPERVISOR_URL || '',
  tunnelUrls: list(env.MONITOR_TUNNEL_URLS),
  diskPaths: list(env.MONITOR_DISK_PATHS),
  diskWarn: num(env.MONITOR_DISK_WARN_PERCENT, 85),
  backupMaxAgeH: num(env.MONITOR_BACKUP_MAX_AGE_HOURS, 0),
  backupMarker: env.MONITOR_BACKUP_MARKER || '/backups/scheduled/last_success.json',
  devices: env.MONITOR_DEVICES !== '0',
  devicesOnlineMin: num(env.MONITOR_DEVICES_ONLINE_MINUTES, 30),
  fleet: { dropPercent: num(env.MONITOR_DEVICES_DROP_PERCENT, 40), minFleet: num(env.MONITOR_DEVICES_MIN_FLEET, 5) },
  debounce: {
    failAfter: Math.max(1, num(env.MONITOR_FAIL_AFTER, 2)),
    recoverAfter: Math.max(1, num(env.MONITOR_RECOVER_AFTER, 2)),
    reminderMs: num(env.MONITOR_REMINDER_MINUTES, 60) * 60000,
  },
  statusPublic: env.MONITOR_STATUS_PUBLIC !== '0',
  dataDir: env.MONITOR_DATA_DIR || '/var/lib/monitor',
  port: num(env.MONITOR_PORT, 9100),
  timeoutMs: num(env.MONITOR_TIMEOUT_SECONDS, 10) * 1000,
  mail: {
    to: list(env.ALERT_EMAIL_TO),
    from: env.ALERT_EMAIL_FROM || 'mdm@localhost',
    host: env.ALERT_SMTP_HOST || '',
    port: num(env.ALERT_SMTP_PORT, 25),
    user: env.ALERT_SMTP_USERNAME || '',
    pass: env.ALERT_SMTP_PASSWORD || '',
    tls: (env.ALERT_SMTP_TLS || 'auto').toLowerCase(),   // auto (STARTTLS when offered) | ssl (implicit TLS) | off
  },
  webhook: env.ALERT_WEBHOOK_URL || '',
  telegram: { token: env.ALERT_TELEGRAM_BOT_TOKEN || '', chat: env.ALERT_TELEGRAM_CHAT_ID || '' },
};

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---- probes ---------------------------------------------------------------------------------------------------------

/** GET url; ok when 2xx. Never throws: resolves {ok, ms, detail}. */
function httpProbe(url) {
  return new Promise((resolve) => {
    const started = Date.now();
    let done = false;
    const finish = (ok, detail) => { if (!done) { done = true; resolve({ ok, ms: Date.now() - started, detail }); } };
    let u;
    try { u = new URL(url); } catch (e) { finish(false, 'URL inválida'); return; }
    const mod = u.protocol === 'https:' ? https : http;
    const req = mod.get(u, { timeout: CFG.timeoutMs, headers: { 'User-Agent': 'dallycontrol-monitor' } }, (res) => {
      res.resume();
      res.on('end', () => finish(res.statusCode >= 200 && res.statusCode < 300, 'HTTP ' + res.statusCode));
      res.on('error', () => finish(false, 'respuesta interrumpida'));
    });
    req.on('timeout', () => { req.destroy(); finish(false, 'sin respuesta en ' + CFG.timeoutMs / 1000 + ' s'); });
    req.on('error', (e) => finish(false, e.code || e.message));
  });
}

function diskProbe(p) {
  const started = Date.now();
  try {
    const st = fs.statfsSync(p);
    const used = st.blocks ? Math.round(((st.blocks - st.bavail) / st.blocks) * 1000) / 10 : 0;
    return { ok: used < CFG.diskWarn, ms: Date.now() - started, detail: used + ' % usado (umbral ' + CFG.diskWarn + ' %)', value: used };
  } catch (e) {
    return { ok: false, ms: Date.now() - started, detail: 'no se pudo leer ' + p + ': ' + (e.code || e.message) };
  }
}

function backupProbe() {
  const started = Date.now();
  try {
    const m = JSON.parse(fs.readFileSync(CFG.backupMarker, 'utf8'));
    const ageH = (Date.now() - new Date(m.finishedAt).getTime()) / 3600000;
    const ok = ageH <= CFG.backupMaxAgeH && m.remoteOk !== false;
    let detail = 'último respaldo hace ' + ageH.toFixed(1) + ' h (máximo ' + CFG.backupMaxAgeH + ' h)';
    if (m.remoteOk === false) detail += '; la copia fuera del servidor falló';
    return { ok, ms: Date.now() - started, detail, value: Math.round(ageH * 10) / 10 };
  } catch (e) {
    return { ok: false, ms: Date.now() - started, detail: 'no hay registro de un respaldo correcto (' + (e.code || e.message) + ')' };
  }
}

function devicesProbe(state) {
  return new Promise((resolve) => {
    const started = Date.now();
    const cutoff = Date.now() - CFG.devicesOnlineMin * 60000;
    const sql = 'SELECT count(*), count(*) FILTER (WHERE lastUpdate >= ' + cutoff + ') FROM devices';
    execFile('psql', ['-X', '-At', '-F', ',', '-v', 'ON_ERROR_STOP=1', '-c', sql],
      { timeout: CFG.timeoutMs, env: Object.assign({}, env, { PGCONNECT_TIMEOUT: '5' }) }, (err, stdout) => {
        const ms = Date.now() - started;
        if (err) { resolve({ ok: null, ms, detail: 'no se pudo consultar la base de datos' }); return; }
        const [total, online] = stdout.trim().split(',').map(Number);
        const history = state.fleetHistory || (state.fleetHistory = []);
        const r = lib.fleetDrop(history.map((h) => h.online), online, total, CFG.fleet);
        // While the drop lasts the baseline stays frozen, so a long incident is not "learned" as normal.
        if (r.ok) {
          history.push({ t: Date.now(), online });
          while (history.length && Date.now() - history[0].t > 3600000) history.shift();
        }
        const pct = total ? Math.round((online / total) * 1000) / 10 : 0;
        let detail = online + ' de ' + total + ' equipos reportaron en los últimos ' + CFG.devicesOnlineMin + ' min (' + pct + ' %)';
        if (!r.ok) detail += '; hace menos de una hora eran ' + r.baseline + ': caída brusca';
        resolve({ ok: r.ok, ms, detail, value: pct });
      });
  });
}

/** The checks of this installation, from the configuration. `service: true` marks the one that defines availability. */
function buildChecks() {
  const checks = [];
  if (CFG.publicUrl) checks.push({ id: 'public', label: 'Acceso público', pub: 'Consola y API (acceso público)', run: () => httpProbe(CFG.publicUrl) });
  if (CFG.edgeUrl) checks.push({ id: 'edge', label: 'Servicio interno (borde + servidor + base de datos)', pub: 'Servidor y base de datos', run: () => httpProbe(CFG.edgeUrl) });
  if (CFG.serverUrl) checks.push({ id: 'server', label: 'Servidor (directo)', run: () => httpProbe(CFG.serverUrl) });
  if (CFG.supervisorUrl) checks.push({ id: 'supervisor', label: 'Supervisor (actualizaciones y recuperación)', run: () => httpProbe(CFG.supervisorUrl) });
  CFG.tunnelUrls.forEach((u, i) => checks.push({
    id: 'tunnel' + (i ? i + 1 : ''), label: 'Túnel Cloudflare' + (CFG.tunnelUrls.length > 1 ? ' (conector ' + (i + 1) + ')' : ''),
    pub: i ? undefined : 'Túnel de acceso', run: () => httpProbe(u),
  }));
  CFG.diskPaths.forEach((p) => checks.push({ id: 'disk:' + p, label: 'Espacio en disco ' + p, run: async () => diskProbe(p) }));
  if (CFG.backupMaxAgeH > 0) checks.push({ id: 'backup', label: 'Antigüedad del último respaldo', run: async () => backupProbe() });
  if (CFG.devices) checks.push({ id: 'devices', label: 'Equipos reportando', pub: 'Comunicación con los equipos', run: (state) => devicesProbe(state) });
  const service = checks.find((c) => c.id === 'public') || checks.find((c) => c.id === 'edge') || checks[0];
  if (service) service.service = true;
  return checks;
}

// ---- notifications --------------------------------------------------------------------------------------------------

function postJson(url, body) {
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(url); } catch (e) { reject(new Error('URL inválida')); return; }
    const data = Buffer.from(JSON.stringify(body));
    const mod = u.protocol === 'https:' ? https : http;
    const req = mod.request(u, { method: 'POST', timeout: 15000, headers: { 'Content-Type': 'application/json', 'Content-Length': data.length } }, (res) => {
      res.resume();
      res.on('end', () => (res.statusCode < 300 ? resolve() : reject(new Error('HTTP ' + res.statusCode))));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    req.end(data);
  });
}

/** Minimal SMTP client: EHLO, optional STARTTLS / implicit TLS, optional AUTH LOGIN, one message. */
function sendMail(subject, text) {
  const m = CFG.mail;
  return new Promise((resolve, reject) => {
    let sock = m.tls === 'ssl' ? tls.connect({ host: m.host, port: m.port, servername: m.host }) : net.connect({ host: m.host, port: m.port });
    let buf = '';
    let waiting = null;
    let finished = false;
    const fail = (e) => { if (!finished) { finished = true; try { sock.destroy(); } catch (_) { /* closed */ } reject(e); } };
    const timer = setTimeout(() => fail(new Error('SMTP: sin respuesta')), 30000);
    const onData = (d) => {
      buf += d.toString('utf8');
      // A reply ends with a line "NNN<space>..." (continuation lines are "NNN-...").
      const lines = buf.split('\r\n');
      for (let i = 0; i < lines.length - 1; i++) {
        if (/^\d{3} /.test(lines[i])) {
          const reply = lines.slice(0, i + 1).join('\n');
          buf = lines.slice(i + 1).join('\r\n');
          const w = waiting; waiting = null;
          if (w) w(reply);
          return;
        }
      }
    };
    const attach = () => { sock.on('data', onData); sock.on('error', fail); };
    const cmd = (line, expect) => new Promise((res, rej) => {
      waiting = (reply) => (expect.includes(reply.slice(0, 3)[0]) ? res(reply) : rej(new Error('SMTP: ' + reply.split('\n').pop())));
      if (line !== null) sock.write(line + '\r\n');
    });
    attach();
    (async () => {
      await cmd(null, '2');
      let ehlo = await cmd('EHLO dallycontrol-monitor', '2');
      if (m.tls !== 'ssl' && m.tls !== 'off' && /STARTTLS/i.test(ehlo)) {
        await cmd('STARTTLS', '2');
        sock.removeAllListeners('data');
        sock = tls.connect({ socket: sock, servername: m.host });
        await new Promise((res, rej) => { sock.once('secureConnect', res); sock.once('error', rej); });
        attach();
        ehlo = await cmd('EHLO dallycontrol-monitor', '2');
      }
      if (m.user) {
        await cmd('AUTH LOGIN', '3');
        await cmd(Buffer.from(m.user).toString('base64'), '3');
        await cmd(Buffer.from(m.pass).toString('base64'), '2');
      }
      await cmd('MAIL FROM:<' + m.from.replace(/^.*<|>.*$/g, '') + '>', '2');
      for (const rcpt of m.to) await cmd('RCPT TO:<' + rcpt + '>', '2');
      await cmd('DATA', '3');
      const body = lib.mailMessage(m.from, m.to, subject, text, Date.now()).replace(/\r\n\./g, '\r\n..');
      await cmd(body + '\r\n.', '2');
      sock.write('QUIT\r\n');
      finished = true;
      clearTimeout(timer);
      sock.end();
      resolve();
    })().catch((e) => { clearTimeout(timer); fail(e); });
  });
}

async function notify(subject, text) {
  const channels = [];
  if (CFG.mail.host && CFG.mail.to.length) channels.push(['correo', () => sendMail(subject, text)]);
  if (CFG.webhook) channels.push(['webhook', () => postJson(CFG.webhook, { text: subject + '\n' + text, title: subject, body: text, service: CFG.name })]);
  if (CFG.telegram.token && CFG.telegram.chat) {
    channels.push(['telegram', () => postJson('https://api.telegram.org/bot' + CFG.telegram.token + '/sendMessage',
      { chat_id: CFG.telegram.chat, text: subject + '\n' + text, disable_web_page_preview: true })]);
  }
  if (!channels.length) { log('ALERTA (sin canal configurado):', subject); return; }
  for (const [name, send] of channels) {
    try { await send(); log('alerta enviada por ' + name + ':', subject); } catch (e) { log('ERROR enviando alerta por ' + name + ':', e.message); }
  }
}

// ---- state + storage ------------------------------------------------------------------------------------------------

const STATE_FILE = path.join(CFG.dataDir, 'state.json');
function loadState() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch (e) { return { checks: {}, incidents: [], agg: { months: {}, days: {} }, last: {} }; }
}
function saveState(state) {
  const tmp = STATE_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(state));
  fs.renameSync(tmp, STATE_FILE);
}
function appendProbe(ts, results) {
  const line = { t: new Date(ts).toISOString() };
  for (const [id, r] of Object.entries(results)) line[id] = [r.ok === null ? null : (r.ok ? 1 : 0), r.ms];
  fs.appendFileSync(path.join(CFG.dataDir, 'checks-' + lib.monthKey(ts) + '.jsonl'), JSON.stringify(line) + '\n');
}
function pruneProbeFiles() {
  const files = fs.readdirSync(CFG.dataDir).filter((f) => /^checks-\d{4}-\d{2}\.jsonl$/.test(f)).sort();
  for (const f of files.slice(0, Math.max(0, files.length - 13))) fs.unlinkSync(path.join(CFG.dataDir, f));
}

// ---- the cycle ------------------------------------------------------------------------------------------------------

const checks = buildChecks();
const state = loadState();
let running = false;

async function cycle() {
  if (running) return;
  running = true;
  const now = Date.now();
  try {
    const results = {};
    await Promise.all(checks.map(async (c) => {
      try { results[c.id] = await c.run(state); } catch (e) { results[c.id] = { ok: false, ms: 0, detail: e.message }; }
    }));
    appendProbe(now, results);
    for (const c of checks) {
      const r = results[c.id];
      state.last[c.id] = { ok: r.ok, ms: r.ms, detail: r.detail, at: now };
      if (r.ok === null) continue;   // could not be evaluated (e.g. database down: the health checks report that)
      if (c.service) lib.record(state.agg, now, r.ok);
      const { state: next, event } = lib.step(state.checks[c.id], r.ok, now, CFG.debounce);
      state.checks[c.id] = next;
      if (event === 'down') {
        state.incidents.unshift({ id: c.id, label: c.label, pub: c.pub || null, service: !!c.service, start: next.since, end: null, detail: r.detail });
        await notify('[' + CFG.name + '] INCIDENTE: ' + c.label,
          'Estado: FALLA\nComprobación: ' + c.label + '\nDetalle: ' + r.detail + '\nDesde: ' + new Date(next.since).toLocaleString('es-CO') +
          '\nDetectado tras ' + next.failStreak + ' comprobaciones fallidas seguidas.\nRunbook: docs/ops/RUNBOOK.md');
      } else if (event === 'reminder') {
        await notify('[' + CFG.name + '] SIGUE EN FALLA: ' + c.label,
          'Estado: FALLA\nComprobación: ' + c.label + '\nDetalle: ' + r.detail + '\nDuración hasta ahora: ' + lib.duration(now - next.since));
      } else if (event === 'recovered') {
        const inc = state.incidents.find((i) => i.id === c.id && i.end === null);
        if (inc) inc.end = now - (CFG.debounce.recoverAfter - 1) * CFG.intervalMs;   // recovered at the first good probe
        const took = (inc ? inc.end : now) - next.downSince;
        await notify('[' + CFG.name + '] RECUPERADO: ' + c.label,
          'Estado: OPERATIVO\nComprobación: ' + c.label + '\nDuración del incidente: ' + lib.duration(took) +
          '\nInicio: ' + new Date(next.downSince).toLocaleString('es-CO') + '\nFin: ' + new Date(inc ? inc.end : now).toLocaleString('es-CO'));
      }
    }
    state.incidents = state.incidents.slice(0, 200);
    lib.prune(state.agg, 13, 90);
    state.updatedAt = now;
    saveState(state);
    if (new Date(now).getMinutes() === 0) pruneProbeFiles();
  } catch (e) {
    log('ERROR en el ciclo:', e.stack || e.message);
  } finally {
    running = false;
  }
}

// ---- status page ----------------------------------------------------------------------------------------------------

function statusModel() {
  const components = checks.filter((c) => c.pub).map((c) => {
    const st = state.checks[c.id];
    return { nombre: c.pub, estado: st && st.status === 'down' ? 'falla' : 'operativo', desde: st && st.since ? new Date(st.since).toISOString() : null };
  });
  const serviceCheck = checks.find((c) => c.service);
  const serviceDown = serviceCheck && state.checks[serviceCheck.id] && state.checks[serviceCheck.id].status === 'down';
  const anyDown = components.some((c) => c.estado === 'falla');
  const months = Object.keys(state.agg.months).sort().reverse().map((k) => ({
    mes: k, disponibilidad: lib.availability(state.agg.months[k]), comprobaciones: state.agg.months[k].total,
    minutosCaido: Math.round(((state.agg.months[k].total - state.agg.months[k].ok) * CFG.intervalMs) / 6000) / 10,
  }));
  const days = Object.keys(state.agg.days).sort().slice(-90).map((k) => ({ dia: k, disponibilidad: lib.availability(state.agg.days[k]) }));
  const incidents = state.incidents.filter((i) => i.pub).slice(0, 20).map((i) => ({
    componente: i.pub, inicio: new Date(i.start).toISOString(), fin: i.end ? new Date(i.end).toISOString() : null,
    duracion: lib.duration((i.end || Date.now()) - i.start),
  }));
  return {
    servicio: CFG.name,
    estado: serviceDown ? 'caida' : (anyDown ? 'degradado' : 'operativo'),
    actualizado: state.updatedAt ? new Date(state.updatedAt).toISOString() : null,
    intervaloSegundos: CFG.intervalMs / 1000,
    componentes: components, disponibilidadMensual: months, disponibilidadDiaria: days, incidentes: incidents,
  };
}

function statusHtml(m) {
  const e = lib.escapeHtml;
  const titles = { operativo: 'Todos los sistemas operativos', degradado: 'Servicio degradado', caida: 'Servicio interrumpido' };
  const fmt = (iso) => (iso ? new Date(iso).toLocaleString('es-CO', { dateStyle: 'medium', timeStyle: 'short' }) : '');
  const pct = (v) => (v === null ? 'sin datos' : v.toFixed(3).replace('.', ',') + ' %');
  const bars = m.disponibilidadDiaria.map((d) => {
    const cls = d.disponibilidad === null ? 'n' : d.disponibilidad >= 99.9 ? 'g' : d.disponibilidad >= 99 ? 'y' : 'r';
    return '<i class="' + cls + '" title="' + e(d.dia) + ': ' + e(pct(d.disponibilidad)) + '"></i>';
  }).join('');
  return '<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<meta http-equiv="refresh" content="60"><title>Estado de ' + e(m.servicio) + '</title><style>' +
    ':root{--bg:#f6f7f9;--fg:#16202b;--mut:#5b6875;--card:#fff;--line:#e2e6ea;--g:#1a8a4a;--y:#b7791f;--r:#c0392b;--n:#c9d0d6}' +
    '@media(prefers-color-scheme:dark){:root{--bg:#10151b;--fg:#e8edf2;--mut:#98a4b0;--card:#18202a;--line:#263241;--n:#34414f}}' +
    'body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}' +
    'main{max-width:760px;margin:0 auto;padding:24px 16px 48px}h1{font-size:22px;margin:0 0 16px}h2{font-size:16px;margin:28px 0 8px}' +
    '.banner{padding:16px;border-radius:8px;color:#fff;font-weight:600;font-size:18px}.operativo{background:var(--g)}.degradado{background:var(--y)}.caida{background:var(--r)}' +
    '.card{background:var(--card);border:1px solid var(--line);border-radius:8px}.row{display:flex;justify-content:space-between;gap:12px;padding:12px 16px;border-top:1px solid var(--line)}' +
    '.row:first-child{border-top:0}.ok{color:var(--g);font-weight:600}.bad{color:var(--r);font-weight:600}.mut{color:var(--mut);font-size:14px}' +
    '.bars{display:flex;gap:2px;padding:12px 16px;height:28px}.bars i{flex:1;border-radius:2px;min-width:2px}.bars .g{background:var(--g)}.bars .y{background:var(--y)}.bars .r{background:var(--r)}.bars .n{background:var(--n)}' +
    'td,th{text-align:left;padding:10px 16px;border-top:1px solid var(--line);font-variant-numeric:tabular-nums}th{border-top:0;font-size:14px;color:var(--mut);font-weight:600}table{width:100%;border-collapse:collapse}' +
    '</style></head><body><main><h1>Estado de ' + e(m.servicio) + '</h1>' +
    '<div class="banner ' + e(m.estado) + '">' + e(titles[m.estado]) + '</div>' +
    '<p class="mut">Última comprobación: ' + e(fmt(m.actualizado) || 'pendiente') + ' · se comprueba cada ' + e(m.intervaloSegundos) + ' s</p>' +
    '<h2>Componentes</h2><div class="card">' + m.componentes.map((c) =>
      '<div class="row"><span>' + e(c.nombre) + '</span><span class="' + (c.estado === 'operativo' ? 'ok' : 'bad') + '">' + (c.estado === 'operativo' ? 'Operativo' : 'En falla') + '</span></div>').join('') + '</div>' +
    '<h2>Disponibilidad de los últimos 90 días</h2><div class="card"><div class="bars">' + (bars || '<span class="mut">sin datos todavía</span>') + '</div></div>' +
    '<h2>Disponibilidad mensual</h2><div class="card"><table><tr><th>Mes</th><th>Disponibilidad</th><th>Tiempo sin servicio</th></tr>' +
    (m.disponibilidadMensual.map((x) => '<tr><td>' + e(x.mes) + '</td><td>' + e(pct(x.disponibilidad)) + '</td><td>' + e(String(x.minutosCaido).replace('.', ',')) + ' min</td></tr>').join('') ||
      '<tr><td colspan="3" class="mut">sin datos todavía</td></tr>') + '</table></div>' +
    '<h2>Incidentes recientes</h2><div class="card">' + (m.incidentes.map((i) =>
      '<div class="row"><span>' + e(i.componente) + '<br><span class="mut">' + e(fmt(i.inicio)) + (i.fin ? ' – ' + e(fmt(i.fin)) : ' – en curso') + '</span></span><span>' + e(i.duracion) + '</span></div>').join('') ||
      '<div class="row"><span class="mut">Sin incidentes registrados.</span></div>') + '</div>' +
    '<p class="mut">La disponibilidad se calcula con las comprobaciones automáticas guardadas (una cada ' + e(m.intervaloSegundos) + ' s).</p></main></body></html>';
}

const server = http.createServer((req, res) => {
  const url = (req.url || '').split('?')[0];
  if (url === '/healthz') { res.writeHead(200, { 'Content-Type': 'text/plain' }).end('ok'); return; }
  if (!CFG.statusPublic && (url.startsWith('/estado'))) { res.writeHead(404).end('not found'); return; }
  if (url === '/estado.json') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }).end(JSON.stringify(statusModel()));
    return;
  }
  if (url === '/estado' || url === '/estado/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' }).end(statusHtml(statusModel()));
    return;
  }
  // Operator detail (every check, with its last result). Internal network only: the edge does not route it.
  if (url === '/interno.json') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify({ checks: state.checks, last: state.last, incidents: state.incidents.slice(0, 50) }));
    return;
  }
  res.writeHead(404).end('not found');
});

if (require.main === module) {
  fs.mkdirSync(CFG.dataDir, { recursive: true });
  log('monitor de ' + CFG.name + ': ' + checks.length + ' comprobaciones cada ' + CFG.intervalMs / 1000 + ' s -> ' + checks.map((c) => c.id).join(', '));
  if (!(CFG.mail.host && CFG.mail.to.length) && !CFG.webhook && !CFG.telegram.token) log('AVISO: no hay canal de alertas configurado (ALERT_EMAIL_TO / ALERT_WEBHOOK_URL / ALERT_TELEGRAM_*)');
  server.listen(CFG.port, '0.0.0.0');
  cycle();
  setInterval(cycle, CFG.intervalMs);
  for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => { try { saveState(state); } catch (_) { /* best effort */ } process.exit(0); });
}

module.exports = { statusModel, statusHtml };
