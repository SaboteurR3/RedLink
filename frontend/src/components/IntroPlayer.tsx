import { lazy, Suspense } from 'react';

// The film needs three.js, so it is its own chunk; `preloadIntro` fetches it before it is needed.
const loadIntro = () => import('../scene/Intro');
const Intro = lazy(loadIntro);

/** Starts downloading the intro, e.g. while the login form is shown, so it can start at once. */
export function preloadIntro(): void {
  loadIntro().catch(() => undefined); // it is tried again when it plays
}

/**
 * The intro film over the whole page. Until its code has arrived, the same black screen with the
 * title covers the page, so the app never flashes before the film.
 */
export function IntroPlayer({ onDone }: { onDone: () => void }) {
  return (
    <Suspense
      fallback={
        <div className="intro" role="dialog" aria-label="Intro">
          <h1 className="intro-title" aria-hidden="true">
            RedLink
          </h1>
        </div>
      }
    >
      <Intro onDone={onDone} />
    </Suspense>
  );
}