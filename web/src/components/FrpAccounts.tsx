import { useCallback, useEffect, useState } from 'react';
import {
  frpAccountProblem, frpSaveError, getFrpDefaults, saveCompanyFrp, saveFolderFrp,
  type FrpAccount, type FrpDefaults,
} from '../api/protection';
import { groupTree, listGroups, type GroupNode } from '../api/fleet';
import { useToast } from '../ui/toast';

/** Google's own page that answers with the signed-in account's id — no credentials of ours involved. */
const PEOPLE_API_EXPLORER =
  'https://developers.google.com/people/api/rest/v1/people/get?apix_params=%7B%22resourceName%22%3A%22people%2Fme%22%2C%22personFields%22%3A%22metadata%22%7D';

/**
 * How to get the numeric id of a Google account. Android's reset protection identifies the account by this id,
 * not by its e-mail, and only Google can translate one into the other: the People API, asked as the account itself.
 */
export function FrpIdGuide() {
  const [open, setOpen] = useState(false);
  return (
    <div className="frp-guide">
      <button type="button" className="frp-guide-toggle" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        {open ? <span key="hide">Ocultar los pasos</span> : <span key="show">¿Cómo obtengo el ID de la cuenta?</span>}
      </button>
      {open && (
        <ol className="frp-guide-steps">
          <li>
            En un navegador, inicia sesión en Google <strong>con la cuenta corporativa</strong> que va a reactivar los
            equipos (la misma cuyo correo escribes aquí). Si tienes otra sesión abierta, usa una ventana de incógnito.
          </li>
          <li>
            Abre la página oficial de Google{' '}
            <a href={PEOPLE_API_EXPLORER} target="_blank" rel="noreferrer">People API · people.get</a>. A la derecha está
            el panel «Try this method» con <code>resourceName = people/me</code> y <code>personFields = metadata</code> ya puestos.
          </li>
          <li>Pulsa <strong>Execute</strong> y acepta el permiso que pide Google para leer tu perfil.</li>
          <li>
            En la respuesta, copia el número de <code>"resourceName": "people/<strong>123456789012345678901</strong>"</code>{' '}
            (también aparece como <code>metadata.sources[0].id</code>). Son 21 dígitos: ese es el ID.
          </li>
          <li>Pégalo en «ID de la cuenta» junto al correo y pulsa «Agregar».</li>
        </ol>
      )}
      {open && (
        <p className="frp-guide-note">
          La consola no puede buscar el ID por ti: Google solo lo entrega a quien inicia sesión con esa cuenta. Guarda la
          contraseña de esa cuenta en un lugar seguro: es la llave para reactivar un equipo restablecido por botones.
        </p>
      )}
    </div>
  );
}

