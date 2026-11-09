import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiClient, ApiError } from './api/client';
import type { Me } from './api/types';
import { clearSession, loadSession, saveSession } from './auth/session';
import { LoginPage } from './components/LoginPage';
import { IntroPlayer, preloadIntro } from './components/IntroPlayer';
import { RoverSelection } from './components/RoverSelection';
import { shows3d } from './components/viewPreference';
import { markIntroSeen, shouldPlayIntro } from './scene/introGate';

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

  // The intro starts the moment a login succeeds and covers the page while the app loads beneath it.
  const [intro, setIntro] = useState(false);
  const endIntro = useCallback(() => {
    markIntroSeen();
    setIntro(false);
  }, []);
  useEffect(() => {
    if (!me && wantsIntro()) preloadIntro(); // fetched while the user types
  }, [me]);
  useEffect(() => {
    if (me && wantsIntro()) setIntro(true); // a session restored in a new tab
  }, [me]);

  const login = async (username: string, password: string) => {
    const { token, expiresIn } = await api.login(username, password);
    saveSession(token, expiresIn);
    api.setToken(token);
    setNotice(null);
    if (wantsIntro()) setIntro(true);
    setMe(await api.me());
  };

  let page;
  if (checking) page = <div className="splash">Connecting to mission control…</div>;
  else if (!me) page = <LoginPage onLogin={login} notice={notice} />;
  else page = <RoverSelection api={api} me={me} onLogout={signOut} />;
  return (
    <>
      {intro && <IntroPlayer onDone={endIntro} />}
      {page}
    </>
  );
}

/** Once per tab, only where the 3D view will be shown, never when reduced motion is asked for. */
function wantsIntro(): boolean {
  return shows3d() && shouldPlayIntro();
}
