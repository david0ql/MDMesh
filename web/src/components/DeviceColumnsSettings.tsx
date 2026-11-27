import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { DEFAULT_DEVICE_COLUMNS, DEVICE_COLUMNS, loadDeviceColumns, maxDeviceColumns, saveDeviceColumns } from '../data/deviceColumns';

/** Which columns the device list shows (per user, in this browser), at most as many as fit the screen width. */
export function DeviceColumnsSettings() {
  const uid = useAuth().user?.id;
  const [keys, setKeys] = useState<string[]>(() => loadDeviceColumns(uid));
  const [max, setMax] = useState(maxDeviceColumns);
  useEffect(() => { setKeys(loadDeviceColumns(uid)); }, [uid]);
  useEffect(() => {
    const onResize = () => setMax(maxDeviceColumns());
    const onCols = () => setKeys(loadDeviceColumns(uid));
    window.addEventListener('resize', onResize);
    window.addEventListener('dc-columns', onCols);
    return () => { window.removeEventListener('resize', onResize); window.removeEventListener('dc-columns', onCols); };
  }, [uid]);
  const update = (next: string[]) => { setKeys(next); saveDeviceColumns(next, uid); };
  const toggle = (k: string) => update(keys.includes(k) ? keys.filter((x) => x !== k) : [...keys, k]);
  const move = (k: string, dir: -1 | 1) => {
    const i = keys.indexOf(k), j = i + dir;
    if (i < 0 || j < 0 || j >= keys.length) return;
    const next = [...keys]; [next[i], next[j]] = [next[j], next[i]]; update(next);
  };

  return (
    <section className="panel" data-testid="device-columns-settings">
      <div className="panel-head"><h2 className="panel-title">Columnas de la lista de dispositivos</h2></div>
      <div style={{ padding: '0 20px 18px' }}>
        <p className="note" style={{ marginTop: 0 }}>
          El nombre siempre se muestra. En este ancho de pantalla caben <b>{max}</b> columnas más; las que pasen de ese número no
          se muestran. Se guarda por usuario en este navegador. También puedes cambiarlas con el botón “Columnas” de la lista.
        </p>
        <div className="colset">
          {keys.filter((k) => DEVICE_COLUMNS.some((c) => c.key === k)).map((k, i) => {
            const c = DEVICE_COLUMNS.find((x) => x.key === k)!;
            return (
              <div key={k} className={`colset-row ${i >= max ? 'over' : ''}`}>
                <label><input type="checkbox" checked onChange={() => toggle(k)} /> {c.label}</label>
                <span className="colset-ord">
                  <button className="btn btn-sm btn-ghost" aria-label={`Subir ${c.label}`} disabled={i === 0} onClick={() => move(k, -1)}>↑</button>
                  <button className="btn btn-sm btn-ghost" aria-label={`Bajar ${c.label}`} disabled={i === keys.length - 1} onClick={() => move(k, 1)}>↓</button>
                </span>
                {i >= max && <span className="muted">no cabe en este ancho</span>}
              </div>
            );
          })}
          {DEVICE_COLUMNS.filter((c) => !keys.includes(c.key)).map((c) => (
            <div key={c.key} className="colset-row off">
              <label title={keys.length >= max ? 'Ya está el máximo para este ancho: quita otra primero' : undefined}>
                <input type="checkbox" checked={false} disabled={keys.length >= max} onChange={() => toggle(c.key)} /> {c.label}
              </label>
            </div>
          ))}
        </div>
        <button className="btn btn-sm" style={{ marginTop: 10 }} onClick={() => update(DEFAULT_DEVICE_COLUMNS)}>Restablecer</button>
      </div>
    </section>
  );
}
