/**
 * Forms — the 6 primary forms (v1 scope).
 *
 * Per design.md §2.2 + §2.6.3 (方案 D — Behavior composition), each
 * form is a `FormBehavior` that the engine calls per frame. We use a
 * single factory (`makeForm`) parameterized by the form's name, count,
 * and motion profile so the behaviors stay small and discoverable.
 *
 * Form primaries (design.md §2.1):
 *   - core      24 particles  / 3 orbital tracks / cyan + violet
 *   - sphere    60 particles  / Fibonacci sphere   / cyan + emerald
 *   - helix     80 particles  / double helix       / violet + pink
 *   - cascade   40 particles  / top-down rain      / sky + cyan
 *   - pulse     16 particles  / 8-direction pulse  / cyan + amber
 *   - net       48 particles  / 3 endpoints + lines/ indigo + cyan
 *
 * Sub-forms (companion / authority) compose with the same factory
 * but bias the secondary palette toward the warmth / authority accent.
 */

import { JarvisEngine, type FormBehavior } from "../core/JarvisEngine";
import { Particle } from "../core/Particle";
import { FORM_PALETTE, hslToCss, lerpHSL } from "../core/Color";
import { applyAttraction, integrate, ringTarget } from "../core/Physics";
import {
  buildPool,
  burstOut,
  phaseAt,
  TRANSITION_MS,
  applyPhaseMotion,
} from "./transitions";
import type { FormName } from "../core/JarvisFSM";

interface FormSpec {
  count: number;
  /** Idle-phase motion multiplier (0 = static, 1 = lively). */
  motion: number;
}

const FORM_SPEC: Record<FormName, FormSpec> = {
  core:      { count: 24, motion: 0.3 },
  sphere:    { count: 60, motion: 0.6 },
  helix:     { count: 80, motion: 0.8 },
  cascade:   { count: 40, motion: 0.9 },
  pulse:     { count: 16, motion: 0.5 },
  net:       { count: 48, motion: 0.4 },
  dawn:      { count: 24, motion: 0.2 },
  archive:   { count:  6, motion: 0.05 },
  vigilant:  { count: 24, motion: 0.7 },
  reflective:{ count: 24, motion: 0.4 },
};

const MOTION_PROFILE: Record<FormName, "orbit" | "drift" | "spiral" | "rain" | "pulse" | "tendril"> = {
  core: "orbit",
  sphere: "drift",
  helix: "spiral",
  cascade: "rain",
  pulse: "pulse",
  net: "tendril",
  dawn: "orbit",
  archive: "orbit",
  vigilant: "pulse",
  reflective: "drift",
};

/**
 * Factory: produces a FormBehavior for the given form. The behavior
 * handles its own enter/exit choreography (burst + reassemble) and
 * per-frame idle motion. The engine doesn't know what a "core" is.
 */
export function makeForm(name: FormName, engine: JarvisEngine): FormBehavior {
  const spec = FORM_SPEC[name];
  const palette = FORM_PALETTE[name];
  let since = 0;
  let spawnExtra = 0;

  return {
    onEnter() {
      since = 0;
      spawnExtra = 0;
      burstOut(engine.getParticles(), 0.4);
    },

    onExit() {
      burstOut(engine.getParticles(), 0.6);
    },

    update(dt, _ctx, cx, cy) {
      since += dt;

      // During the 800ms transition window, drive particles with
      // phase-aware motion. Outside it, run the form's idle motion.
      if (since < TRANSITION_MS) {
        // Reassemble: when the burst phase ends, seed the new pool.
        if (since > TRANSITION_MS / 3 && spawnExtra === 0) {
          const newPool = buildPool(name, spec.count, cx, cy, since);
          // Carry over the dying burst particles too — they fade out
          // gracefully (engine ages them by `dt`).
          const old = engine.getParticles();
          for (const p of old) p.lifespan = Math.min(p.lifespan, since + 200);
          engine.setParticles([...old, ...newPool]);
          spawnExtra = 1;
        }
        const phase = phaseAt(since);
        applyPhaseMotion(engine.getParticles(), phase, name, cx, cy, dt);
        // Color-blend during settle.
        if (phase === "settle") {
          const t = (since - (TRANSITION_MS * 2) / 3) / (TRANSITION_MS / 3);
          for (const p of engine.getParticles()) {
            const c = lerpHSL(
              { h: p.hue, s: p.saturation, l: p.lightness },
              palette.secondary,
              t,
            );
            p.hue = c.h;
            p.saturation = c.s;
            p.lightness = c.l;
          }
        }
        return;
      }

      // Steady-state idle motion.
      const profile = MOTION_PROFILE[name];
      for (const p of engine.getParticles()) {
        idleMotion(p, profile, cx, cy, dt, since);
        // Drift gently toward target.
        applyAttraction(p, p.targetX, p.targetY, 0.02, dt);
        integrate(p, dt, 0.94);

        // Per-particle hue cycle (subtle), to keep the field alive.
        const phase = (since * 0.0006 + p.x * 0.01) % 1;
        const blended = lerpHSL(palette.primary, palette.secondary, phase);
        p.hue = blended.h;
        p.saturation = blended.s;
      }

      // Top up if a particle died (cheap version — re-spawn one).
      if (engine.getParticles().length < spec.count) {
        const missing = spec.count - engine.getParticles().length;
        const extras = buildPool(name, missing, cx, cy, since);
        engine.setParticles([...engine.getParticles(), ...extras]);
      }
      void hslToCss;
    },
  };
}

function idleMotion(
  p: Particle,
  profile: "orbit" | "drift" | "spiral" | "rain" | "pulse" | "tendril",
  cx: number,
  cy: number,
  dt: number,
  t: number,
): void {
  const dx = p.x - cx;
  const dy = p.y - cy;
  const r = Math.sqrt(dx * dx + dy * dy) + 0.001;
  const tangentX = -dy / r;
  const tangentY = dx / r;
  switch (profile) {
    case "orbit":
      p.vx += tangentX * 0.05 * dt;
      p.vy += tangentY * 0.05 * dt;
      break;
    case "drift":
      p.vx += (Math.sin(t * 0.001 + p.y * 0.05)) * 0.04 * dt;
      p.vy += (Math.cos(t * 0.001 + p.x * 0.05)) * 0.04 * dt;
      break;
    case "spiral": {
      const a = 0.08 * dt;
      p.vx += (tangentX * a + dx * 0.001 * dt);
      p.vy += (tangentY * a + dy * 0.001 * dt);
      break;
    }
    case "rain":
      p.vy += 0.02 * dt;
      p.vx += Math.sin(t * 0.003 + p.x * 0.02) * 0.06 * dt;
      break;
    case "pulse": {
      // Sinusoidal expansion from the center
      const phase = Math.sin(t * 0.003);
      p.vx += (dx * 0.02 + tangentX * 0.05) * phase * dt;
      p.vy += (dy * 0.02 + tangentY * 0.05) * phase * dt;
      break;
    }
    case "tendril": {
      // 3 endpoint anchors — see Net layout
      const anchors = [
        { x: cx - 26, y: cy - 16 },
        { x: cx + 26, y: cy - 16 },
        { x: cx, y: cy + 22 },
      ];
      const a = anchors[Math.floor((p.x + p.y) * 0.01) % anchors.length]!;
      applyAttraction(p, a.x, a.y, 0.06, dt);
      // Ring jitter
      p.vx += Math.sin(t * 0.001 + p.y * 0.04) * 0.05 * dt;
      break;
    }
  }
  void ringTarget;
}
