import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { fold, loadHelpIndex, MANUALS_BASE, type HelpIndex, type HelpManual, type HelpSection } from '../data/help';

interface Hit { manual: HelpManual; seccion: HelpSection }

const size = (kb: number) => (kb >= 1024 ? `${(kb / 1024).toFixed(1).replace('.', ',')} MB` : `${kb} KB`);

/** Centro de ayuda: lista los manuales (PDF estáticos), los abre o descarga y busca por título o tema. */
export function HelpPage() {
  const [params, setParams] = useSearchParams();
  const [index, setIndex] = useState<HelpIndex | null>(null);
  const [error, setError] = useState(false);
  const q = params.get('tema') ?? '';

  useEffect(() => {
    let alive = true;
    loadHelpIndex().then((ix) => { if (alive) setIndex(ix); }).catch(() => { if (alive) setError(true); });
    return () => { alive = false; };
  }, []);

  const { manuals, hits } = useMemo(() => {
    const words = fold(q).split(/\s+/).filter(Boolean);
    const matches = (text: string) => { const t = fold(text); return words.every((w) => t.includes(w)); };
    if (!index) return { manuals: [] as HelpManual[], hits: [] as Hit[] };
    if (words.length === 0) return { manuals: index.manuales, hits: [] as Hit[] };
    return {
      manuals: index.manuales.filter((m) => matches(`${m.titulo} ${m.descripcion} ${m.publico}`)
        || m.secciones.some((s) => matches(`${s.titulo} ${s.temas}`))),
      hits: index.manuales.flatMap((manual) => manual.secciones
        .filter((s) => matches(`${s.titulo} ${s.temas}`))
        .map((seccion) => ({ manual, seccion }))),
    };
  }, [index, q]);
  const searching = q.trim() !== '';

  return (
    <AppShell title="Ayuda">
      <div className="page-head">
        <h1>Ayuda</h1>
        <div className="search-row">
          <input
            className="input"
            type="search"
            placeholder="Buscar por título o tema"
            aria-label="Buscar en los manuales"
            value={q}
            onChange={(e) => setParams(e.target.value ? { tema: e.target.value } : {}, { replace: true })}
            data-testid="help-search"
            autoFocus
          />
        </div>
      </div>
      <p className="note" style={{ marginTop: 0 }}>
        Manuales de {index?.producto ?? 'la consola'} en PDF. <b>Abrir</b> lo muestra en una pestaña nueva; <b>Descargar</b> lo
        guarda en tu equipo. El botón <b>?</b> de cada pantalla abre directamente la sección que la explica.
        {index ? ` Edición: ${index.edicion}.` : ''}
      </p>

      {error && (
        <div className="empty" data-testid="help-error">
          <span className="label">No se pudieron cargar los manuales</span>
          Recarga la página. Si sigue igual, avisa al administrador del servidor.
        </div>
      )}
      {!index && !error && <div className="empty">Cargando manuales…</div>}

      {index && searching && (
        <section className="panel" data-testid="help-hits">
          <div className="panel-head keep">
            <h2 className="panel-title">Temas que coinciden con «{q}»</h2>
            <span className="muted">{hits.length === 1 ? '1 sección' : `${hits.length} secciones`}</span>
          </div>
          {hits.length === 0 ? (
            <div className="empty">Ningún tema coincide. Prueba con otra palabra, por ejemplo «QR», «quiosco» o «sin conexión».</div>
          ) : (
            <ul className="help-hits">
              {hits.map(({ manual, seccion }) => (
                <li key={`${manual.id}#${seccion.id}`}>
                  <a href={`${MANUALS_BASE}/${manual.archivo}#page=${seccion.pagina}`} target="_blank" rel="noopener">{seccion.titulo}</a>
                  <span className="muted"> · {manual.titulo}, página {seccion.pagina}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {index && (
        <div className="help-grid" data-testid="help-manuals">
          {manuals.map((m) => (
            <article className="panel help-card" key={m.id} data-testid="help-manual">
              <h2 className="panel-title">{m.titulo}</h2>
              <p className="note">{m.descripcion}</p>
              <p className="muted help-meta">Para: {m.publico}</p>
              <p className="muted help-meta">{m.paginas} {m.paginas === 1 ? 'página' : 'páginas'} · PDF · {size(m.kb)}</p>
              <div className="help-actions">
                <a className="btn btn-primary" href={`${MANUALS_BASE}/${m.archivo}`} target="_blank" rel="noopener">Abrir</a>
                <a className="btn" href={`${MANUALS_BASE}/${m.archivo}`} download={`${index.producto}-${m.archivo}`}>Descargar</a>
              </div>
              <details className="help-toc">
                <summary>Contenido ({m.secciones.length} {m.secciones.length === 1 ? 'sección' : 'secciones'})</summary>
                <ul>
                  {m.secciones.map((s) => (
                    <li key={s.id}>
                      <a href={`${MANUALS_BASE}/${m.archivo}#page=${s.pagina}`} target="_blank" rel="noopener">{s.titulo}</a>
                      <span className="muted"> · pág. {s.pagina}</span>
                    </li>
                  ))}
                </ul>
              </details>
            </article>
          ))}
          {manuals.length === 0 && <div className="empty">Ningún manual coincide con la búsqueda.</div>}
        </div>
      )}
    </AppShell>
  );
}
