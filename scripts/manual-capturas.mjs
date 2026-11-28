// Capturas de pantalla de la consola para los manuales (docs/manuales/src/img), en un Chromium sin ventana contra un
// stack de desarrollo con datos de demostración. Solo datos ficticios: nunca se ejecuta contra una instalación real.
//
//   docker compose --env-file docker/dev.env up -d --build && scripts/dev-seed.sh
//   scripts/manual-demo-data.sh                      # flota de demostración (y --refrescar si pasaron más de 15 min)
//   node scripts/manual-capturas.mjs --web http://localhost:8088 [--apk scripts/testapp/testapp-v1.apk]
//        [--solo a02,e01]   # solo esas capturas (prefijo del nombre)
//        [--sin-datos]      # no crear los datos por la consola (ya existen)
//
// Primero crea por la interfaz REST de la consola (con la sesión del administrador) lo que la flota sintética no
// trae: políticas, usuarios, zonas, destinatarios, códigos de inscripción, scripts, un anuncio, un envío programado
// e intentos de inscripción; es repetible (no duplica lo que ya existe). Después recorre las pantallas y guarda
// cada captura numerada como JPEG. Contraseña del administrador: DALLYCONTROL_ADMIN_PASSWORD (por defecto "admin",
// la del stack de desarrollo). Usa el Playwright de scripts/shots ((cd scripts/shots && npm ci) una vez).
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const aqui = path.dirname(fileURLToPath(import.meta.url));
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const web = (arg('--web') ?? '').replace(/\/$/, '');
const out = path.resolve(arg('--out', path.join(aqui, '..', 'docs', 'manuales', 'src', 'img')));
const apk = arg('--apk');
const solo = (arg('--solo') ?? '').split(',').filter(Boolean);
const sinDatos = process.argv.includes('--sin-datos');
// Dirección que se ve en las capturas (pie del QR, JSON de Zero‑Touch): el navegador pide a esa dirección de ejemplo y
// el script responde con lo que contesta el stack local, así ninguna imagen muestra «localhost».
const como = (arg('--como', 'https://mdm.ejemplo.com')).replace(/\/$/, '');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD ?? 'admin';
if (!web) { console.error('falta --web http://localhost:<puerto>'); process.exit(2); }
if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(web)) { console.error('solo contra un stack local de desarrollo'); process.exit(2); }
mkdirSync(out, { recursive: true });

const md5 = (s) => createHash('md5').update(s).digest('hex').toUpperCase();
const HERO = 'dc-000101';
const ANCHO = 1480;                 // la consola necesita este ancho para no cortar pestañas ni botones
const APP_DEMO = 'Preventa Móvil';  // nombre que scripts/manual-demo-data.sh --refrescar le pone al APK de prueba
let hechas = 0; const fallos = [];

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: ANCHO, height: 940 }, colorScheme: 'light', locale: 'es-CO', timezoneId: 'America/Bogota' });
await ctx.route(`${como}/**`, async (route) => {
  const req = route.request();
  try {
    const resp = await route.fetch({ url: req.url().replace(como, web), headers: await req.allHeaders(), maxRedirects: 0 });
    await route.fulfill({ response: resp });
  } catch { await route.abort(); }
});
const page = await ctx.newPage();
page.setDefaultTimeout(30000);
page.on('dialog', (d) => void d.accept());

