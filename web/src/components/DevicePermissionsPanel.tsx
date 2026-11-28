import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  getDevicePermissions, openPermissionScreen, saveDevicePermissions, scanDevicePermissions,
  type AppPermissionState, type DesiredPermissions, type DevicePermissions,
} from '../api/devicePermissions';
import {
  DEFAULT_POLICY_LABEL, GROUP_LABEL, RULE_LABEL, SPECIAL_LABEL, SPECIAL_STATE_LABEL, cleanRules,
  type PermissionRulesByApp,
} from '../data/permissions';
import { AppPermissionsEditor } from './AppPermissionsEditor';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../ui/toast';
import { fmtRelative } from '../ui/format';

type Device = { number: string; description?: string | null };

const byApp = (d: DesiredPermissions | null): PermissionRulesByApp =>
  Object.fromEntries((d?.apps ?? []).map((a) => [a.packageName, { all: a.all, permissions: a.permissions, special: a.special }]));

/** One row per app and permission (and special access), as the agent reported it. */
export function permissionCsv(apps: AppPermissionState[]): string {
  const head = ['App', 'Paquete', 'Instalada', 'Tipo', 'Permiso', 'Nombre Android', 'Estado real', 'Fijado por política', 'Regla', 'Nota'];
  const lines: (string | number)[][] = [];
  for (const a of apps) {
    if (!a.installed) {
      lines.push([a.label ?? a.packageName, a.packageName, 'no', '', '', '', '', '', '', '']);
      continue;
    }
    for (const p of a.permissions ?? []) {
      lines.push([
        a.label ?? a.packageName, a.packageName, 'sí', 'Permiso', GROUP_LABEL[p.group] ?? p.group, p.name,
        p.granted ? 'concedido' : 'sin conceder', p.fixed === 'granted' ? 'concedido' : p.fixed === 'denied' ? 'denegado' : 'no',
        p.wanted ? RULE_LABEL[p.wanted] : '', p.note === 'sensorsBlocked' ? 'el equipo no deja al administrador concederlo' : '',
      ]);
    }
    for (const s of a.special ?? []) {
      lines.push([
        a.label ?? a.packageName, a.packageName, 'sí', 'Acceso especial', SPECIAL_LABEL[s.key] ?? s.key, '',
        SPECIAL_STATE_LABEL[s.state] ?? s.state, '', s.wanted ? 'pedido' : '', s.managed ?? '',
      ]);
    }
  }
  return [head, ...lines].map((l) => l.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(';')).join('\r\n');
}

/**
 * App permissions of one device: the default policy really in force on the phone, whether Android lets the
 * administrator grant sensor permissions there, the real state of every managed app, and the device's own rules on top
 * of its policy.
 */
