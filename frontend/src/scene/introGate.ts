/** Kept apart from Intro.tsx so deciding whether to play it doesn't download three.js. */
const INTRO_KEY = 'redlink.intro.v1';

/** Whether the intro should play: once per tab session, never when reduced motion is asked for. */
export function shouldPlayIntro(): boolean {
  try {
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return false;
    return sessionStorage.getItem(INTRO_KEY) === null;
  } catch {
    return false;
  }
}

export function markIntroSeen(): void {
  try {
    sessionStorage.setItem(INTRO_KEY, 'seen');
  } catch {
    // private window: it may play again, which is harmless
  }
}