const rest = async (method, p, body) => page.evaluate(async ([m, pp, b]) => {
  const r = await fetch(`/rest${pp}`, { method: m, headers: b ? { 'Content-Type': 'application/json' } : {}, body: b ? JSON.stringify(b) : undefined });
  const t = await r.text();
  try { return { http: r.status, ...JSON.parse(t) }; } catch { return { http: r.status }; }
}, [method, p, body]);
const ok = (r, que) => { if (r.status !== 'OK') console.log(`  aviso: ${que}: ${r.http} ${r.status ?? ''} ${r.message ?? ''}`); return r.status === 'OK' ? r.data : undefined; };
const calma = async (ms = 900) => { await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {}); await page.waitForTimeout(ms); };
const ir = async (ruta, ms) => { await page.goto(`${como}${ruta}`); await calma(ms); };
const quiere = (nombre) => solo.length === 0 || solo.some((s) => nombre.startsWith(s));
/** Guarda una captura. `que`: nada = la ventana; un Locator = ese elemento (con margen y, si se indica, recortado a
 *  `maxAlto` píxeles); { alto } = la ventana con ese alto. */
const captura = async (nombre, que, maxAlto) => {
  if (!quiere(nombre)) return;
  const file = path.join(out, `${nombre}.jpg`);
  try {
    await page.mouse.move(ANCHO - 4, 4);      // fuera de cualquier botón: sin globos de ayuda en la captura
    // Sin foco (los globos también salen con foco) y sin direcciones IP: las del stack local se tapan en la imagen.
    await page.evaluate(() => {
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n = w.nextNode(); n; n = w.nextNode()) {
        if (/\b(?:\d{1,3}\.){3}\d{1,3}\b/.test(n.nodeValue ?? '')) n.nodeValue = n.nodeValue.replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, '•••');
      }
    }).catch(() => {});
    await page.waitForTimeout(250);
    if (que && typeof que.screenshot === 'function') {
      await que.scrollIntoViewIfNeeded();
      const caja = await que.evaluate((el) => { const r = el.getBoundingClientRect(); return { x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height }; });
      const m = 14;
      await page.screenshot({ path: file, type: 'jpeg', quality: 86, fullPage: true,
        clip: { x: Math.max(0, caja.x - m), y: Math.max(0, caja.y - m), width: Math.min(ANCHO, caja.w + 2 * m), height: Math.min(maxAlto ?? 1500, caja.h + 2 * m) } });
    }
    else await page.screenshot({ path: file, type: 'jpeg', quality: 86, clip: { x: 0, y: 0, width: ANCHO, height: que?.alto ?? 940 } });
    hechas++; console.log(`  ok    ${nombre}.jpg`);
  } catch (e) { fallos.push(nombre); console.log(`  FALLO ${nombre}: ${String(e.message).split('\n')[0]}`); }
};
const paso = async (nombre, fn) => {
  if (!quiere(nombre)) return;
  try { await fn(); } catch (e) { fallos.push(nombre); console.log(`  FALLO ${nombre}: ${String(e.message).split('\n')[0]}`); }
};
const pulsar = async (texto, opts = {}) => {
  const exacto = opts.exact ?? true;
  for (const loc of [page.getByRole('tab', { name: texto, exact: exacto }), page.getByRole('button', { name: texto, exact: exacto }),
    page.getByText(texto, { exact: exacto })]) {
    if (await loc.first().isVisible().catch(() => false)) { await loc.first().click(); await calma(opts.ms ?? 700); return; }
  }
  throw new Error(`no encontré «${texto}»`);
};

// ---- pantalla de inicio de sesión (antes de entrar) -----------------------------------------------------------------
await page.goto(`${como}/login`); await calma();
await captura('a01-login', page.locator('.login-card'));
await page.fill('input[type="text"]', 'admin');
await page.fill('input[type="password"]', password);
await page.click('button[type="submit"]');
await page.waitForURL(/dashboard|devices/, { timeout: 60000 });
await calma();

