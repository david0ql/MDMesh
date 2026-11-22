import { useEffect, useState } from 'react';
import { AppShell } from '../ui/AppShell';
import { useToast } from '../ui/toast';
import {
  deleteScript, listScripts, saveScript, scriptParams,
  type SavedScript, type SavedScriptInput, type ScriptParam,
} from '../api/scripts';

// Admin-authored reusable device scripts + the delegable one-click quick actions.
// A script body is one action verb per line (the console runs them in order on each device):
//   open <paquete>            abrir una app
//   cleardata <paquete>       BORRAR LOS DATOS de una app (device.storageClean)
//   message <texto>           mensaje/alerta en el dispositivo
//   lockscreen <texto>        mensaje en la pantalla de bloqueo
//   lock | reboot             bloquear | reiniciar
//   ring [s] | stop ring      hacer sonar | dejar de sonar
//   location accurate|battery modo de ubicación
// Los argumentos pueden llevar {{parametro}} que se piden al ejecutar.

const EXAMPLE = `# Una acción por línea
cleardata {{paquete}}
message Datos de la app restablecidos`;

interface EditState {
  id?: number;
  name: string;
  description: string;
  body: string;
  quickAction: boolean;
  params: ScriptParam[];
}

const EMPTY: EditState = { name: '', description: '', body: '', quickAction: false, params: [] };

function toEdit(s: SavedScript): EditState {
  return {
    id: s.id,
    name: s.name,
    description: s.description ?? '',
    body: s.body,
    quickAction: s.quickAction,
    params: scriptParams(s),
  };
}

