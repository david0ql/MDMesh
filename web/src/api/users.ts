import SparkMD5 from 'spark-md5';
import { apiClient } from './client';

/** A console user: an administrator, or a folder administrator limited to some folders (and everything below them). */
export interface ConsoleUser {
  id: number;
  login: string;
  name?: string | null;
  email?: string | null;
  /** 'custom' = any other role (roleId / roleName), over some folders or the whole fleet (allFleet). */
  role: 'admin' | 'folders' | 'foldersView' | 'custom';
  roleId?: number | null;
  roleName?: string | null;
  allFleet?: boolean;
  folders: number[];
  me?: boolean;
}

/** A role the signed-in user may assign (never one with more permissions than their own). */
export interface AssignableRole {
  id: number;
  name: string;
  permissions: string[];
}

export interface SaveUser {
  id?: number;
  login: string;
  name?: string;
  email?: string;
  /** Plain; sent as MD5 like the login does. Empty keeps the current one. */
  password?: string;
  role: 'admin' | 'folders' | 'foldersView' | 'custom';
  /** With role 'custom': the role to assign. */
  roleId?: number | null;
  /** Empty with role 'custom' = the whole fleet (only an administrator may do that). */
  folders: number[];
}

export const listUsers = () => apiClient.get<ConsoleUser[]>('/private/dc/users');

export const listAssignableRoles = () => apiClient.get<AssignableRole[]>('/private/dc/users/roles');

export const saveUser = (u: SaveUser) =>
  apiClient.put<ConsoleUser>('/private/dc/users', {
    ...u,
    password: u.password ? SparkMD5.hash(u.password).toUpperCase() : undefined,
  });

export const deleteUser = (id: number) => apiClient.del<void>(`/private/dc/users/${id}`);
