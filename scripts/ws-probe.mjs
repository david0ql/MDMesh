#!/usr/bin/env node
// Holds an agent wake WebSocket open the way the Android agent does (Authorization: Bearer <device secret>,
// reconnect with capped exponential backoff: 1 s, 2 s, 4 s … 60 s, reset after 30 s of stable connection) and
// reports every disconnection and how long the device was without its wake channel. Used to measure what a
// deployment does to connected devices. No dependencies.
//
//   node scripts/ws-probe.mjs <base-url> <deviceNumber> <deviceSecret> [seconds]
//   node scripts/ws-probe.mjs http://localhost:8107 TEST-001 s3cret 300
import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';

const [base, device, secret, secondsArg] = process.argv.slice(2);
if (!base || !device || !secret) {
  console.error('usage: ws-probe.mjs <base-url> <deviceNumber> <deviceSecret> [seconds]');
  process.exit(2);
}
const seconds = Number(secondsArg || 60);
const url = new URL('/agent/ws/' + encodeURIComponent(device), base);
const mod = url.protocol === 'https:' ? https : http;
const t0 = Date.now();
const rel = () => ((Date.now() - t0) / 1000).toFixed(1).padStart(6) + ' s';

let attempt = 0;
let connectedAt = 0;
let lostAt = 0;
let drops = 0;
let longestGap = 0;
let totalGap = 0;
let everConnected = false;
let stopping = false;

function connect() {
  const req = mod.request(url, {
    headers: {
      Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13',
      'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64'), Authorization: 'Bearer ' + secret,
    },
    timeout: 10000,
  });
  let done = false;
  const failed = (why) => {
    if (done || stopping) return;
    done = true;
    if (!lostAt) lostAt = Date.now();
    schedule(why);
  };
  req.on('upgrade', (res, socket) => {
    done = true;
    connectedAt = Date.now();
    if (lostAt) {
      const gap = connectedAt - lostAt;
      totalGap += gap;
      longestGap = Math.max(longestGap, gap);
      console.log(rel() + '  reconectado tras ' + (gap / 1000).toFixed(1) + ' s sin canal (' + attempt + ' intento(s))');
      lostAt = 0;
    } else {
      console.log(rel() + '  conectado');
    }
    everConnected = true;
    // The server closes an unauthorized socket right after the handshake: tell that apart from a real session.
    let closedEarly = true;
    setTimeout(() => { closedEarly = false; }, 1500);
    // Frames are not decoded, except the two that matter: close (0x8: the server ends the session and waits for our
    // close, so end it) and ping (0x9: answer with an empty masked pong so the session is kept).
    socket.on('data', (d) => {
      const opcode = d[0] & 0x0f;
      if (opcode === 0x8) socket.destroy();
      else if (opcode === 0x9 && (d[1] & 0x7f) === 0) socket.write(Buffer.from([0x8a, 0x80, 1, 2, 3, 4]));
    });
    socket.on('error', () => { /* the close event follows */ });
    socket.on('close', () => {
      if (stopping) return;
      if (closedEarly) console.log(rel() + '  el servidor cerró al instante (¿secreto del equipo inválido?)');
      drops += 1;
      lostAt = Date.now();
      // Like the agent: the backoff resets only after a stable session (>= 30 s).
      if (Date.now() - connectedAt >= 30000) attempt = 0;
      console.log(rel() + '  DESCONECTADO (la conexión duró ' + ((Date.now() - connectedAt) / 1000).toFixed(1) + ' s)');
      schedule('cierre');
    });
  });
  req.on('response', (res) => { res.resume(); failed('HTTP ' + res.statusCode); });
  req.on('timeout', () => { req.destroy(); failed('sin respuesta'); });
  req.on('error', (e) => failed(e.code || e.message));
  req.end();
}

function schedule(why) {
  const delay = Math.min(60000, 1000 * 2 ** Math.min(attempt, 5));
  attempt += 1;
  if (why !== 'cierre') console.log(rel() + '  intento fallido (' + why + '); reintento en ' + delay / 1000 + ' s');
  setTimeout(connect, delay);
}

connect();
setTimeout(() => {
  stopping = true;
  if (lostAt) { const gap = Date.now() - lostAt; totalGap += gap; longestGap = Math.max(longestGap, gap); }
  console.log('RESULTADO WebSocket ' + url.href + ': ' + (everConnected ? '' : 'NUNCA CONECTÓ; ') + drops + ' desconexión(es), mayor tiempo sin canal ' +
    (longestGap / 1000).toFixed(1) + ' s, total sin canal ' + (totalGap / 1000).toFixed(1) + ' s en ' + seconds + ' s');
  process.exit(everConnected ? 0 : 1);
}, seconds * 1000);
