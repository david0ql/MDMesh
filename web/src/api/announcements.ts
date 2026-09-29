import { apiClient } from './client';
import { commitUpload } from './applications';

export interface Announcement {
  id: number;
  title: string;
  body?: string | null;
  mediaurl?: string | null;
  mediatype?: 'image' | 'video' | null;
  mandatory: boolean;
  targetlabel?: string | null;
  createdby?: string | null;
  createdat: number;
  expiresat?: number | null;
  withdrawnat?: number | null;
  total?: number;
  received?: number;
  seen?: number;
  acked?: number;
}

export interface Receipt {
  devicenumber: string;
  deviceid?: number | null;
  description?: string | null;
  sentat?: number | null;
  receivedat?: number | null;
  seenat?: number | null;
  ackat?: number | null;
  /** False when the phone's agent is too old for announcements (it gets them once updated). */
  supported?: boolean;
}

export interface NewAnnouncement {
  title: string;
  body?: string;
  mediaUrl?: string;
  mediaType?: 'image' | 'video';
  mandatory: boolean;
  expiresInDays?: number;
  all: boolean;
  groupIds: number[];
  deviceIds: number[];
  targetLabel: string;
}

const BASE = '/private/announcements';

export const listAnnouncements = () => apiClient.get<Announcement[]>(BASE);
export const getAnnouncement = (id: number) => apiClient.get<{ announcement: Announcement; receipts: Receipt[] }>(`${BASE}/${id}`);
export const sendAnnouncement = (a: NewAnnouncement) => apiClient.post<{ id: number; devices: number }>(BASE, a);
export const withdrawAnnouncement = (id: number) => apiClient.post<void>(`${BASE}/${id}/withdraw`, {});

/** Host an image or video on the server; returns its public URL. The name gets a timestamp so it never collides. */
export async function uploadMedia(file: File): Promise<string> {
  const safe = file.name.replace(/[^A-Za-z0-9._-]+/g, '_').slice(-60);
  const named = new File([file], `anuncio-${Date.now()}-${safe}`, { type: file.type });
  const form = new FormData();
  form.append('file', named, named.name);
  const up = await apiClient.postForm<{ serverPath: string }>('/private/web-ui-files', form);
  const done = await commitUpload(up.serverPath);
  if (!done.url) throw new Error('El servidor no devolvió la dirección del archivo.');
  return done.url;
}
