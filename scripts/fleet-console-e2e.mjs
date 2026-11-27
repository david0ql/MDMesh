#!/usr/bin/env node
// Browser check (headless Chromium) of the fleet inventory screens of the console, against a running dev stack:
//
//   Dispositivos -> Exportar (Excel and CSV; everything / what the table shows / the selection), each downloaded
//                   file opened and checked; the "Dato de" column; "Actualizar ahora" (list and device page)
//   Reportes -> Inventario de la flota (tiles, thresholds, Excel/CSV export)
//   Reportes -> Envíos programados (create, edit, pause, resume, "Enviar ahora", delete, the no-SMTP notice)
//
//   npx -y -p playwright@1.48 node scripts/fleet-console-e2e.mjs --api http://localhost:8088 --device <number> \
//        [--live] [--mailpit http://localhost:8025 | --no-smtp]
//
// Folder administrator (a user limited to one folder, e.g. the one scripts/fleet-report-scope-e2e.py --keep-user
// leaves): only their devices may show up anywhere, and a schedule for "all devices" must be refused.
//
//   npx -y -p playwright@1.48 node scripts/fleet-console-e2e.mjs --api … --user e2e.carpeta --password-file FILE \
//        --device <one of theirs> --foreign-device <not theirs> --folder-user [--mailpit …]
//
// --live: the device has its live channel open (an enrolled emulator), so "Actualizar ahora" must bring fresh data.
// Playwright is not a dependency of the repo: run through npx as above (or set PLAYWRIGHT_DIR, or install
// scripts/shots). Chromium: `npx -y playwright@1.48 install chromium` once. The admin password comes from
// DALLYCONTROL_ADMIN_PASSWORD (default: the dev seed's). Exit 0 only if every check passed.
import { createRequire } from 'module';
import { execFileSync } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
function loadPlaywright() {
  const roots = [process.env.PLAYWRIGHT_DIR, path.join(here, 'shots')].filter(Boolean);
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    // `npx -p playwright` puts <cache>/node_modules/.bin on PATH
    if (dir.endsWith(path.join('node_modules', '.bin')) && fs.existsSync(path.join(dir, 'playwright'))) roots.push(path.dirname(path.dirname(dir)));
  }
  for (const r of roots) {
    try { return createRequire(path.join(r, 'package.json'))('playwright'); } catch { /* next */ }
  }
  console.error('Playwright not found: run with `npx -y -p playwright@1.48 node scripts/fleet-console-e2e.mjs …`');
  process.exit(2);
}
const { chromium } = loadPlaywright();

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const flag = (k) => process.argv.includes(k);
const api = arg('--api', 'http://localhost:8088');
const device = arg('--device');
const foreignDevice = arg('--foreign-device');
const user = arg('--user', 'admin');
const password = arg('--password-file') ? fs.readFileSync(arg('--password-file'), 'utf8').trim()
  : (process.env.DALLYCONTROL_ADMIN_PASSWORD || 'admin');
const mailpit = arg('--mailpit', '');
const noSmtp = flag('--no-smtp');
const live = flag('--live');
const folderUser = flag('--folder-user');
const out = arg('--out', fs.mkdtempSync(path.join(os.tmpdir(), 'dc-console-e2e-')));
if (!device) { console.error('--device is required'); process.exit(2); }
fs.mkdirSync(out, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  [${detail}]` : ''}`);
  ok ? pass++ : fail++;
  return !!ok;
};