// ---- datos de demostración que se crean por la consola --------------------------------------------------------------
if (!sinDatos) {
  console.log('datos de demostración');
  const grupos = ok(await rest('GET', '/private/fleet/v1/groups'), 'carpetas')?.groups ?? [];
  const g = (n) => grupos.find((x) => x.name === n);
  let pols = ok(await rest('GET', '/private/configurations/search'), 'políticas') ?? [];
  for (const [name, description] of [['Preventa · Quiosco', 'Equipos de preventa en modo quiosco'], ['Bodega · Inventario', 'Lectores de bodega']]) {
    if (!pols.some((p) => p.name === name)) ok(await rest('PUT', '/private/configurations/copy', { id: pols[0].id, name, description }), `política ${name}`);
  }
  pols = ok(await rest('GET', '/private/configurations/search'), 'políticas') ?? [];
  const pol = (n) => pols.find((x) => x.name === n)?.id ?? null;
  for (const [carpeta, politica] of [['Andina Distribución', 'Preventa · Quiosco'], ['Almacenes del Sol', 'Bodega · Inventario']]) {
    const c = g(carpeta);
    if (c && pol(politica)) ok(await rest('PUT', `/private/fleet/v1/groups/${c.id}`, { name: c.name, configurationId: pol(politica), parentId: c.parentId ?? null }), `política de ${carpeta}`);
  }
  const usuarios = ok(await rest('GET', '/private/dc/users'), 'usuarios') ?? [];
  for (const u of [
    { login: 'laura.gomez', name: 'Laura Gómez', email: 'laura.gomez@ejemplo.com', role: 'folders', folders: [g('Andina Distribución')?.id] },
    { login: 'carlos.ruiz', name: 'Carlos Ruiz', email: 'carlos.ruiz@ejemplo.com', role: 'foldersView', folders: [g('Cafetal Logística')?.id] },
    { login: 'marta.diaz', name: 'Marta Díaz', email: 'marta.diaz@ejemplo.com', role: 'folders', folders: [g('Bodega central')?.id, g('Tiendas')?.id] },
  ]) {
    if (!usuarios.some((x) => x.login === u.login) && u.folders.every(Boolean)) ok(await rest('PUT', '/private/dc/users', { ...u, password: md5('Demostracion#2026') }), `usuario ${u.login}`);
  }
  const zonas = ok(await rest('GET', '/private/geofences/v1/zones'), 'zonas') ?? [];
  const zona = { color: '#1f6feb', active: true, alertOnReturn: true, allDevices: false, schedule: null, emails: null, trackingMinutes: null, deviceIds: [] };
  if (!zonas.some((z) => z.name === 'Zona de reparto Bogotá') && g('Bogotá')) {
    ok(await rest('POST', '/private/geofences/v1/zones', { ...zona, name: 'Zona de reparto Bogotá', shape: 'circle', centerLat: 4.66, centerLon: -74.08, radiusM: 9000, points: null, rule: 'stayInside', groupIds: [g('Bogotá').id] }), 'zona 1');
    ok(await rest('POST', '/private/geofences/v1/zones', { ...zona, name: 'Depósito restringido', color: '#d1242f', shape: 'polygon', centerLat: null, centerLon: null, radiusM: null,
      points: [[4.705, -74.115], [4.705, -74.095], [4.69, -74.095], [4.69, -74.115]], rule: 'keepOut', groupIds: [g('Bogotá').id] }), 'zona 2');
  }
  const dest = await rest('GET', '/private/geofences/v1/recipients');
  if (dest.status === 'OK' && (dest.data ?? []).length === 0 && g('Andina Distribución')) {
    ok(await rest('POST', '/private/geofences/v1/recipients', { groupId: g('Andina Distribución').id, emails: 'seguridad@ejemplo.com, operaciones@ejemplo.com', types: [], active: true }), 'destinatarios');
  }
  const codigos = ok(await rest('GET', '/private/agent/v1/codes'), 'códigos') ?? [];
  for (const [carpeta, label] of [['Bogotá', 'Inscripción Bogotá'], ['Cali', 'Lote de equipos nuevos']]) {
    if (!codigos.some((c) => c.label === label) && g(carpeta)) ok(await rest('POST', '/private/agent/v1/codes', { groupId: g(carpeta).id, label }), `código ${label}`);
  }
  const guiones = ok(await rest('GET', '/private/scripts'), 'scripts') ?? [];
  for (const s of [
    { name: 'Reiniciar la app de preventa', description: 'Borra los datos de la app y avisa al usuario', body: 'cleardata {{paquete}}\nmessage Datos de la app restablecidos', paramsJson: JSON.stringify([{ key: 'paquete', label: 'Paquete de la app', example: 'com.ejemplo.preventa' }]), quickAction: true },
    { name: 'Localizar equipo', description: 'Ubicación precisa y sonido para encontrarlo', body: 'location accurate\nring 20', quickAction: true },
    { name: 'Aviso de cierre de jornada', description: 'Mensaje en pantalla al final del turno', body: 'message Recuerda sincronizar tus pedidos antes de salir', quickAction: false },
  ]) if (!guiones.some((x) => x.name === s.name)) ok(await rest('PUT', '/private/scripts', s), `script ${s.name}`);
  const anuncios = ok(await rest('GET', '/private/announcements'), 'anuncios') ?? [];
  if (anuncios.length === 0 && g('Andina Distribución')) {
    ok(await rest('POST', '/private/announcements', { title: 'Nueva lista de precios', body: 'Desde el lunes rige la lista de precios de diciembre. Revísala antes de tu primera visita.', mandatory: true, expiresInDays: 7, all: false, groupIds: [g('Andina Distribución').id], deviceIds: [], targetLabel: 'Andina Distribución' }), 'anuncio 1');
    ok(await rest('POST', '/private/announcements', { title: 'Jornada de inventario', body: 'El sábado se hace el conteo general en la bodega central.', mandatory: false, expiresInDays: 30, all: false, groupIds: [g('Almacenes del Sol')?.id].filter(Boolean), deviceIds: [], targetLabel: 'Almacenes del Sol' }), 'anuncio 2');
  }
  const envios = await rest('GET', '/private/reports/v1/schedules');
  if (envios.status === 'OK' && (envios.data?.schedules ?? []).length === 0) {
    const base = { format: 'xlsx', csvSeparator: ';', runMinute: 0, dayOfWeek: 1, dayOfMonth: 1, timezone: 'America/Bogota', groupId: null, enabled: true };
    ok(await rest('POST', '/private/reports/v1/schedules', { ...base, name: 'Inventario diario para operaciones', report: 'inventory', frequency: 'daily', runHour: 7, recipients: 'operaciones@ejemplo.com' }), 'envío 1');
    ok(await rest('POST', '/private/reports/v1/schedules', { ...base, name: 'Equipos sin conexión, semanal', report: envios.data?.reports?.[1]?.id ?? 'inventory', frequency: 'weekly', runHour: 8, recipients: 'soporte@ejemplo.com, gerencia@ejemplo.com' }), 'envío 2');
  }
  // Intentos de inscripción: tres teléfonos simulados contra la dirección pública de inscripción.
  const intentos = await rest('GET', '/private/agent/v1/enroll/attempts/summary');
  const yaHay = await page.evaluate(async () => (await (await fetch('/rest/private/agent/v1/enroll/attempts?limit=5')).json().catch(() => ({})))?.data?.length ?? 0).catch(() => 0);
  if (yaHay === 0 || intentos.status !== 'OK') {
    const telefono = (fab, modelo, android, sdk, serie) => ({ protocolVersion: '1.1', agent: { versionName: '0.9.7', versionCode: 58, packageName: 'com.dallycontrol.agent' },
      device: { androidSdkInt: sdk, androidRelease: android, manufacturer: fab, model: modelo, isDeviceOwner: true, serial: serie, hardwareId: `demo-${serie}` }, capabilities: {} });
    const nuevo = ok(await rest('POST', '/private/agent/v1/token', { groupId: g('Bogotá')?.id }), 'token');
    const token = nuevo?.token ?? nuevo?.enrollToken;
    const inscribir = (body) => page.evaluate(async (b) => { await fetch('/rest/public/agent/v1/enroll', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) }).catch(() => {}); }, body);
    await inscribir({ ...telefono('samsung', 'SM-A156M', '14', 34, 'R58X000201'), enrollToken: 'QWER-TY12' });
    await inscribir({ ...telefono('motorola', 'moto g54 5G', '14', 34, 'ZY22X00202'), enrollToken: '00000000-0000-0000-0000-000000000000' });
    if (token) {
      await inscribir({ ...telefono('Xiaomi', 'Redmi 12C', '12', 31, 'XM33X00203'), enrollToken: token });
      await inscribir({ ...telefono('samsung', 'SM-A045M', '13', 33, 'R58X000204'), enrollToken: token });
    }
  }
}

