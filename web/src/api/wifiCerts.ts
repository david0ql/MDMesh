import { apiClient } from './client';

// Certificates of enterprise Wi-Fi networks (server: WifiCertificateResource). The server keeps what is uploaded sealed
// and only ever answers this metadata — never the certificate, its key or the PKCS#12 password.
export interface WifiCertificate {
  id: number;
  name: string;
  /** CA = the RADIUS server's certificate authority; CLIENT = the device's certificate and key (EAP-TLS). */
  kind: 'CA' | 'CLIENT';
  subject?: string | null;
  issuer?: string | null;
  serial?: string | null;
  fingerprint: string;
  notBefore?: number | null;
  notAfter: number;
  certCount?: number | null;
  createdAt?: number | null;
  createdBy?: string | null;
  daysLeft: number;
  status: 'valid' | 'expiring' | 'expired';
  /** Names of the policies whose networks use it. */
  usedBy: string[];
}

export interface WifiCertificateUpload {
  name: string;
  kind: 'CA' | 'CLIENT';
  /** The file, base64. */
  data: string;
  /** PKCS#12 password (CLIENT). */
  password?: string;
}

const BASE = '/private/wifi-certificates';

export const listWifiCertificates = () => apiClient.get<WifiCertificate[]>(BASE);
export const uploadWifiCertificate = (b: WifiCertificateUpload) => apiClient.post<WifiCertificate>(BASE, b);
export const deleteWifiCertificate = (id: number) => apiClient.del<void>(`${BASE}/${id}`);

/** A file as base64 (no data: prefix). */
export function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onerror = () => reject(new Error('No se pudo leer el archivo.'));
    r.onload = () => {
      const s = String(r.result ?? '');
      resolve(s.slice(s.indexOf(',') + 1));
    };
    r.readAsDataURL(file);
  });
}
