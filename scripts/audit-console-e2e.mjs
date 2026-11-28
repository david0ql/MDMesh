// Console (web) checks of the Auditoría screen and of roles, in a real headless Chromium, against a running stack:
// the menu entry (last, behind its own permission), the table with its filters, search, expandable detail, server
// pagination and the Excel / CSV exports; retention and the integrity check; the "Actividad" tab of a device and the
// recent activity of a user; and in Roles: "qué puede hacer este rol", creating a role from a template, duplicating
// and deleting it, and assigning a custom role in Usuarios. It also signs in as a limited technician to check what
// the console shows them (their folders only, no enrollment, no roles) and as a user without view_audit.
//
//   node scripts/audit-console-e2e.mjs --web http://localhost:8088 --device <device number> [--shots dir]
//   (admin password: DALLYCONTROL_ADMIN_PASSWORD, default "admin" — the dev stack's)
//
// Creates aud-ui-* folders / roles / users and removes them. Uses the Playwright copy under scripts/shots
// ((cd scripts/shots && npm install && npx playwright install chromium) once). Exit 0 only if every check passes.
import { chromium } from './shots/node_modules/playwright/index.mjs';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, statSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const web = (arg('--web') ?? '').replace(/\/$/, '');
const device = arg('--device');
const shots = arg('--shots', '/tmp/dallycontrol-audit-shots');
const password = process.env.DALLYCONTROL_ADMIN_PASSWORD ?? 'admin';
if (!web || !device) { console.error('--web and --device are required'); process.exit(2); }
mkdirSync(shots, { recursive: true });

let pass = 0, fail = 0;
const check = (name, ok, detail = '') => { console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  [${String(detail).slice(0, 300)}]`}`); ok ? pass++ : fail++; };
const md5 = (s) => createHash('md5').update(s).digest('hex').toUpperCase();
const stamp = String(Date.now()).slice(-6);
const TECH_PW = 'Tecnico#2026';

const browser = await chromium.launch();
const newPage = async () => {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 950 }, acceptDownloads: true });
  const page = await ctx.newPage();
  page.on('dialog', (d) => void d.accept());
  return page;
};
// REST through a page's browser session (same cookie as the UI).
const rest = (page) => async (method, path, body) => page.evaluate(async ([m, p, b]) => {
  const r = await fetch(`/rest${p}`, { method: m, headers: b ? { 'Content-Type': 'application/json' } : {}, body: b ? JSON.stringify(b) : undefined });
  const t = await r.text();
  try { return { http: r.status, ...JSON.parse(t) }; } catch { return { http: r.status }; }
}, [method, path, body]);
const uiLogin = async (page, user, pw) => {
  await page.goto(`${web}/login`);
  await page.fill('input[type="text"], input[name="login"]', user);
  await page.fill('input[type="password"]', pw);
  await page.click('button[type="submit"]');
  await page.waitForURL(/dashboard|devices/, { timeout: 30000 });
};
const navLabels = async (page) => page.locator('nav.nav .nav-item').allTextContents().then((a) => a.map((s) => s.trim()));

