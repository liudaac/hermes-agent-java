/**
 * JarvisCore — the top-level container (design.md §21 core/).
 *
 * Owns:
 *   - the FSM
 *   - cross-space awareness + long-idle triggers
 *   - keyboard shortcuts
 *   - cross-tab sync
 *
 * Renders:
 *   - JarvisOrb (always-on bottom-right HUD entry)
 *   - JarvisHudPanel (summoned state, expands from orb)
 *   - JarvisFullscreen (full-screen immersive mode)
 *
 * Chat/approval wiring is defaulted via useJarvisChat so callers that
 * mount <JarvisCore /> with no props (the common case in all three
 * SPAs) still get a working assistant.
 */

import { useEffect, useMemo, useRef, useCallback } from "react";
import { JarvisFSM } from "./JarvisFSM";
import { JarvisEngine } from "./JarvisEngine";
import { makeForm, FORM_SPEC } from "../forms";
import { buildPool } from "../forms/transitions";
import { jarvisApi } from "../api/jarvisApi";
import { useJarvisStore, setForm as _setForm, setOverlay as _setOverlay } from "../hooks/useJarvisStore";
import { useLongIdle } from "../hooks/useLongIdle";
import { useKeyShortcuts } from "../hooks/useKeyShortcuts";
import { useCrossSpaceSync, broadcastForm } from "../hooks/useCrossSpaceSync";
import { useJarvisChat } from "../hooks/useJarvisChat";
import { JarvisOrb } from "./JarvisOrb";
import { JarvisHudPanel } from "../overlay/JarvisHudPanel";
import { JarvisFullscreen } from "../overlay/JarvisFullscreen";

export interface JarvisCoreProps {
  onSubmit?: (text: string) => void | Promise<void>;
  onApprove?: (approvalId: string) => void | Promise<void>;
  onReject?: (approvalId: string) => void | Promise<void>;
  primarySlot?: React.ReactNode;
  hideOrb?: boolean;
}

/**
 * useJarvisCore — helper hook used by HUD panels to mount a separate
 * particle canvas (the orb and panel each host their own engine so
 * their particle choreographies don't fight).
 */
export function useJarvisCore() {
  const engineRef = useRef<JarvisEngine | null>(null);

  const mountParticleCanvas = useCallback(
    (canvas: HTMLCanvasElement, size: number, form: string, _opts?: { mini?: boolean }) => {
      void _opts;
      canvas.width = size;
      canvas.height = size;
      const engine = new JarvisEngine(canvas);
      engine.setSize(size, size);
      const specCount = (FORM_SPEC as Record<string, { count: number }>)[form]?.count ?? 24;
      engine.setParticles(buildPool(form as "core" | "sphere" | "helix" | "cascade" | "pulse" | "net" | "dawn" | "archive" | "vigilant" | "reflective", specCount, size / 2, size / 2, 0));
      engine.setForm(form as "core" | "sphere" | "helix" | "cascade" | "pulse" | "net" | "dawn" | "archive" | "vigilant" | "reflective", makeForm(form as "core" | "sphere" | "helix" | "cascade" | "pulse" | "net" | "dawn" | "archive" | "vigilant" | "reflective", engine));
      engine.start();
      (canvas as unknown as { __jarvisEngine?: JarvisEngine }).__jarvisEngine = engine;
      engineRef.current = engine;
      return engine;
    },
    [],
  );

  return { mountParticleCanvas, engineRef };
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
  const messages = useJarvisStore((s) => s.messages);
  const pendingApproval = useJarvisStore((s) => s.pendingApproval);
  const setForm = _setForm;
  const setOverlay = _setOverlay;

  const fsmRef = useRef<JarvisFSM>(new JarvisFSM());

  const { isIdle } = useLongIdle();
  useKeyShortcuts();
  useCrossSpaceSync();

  // Default handlers: wire to the real backend if callers don't supply
  // their own. This is the path all three SPAs use (they mount
  // <JarvisCore /> with no props).
  const chatHook = useJarvisChat();
  const submitHandler = onSubmit ?? chatHook.onSubmit;
  const approveHandler = onApprove ?? (async (approvalId: string) => {
    try {
      const result = await jarvisApi.resolveApproval(approvalId, "approve");
      if (result && result.reply) {
        const { pushMessage } = await import("../hooks/useJarvisStore");
        pushMessage({ id: crypto.randomUUID(), role: "jarvis", text: result.reply, timestamp: Date.now() });
      }
    } catch (e) { console.error("Jarvis approve failed", e); }
  });
  const rejectHandler = onReject ?? (async (approvalId: string) => {
    try {
      const result = await jarvisApi.resolveApproval(approvalId, "reject");
      if (result && result.reply) {
        const { pushMessage } = await import("../hooks/useJarvisStore");
        pushMessage({ id: crypto.randomUUID(), role: "jarvis", text: result.reply, timestamp: Date.now() });
      }
    } catch (e) { console.error("Jarvis reject failed", e); }
  });

  // Unread count = pending approval + any jarvis messages added after
  // the panel was last closed (simple heuristic for v1).
  const unreadCount = useMemo(() => {
    let n = pendingApproval ? 1 : 0;
    if (overlay !== "summoned" && overlay !== "fullscreen") {
      // count jarvis messages added while the panel was hidden
      n += messages.filter((m) => m.role === "jarvis" && !m.pending && !m.error).length;
      // cap at 9 so the badge doesn't look silly
      n = Math.min(n, 9);
    }
    return n;
  }, [pendingApproval, messages, overlay]);

  // Long-idle: settle into Archive form after 5 min idle (design.md §4.3).
  useEffect(() => {
    if (!enabled) return;
    if (isIdle) {
      setForm("archive");
      broadcastForm("archive");
    }
  }, [isIdle, setForm, enabled]);

  // FSM tick (currently unused at the JSX layer; engine self-ticks).
  useEffect(() => {
    const id = window.setInterval(() => fsmRef.current.tick(16), 16);
    return () => window.clearInterval(id);
  }, []);

  if (!enabled) {
    return (
      <JarvisHudPanel
        onSubmit={submitHandler}
        onApprove={approveHandler}
        onReject={rejectHandler}
      />
    );
  }

  return (
    <>
      {!hideOrb && (
        <JarvisOrb
          form={form}
          unreadCount={unreadCount}
          isSummoned={overlay === "summoned" || overlay === "fullscreen"}
          onToggle={() => setOverlay(overlay === "summoned" ? "hidden" : "summoned")}
        />
      )}

      {primarySlot}

      <JarvisHudPanel
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