// The "Número" column of a downloaded file (xlsx: sheet Dispositivos, read with python's zipfile; csv: parsed here).
const PY = `
import sys, json, zipfile, re, xml.etree.ElementTree as ET
NS={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main','r':'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}
z=zipfile.ZipFile(sys.argv[1])
sh=[''.join(t.text or '' for t in si.iter('{%s}t'%NS['m'])) for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si',NS)] if 'xl/sharedStrings.xml' in z.namelist() else []
rels={r.get('Id'):r.get('Target') for r in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
names=[s.get('name') for s in ET.fromstring(z.read('xl/workbook.xml')).find('m:sheets',NS)]
t=[rels[s.get('{%s}id'%NS['r'])] for s in ET.fromstring(z.read('xl/workbook.xml')).find('m:sheets',NS) if s.get('name')=='Dispositivos'][0]
rows=[]
for row in ET.fromstring(z.read(t.lstrip('/') if t.startswith('/') else 'xl/'+t)).iter('{%s}row'%NS['m']):
    c={}
    for x in row.findall('m:c',NS):
        col=0
        for ch in re.match(r'[A-Z]+',x.get('r')).group(0): col=col*26+ord(ch)-64
        v=x.find('m:v',NS)
        c[col-1]=sh[int(v.text)] if x.get('t')=='s' and v is not None else ''.join(q.text or '' for q in x.iter('{%s}t'%NS['m'])) if x.get('t')=='inlineStr' else (v.text if v is not None else '')
    rows.append([c.get(i,'') for i in range(max(c)+1 if c else 0)])
h=rows[0]
print(json.dumps({'sheets':names,'header':h,'numbers':[r[h.index('Número')] for r in rows[1:]],'first':dict(zip(h,rows[1])) if len(rows)>1 else {}}))
`;
const readXlsx = (file) => JSON.parse(execFileSync('python3', ['-c', PY, file], { encoding: 'utf8', maxBuffer: 64 << 20 }));
function readCsv(file, sep) {
  const buf = fs.readFileSync(file);
  const bom = buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf;
  const lines = buf.toString('utf8').replace(/^﻿/, '').split('\r\n').filter((l) => l !== '');
  const split = (l) => { // quoted fields may hold the separator
    const o = []; let cur = '', q = false;
    for (let i = 0; i < l.length; i++) {
      const ch = l[i];
      if (q) { if (ch === '"' && l[i + 1] === '"') { cur += '"'; i++; } else if (ch === '"') q = false; else cur += ch; }
      else if (ch === '"') q = true; else if (ch === sep) { o.push(cur); cur = ''; } else cur += ch;
    }
    o.push(cur); return o;
  };
  const header = split(lines[0]);
  const rows = lines.slice(1).map(split);
  return { bom, header, numbers: rows.map((r) => r[header.indexOf('Número')]), first: Object.fromEntries(header.map((h, i) => [h, rows[0]?.[i]])) };
}

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1680, height: 1000 }, acceptDownloads: true, locale: 'es-CO' });
// The pages are driven without outside network: web fonts are not needed and would hold the load event.
await ctx.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.abort());
const page = await ctx.newPage();
// --slow N multiplies every wait (a loaded machine is not a product failure).
const slow = Number(arg('--slow', '1')) || 1;
page.setDefaultTimeout(20000 * slow);
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('dialog', (d) => d.accept());

const toast = async (text, timeout = 45000 * slow) => {
  const t = page.locator('.toast', { hasText: text }).first();
  try { await t.waitFor({ timeout }); return (await t.innerText()).replace(/\s+/g, ' ').trim(); } catch { return null; }
};
const anyToast = async (timeout = 45000 * slow) => {
  try { const t = page.locator('.toast').last(); await t.waitFor({ timeout }); return (await t.innerText()).replace(/\s+/g, ' ').trim(); } catch { return null; }
};
const download = async (name, click) => {
  const [d] = await Promise.all([page.waitForEvent('download', { timeout: 120000 * slow }), click()]);
  const file = path.join(out, `${name}-${d.suggestedFilename()}`);
  await d.saveAs(file);
  return { file, suggested: d.suggestedFilename() };
};
const getJson = async (p) => (await (await ctx.request.get(api + p, { timeout: 30000 * slow })).json()).data;

