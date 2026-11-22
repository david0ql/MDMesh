import { useState } from 'react';
import { uploadMedia } from '../api/announcements';
import type { ConsoleBrand } from '../api/fleet';

const MAX_MB = 5;

/**
 * Console branding of a folder: the logo and client name shown in the admin console header (AppShell) to the folder's
 * administrators once they sign in. Independent of the kiosk branding pushed to the devices. What is left empty is
 * inherited from the folder above (nearest the top of the tree wins).
 */
export function ConsoleBrandEditor({ value, onChange, disabled }: {
  value: ConsoleBrand;
  onChange: (v: ConsoleBrand) => void;
  disabled?: boolean;
}) {
  const set = (patch: Partial<ConsoleBrand>) => onChange({ ...value, ...patch });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const pick = async (f: File) => {
    setErr(null);
    if (!f.type.startsWith('image/')) { setErr('Debe ser una imagen (PNG o JPG).'); return; }
    if (f.size > MAX_MB * 1024 * 1024) { setErr(`Máximo ${MAX_MB} MB.`); return; }
    setBusy(true);
    try {
      set({ logoUrl: await uploadMedia(f, 'consola') });
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'No se pudo subir.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="kb cb-editor">
      <div className="kb-form">
        <div className="kb-slot">
          <div className="kb-thumb cb-thumb">{value.logoUrl ? <img src={value.logoUrl} alt="" /> : <span>Sin logo</span>}</div>
          <div className="kb-slot-body">
            <b>Logo de consola</b>
            <span className="muted small">Se muestra en el encabezado de la consola. Vacío = hereda.</span>
            {!disabled && (
              <div className="kb-slot-actions">
                <label className="btn btn-sm" style={{ cursor: busy ? 'wait' : 'pointer' }}>
                  {busy ? 'Subiendo…' : value.logoUrl ? 'Cambiar' : 'Subir imagen'}
                  <input type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml" hidden disabled={busy}
                         data-testid="cb-logo"
                         onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) void pick(f); }} />
                </label>
                {value.logoUrl && <button type="button" className="btn btn-sm btn-ghost" onClick={() => set({ logoUrl: undefined })}>Quitar</button>}
              </div>
            )}
            {err && <span className="field-error">{err}</span>}
          </div>
        </div>
        <label className="field"><span>Nombre del cliente</span>
          <input value={value.name ?? ''} disabled={disabled} maxLength={40} placeholder="p. ej. Alpina" data-testid="cb-name"
                 onChange={(e) => set({ name: e.target.value || undefined })} />
        </label>
        <span className="muted small">
          Si no subes logo pero escribes el nombre, se muestra el nombre. Vacío en ambos = hereda la carpeta superior.
        </span>
      </div>

      <div className="cb-preview" aria-label="Vista previa del encabezado">
        <div className="cb-preview-head">
          {value.logoUrl
            ? <img src={value.logoUrl} alt="" />
            : <span className="wordmark"><span className="bullet" aria-hidden="true" /><span>{value.name || 'DallyControl'}</span></span>}
        </div>
        <span className="muted small">Encabezado de la consola</span>
      </div>
    </div>
  );
}
