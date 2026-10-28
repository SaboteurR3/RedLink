/** One reported rover state, for the history charts. `t` is Mars time in ms. */
export type StatePoint = { t: number; battery: number; storage: number; temperature: number };

export type Box = { width: number; height: number; left: number; right: number; top: number; bottom: number };

/** Value range for an axis: fixed (percent) or the data's own range with a little air. */
export function domain(values: number[], fixed?: [number, number]): [number, number] {
  if (fixed) return fixed;
  if (values.length === 0) return [0, 1];
  let min = Math.min(...values);
  let max = Math.max(...values);
  if (max - min < 1) {
    min -= 1;
    max += 1;
  }
  const pad = (max - min) * 0.1;
  return [Math.floor(min - pad), Math.ceil(max + pad)];
}

/**
 * An SVG path through the points, scaled into the box (x = time, y = value, up is more). Where two
 * reports are more than `gapMs` apart the line breaks, so it never pretends there was data in a gap.
 */
export function linePath(ts: number[], values: number[], x: [number, number], y: [number, number], box: Box, gapMs = Infinity): string {
  return ts
    .map((t, i) => `${i === 0 || t - (ts[i - 1] ?? t) > gapMs ? 'M' : 'L'}${scaleX(t, x, box).toFixed(1)},${scaleY(values[i] ?? 0, y, box).toFixed(1)}`)
    .join(' ');
}

export function scaleX(t: number, [t0, t1]: [number, number], box: Box): number {
  const span = t1 - t0 || 1;
  return box.left + ((t - t0) / span) * (box.width - box.left - box.right);
}

export function scaleY(v: number, [v0, v1]: [number, number], box: Box): number {
  const span = v1 - v0 || 1;
  return box.height - box.bottom - ((v - v0) / span) * (box.height - box.top - box.bottom);
}

/** Index of the point closest in time to `t` (ts sorted ascending); -1 when empty. */
export function nearestIndex(ts: number[], t: number): number {
  if (ts.length === 0) return -1;
  let lo = 0;
  let hi = ts.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if ((ts[mid] ?? 0) < t) lo = mid + 1;
    else hi = mid;
  }
  if (lo > 0 && Math.abs((ts[lo - 1] ?? 0) - t) <= Math.abs((ts[lo] ?? 0) - t)) return lo - 1;
  return lo;
}

/** Adds a point in time order, ignoring a repeat of the same time (the stream can resend). */
export function addPoint(points: StatePoint[], point: StatePoint, max = 500): StatePoint[] {
  if (points.some((p) => p.t === point.t)) return points;
  const next = [...points, point].sort((a, b) => a.t - b.t);
  return next.length > max ? next.slice(next.length - max) : next;
}
