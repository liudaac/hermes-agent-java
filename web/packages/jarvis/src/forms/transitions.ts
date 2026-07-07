/**
 * Form transitions — the 800ms burst → reassemble → HUD re-flow
 * (design.md §3.2).
 *
 * Implementation note (per §3.3 关键规则):
 *   - Total duration is FIXED at 800ms.
 *   - It cannot be configured below 600ms (anti-eye-strain).
 *   - The center core must remain visible throughout (§22 设计原则 1).
 *
 * The transition is split into three ⅓ phases:
 *   0..33%   burst    — old particles radial-out + slight color shift
 *   33..66%  reassemble — new particles born at center, drift out
 *   66..100% settle     — motion damping, color snap to final
 */

import { Particle } from "../core/Particle";
import { lerpHSL, type HSL, FORM_PALETTE } from "../core/Color";
import { ringTarget, sphereTarget, applyAttraction, integrate } from "../core/Physics";
import type { FormName } from "../core/JarvisFSM";

export const TRANSITION_MS = 800;
export const TRANSITION_MIN_MS = 600;

export type Phase = "burst" | "reassemble" | "settle";

export function phaseAt(t: number): Phase {
  if (t < TRANSITION_MS / 3) return "burst";
  if (t < (TRANSITION_MS * 2) / 3) return "reassemble";
  return "settle";
}

/**
 * Burst the existing pool outward with a quick radial impulse. Used
 * when leaving a form.
 */
export function burstOut(particles: Particle[], strength = 0.4): void {
  for (const p of particles) {
    const theta = Math.random() * Math.PI * 2;
    p.vx += Math.cos(theta) * strength;
    p.vy += Math.sin(theta) * strength;
  }
}

/**
 * Build a new pool of `count` particles laid out for `form`, with
 * primary color from the form's palette and lifespan scaled so all
 * particles die roughly together (settle phase).
 */
export function buildPool(
  form: FormName,
  count: number,
  centerX: number,
  centerY: number,
  seed = 1,
): Particle[] {
  const palette = FORM_PALETTE[form] ?? FORM_PALETTE.core!;
  const out: Particle[] = [];
  for (let i = 0; i < count; i++) {
    const { x, y } = layoutFor(form, i, count, centerX, centerY, seed);
    out.push(
      new Particle({
        x: centerX + (Math.random() - 0.5) * 4,
        y: centerY + (Math.random() - 0.5) * 4,
        vx: (x - centerX) * 0.05,
        vy: (y - centerY) * 0.05,
        lifespan: 1800 + Math.random() * 800,
        hue: palette.primary.h,
        saturation: palette.primary.s,
        lightness: palette.primary.l,
        size: 1.1 + Math.random() * 0.6,
        targetX: x,
        targetY: y,
      }),
    );
  }
  return out;
}

/** Compute the target position of particle #i for the given form. */
export function layoutFor(
  form: FormName,
  i: number,
  count: number,
  cx: number,
  cy: number,
  seed = 1,
): { x: number; y: number } {
  switch (form) {
    case "core":
      // 3 orbital tracks of 8 (24 total) — design.md §2.2.1
      {
        const track = i % 3;
        const idx = Math.floor(i / 3);
        const r = [24, 32, 40][track]!;
        return ringTarget(cx, cy, r, idx, 8, (seed * 0.3));
      }
    case "sphere":
      // 60 particles on a Fibonacci sphere (projected to xy)
      return sphereTarget(cx, cy, 28, i, count, "xy");
    case "helix":
      // 80 particles on a double helix (alternate two strands)
      {
        const turns = 3;
        const t = (i / count) * turns * Math.PI * 2;
        const r = 18 + Math.sin(t) * 3;
        const strand = i % 2;
        const offset = strand * Math.PI;
        return {
          x: cx + Math.cos(t + offset) * r,
          y: cy + (i / count - 0.5) * 40,
        };
      }
    case "cascade":
      // 40 particles in a top-rain layout; rows of 4
      {
        const cols = 5;
        const col = i % cols;
        const row = Math.floor(i / cols);
        const rows = Math.ceil(count / cols);
        return {
          x: cx + (col - (cols - 1) / 2) * 8,
          y: cy - 22 + (row / Math.max(1, rows - 1)) * 44,
        };
      }
    case "pulse":
      // 16 particles in 8-direction ring with one inner shell
      {
        const ring = i < 8 ? i : i - 8;
        const r = i < 8 ? 26 : 12;
        return ringTarget(cx, cy, r, ring, 8, seed);
      }
    case "net":
      // 3 endpoint clusters + 8 connection lines = 24 + 24
      {
        const endpoint = i % 3;
        const idx = Math.floor(i / 3) % 8;
        const anchors: Array<{ x: number; y: number }> = [
          { x: cx - 26, y: cy - 16 },
          { x: cx + 26, y: cy - 16 },
          { x: cx, y: cy + 22 },
        ];
        const a = anchors[endpoint]!;
        const t = (idx / 8) * Math.PI * 2;
        const r = 4;
        return { x: a.x + Math.cos(t) * r, y: a.y + Math.sin(t) * r };
      }
    case "dawn":
      return { x: cx + (Math.random() - 0.5) * 50, y: cy + (Math.random() - 0.5) * 50 };
    case "archive":
      return ringTarget(cx, cy, 18 + (i % 2) * 6, i, 6, seed);
    case "vigilant":
      return ringTarget(cx, cy, 22 + (i % 2) * 4, i, 12, seed);
    case "reflective":
      // 6 thought-streams
      {
        const stream = i % 6;
        const t = Math.floor(i / 6);
        return ringTarget(cx, cy, 14 + t * 6, stream, 6, stream * 0.3);
      }
  }
}

/** Snap particle color toward a target form palette as `t→1`. */
export function easeColor(p: Particle, target: HSL, t: number): void {
  const blended = lerpHSL(
    { h: p.hue, s: p.saturation, l: p.lightness },
    target,
    t,
  );
  p.hue = blended.h;
  p.saturation = blended.s;
  p.lightness = blended.l;
}

/** Per-frame motion for the current phase. Mutates particles in place. */
export function applyPhaseMotion(
  particles: Particle[],
  phase: Phase,
  form: FormName,
  cx: number,
  cy: number,
  dt: number,
): void {
  for (const p of particles) {
    if (phase === "burst") {
      integrate(p, dt, 0.94);
    } else {
      // reassemble + settle: pull toward target
      const k = phase === "reassemble" ? 0.08 : 0.02;
      applyAttraction(p, p.targetX, p.targetY, k, dt);
      integrate(p, dt, phase === "reassemble" ? 0.86 : 0.9);
    }
  }
  void form;
  void cx;
  void cy;
}