export function DevicePermissionsPanel({ device }: { device: Device }) {
  const toast = useToast();
  const user = useAuth().user;
  const canEdit = !!user && (user.superAdmin || user.userRole?.superAdmin
    || (user.userRole?.permissions ?? []).some((p) => p.name === 'edit_devices'));
  const [data, setData] = useState<DevicePermissions | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState<PermissionRulesByApp>({});
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (keepDraft = false) => {
    try {
      const d = await getDevicePermissions(device.number);
      setData(d);
      if (!keepDraft) setDraft(d.override ?? {});
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [device.number]);

  useEffect(() => { void load(); }, [load]);

  const report = data?.report ?? null;
  const states = useMemo(() => Object.fromEntries((report?.apps ?? []).map((a) => [a.packageName, a])), [report]);
  const policyRules = useMemo(() => byApp(data?.policy ?? null), [data]);
  const apps = useMemo(() => (report?.apps ?? []).map((a) => ({ pkg: a.packageName, name: a.label ?? undefined })), [report]);
  const dirty = JSON.stringify(cleanRules(draft)) !== JSON.stringify(cleanRules(data?.override ?? {}));
  const pendingTaps = (report?.apps ?? []).flatMap((a) => (a.special ?? []).filter((s) => s.wanted && s.state === 'needsUser').map((s) => ({ a, s })));
  const wantedDefault = data?.effective?.defaultPolicy ?? 'grant';

  async function save() {
    setBusy(true);
    try {
      const d = await saveDevicePermissions(device.number, cleanRules(draft));
      setData(d);
      setDraft(d.override ?? {});
      toast.push('ok', 'Permisos del equipo guardados', 'El teléfono los aplica en su próximo reporte (se le avisó ahora).');
    } catch (e) {
      toast.push('err', 'No se pudieron guardar los permisos', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function refresh() {
    setBusy(true);
    try {
      await scanDevicePermissions(device.number);
      toast.push('ok', 'Se pidió el estado al teléfono', 'Vuelve a aplicar las reglas y reporta el estado real en unos segundos.');
      // The report arrives with the phone's next check-in.
      window.setTimeout(() => void load(true), 6000);
      window.setTimeout(() => void load(true), 15000);
    } catch (e) {
      toast.push('err', 'No se pudo pedir el estado', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function openScreen(pkg: string, access: string) {
    try {
      await openPermissionScreen(device.number, pkg, access);
      toast.push('ok', 'Pantalla abierta en el teléfono',
        `Quien tenga el equipo debe activar «${SPECIAL_LABEL[access] ?? access}». El estado se actualiza con el siguiente reporte.`);
      window.setTimeout(() => void load(true), 15000);
    } catch (e) {
      toast.push('err', 'No se pudo abrir la pantalla', e instanceof Error ? e.message : '');
    }
  }

  function download() {
    const url = URL.createObjectURL(new Blob(['﻿' + permissionCsv(report?.apps ?? [])], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `permisos-${device.description || device.number}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  if (error) return <div className="panel"><div className="banner banner-alert">No se pudieron leer los permisos: {error}</div></div>;
  if (!data) return <div className="panel"><p className="muted">Cargando…</p></div>;

  return (
    <div className="panel" data-testid="device-permissions">
      <div className="hc-head">
        <h2 className="panel-title">Permisos de apps</h2>
        <button type="button" className="btn btn-ghost" disabled={busy || !data.supported} onClick={() => void refresh()}>Actualizar estado</button>
        <button type="button" className="btn btn-ghost" disabled={!report?.apps?.length} onClick={download}>Descargar CSV</button>
      </div>

      {!data.supported && (
        <div className="banner banner-warn" data-testid="perm-unsupported">
          El agente de este equipo todavía no aplica ni reporta permisos por app. Actualiza el agente (Ajustes → Actualizaciones):
          las reglas se guardan y se aplican en cuanto se actualice.
        </div>
      )}
      {report && !report.deviceOwner && (
        <div className="banner banner-alert">
          El agente no es propietario del dispositivo (Device Owner) en este equipo: Android no le deja conceder permisos.
        </div>
      )}
      {report?.canGrantSensors === false && (
        <div className="banner banner-alert" data-testid="perm-sensors-blocked">
          <b>Este equipo no permite que el administrador conceda permisos de sensores</b> (ubicación, cámara, micrófono, actividad
          física). Se inscribió con esa opción desactivada (Android 12 o superior): el usuario debe aceptarlos en el teléfono, o hay
          que volver a inscribir el equipo permitiéndolo. Denegarlos sí funciona.
        </div>
      )}
      {pendingTaps.length > 0 && (
        <div className="banner banner-warn" data-testid="perm-needs-tap">
          <b>Requiere un toque del usuario:</b>{' '}
          {pendingTaps.map(({ a, s }) => `${SPECIAL_LABEL[s.key] ?? s.key} (${a.label ?? a.packageName})`).join(', ')}.
          Android no deja que el MDM lo active solo; abre cada pantalla desde la app correspondiente, abajo.
        </div>
      )}

      <div className="hc-tiles perm-tiles">
        <div>
          <span>Política por defecto en el equipo</span>
          <b data-testid="perm-effective-policy">{report ? DEFAULT_POLICY_LABEL[report.policy] ?? report.policy : 'Sin reporte'}</b>
          {report && report.policy !== 'unknown' && report.policy !== wantedDefault && (
            <span className="perm-warn small">La política pide «{DEFAULT_POLICY_LABEL[wantedDefault] ?? wantedDefault}»: aún no se aplica.</span>
          )}
        </div>
        <div>
          <span>Permisos de sensores por el administrador</span>
          <b data-testid="perm-sensors">
            {!report ? 'Sin reporte' : report.canGrantSensors == null ? 'Sin límite (Android 11 o anterior)' : report.canGrantSensors ? 'Permitido' : 'No permitido por el equipo'}
          </b>
        </div>
        <div>
          <span>Último resultado</span>
          <b className="perm-outcome">{report?.lastOutcome ? report.lastOutcome.replace(/^applied/, 'aplicado').replace(/^unsupported/, 'no compatible').replace(/^failed/, 'falló') : '—'}</b>
        </div>
        <div>
          <span>Reportado</span>
          <b>{data.reportAt ? fmtRelative(data.reportAt) : '—'}</b>
        </div>
      </div>

      <p className="muted small">
        Reglas de este equipo, encima de las de su política. «Conceder» y «Denegar» quedan fijados (el usuario no puede cambiarlos);
        «Preguntar» lo deja en manos del usuario — con la política por defecto «Conceder todo», Android lo concede al pedirlo sin
        mostrar diálogo. El estado real es el que reporta el teléfono.
      </p>
      <AppPermissionsEditor apps={apps} value={draft} onChange={setDraft} disabled={!canEdit || busy} inherited={policyRules}
        states={states as Record<string, AppPermissionState>} canGrantSensors={report?.canGrantSensors}
        onOpenScreen={canEdit && data.supported ? (pkg, access) => void openScreen(pkg, access) : undefined} testid="device-perm-editor" />
      {canEdit && (
        <div className="perm-actions">
          <button type="button" className="btn btn-primary" disabled={busy || !dirty} onClick={() => void save()} data-testid="perm-save">
            Guardar permisos del equipo
          </button>
          <button type="button" className="btn btn-ghost" disabled={busy || !dirty} onClick={() => setDraft(data.override ?? {})}>Descartar</button>
          {Object.keys(data.override ?? {}).length > 0 && (
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => setDraft({})}>Quitar las reglas del equipo (seguir la política)</button>
          )}
        </div>
      )}
    </div>
  );
}
