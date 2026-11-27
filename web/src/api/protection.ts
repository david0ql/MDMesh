import { apiClient } from './client';
import type { QueueCommandRequest } from './commands';

// Factory-reset protection (FRP), remote wipe and lost mode.
//   /rest/private/protection/v1/...

/** A company Google account that may set a phone up again after a reset from recovery. Android wants the id. */
export interface FrpAccount {
  email?: string | null;
  /** The account's numeric id (not the e-mail). */
  id: string;
}

export interface FrpDefaults {
  company: FrpAccount[];
  /** Folder id -> that folder's own accounts (folders without their own inherit). */
  folders: Record<string, FrpAccount[]>;
  canEditCompany: boolean;
  canEditFolders: boolean;
}

export type ProtectionState = 'active' | 'inactive' | 'attention' | 'unknown';

export interface DeviceProtection {
  state: ProtectionState;
  /** "FRP activa con 2 cuentas" / "FRP inactiva: …" */
  summary: string;
  expected: {
    accounts: FrpAccount[];
    source: 'policy' | 'folder' | 'company' | 'none' | 'off';
    groupId?: number | null;
    groupName?: string | null;
  };
  /** What the policy asks to block. */
  policy: {
    factoryReset: boolean; safeBoot: boolean; debugging: boolean; oemUnlock: boolean;
    maxFailedPasswordsForWipe?: number | null;
  };
  /** What the agent read back from the system; null = an older agent, or no report yet. */
  reported: null | {
    frp: { mechanism: string; active: boolean; accounts?: string[]; reason?: string | null; error?: string | null };
    restrictions?: Record<string, boolean>;
    errors?: string[];
    maxFailedPasswordsForWipe?: number;
    lostMode?: null | {
      active: boolean; since?: number | null; wipeAfterFailedUnlocks?: number | null;
      wipeIfSilentDays?: number | null; lastContactAt?: number | null;
    };
  };
  reportedAt?: number | null;
  capabilities: { wipeOptions: boolean; lostMode: boolean };
}

const BASE = '/private/protection/v1';

export const getFrpDefaults = () => apiClient.get<FrpDefaults>(`${BASE}/defaults`);

export const saveCompanyFrp = (accounts: FrpAccount[]) =>
  apiClient.put<FrpAccount[]>(`${BASE}/defaults/company`, { accounts });

export const saveFolderFrp = (groupId: number, accounts: FrpAccount[]) =>
  apiClient.put<FrpAccount[]>(`${BASE}/defaults/groups/${groupId}`, { accounts });

export const getDeviceProtection = (deviceNumber: string) =>
  apiClient.get<DeviceProtection>(`${BASE}/devices/${encodeURIComponent(deviceNumber)}`);

/** A Google account id: digits only (21 today). */
export const isFrpId = (s: string) => /^[0-9]{6,30}$/.test(s.trim());

export const isEmail = (s: string) => /^[^@\s]+@[^@\s]+\.[a-z]{2,}$/i.test(s.trim());

/** Why an account cannot be added, or null when it can. */
export function frpAccountProblem(email: string, id: string, existing: FrpAccount[]): string | null {
  const i = id.trim();
  const e = email.trim();
  if (!i) return 'Falta el ID numérico de la cuenta. Sigue los pasos de «¿Cómo obtengo el ID?».';
  if (i.includes('@')) return 'Eso es un correo. En este campo va el ID numérico de la cuenta (solo dígitos).';
  if (!isFrpId(i)) return 'El ID es solo de dígitos (normalmente 21). Cópialo tal cual, sin espacios ni letras.';
  if (e && !isEmail(e)) return 'El correo no tiene un formato válido.';
  if (existing.some((a) => a.id === i)) return 'Esa cuenta ya está en la lista.';
  if (existing.length >= 10) return 'Máximo 10 cuentas.';
  return null;
}

/** The server's validation error (`id:<value>`, `email:<value>`, `max:<n>`) in plain words. */
export function frpSaveError(message: string): string {
  if (message.includes('error.protection.account.invalid')) {
    return 'Una de las cuentas no es válida: el ID debe ser numérico y el correo debe tener formato de correo.';
  }
  if (message.includes('error.protection.folder.unknown')) return 'Esa carpeta ya no existe.';
  if (message === 'No autenticado') return 'Tu usuario no tiene permiso para cambiar estas cuentas.';
  return message;
}

// --- Remote wipe ---------------------------------------------------------------------------------------------

export type WipeIntent = 'lost' | 'reassign';

export interface WipeOptions {
  intent: WipeIntent;
  /** Keep the factory-reset protection (the phone asks for a company account after the wipe). */
  keepFrp: boolean;
  externalStorage: boolean;
  euicc: boolean;
  silent: boolean;
  reason: string;
}

/** What each intent starts with: a lost phone stays locked to the company; one being reassigned is freed. */
export function wipeDefaults(intent: WipeIntent): WipeOptions {
  return intent === 'lost'
    ? { intent, keepFrp: true, externalStorage: true, euicc: false, silent: false, reason: '' }
    : { intent, keepFrp: false, externalStorage: true, euicc: false, silent: false, reason: '' };
}

export function wipeRequest(o: WipeOptions): QueueCommandRequest {
  return {
    type: 'device.wipe',
    requiresCapability: 'device.wipe',
    payload: JSON.stringify({
      intent: o.intent,
      keepFrp: o.keepFrp,
      externalStorage: o.externalStorage || undefined,
      euicc: o.euicc || undefined,
      silent: o.silent || undefined,
      reason: o.reason.trim() || undefined,
    }),
  };
}

// --- Lost mode -----------------------------------------------------------------------------------------------

export interface LostModeOptions {
  message: string;
  phone: string;
  ring: boolean;
  wipeAfterFailedUnlocks: string;
  wipeIfSilentDays: string;
}

const intIn = (s: string, min: number, max: number): number | undefined => {
  const n = Number.parseInt(s, 10);
  return Number.isFinite(n) && n >= min && n <= max ? n : undefined;
};

/** Why the lost-mode form cannot be sent yet, or null. */
export function lostModeProblem(o: LostModeOptions): string | null {
  if (!o.message.trim() && !o.phone.trim()) return 'Escribe un mensaje o un teléfono de contacto para la pantalla de bloqueo.';
  if (o.wipeAfterFailedUnlocks.trim() && intIn(o.wipeAfterFailedUnlocks, 3, 100) === undefined) {
    return 'Los intentos fallidos van de 3 a 100 (o vacío para no borrar).';
  }
  if (o.wipeIfSilentDays.trim() && intIn(o.wipeIfSilentDays, 1, 365) === undefined) {
    return 'Los días sin reportar van de 1 a 365 (o vacío para no borrar).';
  }
  return null;
}

export function lostModeRequest(o: LostModeOptions | null): QueueCommandRequest {
  const payload = o === null ? { enabled: false } : {
    enabled: true,
    message: o.message.trim() || undefined,
    phone: o.phone.trim() || undefined,
    ringSeconds: o.ring ? 60 : undefined,
    trackMinutes: 1,
    wipeAfterFailedUnlocks: intIn(o.wipeAfterFailedUnlocks, 3, 100),
    wipeIfSilentDays: intIn(o.wipeIfSilentDays, 1, 365),
  };
  return { type: 'device.lostMode', requiresCapability: 'device.lostMode', payload: JSON.stringify(payload) };
}
