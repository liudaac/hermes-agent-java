/**
 * JarvisFSM — finite-state machine for the 12 forms.
 *
 * Per design.md §2.6.1, the FSM covers 6 main forms (v1 scope) plus
 * 6 secondary forms (added when we wire in Dawn/Archive/Vigilant/
 * Reflective/Companion/Authority in v2).
 *
 * Transitions are *not* hard cuts — design.md §3 mandates 800ms total
 * (200ms burst + 400ms reassemble + 200ms HUD re-flow). The actual
 * particle physics is performed by `forms/transitions.ts`; the FSM
 * only models the discrete state graph.
 *
 *   idle       ─────────►  analyzing
 *     │                       │
 *     │     interrupted        ▼
 *     └───────────────  long_task (>5s)
 *                             │
 *                             ▼
 *                          streaming (token flow)
 *                             │
 *                             ▼
 *                          responded
 *                             │
 *     ┌───────────────────────┘
 *     ▼
 *   idle
 */

export type FormName =
  | "core"
  | "sphere"
  | "helix"
  | "cascade"
  | "pulse"
  | "net"
  | "dawn"
  | "archive"
  | "vigilant"
  | "reflective";

/** A sub-form can be added on top of any primary form. */
export type SubFormName = "companion" | "authority";

export interface FormState {
  primary: FormName;
  sub?: SubFormName;
  /** ms since primary changed (drives transition animation). */
  since: number;
}

const TRANSITION_MS = 800; // design.md §3.2 (cannot go below 600ms)

export class JarvisFSM {
  state: FormState = { primary: "core", since: 0 };

  /**
   * Trigger a form change. The actual particle physics is performed
   * by the form implementations — this just records the request.
   */
  change(form: FormName, sub?: SubFormName): void {
    this.state = { primary: form, sub, since: 0 };
  }

  tick(dt: number): void {
    this.state.since += dt;
  }

  /** How much progress we've made through the 800ms transition. */
  transitionProgress(): number {
    return Math.min(1, this.state.since / TRANSITION_MS);
  }

  /** True when the current form has finished its 800ms transition. */
  isSettled(): boolean {
    return this.state.since >= TRANSITION_MS;
  }

  get transitionMs(): number {
    return TRANSITION_MS;
  }
}
