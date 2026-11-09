/** Which map view the user wants, and whether this browser can show 3D at all. */
export type View = '2d' | '3d';

const STORAGE_KEY = 'redlink.view';

export function hasWebGL(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

export function savedView(): View | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === '2d' || value === '3d' ? value : null;
  } catch {
    return null;
  }
}

export function saveView(view: View): void {
  try {
    localStorage.setItem(STORAGE_KEY, view);
  } catch {
    // private window: the choice just isn't remembered
  }
}

/** The 3D view will be shown: WebGL works and the user hasn't chosen 2D. */
export function shows3d(): boolean {
  return hasWebGL() && savedView() !== '2d';
}