try {
  // --- sign in -------------------------------------------------------------------------------------------------
  await page.goto(api + '/login', { waitUntil: 'domcontentloaded' });
  await page.locator('input[autocomplete="username"]').fill(user);
  await page.locator('input[type="password"]').fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 20000 * slow });
  check(`signed in as ${user}`, true);

  const summaries = await getJson('/rest/private/fleet/v1/devices/summary');
  const total = summaries.length;
  const visible = new Set(summaries.map((s) => s.number));
  check('the target device is one the user can see', visible.has(device));
  if (foreignDevice) check('the foreign device is not in what the API gives this user', !visible.has(foreignDevice));

  // --- Dispositivos ----------------------------------------------------------------------------------------------
  await page.goto(api + '/devices', { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: 'Vista de lista' }).click();
  await page.locator('.dev-row').first().waitFor();
  const exportBtn = page.locator('[data-testid="export-excel"]');
  check('Dispositivos: the Exportar button is there', await exportBtn.isVisible());

  // everything
  await exportBtn.click();
  await page.locator('.export-pop input[name="exp-scope"]').nth(0).check();
  await page.locator('.export-pop input[name="exp-format"]').nth(0).check();
  let d = await download('todo', () => page.locator('[data-testid="export-download"]').click());
  let x = readXlsx(d.file);
  check('Exportar todo (Excel): a .xlsx with the four sheets', d.suggested.endsWith('.xlsx') && x.sheets.join() === 'Resumen,Dispositivos,Carpetas,Conexiones', d.suggested);
  check(`Exportar todo (Excel): every device the user can see (${total})`, x.numbers.length === total && x.numbers.every((n) => visible.has(n)), String(x.numbers.length));
  for (const col of ['Batería %', 'Libre GB', 'Total GB', '% usado', 'Android', 'Estado', 'Red', 'Señal', 'SSID/Operador', 'Última muestra']) {
    if (!x.header.includes(col)) check(`Excel has the column ${col}`, false);
  }
  check('Excel has battery, storage, Android, connection and network columns', ['Batería %', 'Libre GB', 'Total GB', '% usado', 'Android', 'Estado', 'Red', 'Señal', 'SSID/Operador', 'Última muestra'].every((c) => x.header.includes(c)));
  if (foreignDevice) check('Exportar todo: the foreign device is not in the file', !x.numbers.includes(foreignDevice));

  // what the table shows (search)
  await page.locator('.dv-search input').fill(device);
  await page.waitForFunction((n) => { const r = document.querySelectorAll('.dev-row'); return r.length === 1 && r[0].textContent.includes(n); }, device, { timeout: 20000 * slow });
  const rowText = await page.locator('.dev-row').first().innerText();
  check('lista: the "Dato de" column shows the age of the data', /Dato de\s*(hace \d+ (min|h|d|mes|meses|año|años)|justo ahora)/i.test(rowText.replace(/\n/g, ' ')), rowText.replace(/\s+/g, ' ').slice(0, 160));
  await exportBtn.click();
  check('Exportar: with a search the default is "Lo que muestra la tabla (1)"',
    await page.locator('.export-pop input[name="exp-scope"]').nth(1).isChecked() && (await page.locator('.export-pop').innerText()).includes('Lo que muestra la tabla (1)'));
  await page.locator('.export-pop input[name="exp-format"]').nth(1).check();
  await page.locator('.export-pop select').selectOption(',');
  d = await download('filtrado', () => page.locator('[data-testid="export-download"]').click());
  let c = readCsv(d.file, ',');
  check('Exportar lo filtrado (CSV con coma): UTF-8 con BOM, only the filtered device', d.suggested.endsWith('.csv') && c.bom && c.numbers.length === 1 && c.numbers[0] === device, `${c.numbers.length} rows`);
  check('CSV: the device row has battery, storage, Android and state', ['Batería %', 'Libre GB', 'Android', 'Estado'].every((k) => (c.first[k] ?? '') !== ''), JSON.stringify(Object.fromEntries(['Batería %', 'Libre GB', 'Total GB', '% usado', 'Android', 'Estado', 'Red'].map((k) => [k, c.first[k]]))));

  // the selection
  await page.locator('.dev-row .dev-check').first().check();
  await page.locator('.bulk-bar').waitFor();
  await exportBtn.click();
  check('Exportar: with a selection the default is "Los seleccionados (1)"',
    await page.locator('.export-pop input[name="exp-scope"]').nth(2).isChecked() && (await page.locator('.export-pop').innerText()).includes('Los seleccionados (1)'));
  await page.locator('.export-pop input[name="exp-format"]').nth(0).check();
  d = await download('seleccion', () => page.locator('[data-testid="export-download"]').click());
  x = readXlsx(d.file);
  check('Exportar los seleccionados (Excel): only the selected device', x.numbers.length === 1 && x.numbers[0] === device, `${x.numbers.length} rows`);
  await exportBtn.click();
  await page.locator('.export-pop input[name="exp-format"]').nth(1).check();
  await page.locator('.export-pop select').selectOption(';');
  d = await download('seleccion', () => page.locator('[data-testid="export-download"]').click());
  c = readCsv(d.file, ';');
  check('Exportar los seleccionados (CSV con ;): only the selected device', c.numbers.length === 1 && c.numbers[0] === device);

  // "Actualizar ahora" on the selection
  const before = (await getJson('/rest/private/fleet/v1/devices/summary')).find((s) => s.number === device)?.stateAt ?? 0;
  await page.locator('.bulk-bar [data-testid="refresh-now"]').click();
  let t = await toast(live ? 'Datos actualizados' : 'Actualización solicitada', 50000);
  check(`lista: "Actualizar ahora" ${live ? 'brings fresh data' : 'says the device will report later'}`, !!t, t ?? (await anyToast(1000)) ?? 'no toast');
  if (live) {
    const after = (await getJson('/rest/private/fleet/v1/devices/summary')).find((s) => s.number === device)?.stateAt ?? 0;
    check('lista: the device data is newer than before the click', after > before, `${new Date(before).toISOString()} -> ${new Date(after).toISOString()}`);
  }
  if (foreignDevice) {
    await page.locator('.dv-search input').fill(foreignDevice);
    await page.locator('.empty', { hasText: 'Ningún dispositivo coincide' }).waitFor({ timeout: 20000 * slow }).catch(() => undefined);
    check('lista: searching the foreign device finds nothing', await page.locator('.dev-row').count() === 0);
  }

  // --- device page -----------------------------------------------------------------------------------------------
  await page.goto(`${api}/devices/${encodeURIComponent(device)}`, { waitUntil: 'domcontentloaded' });
  const line = page.locator('.refresh-line');
  await line.waitFor();
  const lineText = (await line.innerText()).replace(/\s+/g, ' ');
  check('detalle: shows "Datos de hace …"', /Datos de (hace \d+|justo ahora)/.test(lineText), lineText);
  await page.waitForTimeout(600);
  await line.locator('[data-testid="refresh-now"]').click();
  t = await toast(live ? 'Datos actualizados' : 'Actualización solicitada', 50000);
  check(`detalle: "Actualizar ahora" ${live ? 'reports how long the fresh data took' : 'says the device will report later'}`, !!t && (!live || /reportó en \d+ s/.test(t)), t ?? (await anyToast(1000)) ?? 'no toast');

  // --- Reportes -> Inventario de la flota ----------------------------------------------------------------------------
  await page.goto(api + '/reports', { waitUntil: 'domcontentloaded' });
  await page.locator('.tabs button', { hasText: 'Inventario de la flota' }).click();
  const inv = page.locator('[data-testid="inventory-report"]');
  await inv.locator('.inv-tile').first().waitFor({ timeout: 60000 * slow });
  const tile = async (label) => Number((await inv.locator('.inv-tile', { hasText: label }).locator('b').innerText()).replace(/\D/g, ''));
  check(`Inventario: the "Dispositivos" tile counts what the user can see (${total})`, await tile('Dispositivos') === total, String(await tile('Dispositivos')));
  check('Inventario: breakdowns by Android, model and agent are shown', await inv.locator('.inv-breakdown').count() === 3 && await inv.locator('.inv-bar-row').count() >= 3);
  const full90 = await tile('Almacenamiento ≥ 90 % usado');
  await inv.locator('label', { hasText: 'Almacenamiento casi lleno' }).locator('input').fill('1');
  await inv.getByRole('button', { name: 'Actualizar' }).click();
  await inv.locator('.inv-tile', { hasText: 'Almacenamiento ≥ 1 % usado' }).waitFor({ timeout: 60000 * slow });
  const full1 = await tile('Almacenamiento ≥ 1 % usado');
  check('Inventario: changing the storage threshold recounts the alert', full1 > full90 && full1 > 0, `≥90 %: ${full90}, ≥1 %: ${full1}`);
  await inv.locator('.inv-tile', { hasText: 'Almacenamiento ≥ 1 % usado' }).click();
  await page.waitForTimeout(300);
  const title = (await inv.locator('.report-title').innerText()).replace(/\s+/g, ' ');
  check('Inventario: clicking a tile filters the table', title.includes(`${full1} de ${total} equipos`), title);
  if (foreignDevice) check('Inventario: the foreign device is not on the page', !(await inv.innerText()).includes(foreignDevice));
  d = await download('inventario', () => inv.locator('.report-export').getByRole('button', { name: 'Excel' }).click());
  x = readXlsx(d.file);
  check('Inventario: Excel export has every visible device', d.suggested.endsWith('.xlsx') && x.numbers.length === total && x.numbers.every((n) => visible.has(n)), `${d.suggested}: ${x.numbers.length}`);
  await inv.locator('.report-export select').selectOption('|');
  d = await download('inventario', () => inv.locator('.report-export').getByRole('button', { name: 'CSV' }).click());
  c = readCsv(d.file, '|');
  check('Inventario: CSV export (separator |) has every visible device', d.suggested.endsWith('.csv') && c.bom && c.numbers.length === total && c.header[0] === 'Nombre', `${c.numbers.length}`);

  // --- Reportes -> Envíos programados ----------------------------------------------------------------------------
  await page.locator('.tabs button', { hasText: 'Envíos programados' }).click();
  const sch = page.locator('[data-testid="report-schedules"]');
  await sch.locator('[data-testid="schedule-new"]').waitFor();
  await page.waitForTimeout(800);
  const banner = await sch.locator('[data-testid="smtp-missing"]').count();
  check(noSmtp ? 'Envíos: the "no SMTP" notice is shown' : 'Envíos: no "no SMTP" notice when SMTP is configured', noSmtp ? banner === 1 : banner === 0);
  const name1 = `e2e navegador ${Date.now()}`;
  const form = page.locator('.sched-form');
  const row = (n) => sch.locator('tbody tr', { hasText: n });
  await sch.locator('[data-testid="schedule-new"]').click();
  await form.locator('label', { hasText: 'Nombre' }).locator('input').fill(name1);
  await form.locator('label', { hasText: 'Destinatarios' }).locator('input').fill('navegador@example.com');
  const folderOptions = await form.locator('label', { hasText: 'Carpeta' }).locator('option').allInnerTexts();
  if (folderUser) {
    // "Todos los dispositivos" must be refused for a folder administrator
    await page.locator('[data-testid="schedule-save"]').click();
    t = await toast('No se pudo guardar');
    check('Envíos (carpeta): a schedule for "Todos los dispositivos" is refused', !!t && t.includes('Elige una de tus carpetas'), t ?? '');
    const all = (await getJson('/rest/private/fleet/v1/groups')).groups.map((g) => g.name);
    check('Envíos (carpeta): the folder list offers only their folders', folderOptions.length === all.length + 1, `${folderOptions.length - 1} of ${all.length}`);
    await form.locator('label', { hasText: 'Carpeta' }).locator('select').selectOption({ index: 1 });
  }
  await form.locator('label', { hasText: 'Frecuencia' }).locator('select').selectOption('weekly');
  await form.locator('label', { hasText: 'Día de la semana' }).locator('select').selectOption('5');
  await form.locator('label', { hasText: 'Formato' }).locator('select').selectOption('csv');
  await page.locator('[data-testid="schedule-save"]').click();
  await row(name1).waitFor();
  const r1 = (await row(name1).innerText()).replace(/\s+/g, ' ');
  check('Envíos: created (weekly, Friday, CSV) and listed with its next run', r1.includes('Cada viernes a las 07:00') && r1.includes('CSV') && r1.includes('navegador@example.com'), r1.slice(0, 200));

  const name2 = name1 + ' editado';
  await row(name1).getByRole('button', { name: 'Editar' }).click();
  await form.locator('label', { hasText: 'Nombre' }).locator('input').fill(name2);
  await form.locator('label', { hasText: 'Frecuencia' }).locator('select').selectOption('monthly');
  await form.locator('label', { hasText: 'Día del mes' }).locator('input').fill('15');
  await form.locator('label', { hasText: 'Formato' }).locator('select').selectOption('xlsx');
  await page.locator('[data-testid="schedule-save"]').click();
  await row(name2).waitFor();
  const r2 = (await row(name2).innerText()).replace(/\s+/g, ' ');
  check('Envíos: edited (name, monthly on the 15th, Excel)', r2.includes('El día 15 de cada mes') && r2.includes('Excel'), r2.slice(0, 160));

  await row(name2).getByRole('button', { name: 'Pausar' }).click();
  await row(name2).locator('.chip', { hasText: 'En pausa' }).waitFor();
  check('Envíos: paused shows "En pausa"', true);
  await row(name2).getByRole('button', { name: 'Reanudar' }).click();
  await row(name2).getByRole('button', { name: 'Pausar' }).waitFor();
  check('Envíos: resumed', await row(name2).locator('.chip', { hasText: 'En pausa' }).count() === 0);

  if (mailpit) await fetch(`${mailpit}/api/v1/messages`, { method: 'DELETE' });
  await row(name2).getByRole('button', { name: 'Enviar ahora' }).click();
  if (noSmtp) {
    t = await toast('El servidor no tiene correo configurado', 60000);
    check('Envíos: "Enviar ahora" without SMTP says so (no error page)', !!t, t ?? (await anyToast(1000)) ?? '');
    await row(name2).locator('.sched-status', { hasText: 'Sin SMTP' }).waitFor({ timeout: 10000 * slow }).catch(() => undefined);
    check('Envíos: the row records "Sin SMTP"', await row(name2).locator('.sched-status', { hasText: 'Sin SMTP' }).count() === 1);
  } else {
    t = await toast('Reporte enviado', 90000);
    check('Envíos: "Enviar ahora" reports the e-mail as sent', !!t, t ?? (await anyToast(1000)) ?? '');
    if (mailpit) {
      let m = null;
      for (let i = 0; i < 15 && !m; i++) {
        const j = await (await fetch(`${mailpit}/api/v1/messages?limit=20`)).json();
        m = (j.messages || []).find((q) => q.Subject.includes(name2));
        if (!m) await new Promise((r) => setTimeout(r, 2000));
      }
      if (check('Envíos: the e-mail reached the test SMTP', !!m)) {
        const full = await (await fetch(`${mailpit}/api/v1/message/${m.ID}`)).json();
        const att = full.Attachments?.[0];
        const file = path.join(out, `correo-${att.FileName}`);
        fs.writeFileSync(file, Buffer.from(await (await fetch(`${mailpit}/api/v1/message/${m.ID}/part/${att.PartID}`)).arrayBuffer()));
        const a = readXlsx(file);
        check('Envíos: the Excel attachment has exactly the devices the user can see', a.numbers.length > 0 && a.numbers.every((n) => visible.has(n))
          && (!folderUser || a.numbers.length === total), `${a.numbers.length} rows`);
      }
    }
    await row(name2).locator('.sched-status.ok').waitFor({ timeout: 15000 * slow }).catch(() => undefined);
    check('Envíos: the row records the send', await row(name2).locator('.sched-status.ok').count() === 1);
  }
  await row(name2).getByRole('button', { name: 'Eliminar' }).click();
  await row(name2).waitFor({ state: 'detached' });
  check('Envíos: deleted', await row(name2).count() === 0);

  check('no JavaScript errors on the pages', errors.length === 0, errors.slice(0, 2).join(' | '));
} catch (e) {
  check('the run completed', false, String(e).slice(0, 400));
  await page.screenshot({ path: path.join(out, 'fallo.png') }).catch(() => undefined);
} finally {
  await browser.close();
}
console.log(`files kept in ${out}`);
console.log(`RESULT: PASS=${pass} FAIL=${fail}`);
process.exit(fail === 0 && pass > 0 ? 0 : 1);
