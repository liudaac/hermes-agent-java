/**
 * JarvisOrb — the always-on bottom-right HUD entry point.
 *
 * Design reference: Iron Man J.A.R.V.I.S. circular HUD — a glowing cyan
 * ring with rotating concentric tracks, a central pulsing core, data
 * ticks around the rim, and a subtle radial glow. NOT a chatbot corner
 * badge.
 *
 * States:
 *   - idle:     120px HUD ring, 4 tracks, breathing core, rotating ticks
 *   - thinking: tracks speed up, particles converge to sphere
 *   - active:   overlay summoned; orb expands slightly, glow intensifies
 *
 * The particle canvas is 110×110, centered inside a 160×160 touch target
 * so the click area is generous without bloating the HUD itself.
 */
import { useEffect, useRef } from "react";
import { Sparkles } from "lucide-react";
import { JarvisEngine } from "./JarvisEngine";
import { setOverlay as _setOverlay } from "../hooks/useJarvisStore";
import { useContextAwareness } from "../hooks/useContextAwareness";
import { makeForm, FORM_SPEC } from "../forms";
import { buildPool } from "../forms/transitions";
import { HudRing } from "../hud/HudRing";
import { CenterCore } from "../hud/CenterCore";
import { Scanline } from "../hud/Scanline";
import type { FormName } from "./JarvisFSM";

interface JarvisOrbProps {
  /** Current active form (core/sphere/helix/...) */
  form: FormName;
  /** Number of unread items (suggestions + pending approvals) */
  unreadCount: number;
  /** Highest severity of unread items (drives badge color) */
  unreadSeverity?: "info" | "warning" | "critical";
  /** Whether the overlay is currently summoned */
  isSummoned: boolean;
  /** Called when the user clicks the orb */
  onToggle: () => void;
}

