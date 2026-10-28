import { useState } from 'react';

const KEY = 'redlink.hints.v1';

function seen(): boolean {
  try {
    return localStorage.getItem(KEY) === 'seen';
  } catch {
    return false;
  }
}

/** A short explanation for first-time users, mainly that waiting is normal on a Mars link. */
export function FirstVisitHints() {
  const [hidden, setHidden] = useState(seen);
  if (hidden) return null;

  const dismiss = () => {
    setHidden(true);
    try {
      localStorage.setItem(KEY, 'seen');
    } catch {
      // private window: it shows again next time, which is fine
    }
  };

  return (
    <div className="hints panel" role="note">
      <strong>Welcome to mission control</strong>
      <ul>
        <li>
          Orders are not instant: they wait <b>5 s</b> on Earth (your chance to cancel), travel <b>10 s</b> to Mars, and the
          rover’s report needs another <b>10 s</b> back. About half a minute until you see the result is normal.
        </li>
        <li>
          <b>Click the map</b> to drive somewhere: RedLink plans the way around rocks and shows it before anything is sent.
        </li>
        <li>Drag to look around, scroll to zoom; Follow and Top-down change the camera.</li>
        <li>Everything you see is the rover’s last report, so it is always a little in the past.</li>
      </ul>
      <button className="small" onClick={dismiss}>
        Got it
      </button>
    </div>
  );
}
