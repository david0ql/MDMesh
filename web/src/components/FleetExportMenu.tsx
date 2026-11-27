import { useEffect, useRef, useState } from 'react';
import { downloadFleetExport, type CsvSeparator, type FleetExportFormat } from '../api/fleet';
import { useToast } from '../ui/toast';

type Scope = 'all' | 'filtered' | 'selected';

/**
 * "Exportar" on the device list: the fleet inventory as Excel or CSV, for every device the user may see, for what
 * the table is showing (its filters and search) or for the selected rows.
 */
export function FleetExportMenu({
  groupId,
  groupName,
  filteredNumbers,
  selectedNumbers,
  filtersActive,
}: {
  /** The folder filter, when one is chosen. */
  groupId?: number;
  groupName?: string;
  /** Numbers of the rows the table shows now. */
  filteredNumbers: string[];
  selectedNumbers: string[];
  /** A filter other than the folder is on (status, policy, Android, search, duplicates). */
  filtersActive: boolean;
}) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [format, setFormat] = useState<FleetExportFormat>('xlsx');
  const [sep, setSep] = useState<CsvSeparator>(() => {
    try {
      const v = localStorage.getItem('dc.export.sep');
      return v === ',' || v === 'tab' || v === '|' ? v : ';';
    } catch { return ';'; }
  });
  const [scope, setScope] = useState<Scope>('all');
  const ref = useRef<HTMLDivElement | null>(null);

  // Each time it opens, start from the narrowest thing the user is looking at.
  useEffect(() => {
    if (!open) return;
    setScope(selectedNumbers.length > 0 ? 'selected' : filtersActive ? 'filtered' : 'all');
    const onDown = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  async function download() {
    setBusy(true);
    try {
      try { localStorage.setItem('dc.export.sep', sep); } catch { /* storage blocked */ }
      await downloadFleetExport({
        format,
        separator: sep,
        groupId: scope === 'all' ? groupId : undefined,
        numbers: scope === 'selected' ? selectedNumbers : scope === 'filtered' ? filteredNumbers : undefined,
      });
      setOpen(false);
    } catch (e) {
      toast.push('err', 'No se pudo descargar', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  const allLabel = groupId != null ? `Toda la carpeta ${groupName ?? ''} y sus subcarpetas` : 'Todos los dispositivos';

  return (
    <div className="cols-menu" ref={ref}>
      <button className="btn" data-testid="export-excel" aria-haspopup="true" aria-expanded={open}
        title="Inventario de la flota en Excel o CSV: batería, almacenamiento, Android, conexión y red de cada equipo"
        onClick={() => setOpen(!open)}>
        Exportar
      </button>
      {open && (
        <div className="cols-pop export-pop" role="dialog" aria-label="Exportar dispositivos">
          <div className="cols-pop-head"><span>Exportar dispositivos</span></div>

          <div className="export-group">
            <span className="export-k">Qué exportar</span>
            <label><input type="radio" name="exp-scope" checked={scope === 'all'} onChange={() => setScope('all')} /> {allLabel}</label>
            <label>
              <input type="radio" name="exp-scope" checked={scope === 'filtered'} onChange={() => setScope('filtered')} disabled={filteredNumbers.length === 0} />
              {' '}Lo que muestra la tabla ({filteredNumbers.length})
            </label>
            <label>
              <input type="radio" name="exp-scope" checked={scope === 'selected'} onChange={() => setScope('selected')} disabled={selectedNumbers.length === 0} />
              {' '}Los seleccionados ({selectedNumbers.length})
            </label>
          </div>

          <div className="export-group">
            <span className="export-k">Formato</span>
            <label><input type="radio" name="exp-format" checked={format === 'xlsx'} onChange={() => setFormat('xlsx')} /> Excel (.xlsx) con resumen, carpetas y conexiones</label>
            <label><input type="radio" name="exp-format" checked={format === 'csv'} onChange={() => setFormat('csv')} /> CSV (UTF-8), una fila por dispositivo</label>
          </div>

          {format === 'csv' && (
            <div className="export-group">
              <span className="export-k">Separador</span>
              <select className="sel" value={sep} onChange={(e) => setSep(e.target.value as CsvSeparator)} aria-label="Separador del CSV">
                <option value=";">Punto y coma ( ; ) — Excel en español</option>
                <option value=",">Coma ( , )</option>
                <option value="tab">Tabulador</option>
                <option value="|">Barra vertical ( | )</option>
              </select>
            </div>
          )}

          <p className="note" style={{ margin: '4px 0 10px' }}>
            Los datos son los del último reporte de cada equipo; la columna «Última muestra» dice de cuándo.
          </p>
          <div className="modal-actions" style={{ marginTop: 0 }}>
            <button className="btn btn-sm" onClick={() => setOpen(false)} disabled={busy}>Cancelar</button>
            <button className="btn btn-sm btn-primary" data-testid="export-download" onClick={() => void download()} disabled={busy}>
              {busy ? 'Generando…' : 'Descargar'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
