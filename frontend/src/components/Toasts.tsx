import { useCallback, useEffect, useRef, useState } from 'react';

export type ToastKind = 'ok' | 'bad' | 'warn' | 'info';
export type Toast = { id: number; kind: ToastKind; title: string; text?: string };

const LIFETIME_MS = 6000;
const MAX_SHOWN = 4;

/** Short-lived notices for things that happen while you look elsewhere. */
export function useToasts() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const next = useRef(1);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  const dismiss = useCallback((id: number) => {
    clearTimeout(timers.current.get(id));
    timers.current.delete(id);
    setToasts((shown) => shown.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (kind: ToastKind, title: string, text?: string) => {
      const id = next.current++;
      setToasts((shown) => [...shown, { id, kind, title, text }].slice(-MAX_SHOWN));
      timers.current.set(id, setTimeout(() => dismiss(id), LIFETIME_MS));
    },
    [dismiss],
  );

  useEffect(() => {
    const all = timers.current;
    return () => all.forEach((timer) => clearTimeout(timer));
  }, []);

  return { toasts, push, dismiss };
}

export function Toasts({ toasts, onDismiss }: { toasts: Toast[]; onDismiss: (id: number) => void }) {
  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast-${toast.kind}`}>
          <div>
            <strong>{toast.title}</strong>
            {toast.text && <p>{toast.text}</p>}
          </div>
          <button className="toast-close" aria-label="Dismiss" onClick={() => onDismiss(toast.id)}>
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
