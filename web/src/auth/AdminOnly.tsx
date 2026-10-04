import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { isFolderAdmin } from '../api/auth';

/** Pages for administrators only: a folder administrator lands on their devices instead (the server refuses anyway). */
export function AdminOnly({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  return isFolderAdmin(user) ? <Navigate to="/devices" replace /> : <>{children}</>;
}
