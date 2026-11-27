import { apiClient } from './client';

// Role & permission administration. Backed by the server's UserRoleResource (/private/roles), which is
// administrators-only (super-admin, or the org admin in single-customer mode). The built-in Admin and Super-Admin
// roles are not returned by /all (they always keep full access), so this screen configures the folder roles
// (editor / view-only) and any custom roles. Folder isolation does NOT depend on these permissions: a folder
// user only ever reaches their own folders' devices regardless of which module permissions they hold.

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

/** All editable (non super-admin, non built-in Admin) roles, each with its current permissions. */
export const listRoles = () => apiClient.get<Role[]>('/private/roles/all');

/** The catalog of assignable permissions (super-admin permissions are not included by the server). */
export const listPermissions = () => apiClient.get<Permission[]>('/private/roles/permissions');

/** Replace a role's permission set. The server deletes and re-inserts the role's permissions. */
export const saveRole = (role: {
  id: number;
  name: string;
  description?: string | null;
  permissions: { id: number }[];
}) => apiClient.put<void>('/private/roles', role);
