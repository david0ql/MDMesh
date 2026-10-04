import SparkMD5 from 'spark-md5';
import { apiClient } from './client';

/** A console user: an administrator, or a folder administrator limited to some folders (and everything below them). */
export interface ConsoleUser {
  id: number;
  login: string;
  name?: string | null;
  email?: string | null;
  role: 'admin' | 'folders';
  folders: number[];
  me?: boolean;
}

export interface SaveUser {
  id?: number;
  login: string;
  name?: string;
  email?: string;
  /** Plain; sent as MD5 like the login does. Empty keeps the current one. */
  password?: string;
  role: 'admin' | 'folders';
  folders: number[];
}

export const listUsers = () => apiClient.get<ConsoleUser[]>('/private/dc/users');

export const saveUser = (u: SaveUser) =>
  apiClient.put<ConsoleUser>('/private/dc/users', {
    ...u,
    password: u.password ? SparkMD5.hash(u.password).toUpperCase() : undefined,
  });

export const deleteUser = (id: number) => apiClient.del<void>(`/private/dc/users/${id}`);
