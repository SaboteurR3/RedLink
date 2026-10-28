/**
 * The access token, kept in sessionStorage: it survives a page reload but not closing the tab, and
 * is never sent anywhere automatically (unlike a cookie), so CSRF does not apply. Tokens live 15
 * minutes; an expired one is simply dropped.
 */
const KEY = 'redlink.session';

type Stored = { token: string; expiresAt: number };

export function saveSession(token: string, expiresInSeconds: number): void {
  const stored: Stored = { token, expiresAt: Date.now() + expiresInSeconds * 1000 };
  sessionStorage.setItem(KEY, JSON.stringify(stored));
}

export function loadSession(): string | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const stored = JSON.parse(raw) as Stored;
    if (stored.expiresAt <= Date.now()) {
      clearSession();
      return null;
    }
    return stored.token;
  } catch {
    return null;
  }
}

export function clearSession(): void {
  sessionStorage.removeItem(KEY);
}