/** A list of accounts (e-mail + numeric id) edited one at a time, validated before it is added. */
export function FrpAccountList({ value, disabled, onChange, empty }: {
  value: FrpAccount[]; disabled?: boolean; onChange: (v: FrpAccount[]) => void; empty: string;
}) {
  const [email, setEmail] = useState('');
  const [id, setId] = useState('');
  const [touched, setTouched] = useState(false);
  const problem = frpAccountProblem(email, id, value);
  const add = () => {
    setTouched(true);
    if (problem) return;
    onChange([...value, { email: email.trim().toLowerCase() || null, id: id.trim() }]);
    setEmail(''); setId(''); setTouched(false);
  };
  return (
    <div className="frp-accounts">
      {value.length === 0 ? <p className="muted small frp-empty">{empty}</p> : (
        <ul className="frp-list">
          {value.map((a) => (
            <li key={a.id}>
              <span className="frp-email">{a.email || 'Cuenta sin correo anotado'}</span>
              <span className="frp-id mono">{a.id}</span>
              {!disabled && (
                <button type="button" className="btn btn-sm" aria-label={`Quitar ${a.email || a.id}`}
                  onClick={() => onChange(value.filter((x) => x.id !== a.id))}>Quitar</button>
              )}
            </li>
          ))}
        </ul>
      )}
      {!disabled && (
        <div className="frp-add">
          <input aria-label="Correo de la cuenta de Google" placeholder="ti@empresa.com" value={email} autoComplete="off"
            onChange={(e) => setEmail(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
          <input className="mono" aria-label="ID de la cuenta" placeholder="ID numérico (21 dígitos)" value={id} inputMode="numeric"
            autoComplete="off" onChange={(e) => setId(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
          <button type="button" className="btn btn-sm" disabled={!email.trim() && !id.trim()} onClick={add}>Agregar</button>
        </div>
      )}
      {!disabled && touched && problem && <p className="field-error" role="alert">{problem}</p>}
      {!disabled && <FrpIdGuide />}
    </div>
  );
}

/** The company's and the folders' default accounts, in a dialog. */
export function FrpDefaultsDialog({ onClose }: { onClose: (changed: boolean) => void }) {
  const toast = useToast();
  const [defaults, setDefaults] = useState<FrpDefaults | null>(null);
  const [folders, setFolders] = useState<GroupNode[]>([]);
  const [level, setLevel] = useState<'company' | number>('company');
  const [draft, setDraft] = useState<FrpAccount[]>([]);
  const [dirty, setDirty] = useState(false);
  const [changed, setChanged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const stored = useCallback((d: FrpDefaults | null, l: 'company' | number): FrpAccount[] =>
    (d ? (l === 'company' ? d.company : d.folders[String(l)] ?? []) : []), []);

  useEffect(() => {
    let on = true;
    void Promise.all([getFrpDefaults(), listGroups().catch(() => null)]).then(([d, g]) => {
      if (!on) return;
      setDefaults(d);
      setFolders(g ? groupTree(g.groups) : []);
      setDraft(d.company);
    }).catch((e) => { if (on) setError(e instanceof Error ? e.message : 'No se pudieron leer las cuentas'); });
    return () => { on = false; };
  }, []);

  function pick(l: 'company' | number) {
    if (dirty && !window.confirm('Hay cambios sin guardar en este nivel. ¿Descartarlos?')) return;
    setLevel(l); setDraft(stored(defaults, l)); setDirty(false); setError(null);
  }

  async function save() {
    setBusy(true); setError(null);
    try {
      const saved = level === 'company' ? await saveCompanyFrp(draft) : await saveFolderFrp(level, draft);
      setDefaults((d) => (d ? (level === 'company' ? { ...d, company: saved } : { ...d, folders: { ...d.folders, [String(level)]: saved } }) : d));
      setDraft(saved); setDirty(false); setChanged(true);
      toast.push('ok', 'Cuentas guardadas', 'Los equipos las aplican en su próxima sincronización.');
    } catch (e) {
      setError(frpSaveError(e instanceof Error ? e.message : 'No se pudo guardar'));
    } finally {
      setBusy(false);
    }
  }

  const canEdit = !!defaults && (level === 'company' ? defaults.canEditCompany : defaults.canEditFolders);
  const inherited = level !== 'company' && draft.length === 0;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Cuentas de reactivación">
      <div className="modal frp-modal">
        <h3>Cuentas que pueden reactivar un equipo</h3>
        <p className="muted">
          Tras un restablecimiento por botones (recovery), el teléfono solo termina su configuración con una de estas
          cuentas de Google. Se definen una vez para la empresa; una carpeta puede tener las suyas, que heredan sus
          subcarpetas. Una política puede usar cuentas propias o desactivar la protección.
        </p>
        {!defaults && !error && <p className="muted">Cargando…</p>}
        {defaults && (
          <>
            <label className="field">
              <span>Nivel</span>
              <select className="sel" aria-label="Nivel" value={String(level)}
                onChange={(e) => pick(e.target.value === 'company' ? 'company' : Number(e.target.value))}>
                <option value="company">Toda la empresa ({defaults.company.length} {defaults.company.length === 1 ? 'cuenta' : 'cuentas'})</option>
                {folders.map((f) => (
                  <option key={f.group.id} value={f.group.id}>
                    {'  '.repeat(f.depth)}Carpeta: {f.group.name}
                    {defaults.folders[String(f.group.id)]?.length ? ` (${defaults.folders[String(f.group.id)].length} propias)` : ' (hereda)'}
                  </option>
                ))}
              </select>
            </label>
            <FrpAccountList value={draft} disabled={!canEdit || busy}
              onChange={(v) => { setDraft(v); setDirty(true); }}
              empty={level === 'company'
                ? 'La empresa no tiene cuentas: un equipo restablecido por botones quedaría libre, salvo que su carpeta o su política tengan las suyas.'
                : 'Esta carpeta no tiene cuentas propias: hereda las de la carpeta superior o las de la empresa.'} />
            {inherited && defaults.company.length > 0 && (
              <p className="muted small">Hereda de la empresa: {defaults.company.map((a) => a.email || a.id).join(', ')}.</p>
            )}
            {!canEdit && <p className="muted small">Tu usuario puede ver estas cuentas, pero no cambiarlas en este nivel.</p>}
          </>
        )}
        {error && <p className="field-error" role="alert">{error}</p>}
        <div className="modal-actions">
          <button className="btn" disabled={busy} onClick={() => onClose(changed)}>Cerrar</button>
          {canEdit && (
            <button className="btn btn-primary" disabled={busy || !dirty} onClick={() => { void save(); }}>Guardar cuentas</button>
          )}
        </div>
      </div>
    </div>
  );
}
