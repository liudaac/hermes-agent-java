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
import { makeForm } from "../forms";
import { useJarvisStore, setForm as _setForm, setOverlay as _setOverlay } from "../hooks/useJarvisStore";
import { useContextAwareness } from "../hooks/useContextAwareness";
import { useLongIdle } from "../hooks/useLongIdle";
import { useKeyShortcuts } from "../hooks/useKeyShortcuts";
import { useCrossSpaceSync, broadcastForm } from "../hooks/useCrossSpaceSync";
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
      void form;
      void size;
      canvas.width = size;
      canvas.height = size;
      const engine = new JarvisEngine(canvas);
      engine.setSize(size, size);
      engine.setParticles(makeInitialPool(form, size));
      engine.setForm(form, makeForm(form, engine));
      engine.start();
      engineRef.current = engine;
    },
    [],
  );

  return { mountParticleCanvas, engineRef };
}

function makeInitialPool(_form: FormName, _size: number) {
  // Engine pulls particles from `getParticles()`; we bootstrap with
  // an empty pool — the form's onEnter will fill it via reassemble.
  // Returning [] keeps the first frame clean (center core only).
  return [] as never[];
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

  // Mount the always-on bottom-right orb.
  useEffect(() => {
    if (hideOrb) return;
    const canvas = orbCanvasRef.current;
    if (!canvas) return;
    if (orbEngineRef.current) {
      orbEngineRef.current.stop();
      orbEngineRef.current = null;
    }
    mountParticleCanvas(canvas, 60, form);
    orbEngineRef.current = (canvas as unknown as { __jarvisEngine?: JarvisEngine }).__jarvisEngine ?? null;
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
        onSubmit={onSubmit ?? (() => {})}
        onApprove={onApprove}
        onReject={onReject}
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
          className="group fixed bottom-6 right-6 z-50 h-[88px] w-[88px] focus:outline-none"
        >
          <div className="relative h-full w-full">
            {/* Particle canvas (60×60) */}
            <canvas
              ref={orbCanvasRef}
              width={60}
              height={60}
              className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2"
            />
            {/* HUD ring (96×96, behind canvas) */}
            <HudRing
              size={88}
              className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 opacity-50 transition-opacity group-hover:opacity-80"
            />
            {/* Center core — overlays the canvas center */}
            <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
              <CenterCore size={20} />
            </div>
            {/* Scanline — every 8s */}
            <Scanline size={88} className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 opacity-40" />
            {/* Tiny data label */}
            <DataOverlay text={awareness.space === "hub" ? "J" : awareness.space.toUpperCase()} />
            {/* Idle hint */}
            <span className="absolute -bottom-1 left-1/2 -translate-x-1/2 text-[8px] tracking-[0.2em] uppercase text-[var(--color-text-muted)] opacity-0 transition-opacity group-hover:opacity-100">
              <Sparkles className="inline h-2 w-2" /> open
            </span>
          </div>
        </button>
      )}

      {primarySlot}

      <JarvisOverlay
        onSubmit={onSubmit ?? (() => {})}
        onApprove={onApprove}
        onReject={onReject}
      />
      <JarvisFullscreen
        onSubmit={onSubmit ?? (() => {})}
        onApprove={onApprove}
        onReject={onReject}
      />
    </>
  );
}
