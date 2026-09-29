import { useCallback, useEffect, useMemo, useState } from 'react';
import { useToast } from '../ui/toast';
import { fmtDateTime } from '../ui/format';
import { QrCanvas } from './QrCanvas';
import { buildProvisioningPayload, serverBaseUrl } from '../enroll/provisioning';
import { groupTree, type FleetGroup } from '../api/fleet';
import {
  createEnrollmentCode, deleteEnrollmentCode, displayCode, listEnrollmentCodes, revokeEnrollmentCode, type EnrollmentCode,
} from '../api/enroll';

/**
 * A folder's enrollment policy (MobiControl's "enrollment rule"): a reusable code that sends every phone enrolled
 * with it to that folder. The technician types it on the phone (DallyControl agent → Enrollment code) or scans its QR;
 * it keeps working for any number of phones until revoked. Handy for teams that enroll their own phones (Ecuador,
 * provisioning partners).
 */
export function EnrollmentCodesPanel({ groups }: { groups: FleetGroup[] }) {
  const toast = useToast();
  const [codes, setCodes] = useState<EnrollmentCode[] | null>(null);
  const [folder, setFolder] = useState('');
  const [label, setLabel] = useState('');
  const [ssid, setSsid] = useState('');
  const [wifiPass, setWifiPass] = useState('');
  const [wifiSec, setWifiSec] = useState<'WPA' | 'WEP' | 'NONE'>('WPA');
  const [busy, setBusy] = useState(false);
  const [shown, setShown] = useState<EnrollmentCode | null>(null);
  const tree = useMemo(() => groupTree(groups), [groups]);
  const pathOf = useMemo(() => new Map(tree.map((n) => [n.group.id, n.path])), [tree]);

  const load = useCallback(async () => {
    try { setCodes(await listEnrollmentCodes()); } catch { setCodes([]); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function create() {
    if (!folder) return;
    setBusy(true);
    try {
      const c = await createEnrollmentCode(Number(folder), label.trim() || undefined, undefined,
        ssid.trim() ? { ssid: ssid.trim(), password: wifiPass, security: wifiSec } : undefined);
      toast.push('ok', 'Code created', `${displayCode(c.code)} → ${pathOf.get(c.groupId ?? -1) ?? c.groupName}`);
      setLabel(''); setSsid(''); setWifiPass('');
      setShown(c);
      await load();
    } catch (e) {
      toast.push('err', 'Could not create the code', e instanceof Error ? e.message : '');
    } finally {
      setBusy(false);
    }
  }

  async function revoke(c: EnrollmentCode) {
    if (!window.confirm(`Revoke ${displayCode(c.code)}? Phones already enrolled stay; the code stops working.`)) return;
    try {
      await revokeEnrollmentCode(c.id);
      toast.push('ok', 'Code revoked', displayCode(c.code));
      if (shown?.id === c.id) setShown(null);
      await load();
    } catch (e) {
      toast.push('err', 'Could not revoke', e instanceof Error ? e.message : '');
    }
  }

  async function remove(c: EnrollmentCode) {
    try {
      await deleteEnrollmentCode(c.id);
      await load();
    } catch (e) {
      toast.push('err', 'Could not delete', e instanceof Error ? e.message : '');
    }
  }

  return (
    <section className="panel enroll-wrap" data-testid="enrollment-codes">
      <div className="panel-head">
        <h2 className="panel-title">Folder enrollment codes</h2>
      </div>
      <div style={{ padding: 20 }}>
        <p className="note" style={{ marginTop: 0 }}>
          A reusable code per folder: every phone enrolled with it lands in that folder and takes its configuration. Type it
          on the phone (DallyControl → <b>Enrollment code</b>, server <span className="mono">{serverBaseUrl()}</span>) or scan
          its QR on a factory-reset phone. It works for any number of phones until you revoke it.
        </p>
        <div className="codes-new">
          <select className="sel" value={folder} onChange={(e) => setFolder(e.target.value)} aria-label="Folder for the code">
            <option value="">Folder…</option>
            {tree.map((n) => <option key={n.group.id} value={String(n.group.id)}>{n.path}</option>)}
          </select>
          <input className="input" value={label} maxLength={100} placeholder="Label (optional), e.g. Ecuador ops"
                 onChange={(e) => setLabel(e.target.value)} aria-label="Code label" />
          <button className="btn btn-primary" disabled={busy || !folder} onClick={() => void create()}>Create code</button>
        </div>
        <div className="codes-new" data-testid="code-wifi">
          <input className="input" value={ssid} maxLength={32} placeholder="Wi‑Fi (opcional): nombre de la red"
                 onChange={(e) => setSsid(e.target.value)} aria-label="Wi-Fi SSID" />
          <input className="input" type="password" value={wifiPass} maxLength={63} placeholder="Contraseña del Wi‑Fi"
                 disabled={wifiSec === 'NONE'} onChange={(e) => setWifiPass(e.target.value)} aria-label="Wi-Fi password" />
          <select className="sel" value={wifiSec} onChange={(e) => setWifiSec(e.target.value as 'WPA' | 'WEP' | 'NONE')} aria-label="Wi-Fi security">
            <option value="WPA">WPA/WPA2</option><option value="WEP">WEP</option><option value="NONE">Abierta</option>
          </select>
        </div>
        <p className="note" style={{ marginTop: -8 }}>
          Con Wi‑Fi, el QR del código conecta el teléfono formateado a esa red para descargar el agente. Para que la red quede
          guardada en todos los teléfonos de la carpeta, agrégala también en la configuración (Redes Wi‑Fi).
        </p>

        {codes && codes.length === 0 && <p className="muted">No codes yet.</p>}
        {codes && codes.length > 0 && (
          <table className="gr-table codes-table">
            <thead><tr><th>Code</th><th>Folder</th><th>Label</th><th>Phones</th><th>Created</th><th aria-label="Actions" /></tr></thead>
            <tbody>
              {codes.map((c) => (
                <tr key={c.id} className={c.revoked ? 'codes-revoked' : ''}>
                  <td data-label="Code"><span className="mono code-big">{displayCode(c.code)}</span></td>
                  <td data-label="Folder">{(c.groupId != null && pathOf.get(c.groupId)) || c.groupName || '—'}</td>
                  <td data-label="Label">{c.label ?? '—'}</td>
                  <td data-label="Phones">{c.uses}</td>
                  <td data-label="Created">{fmtDateTime(c.createdAt)}</td>
                  <td>
                    {c.revoked ? (
                      <div className="gr-actions">
                        <span className="muted">Revoked</span>
                        <button className="btn btn-sm btn-ghost" onClick={() => void remove(c)}>Delete</button>
                      </div>
                    ) : (
                      <div className="gr-actions">
                        <button className="btn btn-sm" onClick={() => setShown(c)}>QR</button>
                        <button className="btn btn-sm btn-ghost gr-del" onClick={() => void revoke(c)}>Revoke</button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {shown && !shown.revoked && (
          <div className="codes-qr">
            <div className="qr-frame"><QrCanvas text={buildProvisioningPayload(shown.code, shown.wifiSsid
              ? { ssid: shown.wifiSsid, password: shown.wifiPassword ?? '', security: (shown.wifiSecurity ?? 'WPA') as 'WPA' | 'WEP' | 'NONE' }
              : undefined)} size={260} /></div>
            <div>
              <div className="mono code-big" style={{ fontSize: 28 }}>{displayCode(shown.code)}</div>
              <p className="note">
                {(shown.groupId != null && pathOf.get(shown.groupId)) || shown.groupName}
                {shown.label ? ` · ${shown.label}` : ''}{shown.wifiSsid ? ` · Wi‑Fi ${shown.wifiSsid}` : ''}. Reusable: print it for the team that enrolls these phones.
              </p>
              <button className="btn btn-sm" onClick={() => setShown(null)}>Close</button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
