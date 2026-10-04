import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  login as apiLogin,
  logout as apiLogout,
  type AuthUser,
} from '../api/auth';
import { apiClient } from '../api/client';

// Auth is session based on the server, but the SPA still needs to remember
// "am I logged in" across reloads. The session cookie is HttpOnly and not
// readable from JS, so we persist a lightweight copy of the user object in
// localStorage purely as a UI hint. If the cookie has actually expired, the
// next protected API call will 401 and the UI will bounce back to login.

const STORAGE_KEY = 'hmdm.admin.user';

interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  signIn: (username: string, password: string) => Promise<AuthUser>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function loadStoredUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(loadStoredUser);

  const signIn = useCallback(async (username: string, password: string) => {
    const u = await apiLogin(username, password);
    setUser(u);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(u));
    } catch {
      /* storage may be unavailable; non-fatal */
    }
    return u;
  }, []);

  // The stored copy may be old (an administrator changed this user's role): refresh it once from the server.
  useEffect(() => {
    if (!user) return;
    apiClient.get<AuthUser>('/private/users/current').then((fresh) => {
      if (!fresh) return;
      setUser((u) => (u ? { ...u, ...fresh } : u));
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...user, ...fresh })); } catch { /* non-fatal */ }
    }).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const signOut = useCallback(async () => {
    await apiLogout();
    setUser(null);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* non-fatal */
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, isAuthenticated: user !== null, signIn, signOut }),
    [user, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
