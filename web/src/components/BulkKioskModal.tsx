import { useEffect, useState } from 'react';
import { listApplications, type Application } from '../api/applications';
import { queueForTarget, targetLabel, type Target } from '../api/fleet';
import { buildKioskPayload, type KioskChoice } from './KioskEnterModal';
import { useToast } from '../ui/toast';

type Mode = 'launcher' | 'single';

export function BulkKioskModal({
  target, onClose, onDone,
}: { target: Target; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const who = targetLabel(target);
  const [apps, setApps] = useState<Application[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [mode, setMode] = useState<Mode>('launcher');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [exitMode, setExitMode] = useState<'gesture' | 'visible' | 'remote'>('gesture');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listApplications()
      .then((r) => { if (!cancelled) setApps(r); })
      .catch((e) => { if (!cancelled) setErr(e instanceof Error ? e.message : 'No se pudieron cargar las apps'); });
    return () => { cancelled = true; };
  }, []);

  function togglePkg(pkg: string) {
    setSelected((prev) => {
      if (mode === 'single') return new Set(prev.has(pkg) ? [] : [pkg]);
      const next = new Set(prev);
      next.has(pkg) ? next.delete(pkg) : next.add(pkg);
      return next;
    });
  }
  function switchMode(m: Mode) {
    setMode(m);
    if (m === 'single' && selected.size > 1) setSelected(new Set([Array.from(selected)[0]]));
  }

  const canApply = selected.size > 0 && (mode === 'launcher' || selected.size === 1);

  async function apply() {
    setBusy(true);
    try {
      const payload = buildKioskPayload({
        mode, packages: Array.from(selected), exitMode, password,
      } as KioskChoice);
      const res = await queueForTarget(target, {
        type: 'kiosk.enter', payload: JSON.stringify(payload),
      });
      const skipped = res.skipped;
      toast.push('ok', 'Quiosco en cola',
        `Entrar en quiosco → ${res.queued} dispositivo${res.queued === 1 ? '' : 's'}` +
        (skipped ? ` (${skipped} omitido${skipped === 1 ? '' : 's'})` : '') + '.');
      onDone(); onClose();
    } catch (e) {
      toast.push('err', 'No se pudo enviar el quiosco', e instanceof Error ? e.message : '');
    } finally { setBusy(false); }
  }

  const filtered = (apps ?? []).filter(
    (a) => `${a.name} ${a.pkg}`.toLowerCase().includes(query.toLowerCase()));

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Entrar en quiosco en {who}</h3>
        <div className="kiosk-mode">
          <label><input type="radio" checked={mode === 'launcher'}
            onChange={() => switchMode('launcher')} /> Apps permitidas (cuadrícula de inicio)</label>
          <label><input type="radio" checked={mode === 'single'}
            onChange={() => switchMode('single')} /> Fijar una sola app</label>
        </div>

        <input className="field" placeholder="Filtrar apps de la biblioteca" value={query}
               onChange={(e) => setQuery(e.target.value)} />
        {err && <p className="muted">{err}</p>}
        {!apps && !err && <p className="muted">Cargando biblioteca…</p>}
        <div className="action-grid">
          {filtered.map((a) => (
            <button key={a.id ?? a.pkg}
                    className={`btn ${selected.has(a.pkg) ? 'btn-primary' : ''}`}
                    disabled={busy} title={a.pkg} onClick={() => togglePkg(a.pkg)}>
              {a.name}
            </button>
          ))}
        </div>

        <label className="field">
          <span>Modo de salida</span>
          <select value={exitMode} onChange={(e) => setExitMode(e.target.value as typeof exitMode)}>
            <option value="gesture">Gesto (7 toques en la esquina)</option>
            <option value="visible">Botón visible</option>
            <option value="remote">Solo remoto</option>
          </select>
        </label>
        <label className="field">
          <span>Contraseña de salida</span>
          <input type="password" value={password} placeholder="opcional"
                 onChange={(e) => setPassword(e.target.value)} />
        </label>

        <div className="modal-actions">
          <button className="btn" disabled={busy} onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" disabled={busy || !canApply}
                  onClick={() => { void apply(); }}>
            {busy ? <span key="busy">Enviando…</span> : <span key="idle">Entrar en quiosco</span>}
          </button>
        </div>
      </div>
    </div>
  );
}
