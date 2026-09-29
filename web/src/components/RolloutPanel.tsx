import { useCallback, useEffect, useRef, useState } from 'react';
import { getUpdateStatus, type UpdateStatus } from '../api/updates';
import {
  getActiveRollout, createRollout, promoteRollout, cancelRollout,
  type ActiveRollout, type RolloutCounts,
} from '../api/rollout';
import { searchDevices, type DeviceView } from '../api/devices';

const AGENT_PACKAGE = (import.meta.env.VITE_AGENT_PACKAGE as string) || 'com.dallycontrol.agent';

/** A labelled progress bar for one cohort (canary or fleet). */
function CohortBar({ label, c }: { label: string; c: RolloutCounts }) {
  const denom = Math.max(c.total - c.ineligible, 1); // ineligible devices can't be updated — exclude
  const pct = Math.round((c.updated / denom) * 100);
  return (
    <div className="rollout-cohort">
      <div className="rollout-cohort-head">
        <span>{label}</span>
        <span className="mono">{c.updated}/{c.total - c.ineligible} actualizados</span>
      </div>
      <div className="rollout-track"><div className="rollout-fill" style={{ width: `${pct}%` }} /></div>
      <div className="rollout-legend">
        {c.pending > 0 && <span className="ub-warn">{c.pending} instalando</span>}
        {c.outstanding > 0 && <span>{c.outstanding} en cola</span>}
        {c.ineligible > 0 && <span className="muted">{c.ineligible} muy antiguos</span>}
      </div>
    </div>
  );
}

/** Staged agent-APK rollout: pick a canary set, watch it land, then promote to the fleet. */
export function RolloutPanel() {
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const [rollout, setRollout] = useState<ActiveRollout | null>(null);
  const [picking, setPicking] = useState(false);
  const [devices, setDevices] = useState<DeviceView[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const on = useRef(true); // false after unmount — stops in-flight refreshes from re-arming the poll

  const refresh = useCallback(async () => {
    const r = await getActiveRollout().catch(() => null);
    if (!on.current) return;
    setRollout(r);
    const active = !!r && (r.stage === 'canary' || r.stage === 'fleet');
    clearTimeout(timer.current);
    if (active) timer.current = setTimeout(() => void refresh(), 10000); // poll while a rollout runs
  }, []);

  useEffect(() => {
    on.current = true;
    void getUpdateStatus().then((s) => { if (on.current) setStatus(s); });
    void refresh();
    return () => { on.current = false; clearTimeout(timer.current); };
  }, [refresh]);

  const openPicker = async () => {
    setErr(null);
    setPicking(true);
    try {
      const list = await searchDevices({ pageSize: 200 });
      setDevices(list.devices.items);
    } catch (e) {
      setErr((e as Error).message);
    }
  };

  const toggle = (num: string) => setSelected((s) => {
    const next = new Set(s);
    if (next.has(num)) next.delete(num); else next.add(num);
    return next;
  });

  const apk = status?.apk;

  const start = async () => {
    if (!apk) return;
    setBusy(true); setErr(null);
    try {
      const created = await createRollout({
        targetVersion: apk.version,
        packageName: AGENT_PACKAGE,
        apkVersionCode: apk.versionCode,
        apkSha256: apk.sha256,
        canaryDeviceNumbers: Array.from(selected),
      });
      setRollout(created);
      setPicking(false);
      setSelected(new Set());
      void refresh();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const promote = async () => {
    if (!rollout) return;
    if (!window.confirm('¿Extender esta actualización al resto de la flota?')) return;
    setBusy(true); setErr(null);
    try { setRollout(await promoteRollout(rollout.id)); void refresh(); }
    catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  };

  const finish = async () => {
    if (!rollout) return;
    setBusy(true); setErr(null);
    try { await cancelRollout(rollout.id); setRollout(null); }
    catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  };

  // Nothing to show: no active rollout and no mirrored APK to offer.
  const active = rollout && (rollout.stage === 'canary' || rollout.stage === 'fleet');
  if (!active && !(apk && apk.available)) return null;

  return (
    <section className="panel">
      <div className="panel-head"><h2 className="panel-title">Despliegue del agente</h2></div>

      {active && rollout && (
        <>
          <div className="set-row">
            <span className="k">Desplegando</span>
            <span className="v mono">
              v{rollout.targetVersion} · <span className="ub-ch">{rollout.stage === 'canary' ? 'canario' : rollout.stage === 'fleet' ? 'flota' : rollout.stage}</span>
            </span>
          </div>
          <CohortBar label="Canario" c={rollout.progress.canary} />
          {rollout.progress.fleet && <CohortBar label="Flota" c={rollout.progress.fleet} />}
          <div className="set-row" style={{ justifyContent: 'flex-end', gap: 8 }}>
            {rollout.stage === 'canary' && (
              <button
                className="btn btn-sm btn-primary"
                onClick={() => void promote()}
                disabled={busy
                  || rollout.progress.canary.pending > 0
                  || rollout.progress.canary.outstanding > 0
                  || rollout.progress.canary.updated < 1}
                title="Se habilita cuando todos los dispositivos canario se hayan actualizado"
              >
                Extender a la flota
              </button>
            )}
            <button className="btn btn-sm" onClick={() => void finish()} disabled={busy}>
              {rollout.stage === 'fleet' ? <span key="finish">Finalizar</span> : <span key="cancel">Cancelar</span>}
            </button>
          </div>
        </>
      )}

      {!active && apk && apk.available && !picking && (
        <div className="set-row">
          <span className="k">
            Agente v{apk.version} disponible
            <small>Envía el nuevo APK del agente a los dispositivos por etapas.</small>
          </span>
          <span className="v">
            <button className="btn btn-sm btn-primary" onClick={() => void openPicker()}>
              Desplegar…
            </button>
          </span>
        </div>
      )}

      {!active && picking && (
        <>
          <p className="muted" style={{ margin: '0 0 8px' }}>
            Selecciona los dispositivos <b>canario</b> que se actualizan primero (v{apk?.version}). Cuando confirmes que
            funcionan bien, extiendes la actualización al resto de la flota.
          </p>
          <div className="rollout-devicelist">
            {devices.map((d) => (
              <label key={d.id} className="rollout-device">
                <input type="checkbox" checked={selected.has(d.number)} onChange={() => toggle(d.number)} />
                <span className="mono">{d.number}</span>
                {d.description && <span className="muted">{d.description}</span>}
              </label>
            ))}
            {devices.length === 0 && <span className="muted">No hay dispositivos.</span>}
          </div>
          <div className="set-row" style={{ justifyContent: 'flex-end', gap: 8 }}>
            <button className="btn btn-sm" onClick={() => { setPicking(false); setSelected(new Set()); }} disabled={busy}>
              Cancelar
            </button>
            <button className="btn btn-sm btn-primary" onClick={() => void start()} disabled={busy || selected.size === 0}>
              {busy ? <span key="busy">Iniciando…</span> : <span key="idle">Iniciar canario ({selected.size})</span>}
            </button>
          </div>
        </>
      )}

      {err && <p className="ub-warn" style={{ marginTop: 8 }}>{err}</p>}
    </section>
  );
}
