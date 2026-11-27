import { apiClient } from './client';

// Role & permission administration. Backed by the server's UserRoleResource (/private/roles), which is
// administrators-only (super-admin, or the org admin in single-customer mode). The built-in Admin and Super-Admin
// roles are not returned by /all (they always keep full access and the server refuses to edit them), so this screen
// configures the folder roles (editor / view-only), the ready-made roles and any custom role. Folder isolation does
// NOT depend on these permissions: a folder user only ever reaches their own folders' devices regardless of which
// module permissions they hold.

export interface Permission {
  id: number;
  name: string;
  superAdmin?: boolean;
  description?: string | null;
}

export interface Role {
  id: number;
  name: string;
  description?: string | null;
  superAdmin?: boolean;
  permissions: Permission[];
}

/** Roles the console itself assigns: they can be edited but not deleted. */
export const BUILT_IN_ROLE_IDS = new Set([1, 2, 3, 100, 200]);

/** All editable (non super-admin, non built-in Admin) roles, each with its current permissions. */
export const listRoles = () => apiClient.get<Role[]>('/private/roles/all');

/** The catalog of assignable permissions (super-admin permissions are not included by the server). */
export const listPermissions = () => apiClient.get<Permission[]>('/private/roles/permissions');

/**
 * Create a role (no id) or replace a role's name, description and permission set. The server deletes and re-inserts
 * the role's permissions, and answers with the role's id (so a new or duplicated role can be found).
 */
export const saveRole = (role: {
  id?: number;
  name: string;
  description?: string | null;
  permissions: { id: number }[];
}) => apiClient.put<{ id: number; name: string } | undefined>('/private/roles', role);

/** Delete a custom role. Refused for the built-in roles and for a role that is assigned to someone. */
export const deleteRole = (id: number) => apiClient.del<void>(`/private/roles/${id}`);