// ---- capturas ----------------------------------------------------------------------------------------------------------
console.log('capturas');
const heroId = await page.evaluate(async (n) => (await (await fetch(`/rest/private/devices/number/${encodeURIComponent(n)}`)).json())?.data?.id, HERO).catch(() => undefined);

await paso('a02', async () => { await ir('/dashboard', 1500); await captura('a02-resumen'); });
await paso('a03', async () => { await ir('/tablero', 1500); await captura('a03-tablero'); });
await paso('a04', async () => { await ir('/users'); await captura('a04-usuarios', { alto: 560 }); });
await paso('a06', async () => { await ir('/roles'); await captura('a06-roles'); });
await paso('a07', async () => { await ir('/groups'); await captura('a07-carpetas', { alto: 760 }); });
await paso('a08', async () => { await ir('/devices'); await page.getByRole('button', { name: 'Vista de lista' }).click(); await calma();
  await captura('a08-dispositivos-lista', { alto: 760 }); });
await paso('a09', async () => { await ir(`/devices/${heroId}`, 2500); await captura('a09-dispositivo-detalle'); });
await paso('a10', async () => {
  await ir(`/devices/${heroId}`, 2000);
  for (const orden of ['Hacer sonar', 'Dejar de sonar']) { await pulsar(orden).catch(() => {}); await page.waitForTimeout(1200); }
  await page.locator('section.timeline').first().scrollIntoViewIfNeeded();
  await page.mouse.wheel(0, 200); await page.waitForTimeout(6000);      // el historial se refresca cada 5 s
  await captura('a10-control', { alto: 760 });
});
await paso('a11', async () => { await ir('/configs'); await captura('a11-politicas', { alto: 620 }); });
await paso('a12', async () => {
  await ir('/configs');
  await page.getByRole('button', { name: 'Editar', exact: true }).first().click(); await calma(1500);
  await captura('a12-politica-editor');
});
await paso('a13', async () => {
  await ir('/apps');
  await page.getByPlaceholder('Buscar apps').fill('testapp'); await calma(900);
  const yaEsta = (await page.getByText('com.dallycontrol.testapp').count()) > 0;
  if (!yaEsta && apk && existsSync(apk)) {
    await pulsar('APK propio');
    await page.setInputFiles('input[type="file"]', apk);
    await page.getByText(/APK listo|Nueva versión agregada|ya está en la Biblioteca/).first().waitFor({ timeout: 60000 }).catch(() => {});
    const agregar = page.getByRole('button', { name: 'Agregar a la Biblioteca' });
    if (await agregar.isEnabled().catch(() => false)) await agregar.click();
    await page.getByText('En la Biblioteca').first().waitFor({ timeout: 30000 }).catch(() => {});
    await calma(1500);
    console.log('  APK de prueba subido: ejecute scripts/manual-demo-data.sh --refrescar (le pone nombre) y repita con --solo a13,a15');
  }
  await ir('/apps');
  await page.getByPlaceholder('Buscar apps').fill(APP_DEMO.split(' ')[0]); await calma(900);
  await captura('a13-apps-biblioteca', { alto: 640 });
});
await paso('a14', async () => { await ir('/apps'); await pulsar('APK propio'); await captura('a14-apps-subir', { alto: 720 }); });
await paso('a15', async () => {
  await ir('/apps');
  await page.getByPlaceholder('Buscar apps').fill('com.dallycontrol.testapp'); await calma(1200);
  // La app de demostración se asigna a una política (una vez) para que la ventana tenga equipos que mostrar.
  await page.getByRole('button', { name: 'Desplegar', exact: true }).first().click(); await calma();
  await page.getByText('Agregar a una política').first().waitFor({ timeout: 20000 });
  await page.getByText('Agregar a una política').first().click({ timeout: 5000 }).catch(() => {}); await calma();
  await page.locator('.modal select').last().selectOption({ label: 'Preventa · Quiosco' }, { timeout: 5000 }).catch(() => {});
  await page.getByRole('button', { name: 'Asignar', exact: true }).click({ timeout: 5000 }).catch(() => {}); await calma(2500);
  await ir('/apps');
  await page.getByPlaceholder('Buscar apps').fill('com.dallycontrol.testapp'); await calma(1200);
  await page.getByRole('button', { name: 'Instalación', exact: true }).first().click();
  await page.getByText('versión objetivo').first().waitFor({ timeout: 30000 }).catch(() => {});
  await calma(1500);
  await captura('a15-instalacion-flota', page.locator('.modal, [role="dialog"]').last());
});
await paso('a16', async () => { await ir('/map', 5000); await captura('a16-mapa', { alto: 780 }); });
await paso('a17', async () => { await ir('/geofences', 5000); await captura('a17-geocercas', { alto: 780 }); });
await paso('a18', async () => { await ir('/alerts'); await captura('a18-alertas', { alto: 600 }); });
await paso('a19', async () => { await ir('/reports'); await pulsar('Inventario de la flota', { ms: 2500 }); await captura('a19-reportes-inventario'); });
await paso('a20', async () => { await ir('/reports'); await pulsar('Envíos programados', { ms: 1500 }); await captura('a20-envios-programados', { alto: 560 }); });
await paso('a21', async () => {
  await ir('/audit', 1500);
  await page.getByPlaceholder('técnico, equipo, IP, detalle…').fill('Carpeta').catch(() => {});
  await pulsar('Buscar').catch(() => {});
  await page.getByRole('button', { name: 'Detalle', exact: true }).first().click().catch(() => {}); await page.waitForTimeout(700);
  await captura('a21-auditoria');
});
await paso('a22', async () => { await ir('/settings', 1500); await captura('a22-ajustes'); });
await paso('a23', async () => { await ir('/announcements'); await captura('a23-anuncios', { alto: 520 }); });
await paso('a24', async () => { await ir('/scripts'); await captura('a24-scripts', { alto: 520 }); });

