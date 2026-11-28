// Centro de ayuda: los manuales son archivos estáticos (web/public/manuales, generados por docs/manuales/build.sh) y
// su índice se lee en tiempo de ejecución, así que no pesan en el bundle. Aquí solo vive el mapa pantalla → sección.

export interface HelpSection { id: string; titulo: string; pagina: number; temas: string }
export interface HelpManual {
  id: string; archivo: string; titulo: string; descripcion: string; publico: string; paginas: number; kb: number;
  secciones: HelpSection[];
}
export interface HelpIndex { producto: string; empresa: string; edicion: string; version: string; manuales: HelpManual[] }

export const MANUALS_BASE = '/manuales';

let cache: Promise<HelpIndex> | undefined;
/** El índice de manuales (una sola descarga por sesión). */
export function loadHelpIndex(): Promise<HelpIndex> {
  if (!cache) {
    cache = fetch(`${MANUALS_BASE}/indice.json`, { cache: 'no-cache' }).then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return r.json() as Promise<HelpIndex>;
    });
    cache.catch(() => { cache = undefined; });
  }
  return cache;
}

export const MANUAL_ADMIN = '01-manual-administrador';
export const MANUAL_ENROLL = '02-guia-enrolamiento';
export const MANUAL_TROUBLE = '04-solucion-problemas';

/** Dirección del PDF abierto en la página de una sección (los visores de PDF de los navegadores entienden #page=N). */
export function sectionHref(index: HelpIndex, manual: string, seccion?: string): string | undefined {
  const m = index.manuales.find((x) => x.id === manual);
  if (!m) return undefined;
  const s = seccion ? m.secciones.find((x) => x.id === seccion) : undefined;
  return `${MANUALS_BASE}/${m.archivo}${s ? `#page=${s.pagina}` : ''}`;
}

/** Enlace de respaldo mientras el índice carga (o si falta): la página Ayuda con la búsqueda puesta. */
export const fallbackHref = (tema: string) => `/ayuda?tema=${encodeURIComponent(tema)}`;

export interface HelpTopic { manual: string; seccion: string; tema: string }
/** Pantalla (prefijo de la ruta) → sección del manual que la explica. Se evalúa en orden. */
const BY_ROUTE: [string, HelpTopic][] = [
  ['/dashboard', { manual: MANUAL_ADMIN, seccion: 'tablero', tema: 'resumen' }],
  ['/tablero', { manual: MANUAL_ADMIN, seccion: 'tablero', tema: 'tablero' }],
  ['/groups', { manual: MANUAL_ADMIN, seccion: 'carpetas', tema: 'carpetas' }],
  ['/devices', { manual: MANUAL_ADMIN, seccion: 'dispositivos', tema: 'dispositivos' }],
  ['/map', { manual: MANUAL_ADMIN, seccion: 'ubicacion', tema: 'mapa' }],
  ['/configs', { manual: MANUAL_ADMIN, seccion: 'politicas', tema: 'políticas' }],
  ['/apps', { manual: MANUAL_ADMIN, seccion: 'aplicaciones', tema: 'aplicaciones' }],
  ['/announcements', { manual: MANUAL_ADMIN, seccion: 'anuncios', tema: 'anuncios' }],
  ['/reports', { manual: MANUAL_ADMIN, seccion: 'reportes', tema: 'reportes' }],
  ['/alerts', { manual: MANUAL_ADMIN, seccion: 'alertas', tema: 'alertas' }],
  ['/scripts', { manual: MANUAL_ADMIN, seccion: 'anuncios', tema: 'scripts' }],
  ['/enroll', { manual: MANUAL_ENROLL, seccion: 'qr', tema: 'QR' }],
  ['/users', { manual: MANUAL_ADMIN, seccion: 'usuarios', tema: 'usuarios' }],
  ['/roles', { manual: MANUAL_ADMIN, seccion: 'roles', tema: 'roles' }],
  ['/settings', { manual: MANUAL_ADMIN, seccion: 'ajustes', tema: 'ajustes' }],
  ['/geofences', { manual: MANUAL_ADMIN, seccion: 'ubicacion', tema: 'geocercas' }],
  ['/audit', { manual: MANUAL_ADMIN, seccion: 'auditoria', tema: 'auditoría' }],
];

export function topicForPath(pathname: string): HelpTopic | undefined {
  return BY_ROUTE.find(([prefix]) => pathname === prefix || pathname.startsWith(`${prefix}/`))?.[1];
}

/** Comparación sin tildes ni mayúsculas, para el buscador. */
export const fold = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
