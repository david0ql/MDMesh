import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AppShell } from '../ui/AppShell';
import { useAuth } from '../auth/AuthContext';
import { useTheme } from '../ui/theme';
import { listConfigurations, type ConfigurationSummary } from '../api/configurations';
import { API_BASE } from '../api/client';
import { fetchAuthOptions } from '../api/auth';
import { getUpdateStatus, setAutoUpdate, checkForUpdates, applyUpdate, type UpdateStatus } from '../api/updates';
import { RolloutPanel } from '../components/RolloutPanel';
import { orDash, fmtRelative } from '../ui/format';
import { useToast } from '../ui/toast';
import { listGroups, setGlobalConfiguration } from '../api/fleet';

const APP_VERSION = '0.1.0';

type Conn = 'checking' | 'ok' | 'down';

export function SettingsPage() {
  const navigate = useNavigate();
  const { user, signOut } = useAuth();
  const { theme, setTheme, density, setDensity } = useTheme();
  const toast = useToast();
  const [configList, setConfigList] = useState<ConfigurationSummary[]>([]);
  const [conn, setConn] = useState<Conn>('checking');
  const [upd, setUpd] = useState<UpdateStatus | null>(null);
  const [autoSaving, setAutoSaving] = useState(false);
  const [autoErr, setAutoErr] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [applying, setApplying] = useState(false);
  const [updMsg, setUpdMsg] = useState<string | null>(null);
  // The global configuration: devices without a group configuration or their own run it.
  const [defaultConfig, setDefaultConfig] = useState<string>('');
  useEffect(() => {
    listGroups().then((o) => setDefaultConfig(o.global.configurationId == null ? '' : String(o.global.configurationId)))
      .catch(() => undefined);
  }, []);
  async function changeGlobal(value: string) {
    if (!value) return;
    const before = defaultConfig;
    setDefaultConfig(value);
    try {
      const r = await setGlobalConfiguration(Number(value));
      toast.push('ok', 'Configuración global cambiada',
        r.devicesReconfigured ? `${r.devicesReconfigured} dispositivo(s) reconfigurado(s).` : 'Ningún dispositivo necesitó cambios.');
    } catch (e) {
      setDefaultConfig(before);
      toast.push('err', 'No se pudo cambiar', e instanceof Error ? e.message : '');
    }
  }

  useEffect(() => {
    let cancelled = false;
    fetchAuthOptions()
      .then(() => !cancelled && setConn('ok'))
      .catch(() => !cancelled && setConn('down'));
    listConfigurations()
      .then((list) => {
        if (!cancelled)
          setConfigList([...list].sort((a, b) => a.name.localeCompare(b.name)));
      })
      .catch(() => undefined);
    getUpdateStatus()
      .then((x) => !cancelled && setUpd(x))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const checkNow = async () => {
    setChecking(true);
    setUpdMsg(null);
    const x = await checkForUpdates();
    setChecking(false);
    if (x) setUpd(x);
    else setUpdMsg('No se pudo buscar actualizaciones.');
  };

  const applyNow = async () => {
    if (
      !window.confirm(
        `¿Actualizar a v${upd?.latest}?\n\nEl servidor se reinicia por un momento. Primero se respalda la base de datos `
          + 'y la actualización se revierte automáticamente si falla.',
      )
    )
      return;
    setApplying(true);
    setUpdMsg(null);
    const r = await applyUpdate();
    setApplying(false);
    if (!r.ok) setUpdMsg(r.error || 'No se pudo iniciar la actualización.');
    else setUpdMsg('Actualización iniciada: sigue el progreso en el aviso de la parte superior.');
  };

  const scrollToRollout = () =>
    document.getElementById('rollout-anchor')?.scrollIntoView({ behavior: 'smooth', block: 'start' });

  const toggleAuto = async (next: boolean) => {
    setAutoSaving(true);
    setAutoErr(null);
    const r = await setAutoUpdate(next);
    setAutoSaving(false);
    if (!r.ok) {
      setAutoErr(r.error || 'No se pudo guardar');
      return;
    }
    setUpd((p) => (p ? { ...p, auto: next } : p));
  };


  const connMeta: Record<Conn, { tone: string; label: string }> = {
    checking: { tone: 'idle', label: 'Comprobando…' },
    ok: { tone: 'ok', label: 'Conectado' },
    down: { tone: 'alert', label: 'Sin acceso' },
  };
  const cm = connMeta[conn];

  return (
    <AppShell title="Ajustes">
      <div className="page-head">
        <h1>Ajustes</h1>
      </div>

      <div className="settings">
        {/* Account & session */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Cuenta</h2>
            <button className="btn btn-sm" onClick={() => void signOut()}>
              Cerrar sesión
            </button>
          </div>
          <div className="set-row">
            <span className="k">Sesión iniciada como</span>
            <span className="v">{orDash(user?.name || user?.login)}</span>
          </div>
          <div className="set-row">
            <span className="k">Usuario</span>
            <span className="v mono">{orDash(user?.login)}</span>
          </div>
          <div className="set-row">
            <span className="k">Correo</span>
            <span className="v mono">{orDash(user?.email)}</span>
          </div>
          <div className="set-row">
            <span className="k">Rol</span>
            <span className="v">{user?.superAdmin ? 'Superadministrador' : 'Administrador'}</span>
          </div>
        </section>

        {/* Server & connection */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Servidor y conexión</h2>
          </div>
          <div className="set-row">
            <span className="k">Estado</span>
            <span className="v">
              <span className="conn">
                <span className={`dot dot-${cm.tone}`} />
                {cm.label}
              </span>
            </span>
          </div>
          <div className="set-row">
            <span className="k">Base de la API</span>
            <span className="v mono">{API_BASE || '(mismo origen)'}</span>
          </div>
          <div className="set-row">
            <span className="k">Versión de la consola</span>
            <span className="v mono">DallyControl {APP_VERSION}</span>
          </div>
        </section>

        {/* Updates */}
        {upd && (
          <section className="panel">
            <div className="panel-head">
              <h2 className="panel-title">Actualizaciones</h2>
            </div>
            <div className="set-row">
              <span className="k">Versión en ejecución</span>
              <span className="v mono">{orDash(upd.current)}</span>
            </div>
            <div className="set-row">
              <span className="k">Última disponible</span>
              <span className="v mono">
                {orDash(upd.latest)}
                {upd.latest && upd.verified ? ' ✓' : ''}
                {upd.channel ? ` (${upd.channel})` : ''}
              </span>
            </div>
            {upd.updateAvailable && upd.release?.notes && (
              <div className="set-row">
                <span className="k">
                  Novedades
                  <small>Notas de la versión v{orDash(upd.latest)}.</small>
                </span>
                <span className="v">
                  <div className="whatsnew">{upd.release.notes}</div>
                  {upd.release.url && (
                    <a className="whatsnew-link" href={upd.release.url} target="_blank" rel="noreferrer">
                      Notas completas de la versión ↗
                    </a>
                  )}
                </span>
              </div>
            )}
            {upd.updateAvailable && (
              <div className="set-row">
                <span className="k">
                  Aplicar esta versión
                  <small>
                    {upd.applySupported === false
                      ? 'El APK del agente se despliega a los dispositivos; el servidor y la consola se actualizan con el instalador.'
                      : 'El servidor y la consola se actualizan ahora; el APK del agente se despliega a los dispositivos.'}
                  </small>
                </span>
                <span className="v">
                  <div className="upd-actions">
                    {upd.applySupported !== false && (
                      <button
                        className="btn btn-sm btn-primary"
                        onClick={() => void applyNow()}
                        disabled={applying || !upd.verified}
                      >
                        {applying ? <span key="busy">Iniciando…</span> : <span key="idle">Actualizar servidor y consola</span>}
                      </button>
                    )}
                    <button className="btn btn-sm" onClick={scrollToRollout}>
                      Desplegar el agente a los dispositivos ↓
                    </button>
                  </div>
                  {upd.applySupported === false && (
                    <p className="au-note">
                      Esta instalación se hizo desde el código fuente: actualiza el servidor y la consola con el
                      comando de tu instalación. Docker (desde el código): <span className="mono">git pull && ./setup.sh</span> · Nativa:{' '}
                      <span className="mono">git pull && sudo ./install/install-native.sh</span>
                    </p>
                  )}
                  {!upd.verified && (
                    <p className="au-note" style={{ color: 'var(--err)' }}>
                      La firma de la versión no está verificada: no se puede aplicar.
                    </p>
                  )}
                </span>
              </div>
            )}
            <div className="set-row">
              <span className="k">
                Buscar actualizaciones
                <small>
                  {upd.checkedAt ? `Última comprobación: ${fmtRelative(upd.checkedAt)}.` : 'Aún no se ha comprobado.'}
                </small>
                {updMsg && <p className="au-note">{updMsg}</p>}
              </span>
              <span className="v">
                <button className="btn btn-sm" onClick={() => void checkNow()} disabled={checking}>
                  {checking ? <span key="busy">Comprobando…</span> : <span key="idle">Comprobar ahora</span>}
                </button>
              </span>
            </div>
            {upd.applySupported !== false && (
            <div className="set-row auto-update-row">
              <span className="k">
                Actualizaciones automáticas
                <small>Aplica las versiones verificadas sin preguntar.</small>
                <p className="au-note">
                  Si está activado, el actualizador aplica cada versión verificada por su cuenta: primero respalda la
                  base de datos y revierte automáticamente si algo falla. Déjalo desactivado para revisar y hacer
                  clic en Actualizar cada vez.
                </p>
                {autoErr && <p className="au-note" style={{ color: 'var(--err)' }}>{autoErr}</p>}
              </span>
              <span className="v">
                <span className="seg">
                  <button
                    className={upd.auto ? 'on' : ''}
                    onClick={() => void toggleAuto(true)}
                    disabled={autoSaving}
                  >
                    Activado
                  </button>
                  <button
                    className={!upd.auto ? 'on' : ''}
                    onClick={() => void toggleAuto(false)}
                    disabled={autoSaving}
                  >
                    Desactivado
                  </button>
                </span>
              </span>
            </div>
            )}
          </section>
        )}

        {/* Staged agent-APK rollout (renders itself only when there's an apk to roll out or an active rollout) */}
        <div id="rollout-anchor">
          <RolloutPanel />
        </div>

        {/* Enrollment defaults */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Configuración global</h2>
            <button
              className="btn btn-sm btn-primary"
              onClick={() => navigate('/enroll')}
            >
              Inscribir un dispositivo →
            </button>
          </div>
          <div className="set-row">
            <span className="k">
              Configuración por defecto
              <small>Para todo dispositivo cuya carpeta no tenga configuración y que no tenga una propia.</small>
            </span>
            <span className="v">
              <select
                className="sel"
                value={defaultConfig}
                onChange={(e) => void changeGlobal(e.target.value)}
              >
                {defaultConfig === '' && <option value="">Seleccionar…</option>}
                {configList.map((c) => (
                  <option key={c.id} value={String(c.id)}>
                    {c.name}
                  </option>
                ))}
              </select>
            </span>
          </div>
          {configList.length === 0 && (
            <div className="set-row">
              <span className="k" style={{ fontWeight: 400 }}>
                Aún no hay configuraciones asignadas a dispositivos.
              </span>
            </div>
          )}
        </section>

        {/* Appearance */}
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">Apariencia</h2>
          </div>
          <div className="set-row">
            <span className="k">
              Tema
              <small>Cambia con una transición suave.</small>
            </span>
            <span className="v">
              <span className="seg">
                <button
                  className={theme === 'light' ? 'on' : ''}
                  onClick={() => setTheme('light')}
                >
                  Claro
                </button>
                <button
                  className={theme === 'dark' ? 'on' : ''}
                  onClick={() => setTheme('dark')}
                >
                  Oscuro
                </button>
              </span>
            </span>
          </div>
          <div className="set-row">
            <span className="k">
              Densidad
              <small>Reduce el espaciado en toda la consola.</small>
            </span>
            <span className="v">
              <span className="seg">
                <button
                  className={density === 'comfortable' ? 'on' : ''}
                  onClick={() => setDensity('comfortable')}
                >
                  Cómoda
                </button>
                <button
                  className={density === 'compact' ? 'on' : ''}
                  onClick={() => setDensity('compact')}
                >
                  Compacta
                </button>
              </span>
            </span>
          </div>
        </section>
      </div>
    </AppShell>
  );
}
