import { useEffect, useState } from 'react';

/** The current time, re-rendering every {@code intervalMs} (for countdowns and "N s ago"). */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

/** "12 s ago", "3 min ago". */
export function ago(isoTime: string, now: number): string {
  const seconds = Math.max(0, Math.round((now - Date.parse(isoTime)) / 1000));
  return seconds < 120 ? `${seconds} s ago` : `${Math.round(seconds / 60)} min ago`;
}
