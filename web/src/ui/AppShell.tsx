import { useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useTheme } from './theme';
import { UpdateBanner } from '../components/UpdateBanner';
import { ReloadPrompt } from '../components/ReloadPrompt';
import {
  IconDashboard,
  IconDevices,
  IconMap,
  IconGroups,
  IconConfig,
  IconApps,
  IconEnroll,
  IconAnnounce,
  IconSettings,
  IconSignOut,
  IconMenu,
  IconSun,
  IconMoon,
} from './icons';

interface NavEntry {
  to: string;
  label: string;
  Icon: (p: { className?: string }) => ReactNode;
}

const NAV: NavEntry[] = [
  { to: '/dashboard', label: 'Resumen', Icon: IconDashboard },
  { to: '/groups', label: 'Carpetas', Icon: IconGroups },
  { to: '/devices', label: 'Dispositivos', Icon: IconDevices },
  { to: '/map', label: 'Mapa', Icon: IconMap },
  { to: '/configs', label: 'Políticas', Icon: IconConfig },
  { to: '/apps', label: 'Aplicaciones', Icon: IconApps },
  { to: '/announcements', label: 'Anuncios', Icon: IconAnnounce },
  { to: '/enroll', label: 'Inscribir', Icon: IconEnroll },
  { to: '/settings', label: 'Ajustes', Icon: IconSettings },
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

  return (
    <div className="shell">
      <div
        className={`scrim ${open ? 'show' : ''}`}
        onClick={close}
        aria-hidden="true"
      />
      <aside className={`sidebar ${open ? 'open' : ''}`}>
        <div className="sidebar-brand">
          <span className="wordmark" aria-label="DallyControl">
            <span className="bullet" aria-hidden="true" />
            <span>
              <span className="mdm">Dally</span>
              <span className="esh">Control</span>
            </span>
          </span>
        </div>
        <nav className="nav">
          {NAV.map(({ to, label, Icon }) => (
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
          <button className="btn btn-ghost" onClick={() => void signOut()}>
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
          <span style={{ fontWeight: 600 }}>{title ?? 'DallyControl'}</span>
        </div>
        <main className="content route-enter"><ReloadPrompt /><UpdateBanner />{children}</main>
      </div>
    </div>
  );
}
