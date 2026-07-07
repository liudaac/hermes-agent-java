/**
 * JarvisEngine — 60fps particle renderer (Canvas 2D).
 *
 * Per design.md §2.6.3 (方案 D — Behavior composition), each form is
 * a set of behaviors that read/write the shared particle pool. The
 * engine:
 *
 *   1. clears the canvas
 *   2. runs the active form's per-frame behavior (motion, color, …)
 *   3. draws each particle
 *
 * The engine is intentionally form-agnostic — it only knows about the
 * particle pool. Forms register themselves through the
 * `FormBehavior` interface below.
 */

import { Particle } from "./Particle";
import { applyAttraction, integrate } from "./Physics";
import { hslToCss } from "./Color";
import type { FormName } from "./JarvisFSM";

export interface FormBehavior {
  /** Called when this form becomes active. */
  onEnter(): void;
  /** Called when this form is leaving. */
  onExit(): void;
  /**
   * Per-frame update. Mutates the particle pool.
   * @param dt milliseconds since last frame
   * @param ctx drawing context (so forms may also paint bg)
   * @param centerX center of the form on the canvas
   * @param centerY center of the form on the canvas
   * @param seed   random seed for reproducibility (advanced)
   */
  update(
    dt: number,
    ctx: CanvasRenderingContext2D,
    centerX: number,
    centerY: number,
    seed?: number,
  ): void;
}

export class JarvisEngine {
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private particles: Particle[] = [];
  private raf = 0;
  private lastT = 0;
  private active: FormBehavior | null = null;
  private formName: FormName = "core";
  private running = false;
  private centerX = 60;
  private centerY = 60;

  constructor(canvas: HTMLCanvasElement) {
    this.canvas = canvas;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("JarvisEngine: 2d context unavailable");
    this.ctx = ctx;
  }

  /** Re-bind the canvas (e.g. after resize). */
  setCanvas(canvas: HTMLCanvasElement): void {
    this.canvas = canvas;
    const ctx = canvas.getContext("2d");
    if (ctx) this.ctx = ctx;
  }

  setSize(w: number, h: number): void {
    this.canvas.width = w;
    this.canvas.height = h;
    this.centerX = w / 2;
    this.centerY = h / 2;
  }

  /**
   * Swap the active form. Existing particles are not deleted —
   * `forms/transitions.ts` will arrange the burst→reassemble
   * choreography (design.md §3).
   */
  setForm(name: FormName, behavior: FormBehavior): void {
    if (this.active) this.active.onExit();
    this.formName = name;
    this.active = behavior;
    this.active.onEnter();
  }

  /** Replace the particle pool (used during reassemble phase). */
  setParticles(list: Particle[]): void {
    this.particles = list;
  }

  getParticles(): Particle[] {
    return this.particles;
  }

  getFormName(): FormName {
    return this.formName;
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.lastT = performance.now();
    const loop = (t: number) => {
      if (!this.running) return;
      const dt = Math.min(48, t - this.lastT);
      this.lastT = t;
      this.render(dt);
      this.raf = requestAnimationFrame(loop);
    };
    this.raf = requestAnimationFrame(loop);
  }

  stop(): void {
    this.running = false;
    if (this.raf) cancelAnimationFrame(this.raf);
    this.raf = 0;
  }

  private render(dt: number): void {
    const { ctx } = this;
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

    if (this.active) {
      this.active.update(dt, ctx, this.centerX, this.centerY);
    }

    // Drop dead particles, draw survivors.
    const survivors: Particle[] = [];
    for (const p of this.particles) {
      if (p.step(dt)) {
        survivors.push(p);
        // Subtle 2D drift even when form has no motion behavior.
        integrate(p, 1, 0.96);
        applyAttraction(p, this.centerX, this.centerY, 0.02, 1);

        ctx.beginPath();
        ctx.fillStyle = hslToCss(
          { h: p.hue, s: p.saturation, l: p.lightness },
          p.force,
        );
        ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    this.particles = survivors;
  }
}