export function ScriptsPage() {
  const toast = useToast();
  const [scripts, setScripts] = useState<SavedScript[] | null>(null);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => {
    listScripts()
      .then(setScripts)
      .catch((e) => { setScripts([]); toast.push('err', 'No se pudieron cargar los scripts', String(e?.message ?? e)); });
  };
  useEffect(load, []); // eslint-disable-line react-hooks/exhaustive-deps

  function setParam(i: number, patch: Partial<ScriptParam>) {
    if (!edit) return;
    const params = edit.params.map((p, j) => (j === i ? { ...p, ...patch } : p));
    setEdit({ ...edit, params });
  }
  function addParam() {
    if (!edit) return;
    setEdit({ ...edit, params: [...edit.params, { key: '', label: '' }] });
  }
  function removeParam(i: number) {
    if (!edit) return;
    setEdit({ ...edit, params: edit.params.filter((_, j) => j !== i) });
  }

  async function save() {
    if (!edit) return;
    if (!edit.name.trim()) return toast.push('err', 'Falta el nombre', 'Dale un nombre al script.');
    if (!edit.body.trim()) return toast.push('err', 'Script vacío', 'Escribe al menos una acción.');
    const params = edit.params
      .map((p) => ({ ...p, key: p.key.trim(), label: p.label.trim() }))
      .filter((p) => p.key);
    if (params.some((p) => !/^[A-Za-z0-9_]+$/.test(p.key)))
      return toast.push('err', 'Parámetro inválido', 'La clave de un parámetro solo puede llevar letras, números o guion bajo.');
    const input: SavedScriptInput = {
      id: edit.id,
      name: edit.name.trim(),
      description: edit.description.trim() || undefined,
      body: edit.body,
      quickAction: edit.quickAction,
      paramsJson: params.length ? JSON.stringify(params) : undefined,
    };
    setBusy(true);
    try {
      await saveScript(input);
      toast.push('ok', 'Script guardado', input.name);
      setEdit(null);
      load();
    } catch (e) {
      toast.push('err', 'No se pudo guardar', e instanceof Error ? e.message : String(e));
    }
    setBusy(false);
  }

  async function remove(s: SavedScript) {
    if (!window.confirm(`¿Eliminar el script «${s.name}»?`)) return;
    try { await deleteScript(s.id); toast.push('ok', 'Script eliminado', s.name); load(); }
    catch (e) { toast.push('err', 'No se pudo eliminar', e instanceof Error ? e.message : String(e)); }
  }

  return (
    <AppShell title="Scripts">
      <div className="page-head">
        <h1>Scripts</h1>
        <div className="sp" />
        {!edit && <button className="btn btn-primary" onClick={() => setEdit({ ...EMPTY })} data-testid="script-new">Nuevo script</button>}
      </div>
      <p className="note" style={{ marginTop: 0 }}>
        Acciones reutilizables que se ejecutan sobre un equipo desde su pestaña <b>Control</b>. Marca <b>Acción rápida</b> para que
        aparezca como botón de un clic (por ejemplo «Borrar datos de Amovil Preventa»). Una acción por línea:{' '}
        <span className="mono">open &lt;paquete&gt;</span> · <span className="mono">cleardata &lt;paquete&gt;</span> ·{' '}
        <span className="mono">message &lt;texto&gt;</span> · <span className="mono">lockscreen &lt;texto&gt;</span> ·{' '}
        <span className="mono">lock</span> · <span className="mono">reboot</span> · <span className="mono">ring [s]</span> ·{' '}
        <span className="mono">stop ring</span> · <span className="mono">location accurate|battery</span>. Usa{' '}
        <span className="mono">{'{{parametro}}'}</span> para pedir un dato al ejecutar.
      </p>

      {edit && (
        <section className="panel" data-testid="script-editor">
          <div className="panel-head keep"><h2 className="panel-title">{edit.id ? `Editar «${edit.name}»` : 'Nuevo script'}</h2></div>
          <div className="user-form">
            <label>Nombre<input className="input" value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} placeholder="Borrar datos de Amovil Preventa" /></label>
            <label>Descripción (opcional)<input className="input" value={edit.description} onChange={(e) => setEdit({ ...edit, description: e.target.value })} placeholder="Qué hace y cuándo usarlo" /></label>
          </div>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '12px 0' }}>
            <input type="checkbox" className="dev-check" checked={edit.quickAction} onChange={(e) => setEdit({ ...edit, quickAction: e.target.checked })} />
            Acción rápida (botón de un clic en el equipo)
          </label>
          <label style={{ display: 'block' }}>
            <span className="sub-h">Acciones</span>
            <textarea className="dcp-list mono" rows={6} value={edit.body} placeholder={EXAMPLE} aria-label="Cuerpo del script" onChange={(e) => setEdit({ ...edit, body: e.target.value })} />
          </label>

          <div className="user-folders">
            <div className="sub-h">Parámetros <span className="muted small">(se piden al ejecutar; úsalos como <span className="mono">{'{{clave}}'}</span>)</span></div>
            {edit.params.length === 0 ? <p className="muted small">Sin parámetros.</p> : (
              <ul aria-label="Parámetros del script">
                {edit.params.map((p, i) => (
                  <li key={i} style={{ display: 'flex', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
                    <input className="input mono" style={{ maxWidth: 160 }} value={p.key} placeholder="clave" onChange={(e) => setParam(i, { key: e.target.value })} aria-label="Clave del parámetro" />
                    <input className="input" style={{ maxWidth: 220 }} value={p.label} placeholder="Etiqueta visible" onChange={(e) => setParam(i, { label: e.target.value })} aria-label="Etiqueta del parámetro" />
                    <input className="input" style={{ maxWidth: 220 }} value={p.placeholder ?? ''} placeholder="Ejemplo (opcional)" onChange={(e) => setParam(i, { placeholder: e.target.value })} aria-label="Ejemplo del parámetro" />
                    <button className="btn btn-sm btn-ghost" onClick={() => removeParam(i)}>Quitar</button>
                  </li>
                ))}
              </ul>
            )}
            <button className="btn btn-sm" onClick={addParam}>Añadir parámetro</button>
          </div>

          <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
            <button className="btn btn-primary" disabled={busy} onClick={() => void save()} data-testid="script-save">{busy ? 'Guardando…' : 'Guardar'}</button>
            <button className="btn" onClick={() => setEdit(null)}>Cancelar</button>
          </div>
        </section>
      )}

      <section className="panel">
        {scripts === null ? <p className="muted" style={{ padding: 16 }}>Cargando…</p>
          : scripts.length === 0 ? <p className="muted" style={{ padding: 16 }}>Aún no hay scripts.</p> : (
          <table className="gr-table" data-testid="scripts-table">
            <thead><tr><th>Nombre</th><th>Tipo</th><th>Descripción</th><th aria-label="Acciones" /></tr></thead>
            <tbody>
              {scripts.map((s) => (
                <tr key={s.id}>
                  <td data-label="Nombre">{s.name}</td>
                  <td data-label="Tipo">{s.quickAction ? 'Acción rápida' : 'Script'}</td>
                  <td data-label="Descripción" className="muted">{s.description || '—'}</td>
                  <td>
                    <div className="gr-actions">
                      <button className="btn btn-sm" onClick={() => setEdit(toEdit(s))}>Editar</button>
                      <button className="btn btn-sm btn-ghost gr-del" onClick={() => void remove(s)}>Eliminar</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </AppShell>
  );
}
