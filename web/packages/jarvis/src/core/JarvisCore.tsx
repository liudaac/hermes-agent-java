/**
 * JarvisCore — the top-level container (design.md §21 core/).
 *
 * Owns:
 *   - the particle engine (Canvas2D)
 *   - the FSM
 *   - cross-space awareness + long-idle triggers
 *   - keyboard shortcuts
 *   - cross-tab sync
 *
 * Renders:
 *   - bottom-right orb (the always-visible "Jarvis is here" affordance)
 *   - overlay (summoned / fullscreen) on demand
 *   - fullscreen on demand
 *
 * This module also exposes `useJarvisCore()` which provides a
 * `mountParticleCanvas(canvas, size, form)` helper used by
 * `JarvisOverlay` and `JarvisFullscreen` to host a *separate* canvas
 * (so the floating orb and the overlay particle field are decoupled).
 */

import {
  useCallback,
  useEffect,
  useRef,
  type ReactNode,
} from "react";
import { Sparkles } from "lucide-react";
import { JarvisEngine } from "./JarvisEngine";
import { JarvisFSM, type FormName } from "./JarvisFSM";
import { makeForm, FORM_SPEC } from "../forms";
import { buildPool } from "../forms/transitions";
import { jarvisApi } from "../api/jarvisApi";
import { useJarvisStore, setForm as _setForm, setOverlay as _setOverlay } from "../hooks/useJarvisStore";
import { useContextAwareness } from "../hooks/useContextAwareness";
import { useLongIdle } from "../hooks/useLongIdle";
import { useKeyShortcuts } from "../hooks/useKeyShortcuts";
import { useCrossSpaceSync, broadcastForm } from "../hooks/useCrossSpaceSync";
import { useJarvisChat } from "../hooks/useJarvisChat";
import { HudRing } from "../hud/HudRing";
import { CenterCore } from "../hud/CenterCore";
import { Scanline } from "../hud/Scanline";
import { DataOverlay } from "../hud/DataOverlay";
import { JarvisOverlay } from "../overlay/JarvisOverlay";
import { JarvisFullscreen } from "../overlay/JarvisFullscreen";

export interface JarvisCoreProps {
  onSubmit?: (text: string) => void;
  onApprove?: (approvalId: string) => void;
  onReject?: (approvalId: string) => void;
  /**
   * Optional custom primary-node render. Defaults to a bottom-right
   * 60×60 orb with HUD ring + center core + scanline.
   */
  primarySlot?: ReactNode;
  /** Hide the bottom-right orb entirely (still keeps overlay support). */
  hideOrb?: boolean;
}

/**
 * Hook: exposes the engine + helpers so siblings (overlay, fullscreen)
 * can mount their own particle canvases. The hook is intentionally
 * local to a single <JarvisCore> tree.
 */
export function useJarvisCore() {
  const engineRef = useRef<JarvisEngine | null>(null);

  const mountParticleCanvas = useCallback(
    (canvas: HTMLCanvasElement, size: number, form: FormName, _opts?: { mini?: boolean }) => {
      void _opts;
      // Canvas must be sized first so getContext and setSize align.
      canvas.width = size;
      canvas.height = size;
      const engine = new JarvisEngine(canvas);
      engine.setSize(size, size);
      // Bootstrap with the full form pool so particles are visible on
      // first frame (not just the center core). The old
      // makeInitialPool returned [] and relied on the 800ms reassemble
      // window to seed particles — which meant the orb was an empty
      // canvas for ~266ms on mount and looked like a dead button.
      const specCount = FORM_SPEC[form]?.count ?? 24;
      const initial = buildPool(form, specCount, size / 2, size / 2, 0);
      engine.setParticles(initial);
      engine.setForm(form, makeForm(form, engine));
      engine.start();
      // Expose so callers (useEffect cleanup, tests) can reach the
      // engine instance. Previously the engine was created but never
      // saved outside mountParticleCanvas's closure, so cleanup
      // couldn't stop it and the orb kept its previous engine alive.
      (canvas as unknown as { __jarvisEngine?: JarvisEngine }).__jarvisEngine = engine;
      return engine;
    },
    [],
  );

  return { mountParticleCanvas, engineRef };
}

// FORM_SPEC lookup lives in the forms module — imported above.

// Helper to call the approval resolve endpoint without exposing the
// full jarvisApi shape into every caller. Approval responses carry
// `reply` (agent continuation text for tool-level approvals), which
// we surface as a normal jarvis message once resolved.
async function jarvisApiResolveApproval(approvalId: string, decision: "approve" | "reject") {
  const result = await jarvisApi.resolveApproval(approvalId, decision);
  if (result && result.reply) {
    // Import lazily to avoid a circular dependency at module init.
    const { pushMessage } = await import("../hooks/useJarvisStore");
    pushMessage({
      id: crypto.randomUUID(),
      role: "jarvis",
      text: result.reply,
      timestamp: Date.now(),
    });
  }
  return result;
}

