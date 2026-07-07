/**
 * Physics — shared rules applied to particles each frame.
 *
 * Per design.md §2.6.3 (Behavior 2: MotionBehavior), these helpers are
 * composed by individual forms (CoreForm / SphereForm / …). The form
 * decides which rules to mix.
 */

/** Apply a gentle attraction toward (tx, ty). Strength in [0,1]. */
export function applyAttraction(
  p: { x: number; y: number; vx: number; vy: number },
  tx: number,
  ty: number,
  strength: number,
  dt: number,
): void {
  const dx = tx - p.x;
  const dy = ty - p.y;
  p.vx += dx * strength * dt;
  p.vy += dy * strength * dt;
}

/** Apply repulsion from (ox, oy). */
export function applyRepulsion(
  p: { x: number; y: number; vx: number; vy: number },
  ox: number,
  oy: number,
  strength: number,
  dt: number,
): void {
  const dx = p.x - ox;
  const dy = p.y - oy;
  const d2 = dx * dx + dy * dy + 0.0001;
  const inv = 1 / Math.sqrt(d2);
  p.vx += dx * inv * strength * dt;
  p.vy += dy * inv * strength * dt;
}

/** Apply velocity with exponential damping (friction). */
export function integrate(
  p: { x: number; y: number; vx: number; vy: number },
  dt: number,
  damping = 0.9,
): void {
  p.x += p.vx * dt;
  p.y += p.vy * dt;
  p.vx *= damping;
  p.vy *= damping;
}

/**
 * Place particle on a ring of given radius, evenly spaced.
 * `count` particles per ring; `i` is the index in [0, count).
 */
export function ringTarget(
  cx: number,
  cy: number,
  radius: number,
  i: number,
  count: number,
  phase = 0,
): { x: number; y: number } {
  const theta = (i / count) * Math.PI * 2 + phase;
  return { x: cx + Math.cos(theta) * radius, y: cy + Math.sin(theta) * radius };
}

/** Place particle on a sphere (3D projected to 2D). phi: 0..π (polar), theta: 0..2π. */
export function sphereTarget(
  cx: number,
  cy: number,
  radius: number,
  i: number,
  count: number,
  axis: "xy" | "xz" | "yz" = "xy",
): { x: number; y: number } {
  const phi = Math.acos(1 - (2 * (i + 0.5)) / count);
  const theta = Math.PI * (1 + Math.sqrt(5)) * (i + 0.5);
  let x: number;
  let y: number;
  if (axis === "xy") {
    x = Math.cos(theta) * Math.sin(phi);
    y = Math.sin(theta) * Math.sin(phi);
  } else if (axis === "xz") {
    x = Math.cos(theta) * Math.sin(phi);
    y = Math.cos(phi);
  } else {
    x = Math.cos(phi);
    y = Math.sin(theta) * Math.sin(phi);
  }
  return { x: cx + x * radius, y: cy + y * radius };
}