const page = await newPage();
const api = rest(page);
const cleanup = [];
try {
  // --- setup: a folder, a limited role, a technician; the technician does one allowed and one refused thing --------
  await uiLogin(page, 'admin', password);
  check('inicio de sesión del administrador en la consola', true);
  // No answer that carries a user may carry its secrets (password hash, session token, two-factor secret…).
  const secretsIn = (o) => JSON.stringify(o ?? {}).match(/"(password|oldPassword|newPassword|authToken|authData|twoFactorSecret)"\s*:/g) ?? [];
  const meRaw = await api('GET', '/private/users/current');
  const allRaw = await api('GET', '/private/users/all');
  const dcRaw = await api('GET', '/private/dc/users');
  const leaked = [...secretsIn(meRaw), ...secretsIn(allRaw), ...secretsIn(dcRaw)];
  check('las respuestas con usuarios no traen hash de contraseña, token de sesión ni secretos', meRaw.status === 'OK' && leaked.length === 0, leaked.join(' '));
  const dev = (await api('GET', `/private/devices/number/${encodeURIComponent(device)}`)).data;
  const scope = (await api('GET', `/private/fleet/v1/devices/${dev.id}/scope`)).data ?? {};
  const folder = (await api('POST', '/private/fleet/v1/groups', { name: `aud-ui-${stamp}` })).data;
  await api('POST', '/private/fleet/v1/devices/group', { deviceIds: [dev.id], groupId: folder.id });
  cleanup.push(async () => {
    if (scope.groupId) {
      await api('POST', '/private/fleet/v1/devices/group', { deviceIds: [dev.id], groupId: scope.groupId });
      await api('DELETE', `/private/fleet/v1/groups/${folder.id}`);
    }
  });
  const perms = Object.fromEntries((await api('GET', '/private/roles/permissions')).data.map((p) => [p.name, p.id]));
  const roleName = `aud-ui-soporte-${stamp}`;
  const role = (await api('PUT', '/private/roles', { name: roleName, description: 'Soporte sin acciones disruptivas (prueba)',
    permissions: ['edit_devices', 'edit_device_desc', 'view_audit'].map((n) => ({ id: perms[n] })) })).data;
  cleanup.push(() => api('DELETE', `/private/roles/${role.id}`));
  const techLogin = `aud.ui.${stamp}`;
  const tech = (await api('PUT', '/private/dc/users', { login: techLogin, name: 'Técnico Pantalla', password: md5('Inicial#2026'),
    role: 'custom', roleId: role.id, folders: [folder.id] })).data;
  cleanup.unshift(() => api('DELETE', `/private/dc/users/${tech.id}`));
  const visorLogin = `aud.visor.${stamp}`;
  const visor = (await api('PUT', '/private/dc/users', { login: visorLogin, name: 'Visor Pantalla', password: md5('Inicial#2026'),
    role: 'foldersView', folders: [folder.id] })).data;
  cleanup.unshift(() => api('DELETE', `/private/dc/users/${visor.id}`));

  // First login of both users (the server forces a password change), through their own browser sessions.
  const firstLogin = async (login) => {
    const p = await newPage();
    await p.goto(`${web}/login`);
    const r = rest(p);
    const first = await r('POST', '/public/auth/login', { login, password: md5('Inicial#2026') });
    await r('POST', '/public/passwordReset/reset', { passwordResetToken: first.data.passwordResetToken, newPassword: md5(TECH_PW) });
    await p.waitForTimeout(1300);
    return p;
  };
  const techPage = await firstLogin(techLogin);
  const visorPage = await firstLogin(visorLogin);
  await uiLogin(techPage, techLogin, TECH_PW);
  const techApi = rest(techPage);
  const cmd = `/private/agent/v1/devices/${encodeURIComponent(device)}/commands`;
  const ring = await techApi('POST', cmd, { type: 'device.ring', requiresCapability: 'device.ring', payload: JSON.stringify({ durationMs: 2000 }) });
  check('el técnico envía un comando permitido', ring.status === 'OK', JSON.stringify(ring));
  const wipe = await techApi('POST', cmd, { type: 'device.wipe' });
  check('el técnico intenta un wipe: el servidor lo niega', wipe.status === 'ERROR' && /permission/.test(wipe.message ?? ''), JSON.stringify(wipe));
  await techApi('POST', cmd, { type: 'device.lock' });
  // An edit with a visible before / after.
  await api('PUT', '/private/roles', { id: role.id, name: roleName, description: 'Soporte (prueba, editado)',
    permissions: ['edit_devices', 'edit_device_desc', 'view_audit', 'files'].map((n) => ({ id: perms[n] })) });
  await page.waitForTimeout(5000); // the audit rows are written in the background

  // --- the menu and the screen ------------------------------------------------------------------------------------
  await page.goto(`${web}/dashboard`);
  await page.locator('nav.nav .nav-item').first().waitFor({ timeout: 20000 });
  const labels = await navLabels(page);
  const menuModules = labels.filter((l) => l !== 'Ayuda'); // the help entry is not a module; it stays last
  check('«Auditoría» está en el menú, al final de los módulos', menuModules[menuModules.length - 1] === 'Auditoría', labels.join(' | '));
  await page.locator('nav.nav .nav-item', { hasText: 'Auditoría' }).click();
  await page.waitForURL(/\/audit$/);
  await page.getByTestId('audit-table').waitFor({ timeout: 20000 });
  const total = Number((await page.getByTestId('audit-total').textContent()) ?? '0');
  check('la tabla carga registros con su total', total > 5 && (await page.getByTestId('audit-row').count()) > 5, `total ${total}`);
  const headers = await page.locator('[data-testid="audit-table"] thead th').allTextContents();
  check('columnas: fecha, técnico, módulo, acción, objetivo, resultado, IP',
    ['Fecha y hora', 'Técnico', 'Módulo', 'Acción', 'Objetivo', 'Resultado', 'IP'].every((h) => headers.includes(h)), headers.join(' | '));
  await page.screenshot({ path: `${shots}/audit-list.png`, fullPage: true });

  // Every search is a request to the server: wait for ITS answer (a loaded machine can take seconds), then for React.
  const logResponse = () => page.waitForResponse((r) => r.url().includes('/audit/v1/log') && r.status() === 200, { timeout: 60000 });
  const search = async () => {
    await Promise.all([logResponse(), page.getByTestId('audit-search').click()]);
    await page.waitForTimeout(400);
  };
  // Filter: result.
  await page.getByTestId('audit-f-result').selectOption('denied');
  await search();
  const results = await page.locator('[data-testid="audit-row"] .audit-result').allTextContents();
  check('filtro por resultado «Denegado»', results.length > 0 && results.every((r) => r === 'Denegado'), results.join(','));
  // Filter: technician (+ result).
  await page.getByTestId('audit-f-user').selectOption(String(tech.id));
  await search();
  const rowsText = await page.getByTestId('audit-row').allTextContents();
  check('filtro por técnico: solo sus acciones denegadas', rowsText.length >= 2 && rowsText.every((t) => t.includes('Técnico Pantalla')), rowsText[0]);
  check('el wipe denegado aparece con su nombre claro', rowsText.some((t) => t.includes('borrado de fábrica (wipe)')), rowsText.join(' || '));
  // Filter: module + action.
  await page.getByTestId('audit-f-result').selectOption('');
  await page.getByTestId('audit-f-category').selectOption('comandos');
  await search();
  const modules = await page.locator('[data-testid="audit-row"] td:nth-child(3)').allTextContents();
  check('filtro por módulo «Comandos remotos»', modules.length > 0 && modules.every((m) => m === 'Comandos remotos'), modules.join(','));
  const actionOptions = await page.locator('[data-testid="audit-f-action"] option').allTextContents();
  check('el filtro de acción ofrece las acciones del módulo', actionOptions.some((o) => o.includes('hacer sonar')), actionOptions.join(' | '));
  // Filter: target = device.
  await page.getByTestId('audit-f-category').selectOption('');
  await page.getByTestId('audit-f-user').selectOption('');
  await page.getByTestId('audit-f-device').fill(device);
  await search();
  const deviceRows = await page.getByTestId('audit-row').count();
  check('filtro por dispositivo (objetivo)', deviceRows >= 3, `filas ${deviceRows}`);
  // Filter: target = folder.
  await page.getByTestId('audit-f-device').fill('');
  await page.getByTestId('audit-f-group').selectOption(String(folder.id));
  await search();
  check('filtro por carpeta (objetivo)', (await page.getByTestId('audit-row').count()) >= 3);
  // Date range: tomorrow → nothing.
  await page.getByTestId('audit-f-group').selectOption('');
  const today = await page.getByTestId('audit-f-to').inputValue();
  await page.getByTestId('audit-f-from').fill('2020-01-01');
  await page.getByTestId('audit-f-to').fill('2020-01-02');
  await search();
  check('filtro por rango de fechas (un rango sin actividad queda vacío)', await page.getByTestId('audit-empty').isVisible());
  await page.getByTestId('audit-f-from').fill(today);
  await page.getByTestId('audit-f-to').fill(today);
  // Free text search.
  await page.getByTestId('audit-f-q').fill(roleName);
  await search();
  const found = await page.getByTestId('audit-row').allTextContents();
  check('búsqueda por texto', found.length >= 1 && found.every((t) => t.includes(roleName) || t.includes('rol')), found[0]);
  // Expandable detail with before / after.
  const editRow = page.getByTestId('audit-row').filter({ hasText: 'Alta o edición de rol' }).first();
  await editRow.getByTestId('audit-toggle').click();
  const detail = page.getByTestId('audit-detail').first();
  await detail.waitFor();
  const detailText = (await detail.textContent()) ?? '';
  check('detalle expandible: «Qué cambió» con antes y después', detailText.includes('Qué cambió') && detailText.includes('permisos') && detailText.includes('files'), detailText.slice(0, 200));
  const campo = await detail.locator('.audit-diff tbody td:first-child').first().evaluate((td) => {
    // Lines of the TEXT (the cell itself is as tall as its row, which grows with the before / after values).
    const range = document.createRange();
    range.selectNodeContents(td);
    const tops = new Set([...range.getClientRects()].map((r) => Math.round(r.top)));
    return { nowrap: getComputedStyle(td).whiteSpace, lines: tops.size };
  });
  check('detalle: la columna «Campo» no parte el nombre del campo en varias líneas', campo.nowrap === 'nowrap' && campo.lines <= 1, JSON.stringify(campo));
  check('detalle: origen con IP, navegador y huella', detailText.includes('Dirección IP') && detailText.includes('Navegador') && detailText.includes('huella'), detailText.slice(-300));
  await page.screenshot({ path: `${shots}/audit-detail.png`, fullPage: true });

  // Server pagination.
  await page.getByTestId('audit-f-q').fill('');
  await page.getByTestId('audit-f-from').fill('2020-01-01');
  await search();
  await Promise.all([logResponse(), page.getByTestId('audit-pagesize').selectOption('25')]);
  await page.waitForTimeout(400);
  const all = Number((await page.getByTestId('audit-total').textContent()) ?? '0');
  const firstIds = await page.getByTestId('audit-row').evaluateAll((rs) => rs.map((r) => r.textContent));
  check('paginación en servidor: 25 filas por página', firstIds.length === Math.min(25, all), `filas ${firstIds.length} de ${all}`);
  if (all > 25) {
    const [resp] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/audit/v1/log') && r.url().includes('page=2')),
      page.getByTestId('audit-next').click(),
    ]);
    await page.waitForTimeout(600);
    const second = await page.getByTestId('audit-row').evaluateAll((rs) => rs.map((r) => r.textContent));
    check('«Siguiente» pide la página 2 al servidor y muestra otras filas', resp.ok() && second.length > 0 && second[0] !== firstIds[0]
      && ((await page.getByTestId('audit-page').textContent()) ?? '').includes('Página 2'), await page.getByTestId('audit-page').textContent());
    await page.getByTestId('audit-prev').click();
    await page.waitForTimeout(600);
  } else {
    check('«Siguiente» pide la página 2 al servidor y muestra otras filas', false, `solo hay ${all} registros; hacen falta más de 25`);
  }

  // Exports.
  await page.getByTestId('audit-f-user').selectOption(String(tech.id));
  await search();
  const [xlsx] = await Promise.all([page.waitForEvent('download'), page.getByTestId('audit-export-xlsx').click()]);
  const xlsxPath = `${shots}/${xlsx.suggestedFilename()}`;
  await xlsx.saveAs(xlsxPath);
  check('exportación a Excel: se descarga un .xlsx', /^auditoria-\d{8}-\d{4}\.xlsx$/.test(xlsx.suggestedFilename()) && statSync(xlsxPath).size > 2000
    && readFileSync(xlsxPath).subarray(0, 2).toString() === 'PK', `${xlsx.suggestedFilename()} ${statSync(xlsxPath).size}`);
  const [csv] = await Promise.all([page.waitForEvent('download'), page.getByTestId('audit-export-csv').click()]);
  const csvPath = `${shots}/${csv.suggestedFilename()}`;
  await csv.saveAs(csvPath);
  const csvText = readFileSync(csvPath, 'utf8');
  check('exportación a CSV: con las filas filtradas del técnico', csv.suggestedFilename().endsWith('.csv') && csvText.includes('borrado de fábrica (wipe)')
    && csvText.includes('Denegado') && csvText.includes(techLogin) && !csvText.includes(md5(TECH_PW)), csvText.slice(0, 200));

  // Retention + integrity.
  const admin = page.getByTestId('audit-admin');
  await admin.waitFor({ timeout: 20000 });
  check('panel de retención visible para el administrador (alcance completo)', await admin.isVisible());
  await admin.scrollIntoViewIfNeeded();
  const before = await page.getByTestId('audit-retention').inputValue();
  check('retención visible (365 días por defecto)', Number(before) >= 30, before);
  await page.getByTestId('audit-retention').fill('200');
  await page.getByTestId('audit-retention-save').click();
  await page.waitForTimeout(1200);
  check('retención configurable desde la pantalla', (await api('GET', '/private/audit/v1/settings')).data.retentionDays === 200);
  await api('PUT', '/private/audit/v1/settings', { retentionDays: Number(before) });
  await page.getByTestId('audit-verify').click();
  const verify = page.getByTestId('audit-verify-result');
  await verify.waitFor({ timeout: 30000 });
  check('verificación de integridad: cadena íntegra', ((await verify.textContent()) ?? '').includes('Cadena íntegra'), await verify.textContent());
  await page.screenshot({ path: `${shots}/audit-admin.png`, fullPage: true });
  const noEdit = await page.locator('[data-testid="audit-table"] button', { hasText: /Editar|Eliminar|Borrar/ }).count();
  check('la pantalla no ofrece editar ni borrar registros', noEdit === 0);

  // --- device: "Actividad" tab ------------------------------------------------------------------------------------
  await page.goto(`${web}/devices/${encodeURIComponent(device)}`);
  await page.getByTestId('tab-activity').waitFor({ timeout: 20000 });
  await page.getByTestId('tab-activity').click();
  const activity = page.getByTestId('audit-activity');
  await activity.waitFor({ timeout: 15000 });
  const actText = (await activity.textContent()) ?? '';
  check('detalle del dispositivo → pestaña «Actividad» con lo que hizo cada técnico',
    actText.includes('Técnico Pantalla') && actText.includes('hacer sonar') && actText.includes('borrado de fábrica (wipe)') && actText.includes('Denegado'), actText.slice(0, 300));
  await activity.locator('button', { hasText: 'Detalle' }).first().click();
  check('la actividad del dispositivo abre el detalle', await page.getByTestId('audit-detail').first().isVisible());
  await page.screenshot({ path: `${shots}/device-activity.png`, fullPage: true });

  // --- users: recent activity -------------------------------------------------------------------------------------
  await page.goto(`${web}/users`);
  const userRow = page.locator('[data-testid="users-table"] tbody tr', { hasText: techLogin });
  await userRow.waitFor({ timeout: 15000 });
  check('Usuarios muestra el rol personalizado del técnico', ((await userRow.textContent()) ?? '').includes(`Rol: ${roleName}`), await userRow.textContent());
  await userRow.getByTestId('user-activity').click();
  const modal = page.getByTestId('user-activity-modal');
  await modal.getByTestId('audit-activity').waitFor({ timeout: 15000 });
  const modalText = (await modal.textContent()) ?? '';
  check('Usuarios → «Actividad»: actividad reciente del usuario', modalText.includes('Inicio de sesión') && modalText.includes('borrado de fábrica (wipe)'), modalText.slice(0, 300));
  await page.screenshot({ path: `${shots}/user-activity.png`, fullPage: true });
  await modal.locator('button', { hasText: 'Cerrar' }).click();

  // Assign a custom role from the UI.
  await page.getByTestId('user-new').click();
  const editor = page.getByTestId('user-editor');
  const uiUser = `aud.rol.${stamp}`;
  await editor.locator('input').nth(0).fill('Usuario con rol');
  await editor.locator('input').nth(1).fill(uiUser);
  await editor.locator('input[type="password"]').nth(0).fill('Clave#2026x');
  await editor.locator('input[type="password"]').nth(1).fill('Clave#2026x');
  await page.getByTestId('user-type').selectOption('custom');
  await page.getByTestId('user-role').selectOption(String(role.id));
  const info = (await page.getByTestId('user-role-info').textContent()) ?? '';
  check('al elegir un rol personalizado se explica qué podrá hacer', info.includes('Opera los dispositivos') && info.includes('auditoría'), info.slice(0, 200));
  await editor.locator('.user-folders ul label', { hasText: `aud-ui-${stamp}` }).locator('input').check();
  await page.getByTestId('user-save').click();
  const newRow = page.locator('[data-testid="users-table"] tbody tr', { hasText: uiUser });
  await newRow.waitFor({ timeout: 15000 });
  check('usuario creado desde la pantalla con el rol personalizado', ((await newRow.textContent()) ?? '').includes(`Rol: ${roleName}`), await newRow.textContent());
  const created = (await api('GET', '/private/dc/users')).data.find((u) => u.login === uiUser);
  cleanup.unshift(() => api('DELETE', `/private/dc/users/${created?.id}`));
  check('…y el servidor lo guardó con ese rol y esa carpeta', created?.roleId === role.id && created?.folders?.[0] === folder.id, JSON.stringify(created));

  // --- roles ------------------------------------------------------------------------------------------------------
  await page.goto(`${web}/roles`);
  await page.getByTestId('roles-matrix').waitFor({ timeout: 20000 });
  const support = page.locator('[data-testid="role-card"][data-role="Técnico de soporte"]');
  await support.getByTestId('role-explain').click();
  const summary = (await support.getByTestId('role-summary').textContent()) ?? '';
  check('«Qué puede hacer este rol» en lenguaje claro (plantilla Técnico de soporte)',
    summary.includes('Opera los dispositivos') && summary.includes('No puede bloquear, reiniciar, cambiar la clave ni borrar dispositivos'), summary.slice(0, 300));
  const readOnly = page.locator('[data-testid="role-card"][data-role="Solo lectura"]');
  await readOnly.getByTestId('role-explain').click();
  check('plantilla «Solo lectura»: no envía comandos', ((await readOnly.getByTestId('role-summary').textContent()) ?? '').includes('No envía comandos ni modifica dispositivos'));
  const heights = await page.locator('[data-testid="role-card"]').evaluateAll((cards) => cards.map((c) => ({ role: c.dataset.role, h: Math.round(c.getBoundingClientRect().height) })));
  const open = heights.filter((h) => h.role === 'Técnico de soporte' || h.role === 'Solo lectura').map((h) => h.h);
  const closed = heights.filter((h) => h.role !== 'Técnico de soporte' && h.role !== 'Solo lectura').map((h) => h.h);
  check('tarjetas de roles: abrir una no estira las demás', closed.length > 0 && Math.max(...closed) < Math.min(...open) - 60, JSON.stringify(heights));
  check('las tarjetas muestran la descripción del rol', ((await support.textContent()) ?? '').includes('acciones seguras') && !((await support.textContent()) ?? '').includes('Sin descripción'), await support.textContent());
  const matrixText = (await page.getByTestId('roles-matrix').textContent()) ?? '';
  check('la matriz nombra los módulos fusionados (soporte remoto, datos móviles, hardware, permisos de apps, FRP, certificados Wi-Fi)',
    ['soporte y control remoto', 'datos móviles', 'hardware', 'permisos de apps', 'protección (FRP)', 'certificados Wi-Fi'].every((t) => matrixText.includes(t)), matrixText.slice(0, 300));
  const matrixPerms = await page.locator('[data-testid="roles-matrix"] input[data-perm]').evaluateAll((xs) => [...new Set(xs.map((x) => x.dataset.perm))]);
  const serverPerms = (await api('GET', '/private/roles/permissions')).data.map((p) => p.name).filter((n) => !n.startsWith('plugin') && n !== 'get_updates');
  check('la matriz tiene una fila por cada permiso que el servidor comprueba', serverPerms.every((n) => matrixPerms.includes(n)), `faltan: ${serverPerms.filter((n) => !matrixPerms.includes(n)).join(', ')}`);
  await page.screenshot({ path: `${shots}/roles.png`, fullPage: true });
  // New role from a template.
  const tplRole = `aud-ui-plantilla-${stamp}`;
  await page.getByTestId('role-new').click();
  await page.getByTestId('role-name').fill(tplRole);
  await page.getByTestId('role-from').selectOption('support');
  await page.getByTestId('role-create').click();
  const tplCard = page.locator(`[data-testid="role-card"][data-role="${tplRole}"]`);
  await tplCard.waitFor({ timeout: 15000 });
  const tplPerms = async (name) => (await api('GET', '/private/roles/all')).data.find((r) => r.name === name)?.permissions.map((p) => p.name).sort().join(',');
  check('rol nuevo desde la plantilla «Técnico de soporte»', (await tplPerms(tplRole)) === 'applications,edit_device_desc,edit_devices,files', await tplPerms(tplRole));
  // Any combination: tick one more box in the matrix and save.
  await page.locator(`input[data-role="${tplRole}"][data-perm="view_audit"]`).check();
  await page.getByTestId('roles-save').click();
  await page.waitForTimeout(1500);
  check('la matriz guarda cualquier combinación de permisos', ((await tplPerms(tplRole)) ?? '').includes('view_audit'), await tplPerms(tplRole));
  // Duplicate.
  await tplCard.getByTestId('role-duplicate').click();
  const copyName = await page.getByTestId('role-name').inputValue();
  await page.getByTestId('role-create').click();
  const copyCard = page.locator(`[data-testid="role-card"][data-role="${copyName}"]`);
  await copyCard.waitFor({ timeout: 15000 });
  check('duplicar un rol copia sus permisos', copyName === `Copia de ${tplRole}` && (await tplPerms(copyName)) === (await tplPerms(tplRole)), `${copyName}: ${await tplPerms(copyName)}`);
  // Delete both.
  await copyCard.getByTestId('role-delete').click();
  await copyCard.waitFor({ state: 'detached', timeout: 15000 });
  await tplCard.getByTestId('role-delete').click();
  await tplCard.waitFor({ state: 'detached', timeout: 15000 });
  check('un rol personalizado sin usuarios se elimina', !(await api('GET', '/private/roles/all')).data.some((r) => r.name === tplRole || r.name === copyName));
  check('los roles del sistema no ofrecen «Eliminar»', (await page.locator('[data-testid="role-card"][data-role="Visor"]').getByTestId('role-delete').count()) === 0);

  // --- what a limited technician sees -----------------------------------------------------------------------------
  await techPage.goto(`${web}/dashboard`);
  await techPage.locator('nav.nav .nav-item').first().waitFor({ timeout: 20000 });
  const techNav = await navLabels(techPage);
  check('técnico limitado: ve «Auditoría»; no ve «Inscribir», «Roles», «Ajustes», «Reportes» ni «Usuarios»',
    techNav.includes('Auditoría') && !['Inscribir', 'Roles', 'Ajustes', 'Reportes', 'Usuarios'].some((x) => techNav.includes(x)), techNav.join(' | '));
  // Hold the answer that says whether the viewer is limited to folders: meanwhile the panel must stay hidden.
  await techPage.route('**/rest/private/audit/v1/facets', async (route) => { await new Promise((r) => setTimeout(r, 4000)); await route.continue(); });
  await techPage.goto(`${web}/audit`);
  await techPage.getByTestId('audit-table').waitFor({ timeout: 20000 });
  const early = await techPage.getByTestId('audit-admin').count();
  await techPage.waitForTimeout(1500);
  const stillEarly = await techPage.getByTestId('audit-admin').count();
  check('panel de retención oculto mientras no se sabe el alcance del usuario', early === 0 && stillEarly === 0, `${early}/${stillEarly}`);
  await techPage.locator('.report-hint', { hasText: 'tus carpetas' }).waitFor({ timeout: 20000 }).catch(() => undefined);
  const hint = (await techPage.locator('.report-hint').textContent()) ?? '';
  check('técnico limitado: la pantalla avisa que ve solo sus carpetas y usuarios', hint.includes('tus carpetas'), hint.slice(-120));
  const techRows = await techPage.getByTestId('audit-row').allTextContents();
  check('técnico limitado: no ve la creación de roles ni otras acciones ajenas a su carpeta',
    techRows.length > 0 && !techRows.some((t) => t.includes('Alta o edición de rol')), techRows.find((t) => t.includes('rol')) ?? '');
  check('técnico limitado: sin panel de retención ni verificación', (await techPage.getByTestId('audit-admin').count()) === 0);
  await techPage.screenshot({ path: `${shots}/audit-technician.png`, fullPage: true });
  await techPage.goto(`${web}/enroll`);
  await techPage.waitForURL(/\/devices$/, { timeout: 15000 });
  check('técnico limitado: /enroll lo devuelve a sus dispositivos', true);

  // --- a user without view_audit ----------------------------------------------------------------------------------
  await uiLogin(visorPage, visorLogin, TECH_PW);
  await visorPage.locator('nav.nav .nav-item').first().waitFor({ timeout: 20000 });
  const visorNav = await navLabels(visorPage);
  check('sin el permiso view_audit: «Auditoría» no está en el menú', !visorNav.includes('Auditoría'), visorNav.join(' | '));
  await visorPage.goto(`${web}/audit`);
  await visorPage.waitForURL(/\/devices$/, { timeout: 15000 });
  check('…y /audit lo devuelve a sus dispositivos', true);
  const refused = await rest(visorPage)('GET', '/private/audit/v1/log');
  check('…y la API se lo niega', refused.status === 'ERROR' && /permission/.test(refused.message ?? ''), JSON.stringify(refused));
  await visorPage.goto(`${web}/devices/${encodeURIComponent(device)}`);
  await visorPage.locator('.tabs button').first().waitFor({ timeout: 20000 });
  check('…y el dispositivo no le muestra la pestaña «Actividad»', (await visorPage.getByTestId('tab-activity').count()) === 0);
} catch (e) {
  check('la prueba terminó sin excepciones', false, e?.stack ?? String(e));
  await page.screenshot({ path: `${shots}/audit-error.png`, fullPage: true }).catch(() => undefined);
} finally {
  for (const fn of cleanup) { try { await fn(); } catch { /* best effort */ } }
  await browser.close();
}
console.log(`\n${pass} PASS, ${fail} FAIL  (capturas en ${shots})`);
process.exit(fail ? 1 : 0);
