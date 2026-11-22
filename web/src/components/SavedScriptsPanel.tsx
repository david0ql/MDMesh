import { useCallback, useEffect, useState } from 'react';
import { useToast } from '../ui/toast';
import {
  listScripts, runScript, scriptParams, type SavedScript, type ScriptParam,
} from '../api/scripts';

type Device = { id: number; number: string };

/**
 * Delegable actions on a device: the admin-authored saved scripts, with the one-click "quick actions"
 * surfaced first. Visible to any operator; the run itself is gated server-side by edit_devices.
 */
export function SavedScriptsPanel({ device, onQueued }: { device: Device; onQueued?: () => void }) {
  const toast = useToast();
  const [scripts, setScripts] = useState<SavedScript[]>([]);
  const [busy, setBusy] = useState<number | null>(null);
  const [active, setActive] = useState<SavedScript | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      setScripts(await listScripts(signal));
    } catch {
      /* non-fatal: the panel just stays empty */
    }
  }, []);

  useEffect(() => {
    const ctrl = new AbortController();
    void refresh(ctrl.signal);
    return () => ctrl.abort();
  }, [refresh]);

  async function execute(s: SavedScript, params: Record<string, string>) {
    setBusy(s.id);
    try {
      const res = await runScript(s.id, [device.id], params);
      toast.push('ok', `${s.name}: enviado`, `${res.queued} ${res.queued === 1 ? 'acción enviada' : 'acciones enviadas'} al dispositivo.`);
      onQueued?.();
    } catch (e) {
      toast.push('err', `${s.name}: falló`, e instanceof Error ? e.message : '');
    } finally {
      setBusy(null);
    }
  }

  function onRun(s: SavedScript) {
    const params = scriptParams(s);
    if (params.length > 0) {
      const init: Record<string, string> = {};
      params.forEach((p) => { init[p.key] = p.default ?? ''; });
      setValues(init);
      setActive(s);
    } else {
      void execute(s, {});
    }
  }

  const quick = scripts.filter((s) => s.quickAction);
  const rest = scripts.filter((s) => !s.quickAction);
  if (scripts.length === 0) return null;

  const activeParams: ScriptParam[] = active ? scriptParams(active) : [];
  const canSend = activeParams.every((p) => (values[p.key] ?? '').trim().length > 0);

  return (
    <section className="action-group" data-testid="saved-scripts-panel">
      <h3 className="action-group-title">Acciones rápidas</h3>
      {quick.length > 0 && (
        <div className="action-grid">
          {quick.map((s) => (
            <button
              key={s.id}
              className="btn"
              disabled={busy !== null}
              title={s.description ?? s.name}
              onClick={() => onRun(s)}
            >
              {busy === s.id ? 'Enviando…' : s.name}
            </button>
          ))}
        </div>
      )}
      {rest.length > 0 && (
        <ul className="saved-script-list">
          {rest.map((s) => (
            <li key={s.id} className="saved-script-row">
              <div className="saved-script-meta">
                <span className="saved-script-name">{s.name}</span>
                {s.description && <span className="muted small">{s.description}</span>}
              </div>
              <button className="btn" disabled={busy !== null} onClick={() => onRun(s)}>
                {busy === s.id ? 'Enviando…' : 'Ejecutar'}
              </button>
            </li>
          ))}
        </ul>
      )}

      {active && (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="modal">
            <h3>{active.name}</h3>
            {active.description && <p className="muted">{active.description}</p>}
            {activeParams.map((p) => (
              <label key={p.key} className="field">
                <span>{p.label}</span>
                <input
                  value={values[p.key] ?? ''}
                  placeholder={p.placeholder}
                  onChange={(e) => setValues((v) => ({ ...v, [p.key]: e.target.value }))}
                />
              </label>
            ))}
            <div className="modal-actions">
              <button className="btn" disabled={busy !== null} onClick={() => setActive(null)}>Cancelar</button>
              <button
                className="btn btn-primary"
                disabled={busy !== null || !canSend}
                onClick={() => { const s = active; setActive(null); void execute(s, values); }}
              >
                Ejecutar
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
