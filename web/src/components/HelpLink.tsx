import { useEffect, useState, type ReactNode } from 'react';
import { fallbackHref, loadHelpIndex, sectionHref } from '../data/help';

/**
 * Enlace a una sección de un manual (PDF en /manuales). Mientras el índice no ha cargado apunta a la página Ayuda
 * con la búsqueda puesta, así nunca queda un enlace roto.
 */
export function HelpLink({ manual, seccion, tema, children, className, title }: {
  manual: string;
  seccion?: string;
  /** Texto de búsqueda de respaldo en la página Ayuda. */
  tema: string;
  children?: ReactNode;
  className?: string;
  title?: string;
}) {
  const [href, setHref] = useState(fallbackHref(tema));
  useEffect(() => {
    let alive = true;
    loadHelpIndex()
      .then((ix) => { const h = sectionHref(ix, manual, seccion); if (alive && h) setHref(h); })
      .catch(() => undefined);
    return () => { alive = false; };
  }, [manual, seccion]);
  return (
    <a className={className ?? 'help-link'} href={href} target="_blank" rel="noopener"
       title={title ?? 'Abrir la ayuda de este tema'} data-testid="help-link">
      {children ?? '¿Cómo se resuelve?'}
    </a>
  );
}