export function JarvisCore({
  onSubmit,
  onApprove,
  onReject,
  primarySlot,
  hideOrb = false,
}: JarvisCoreProps) {
  const enabled = useJarvisStore((s) => s.enabled);
  const form = useJarvisStore((s) => s.form);
  const overlay = useJarvisStore((s) => s.overlay);
  const setForm = _setForm;
  const setOverlay = _setOverlay;

  const orbCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const orbEngineRef = useRef<JarvisEngine | null>(null);
  const fsmRef = useRef<JarvisFSM>(new JarvisFSM());

  const { mountParticleCanvas } = useJarvisCore();
  const awareness = useContextAwareness();
  const { isIdle } = useLongIdle();
  useKeyShortcuts();
  useCrossSpaceSync();

  // If the caller didn't wire up an onSubmit handler (which is the
  // common case — all three SPAs mount <JarvisCore /> with no props),
  // default to the real backend /api/jarvis/chat hook. This is what
  // makes Jarvis actually talk instead of echoing nothing.
  const chatHook = useJarvisChat();
  const submitHandler = onSubmit ?? chatHook.onSubmit;
  const approveHandler = onApprove ?? (async (approvalId: string) => {
    try { await jarvisApiResolveApproval(approvalId, "approve"); } catch (e) {
      console.error("Jarvis approve failed", e);
    }
  });
  const rejectHandler = onReject ?? (async (approvalId: string) => {
    try { await jarvisApiResolveApproval(approvalId, "reject"); } catch (e) {
      console.error("Jarvis reject failed", e);
    }
  });

  // Mount the always-on bottom-right orb.
  useEffect(() => {
    if (hideOrb) return;
    const canvas = orbCanvasRef.current;
    if (!canvas) return;
    // Stop any previous engine (defensive — should be null after
    // unmount, but during React dev StrictMode double-mount we can
    // have a stale instance).
    if (orbEngineRef.current) {
      orbEngineRef.current.stop();
      orbEngineRef.current = null;
    }
    const engine = mountParticleCanvas(canvas, 80, form);
    orbEngineRef.current = engine ?? null;
    return () => {
      orbEngineRef.current?.stop();
      orbEngineRef.current = null;
    };
  }, [form, hideOrb, mountParticleCanvas]);

  // Long-idle: settle into Archive form after 5 min idle (design.md §4.3).
  useEffect(() => {
    if (!enabled) return;
    if (isIdle) {
      setForm("archive");
      broadcastForm("archive");
    }
  }, [isIdle, setForm, enabled]);

  // Tick the FSM (currently unused; the engine self-ticks). Kept for
  // future migration if we move per-frame state out of the engine.
  useEffect(() => {
    const id = window.setInterval(() => fsmRef.current.tick(16), 16);
    return () => window.clearInterval(id);
  }, []);

  if (!enabled) {
    return (
      <JarvisOverlay
        onSubmit={submitHandler}
        onApprove={approveHandler}
        onReject={rejectHandler}
      />
    );
  }

  return (
    <>
      {/* Always-on bottom-right orb */}
      {!hideOrb && (
        <button
          type="button"
          aria-label="召唤 Jarvis (⌘K)"
          onClick={() => setOverlay(overlay === "summoned" ? "hidden" : "summoned")}
          // z-50 = above SPA chrome (TopBar / BottomTabBar use z-40).
          // Without this, the portal's BottomTabBar covers the bottom
          // 32-66px of the orb, and on some layouts the entire orb can
          // be visually swallowed by the tab bar.
          className="group fixed bottom-6 right-6 z-50 h-[96px] w-[96px] focus:outline-none"
        >
          <div className="relative h-full w-full">
            {/* Particle canvas (80×80) — sits centered, large enough
                for the 24-particle idle ring + breathing core. */}
            <canvas
              ref={orbCanvasRef}
              width={80}
              height={80}
              className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2"
            />
            {/* HUD ring (108×108) — slightly larger than canvas so
                the 8 data points per track are visible around the
                particle field. */}
            <HudRing
              size={108}
              className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 opacity-50 transition-opacity group-hover:opacity-80"
            />
            {/* Center core — overlays the canvas center */}
            <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
              <CenterCore size={28} />
            </div>
            {/* Scanline — every 8s, spans the HUD diameter */}
            <Scanline size={108} className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 opacity-40" />
            {/* Tiny data label */}
            <DataOverlay text={awareness.space === "hub" ? "J" : awareness.space.toUpperCase()} />
            {/* Idle hint */}
            <span className="absolute -bottom-1 left-1/2 -translate-x-1/2 text-[8px] tracking-[0.2em] uppercase opacity-0 transition-opacity group-hover:opacity-100 text-[var(--color-text-muted)]">
              <Sparkles className="inline h-2 w-2" /> open
            </span>
          </div>
        </button>
      )}

      {primarySlot}

      <JarvisOverlay
        onSubmit={submitHandler}
        onApprove={approveHandler}
        onReject={rejectHandler}
      />
      <JarvisFullscreen
        onSubmit={submitHandler}
        onApprove={approveHandler}
        onReject={rejectHandler}
      />
    </>
  );
}
