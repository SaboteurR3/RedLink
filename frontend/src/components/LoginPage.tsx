import { useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';

export function LoginPage({ onLogin, notice }: { onLogin: (u: string, p: string) => Promise<void>; notice: string | null }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onLogin(username, password);
    } catch (e) {
      // Same message for unknown user and wrong password: that is the server's job, we just show it.
      setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable');
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="login">
      <form className="panel login-form" onSubmit={submit}>
        <h1>
          <span className="logo-red">Red</span>Link
        </h1>
        <p className="muted">Mission control for a rover 10 light-seconds away.</p>
        {notice && <p className="notice">{notice}</p>}
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={busy}>
          {busy ? 'Logging in…' : 'Log in'}
        </button>
        <p className="hint">Local dev users: viewer, operator, director — password redlink-dev</p>
      </form>
    </main>
  );
}
