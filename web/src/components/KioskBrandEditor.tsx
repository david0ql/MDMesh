import { useState } from 'react';
import { uploadMedia } from '../api/announcements';
import type { KioskBrand } from '../api/fleet';

const MAX_MB = 5;

/** One image slot: preview, upload (hosted on this server) and remove. */
function ImageSlot({ label, help, url, disabled, testId, onChange }: {
  label: string; help: string; url?: string; disabled?: boolean; testId: string; onChange: (url: string | undefined) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const pick = async (f: File) => {
    setErr(null);
    if (!f.type.startsWith('image/')) { setErr('Debe ser una imagen (PNG o JPG).'); return; }
    if (f.size > MAX_MB * 1024 * 1024) { setErr(`Máximo ${MAX_MB} MB.`); return; }
    setBusy(true);
    try {
      onChange(await uploadMedia(f, 'quiosco'));
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'No se pudo subir.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="kb-slot">
      <div className="kb-thumb">{url ? <img src={url} alt="" /> : <span>Sin imagen</span>}</div>
      <div className="kb-slot-body">
        <b>{label}</b>
        <span className="muted small">{help}</span>
        {!disabled && (
          <div className="kb-slot-actions">
            <label className="btn btn-sm" style={{ cursor: busy ? 'wait' : 'pointer' }}>
              {busy ? 'Subiendo…' : url ? 'Cambiar' : 'Subir imagen'}
              <input type="file" accept="image/png,image/jpeg,image/webp" hidden disabled={busy} data-testid={testId}
                     onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) void pick(f); }} />
            </label>
            {url && <button type="button" className="btn btn-sm btn-ghost" onClick={() => onChange(undefined)}>Quitar</button>}
          </div>
        )}
        {err && <span className="field-error">{err}</span>}
      </div>
    </div>
  );
}

/**
 * Kiosk branding: the logo above the apps, the logo below them, a wallpaper, the serial at the bottom and a support
 * line with a call button. Used by a policy (with the serial switch) and by a folder (logos, wallpaper and support
 * line that override the policy's for that folder and the ones below it).
 */
export function KioskBrandEditor({ value, onChange, disabled, forFolder, colors }: {
  value: KioskBrand;
  onChange: (v: KioskBrand) => void;
  disabled?: boolean;
  /** A folder inherits what it leaves empty and has no serial switch (the policy decides it). */
  forFolder?: boolean;
  colors?: { bg?: string; text?: string };
}) {
  const set = (patch: Partial<KioskBrand>) => onChange({ ...value, ...patch });
  const bg = colors?.bg || '#0E1117';
  const fg = colors?.text || '#E8EEF4';
  const inherit = forFolder ? ' Vacío = hereda.' : '';
  return (
    <div className="kb">
      <div className="kb-form">
        <ImageSlot label="Logo de arriba" help={`Sobre las apps; reemplaza el título del quiosco.${inherit}`} url={value.logoUrl}
                   disabled={disabled} testId="kb-logo" onChange={(u) => set({ logoUrl: u })} />
        <ImageSlot label="Logo de abajo" help={`En el pie, a la derecha del serial.${inherit}`} url={value.footerLogoUrl}
                   disabled={disabled} testId="kb-footer" onChange={(u) => set({ footerLogoUrl: u })} />
        <ImageSlot label="Fondo" help={`Imagen detrás de las apps (opcional).${inherit}`} url={value.backgroundUrl}
                   disabled={disabled} testId="kb-bg" onChange={(u) => set({ backgroundUrl: u })} />
        {!forFolder && (
          <label className="kb-check">
            <input type="checkbox" disabled={disabled} checked={!!value.showSerial} onChange={(e) => set({ showSerial: e.target.checked || undefined })} />
            Mostrar el serial del dispositivo en el pie
          </label>
        )}
        <div className="kb-support">
          <label className="field"><span>Número de soporte</span>
            <input value={value.supportPhone ?? ''} disabled={disabled} inputMode="tel" placeholder="+573001234567" data-testid="kb-phone"
                   onChange={(e) => set({ supportPhone: e.target.value.replace(/[^0-9+*#]/g, '') || undefined })} />
          </label>
          <label className="field"><span>Nombre del botón</span>
            <input value={value.supportLabel ?? ''} disabled={disabled || !value.supportPhone} maxLength={30} placeholder="Soporte"
                   onChange={(e) => set({ supportLabel: e.target.value || undefined })} />
          </label>
        </div>
        <span className="muted small">
          Con un número, el quiosco muestra un botón que llama directo a soporte (no hace falta habilitar el teléfono completo).
          {forFolder ? ' Vacío = hereda el de la carpeta superior o el de la política.' : ''}
        </span>
      </div>

      <div className="kb-phone" style={{ background: value.backgroundUrl ? `center / cover no-repeat url("${value.backgroundUrl}")` : bg, color: fg }} aria-label="Vista previa del quiosco">
        <div className="kb-ph-head">{value.logoUrl ? <img src={value.logoUrl} alt="" /> : <b>DallyControl Kiosk</b>}</div>
        <div className="kb-ph-grid">
          {['App', 'App', 'App', 'App', 'App'].map((t, i) => <span key={i}><i />{t}</span>)}
          {value.supportPhone && <span><i className="call">✆</i>{value.supportLabel || 'Soporte'}</span>}
        </div>
        {(value.showSerial || value.footerLogoUrl) && (
          <div className="kb-ph-foot" style={{ background: bg }}>
            <span>{value.showSerial ? 'Serial: ZY32FCBNM5' : ''}</span>
            {value.footerLogoUrl && <img src={value.footerLogoUrl} alt="" />}
          </div>
        )}
      </div>
    </div>
  );
}
