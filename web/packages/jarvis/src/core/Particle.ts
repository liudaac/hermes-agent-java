/**
 * Particle — the atomic unit in the Jarvis particle field.
 *
 * Each particle has a position, velocity, lifespan, color, and target.
 * A `Particle` is mutable; the engine rewrites these every frame.
 *
 * Per design.md §2.6.3 (Behavior 1: LifespanBehavior):
 *   - birth()  — set position + lifespan random
 *   - step(dt) — age, expire when lifespan ≤ 0
 */
export interface ParticleInit {
  x: number;
  y: number;
  vx?: number;
  vy?: number;
  lifespan: number;
  hue?: number;
  saturation?: number;
  lightness?: number;
  size?: number;
  targetX?: number;
  targetY?: number;
}

export class Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  age: number;
  lifespan: number;
  hue: number;
  saturation: number;
  lightness: number;
  size: number;
  /** Optional attractor — particle will drift toward this. */
  targetX: number;
  targetY: number;
  /** Optional life-force in [0,1]. 0 = expired, 1 = newborn. */
  force: number;

  constructor(init: ParticleInit) {
    this.x = init.x;
    this.y = init.y;
    this.vx = init.vx ?? 0;
    this.vy = init.vy ?? 0;
    this.age = 0;
    this.lifespan = init.lifespan;
    this.hue = init.hue ?? 200;
    this.saturation = init.saturation ?? 70;
    this.lightness = init.lightness ?? 60;
    this.size = init.size ?? 1.2;
    this.targetX = init.targetX ?? init.x;
    this.targetY = init.targetY ?? init.y;
    this.force = 1;
  }

  /** Tick one frame. Returns true while still alive. */
  step(dt: number): boolean {
    this.age += dt;
    if (this.age >= this.lifespan) {
      this.force = 0;
      return false;
    }
    this.force = 1 - this.age / this.lifespan;
    return true;
  }
}
