import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { hasPermission } from '../api/auth';

/**
 * Gate a page by a named permission (e.g. "configurations"), instead of restricting it to full administrators.
 * A folder editor who holds the permission can open it; the server still scopes what they see/edit
 * (userConfigurationAccess / device scope). Users without the permission land on their devices.
 */
export function RequirePermission({ name, children }: { name: string; children: ReactNode }) {
  const { user } = useAuth();
  return hasPermission(user, name) ? <>{children}</> : <Navigate to="/devices" replace />;
}