export function JarvisOrb({ form, unreadCount, unreadSeverity = "info", isSummoned, onToggle }: JarvisOrbProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const engineRef = useRef<JarvisEngine | null>(null);
  const awareness = useContextAwareness();

  // Mount particle engine on the orb canvas.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    if (engineRef.current) {
      engineRef.current.stop();
      engineRef.current = null;
    }
    canvas.width = 110;
    canvas.height = 110;
    const engine = new JarvisEngine(canvas);
    engine.setSize(110, 110);
    const specCount = FORM_SPEC[form]?.count ?? 24;
    engine.setParticles(buildPool(form, specCount, 55, 55, 0));
    engine.setForm(form, makeForm(form, engine));
    engine.start();
    engineRef.current = engine;
    return () => {
      engine.stop();
      engineRef.current = null;
    };
  }, [form]);

  const label = awareness.space === "unknown" || awareness.space === "hub"
    ? "JARVIS"
    : awareness.space.toUpperCase();

  return (
    <button
      type="button"
      aria-label={isSummoned ? "收起 Jarvis" : "召唤 Jarvis (⌘K)"}
      onClick={onToggle}
      className={[
        "group fixed bottom-5 right-5 z-50 h-[160px] w-[160px]",
        "focus:outline-none",
        // Disable default mobile tap highlight
        "tap-highlight-transparent [-webkit-tap-highlight-color:transparent]",
      ].join(" ")}
    >
      {/* Outer radial glow — visible when summoned / hovered */}
      <div
        className={[
          "pointer-events-none absolute inset-0 rounded-full",
          "bg-[radial-gradient(circle,oklch(0.75_0.18_200_/_0.35)_0%,oklch(0.75_0.18_200_/_0)_65%)]",
          "opacity-0 blur-xl transition-opacity duration-500",
          isSummoned ? "opacity-100" : "group-hover:opacity-60",
        ].join(" ")}
      />

      {/* Thin outer status ring — rotates slowly (CSS) */}
      <svg
        className="pointer-events-none absolute inset-0 h-full w-full"
        viewBox="0 0 160 160"
        aria-hidden
      >
        <defs>
          <linearGradient id="jarvis-orbit-grad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="oklch(0.82 0.15 200)" stopOpacity="0.8" />
            <stop offset="50%" stopColor="oklch(0.75 0.18 260)" stopOpacity="0.5" />
            <stop offset="100%" stopColor="oklch(0.82 0.15 200)" stopOpacity="0.8" />
          </linearGradient>
        </defs>
        <circle
          cx="80" cy="80" r="76"
          fill="none"
          stroke="url(#jarvis-orbit-grad)"
          strokeWidth="1"
          strokeDasharray="3 6"
          className={[
            "origin-center",
            "animate-[jarvis-rotate_24s_linear_infinite]",
            isSummoned ? "opacity-100" : "opacity-40 group-hover:opacity-80",
            "transition-opacity duration-300",
          ].join(" ")}
        />
        <circle
          cx="80" cy="80" r="68"
          fill="none"
          stroke="oklch(0.75 0.15 200 / 0.25)"
          strokeWidth="0.5"
          strokeDasharray="1 4"
          className="origin-center animate-[jarvis-rotate-rev_32s_linear_infinite] opacity-60"
        />
      </svg>

      {/* HUD ring + particles + center core — all centered */}
      <div className="absolute left-1/2 top-1/2 h-[130px] w-[130px] -translate-x-1/2 -translate-y-1/2">
        {/* Background HUD ring (data tracks) */}
        <HudRing
          size={130}
          className={[
            "absolute inset-0 transition-opacity duration-300",
            isSummoned ? "opacity-100" : "opacity-50 group-hover:opacity-80",
          ].join(" ")}
        />
        {/* Scanline — traverses once every 8s */}
        <Scanline
          size={130}
          className="absolute inset-0 opacity-50"
        />
        {/* Particle canvas */}
        <canvas
          ref={canvasRef}
          width={110}
          height={110}
          className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2"
        />
        {/* Center core */}
        <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
          <CenterCore size={36} />
        </div>
      </div>

      {/* Space label (top) */}
      <div
        className={[
          "pointer-events-none absolute left-1/2 top-2 -translate-x-1/2",
          "font-mono text-[9px] tracking-[0.3em] uppercase",
          "text-[oklch(0.78_0.14_200)]",
          "opacity-60 group-hover:opacity-100 transition-opacity",
        ].join(" ")}
      >
        {label}
      </div>

      {/* Bottom hint (shown on hover / summoned) */}
      <div
        className={[
          "pointer-events-none absolute left-1/2 bottom-3 -translate-x-1/2",
          "flex items-center gap-1",
          "font-mono text-[8px] tracking-[0.25em] uppercase",
          "text-[oklch(0.78_0.14_200/0.7)]",
          "opacity-0 transition-opacity duration-200",
          isSummoned ? "opacity-100" : "group-hover:opacity-100",
        ].join(" ")}
      >
        <Sparkles className="h-2.5 w-2.5" />
        {isSummoned ? "ACTIVE" : "⌘K TAP"}
      </div>

      {/* Unread badge — top-right quadrant, color-coded by severity */}
      {unreadCount > 0 && (() => {
        const badge = unreadSeverity === "critical"
          ? "bg-[oklch(0.65_0.22_25)] text-[oklch(0.98_0.05_25)]"
          : unreadSeverity === "warning"
            ? "bg-[oklch(0.75_0.16_80)] text-[oklch(0.18_0.05_80)]"
            : "bg-[oklch(0.70_0.15_220)] text-[oklch(0.10_0.03_220)]";
        const glow = unreadSeverity === "critical"
          ? "animate-[jarvis-pulse_1.2s_ease-in-out_infinite] 0 0 0 0 oklch(0.65 0.22 25 / 0.7)"
          : unreadSeverity === "warning"
            ? "animate-[jarvis-pulse_2s_ease-in-out_infinite] 0 0 0 0 oklch(0.75 0.16 80 / 0.5)"
            : "animate-[jarvis-pulse_3s_ease-in-out_infinite] 0 0 0 0 oklch(0.70 0.15 220 / 0.4)";
        return (
          <div
            className={[
              "pointer-events-none absolute right-7 top-7",
              "flex h-5 min-w-5 items-center justify-center rounded-full",
              "px-1.5",
              "font-mono text-[10px] font-bold leading-none",
              "ring-2 ring-[oklch(0.14_0.015_60)]",
              badge,
            ].join(" ")}
            style={{ boxShadow: glow }}
          >
            {unreadCount > 99 ? "99+" : unreadCount}
          </div>
        );
      })()}

      {/* Keyframes injected once via a style tag. Using inline <style>
          avoids needing to coordinate with the three SPA theme files. */}
      <style>{ORB_KEYFRAMES}</style>
    </button>
  );
}

const ORB_KEYFRAMES = `
@keyframes jarvis-rotate {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}
@keyframes jarvis-rotate-rev {
  from { transform: rotate(360deg); }
  to   { transform: rotate(0deg); }
}
@keyframes jarvis-pulse {
  0%, 100% { transform: scale(1);   box-shadow: 0 0 0 0 oklch(0.65 0.22 30 / 0.6); }
  50%      { transform: scale(1.08); box-shadow: 0 0 0 6px oklch(0.65 0.22 30 / 0); }
}
`;
