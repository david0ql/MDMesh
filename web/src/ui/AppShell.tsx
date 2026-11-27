import { useEffect, useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { isFolderAdmin, hasPermission } from '../api/auth';
import { getConsoleBrand, type ConsoleBrand } from '../api/fleet';
import { useTheme } from './theme';
import { UpdateBanner } from '../components/UpdateBanner';
import { ReloadPrompt } from '../components/ReloadPrompt';
import {
  IconDashboard,
  IconBoard,
  IconDevices,
  IconMap,
  IconGroups,
  IconConfig,
  IconApps,
  IconReports,
  IconAlert,
  IconScript,
  IconEnroll,
  IconAnnounce,
  IconSettings,
  IconUsers,
  IconRoles,
  IconSignOut,
  IconMenu,
  IconSun,
  IconMoon,
  IconZone,
  IconAudit,
} from './icons';

// Cached across page navigations (AppShell remounts per page) so the header brand does not flicker.
let consoleBrandCache: ConsoleBrand | null | undefined;

/** The DallyControl wordmark, the console's default brand. */
function DefaultWordmark() {
  return (
    <span className="wordmark" aria-label="DallyControl">
      <span className="bullet" aria-hidden="true" />
      <span>
        <span className="mdm">Dally</span>
        <span className="esh">Control</span>
      </span>
    </span>
  );
}

interface NavEntry {
  to: string;
  label: string;
  Icon: (p: { className?: string }) => ReactNode;
  /** Hidden for folder administrators (they manage only their folders' devices). */
  adminOnly?: boolean;
  /** Shown only to users holding this permission (folder editors included); the server still scopes the data. */
  perm?: string;
}

const NAV: NavEntry[] = [
  { to: '/dashboard', label: 'Resumen', Icon: IconDashboard },
  { to: '/tablero', label: 'Tablero', Icon: IconBoard },
  { to: '/groups', label: 'Carpetas', Icon: IconGroups },
  { to: '/devices', label: 'Dispositivos', Icon: IconDevices },
  { to: '/map', label: 'Mapa', Icon: IconMap },
  { to: '/configs', label: 'Políticas', Icon: IconConfig, perm: 'configurations' },
  { to: '/apps', label: 'Aplicaciones', Icon: IconApps, perm: 'applications' },
  { to: '/announcements', label: 'Anuncios', Icon: IconAnnounce },
  { to: '/reports', label: 'Reportes', Icon: IconReports, perm: 'manage_fleet' },
  { to: '/alerts', label: 'Alertas', Icon: IconAlert, perm: 'manage_fleet' },
  { to: '/scripts', label: 'Scripts', Icon: IconScript, perm: 'manage_scripts' },
  { to: '/enroll', label: 'Inscribir', Icon: IconEnroll, perm: 'enroll_devices' },
  { to: '/users', label: 'Usuarios', Icon: IconUsers, perm: 'manage_users' },
  { to: '/roles', label: 'Roles', Icon: IconRoles, adminOnly: true, perm: 'settings' },
  { to: '/settings', label: 'Ajustes', Icon: IconSettings, adminOnly: true, perm: 'settings' },
  { to: '/geofences', label: 'Geocercas', Icon: IconZone, perm: 'manage_fleet' },
  { to: '/audit', label: 'Auditoría', Icon: IconAudit, perm: 'view_audit' },
];

export function AppShell({
  title,
  children,
}: {
  /** Page label, shown only in the mobile top bar. */
  title?: string;
  children: ReactNode;
}) {
  const { user, signOut } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);
  // Per-client console branding (header), resolved from the signed-in user's folders. Empty for full administrators.
  const [brand, setBrand] = useState<ConsoleBrand | null | undefined>(consoleBrandCache);

  useEffect(() => {
    if (consoleBrandCache !== undefined) return; // already resolved this session
    let alive = true;
    getConsoleBrand()
      .then((b) => { const v = b && (b.logoUrl || b.name) ? b : null; consoleBrandCache = v; if (alive) setBrand(v); })
      .catch(() => { consoleBrandCache = null; if (alive) setBrand(null); });
    return () => { alive = false; };
  }, []);

  const branded = brand && (brand.logoUrl || brand.name);

  return (
    <div className="shell">
      <div
        className={`scrim ${open ? 'show' : ''}`}
        onClick={close}
        aria-hidden="true"
      />
      <aside className={`sidebar ${open ? 'open' : ''}`}>
        <div className="sidebar-brand">
          {branded ? (
            <span className="console-brand" aria-label={brand?.name || 'Marca de consola'}>
              {brand?.logoUrl && <img className="console-brand-logo" src={brand.logoUrl} alt={brand.name || ''} />}
              {brand?.name && <span className="console-brand-name">{brand.name}</span>}
            </span>
          ) : (
            <DefaultWordmark />
          )}
        </div>
        <nav className="nav">
          {NAV.filter((n) => (!n.adminOnly || !isFolderAdmin(user)) && (!n.perm || hasPermission(user, n.perm))).map(({ to, label, Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
              onClick={close}
            >
              <Icon className="ico" />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className="sidebar-user">
            <span className="who">
              {user?.login || user?.name || 'admin@localhost'}
            </span>
          </div>
          <button
            className="btn btn-ghost"
            onClick={toggleTheme}
            aria-label={`Cambiar a tema ${theme === 'dark' ? 'claro' : 'oscuro'}`}
          >
            {theme === 'dark' ? <IconSun className="ico" /> : <IconMoon className="ico" />}
            <span style={{ marginLeft: 8 }}>{theme === 'dark' ? 'Claro' : 'Oscuro'}</span>
          </button>
          <button className="btn btn-ghost" onClick={() => { consoleBrandCache = undefined; void signOut(); }}>
            <IconSignOut className="ico" />
            <span style={{ marginLeft: 8 }}>Cerrar sesión</span>
          </button>
        </div>
      </aside>

      <div className="main">
        <div className="rail-mobilebar">
          <button
            className="btn btn-ghost menu-btn"
            onClick={() => setOpen((v) => !v)}
            aria-label="Mostrar u ocultar navegación"
          >
            <IconMenu />
          </button>
          <span style={{ fontWeight: 600 }}>{title ?? (branded ? brand?.name || 'Consola' : 'DallyControl')}</span>
        </div>
        <main className="content route-enter"><ReloadPrompt /><UpdateBanner />{children}</main>
      </div>
    </div>
  );
}