// ---- funciones fusionadas después: política (quiosco, hardware, permisos, Wi‑Fi, restablecimiento) y ficha del equipo --
const abrirPolitica = async () => {
  await ir('/configs');
  await page.locator('.cfg-card', { hasText: 'Preventa · Quiosco' }).getByRole('button', { name: 'Editar', exact: true }).click();
  await calma(1800);
};
const seccion = (titulo) => page.locator('section.cfg-panel', { has: page.locator('.cfg-sec-h', { hasText: titulo }) }).first();
const tab = async (nombre) => { await page.locator('.detail-main .tabs').first().getByRole('button', { name: nombre, exact: true }).click(); await calma(1500); };
await paso('p01', async () => {
  await abrirPolitica();
  await seccion('Quiosco').scrollIntoViewIfNeeded();
  await captura('p01-politica-quiosco', seccion('Quiosco'));
  await captura('p02-politica-seguridad', seccion('Seguridad'));
});
await paso('p03', async () => {
  await abrirPolitica();
  const hw = page.locator('[data-testid="hardware-policy"]');
  await hw.scrollIntoViewIfNeeded();
  for (const regla of ['camera', 'usbDebug']) await hw.locator(`[data-testid="hw-rule-${regla}"] select`).selectOption({ label: 'Bloquear' }).catch(() => {});
  await page.waitForTimeout(500);
  await captura('p03-politica-hardware', hw, 1150);
  // Se guarda (cámara y depuración USB bloqueadas) para que la pestaña «Restricciones» del equipo tenga qué mostrar.
  await page.getByRole('button', { name: 'Guardar', exact: true }).first().click(); await calma(1500);
  await page.getByRole('button', { name: 'Guardar y aplicar' }).click({ timeout: 4000 }).catch(() => {});
  await calma(1500);
});
await paso('p04', async () => {
  await abrirPolitica();
  const pp = page.locator('[data-testid="policy-permissions"]');
  await pp.scrollIntoViewIfNeeded();
  await pp.locator('.perm-app-head').first().click().catch(() => {});
  await page.waitForTimeout(700);
  await captura('p04-politica-permisos', pp, 760);
});
await paso('p05', async () => {
  await abrirPolitica();
  const wf = page.locator('[data-testid="policy-wifi"]');
  await wf.scrollIntoViewIfNeeded();
  await wf.getByRole('button', { name: '+ Agregar red' }).click();
  const red = wf.locator('[data-testid="wifi-net-0"]');
  await red.getByPlaceholder('SSID').fill('Oficina-Corporativa').catch(() => {});
  await red.locator('select').first().selectOption({ label: 'WPA2‑Enterprise (802.1X)' }).catch(() => {});
  await page.waitForTimeout(700);
  await captura('p05-politica-wifi', wf, 900);
});
await paso('d01', async () => {
  await ir(`/devices/${heroId}`, 2500);
  const pr = page.locator('[data-testid="protection-panel"]');
  await pr.scrollIntoViewIfNeeded();
  await captura('d01-proteccion', pr);
  await pr.getByRole('button', { name: 'Borrado remoto (restablecer de fábrica)' }).click(); await calma(800);
  await captura('d03-borrado-remoto', page.locator('[role="dialog"][aria-label="Borrado remoto"] .modal').first());
  await pulsar('Cancelar');
});
await paso('d04', async () => { await ir(`/devices/${heroId}`, 2000); await tab('Restricciones'); await captura('d04-restricciones', page.locator('[data-testid="hardware-panel"]'), 820); });

