import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiClient, ApiError } from './api/client';
import type { Me } from './api/types';
import { clearSession, loadSession, saveSession } from './auth/session';
import { LoginPage } from './components/LoginPage';
import { MissionControl } from './components/MissionControl';

export function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [checking, setChecking] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);

  const logout = useCallback((reason?: string) => {
    clearSession();
    setMe(null);
    setNotice(reason ?? null);
  }, []);

  // One client for the whole app; a 401 anywhere means the session is over.
  const api = useMemo(() => {
    const client: ApiClient = new ApiClient(undefined, () => {
      client.setToken(null);
      logout('Your session expired. Please log in again.');
    });
    return client;
  }, [logout]);

  const signOut = useCallback(() => {
    api.setToken(null); // nothing keeps the old token in memory
    logout();
  }, [api, logout]);

  useEffect(() => {
    const token = loadSession();
    if (!token) {
      setChecking(false);
      return;
    }
    api.setToken(token);
    api
      .me()
      .then(setMe)
      // A 401 has already logged out with "session expired"; don't wipe that message.
      .catch((e: unknown) => {
        if (!(e instanceof ApiError && e.status === 401)) signOut();
      })
      .finally(() => setChecking(false));
  }, [api, logout, signOut]);

  const login = async (username: string, password: string) => {
    const { token, expiresIn } = await api.login(username, password);
    saveSession(token, expiresIn);
    api.setToken(token);
    setNotice(null);
    setMe(await api.me());
  };

  if (checking) return <div className="splash">Connecting to mission control…</div>;
  if (!me) return <LoginPage onLogin={login} notice={notice} />;
  return <MissionControl api={api} me={me} onLogout={signOut} />;
}