const elegirCarpeta = async () => {
  const sel = page.locator('select').first();
  const valor = await sel.locator('option', { hasText: 'Bogotá' }).first().getAttribute('value').catch(() => null);
  if (valor) { await sel.selectOption(valor); await calma(1500); }
};
await paso('e01', async () => { await ir('/enroll', 1500); await elegirCarpeta(); await captura('e01-inscribir-qr', { alto: 820 }); });
await paso('e02', async () => { await ir('/enroll'); await pulsar('Códigos de carpeta'); await captura('e02-codigos-carpeta', { alto: 800 }); });
await paso('e04', async () => { await ir('/enroll'); await pulsar('Sin formatear'); await captura('e04-sin-formatear', { alto: 640 }); });
await paso('e05', async () => { await ir('/enroll'); await pulsar('Por cable (USB)'); await captura('e05-usb', { alto: 760 }); });
await paso('e06', async () => {
  await ir('/enroll', 1500);
  const panel = page.locator('[data-testid="enroll-attempts"]');
  await panel.scrollIntoViewIfNeeded();
  await captura('e06-intentos', panel);
});

await browser.close();
console.log(`\n${hechas} capturas en ${path.relative(process.cwd(), out)}${fallos.length ? ` · fallaron: ${fallos.join(', ')}` : ''}`);
process.exit(fallos.length ? 1 : 0);
