/**
 * JarvisHudPanel — the summoned state. The panel animates out from the
 * bottom-right orb (scale-from + translate-from), covers ~520×620px on
 * desktop, full-screen on mobile.
 *
 * Visual language (Iron Man JARVIS HUD):
 *   - Dark glass panel with cyan/violet grid-glow borders
 *   - Corner brackets (not rounded rectangles) — HUD frame markers
 *   - Monospace typography throughout
 *   - Cyan primary / violet secondary / amber warning / red critical
 *   - Scanline, corner ticks, system status bar at top
 *   - Messages are left-aligned monospace blocks, not chat bubbles
 *   - Input is a terminal-style prompt ("> _")
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { X, Mic, Send, Settings, AlertTriangle } from "lucide-react";
import {
  useJarvisStore,
  setOverlay,
  setForm,
  clearMessages,
} from "../hooks/useJarvisStore";
import { useJarvisCore } from "../core/JarvisCore";
import { ConversationFlow } from "./ConversationFlow";

interface JarvisHudPanelProps {
  onSubmit: (text: string) => void | Promise<void>;
  onApprove: (approvalId: string) => void | Promise<void>;
  onReject: (approvalId: string) => void | Promise<void>;
  onMicClick?: () => void;
  onSettingsClick?: () => void;
}

export function JarvisHudPanel({
  onSubmit,
  onApprove,
  onReject,
  onMicClick,
  onSettingsClick,
}: JarvisHudPanelProps) {
  const overlay = useJarvisStore((s) => s.overlay);
  const form = useJarvisStore((s) => s.form);
  const messages = useJarvisStore((s) => s.messages);
  const pendingApproval = useJarvisStore((s) => s.pendingApproval);
  const isSummoned = overlay === "summoned";

  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const { mountParticleCanvas } = useJarvisCore();

  // Mini particle display in the panel header (80×80).
  useEffect(() => {
    if (!isSummoned) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const engine = mountParticleCanvas(canvas, 80, form === "core" ? "sphere" : form, { mini: true });
    return () => { engine?.stop?.(); };
  }, [isSummoned, form, mountParticleCanvas]);

  // Auto-focus input when summoned.
  useEffect(() => {
    if (isSummoned) {
      setTimeout(() => inputRef.current?.focus(), 250);
    } else {
      setInput("");
    }
  }, [isSummoned]);

  // Auto-scroll to bottom when messages change.
  useEffect(() => {
    if (!scrollRef.current) return;
    scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [messages]);

  // Switch form to sphere while thinking, back to core when done.
  const lastMsg = messages[messages.length - 1];
  useEffect(() => {
    if (!lastMsg) return;
    if (lastMsg.pending) setForm("sphere");
    else if (lastMsg.role === "jarvis" && !lastMsg.pending) setForm("core");
  }, [lastMsg]);

  const handleSubmit = useCallback(async (e?: React.FormEvent) => {
    e?.preventDefault();
    const text = input.trim();
    if (!text || sending) return;
    setSending(true);
    setInput("");
    try {
      await onSubmit(text);
    } finally {
      setSending(false);
      setTimeout(() => inputRef.current?.focus(), 50);
    }
  }, [input, sending, onSubmit]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === "Escape") {
      setOverlay("hidden");
    } else if ((e.metaKey || e.ctrlKey) && e.key === "k") {
      setOverlay("hidden");
    }
  }, []);

  if (!isSummoned) return null;

  return (
    <div
      className={[
        "fixed inset-0 z-40",
        // Allow clicks on the page behind to dismiss (the backdrop is
        // semi-transparent and closes the panel on click).
        "bg-[oklch(0.14_0.015_60/_0.55)] backdrop-blur-[2px]",
        "animate-[jarvis-fadeIn_180ms_ease-out]",
      ].join(" ")}
      onClick={(e) => {
        if (e.target === e.currentTarget) setOverlay("hidden");
      }}
      onKeyDown={handleKeyDown}
    >
      {/* The HUD panel — anchored bottom-right, animates out from the orb */}
      <div
        className={[
          "fixed bottom-5 right-5",
          // Dimensions: desktop 520×620, mobile full-screen.
          "h-[min(620px,calc(100vh-40px))] w-[min(520px,calc(100vw-40px))]",
          // HUD frame: dark glass, glowing border (done via box-shadow + inset).
          "bg-[oklch(0.14_0.02_60/_0.92)] backdrop-blur-md",
          "border border-[oklch(0.70_0.15_200/_0.35)]",
          "shadow-[0_0_40px_-5px_oklch(0.75_0.18_200/_0.3),inset_0_0_0_1px_oklch(0.75_0.18_200/_0.1)]",
          "font-mono text-[13px] leading-relaxed",
          "text-[oklch(0.92_0.03_200)]",
          // Entry animation: scale + translate from the orb's corner
          "origin-bottom-right",
          "animate-[jarvis-panelIn_260ms_cubic-bezier(0.2,0.9,0.25,1)]",
          "flex flex-col overflow-hidden",
        ].join(" ")}
      >
        {/* Corner brackets — pure CSS, no images */}
        <span className="pointer-events-none absolute left-0 top-0 h-4 w-4 border-l border-t border-[oklch(0.78_0.16_200)]" />
        <span className="pointer-events-none absolute right-0 top-0 h-4 w-4 border-r border-t border-[oklch(0.78_0.16_200)]" />
        <span className="pointer-events-none absolute bottom-0 left-0 h-4 w-4 border-b border-l border-[oklch(0.78_0.16_200)]" />
        <span className="pointer-events-none absolute bottom-0 right-0 h-4 w-4 border-b border-r border-[oklch(0.78_0.16_200)]" />

        {/* Thin accent scanline at top */}
        <div className="pointer-events-none absolute left-4 right-4 top-0 h-px bg-gradient-to-r from-transparent via-[oklch(0.78_0.18_200/0.6)] to-transparent" />

        {/* ── Header ────────────────────────────────────────────── */}
        <header className="flex items-center gap-3 border-b border-[oklch(0.70_0.15_200/_0.2)] px-4 py-2.5">
          <div className="relative h-[56px] w-[56px] shrink-0">
            <canvas
              ref={canvasRef}
              width={80}
              height={80}
              className="absolute inset-0 h-full w-full"
            />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-baseline gap-2">
              <span className="text-[13px] font-bold tracking-[0.25em] text-[oklch(0.88_0.12_200)]">JARVIS</span>
              <span className="text-[10px] tracking-[0.2em] text-[oklch(0.55_0.10_200)]">v1.0 / CROSS-SPACE</span>
            </div>
            <div className="mt-1 flex items-center gap-3 text-[9px] tracking-[0.2em] uppercase text-[oklch(0.55_0.08_200)]">
              <span className="flex items-center gap-1">
                <span className={[
                  "inline-block h-1.5 w-1.5 rounded-full",
                  sending ? "bg-[oklch(0.78_0.16_85)] animate-pulse" : "bg-[oklch(0.72_0.14_145)]",
                ].join(" ")} />
                {sending ? "PROCESSING" : "STANDBY"}
              </span>
              <span>FORM: {form.toUpperCase()}</span>
              <span className="ml-auto">MESSAGES: {messages.length}</span>
            </div>
          </div>
          <div className="flex items-center gap-1">
            {onSettingsClick && (
              <button
                type="button"
                onClick={onSettingsClick}
                aria-label="Settings"
                className="rounded p-1.5 text-[oklch(0.55_0.10_200)] transition-colors hover:bg-[oklch(0.75_0.18_200/_0.1)] hover:text-[oklch(0.82_0.14_200)]"
              >
                <Settings className="h-4 w-4" />
              </button>
            )}
            <button
              type="button"
              onClick={() => setOverlay("hidden")}
              aria-label="Close"
              className="rounded p-1.5 text-[oklch(0.55_0.10_200)] transition-colors hover:bg-[oklch(0.75_0.18_200/_0.1)] hover:text-[oklch(0.82_0.14_200)]"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </header>

        {/* ── Pending approval banner ───────────────────────────── */}
        {pendingApproval && (
          <div className="flex items-start gap-3 border-b border-[oklch(0.65_0.16_80/_0.3)] bg-[oklch(0.65_0.16_80/_0.08)] px-4 py-2.5">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-[oklch(0.78_0.16_85)]" />
            <div className="min-w-0 flex-1 text-[11px] leading-relaxed">
              <div className="tracking-[0.2em] uppercase text-[oklch(0.78_0.16_85)]">
                APPROVAL REQUIRED · {pendingApproval.risk.toUpperCase()}
              </div>
              <div className="mt-0.5 text-[oklch(0.92_0.05_80)]">{pendingApproval.title}</div>
              <div className="mt-1.5 flex gap-2">
                <button
                  type="button"
                  onClick={() => { onApprove(pendingApproval.approvalId); }}
                  className="rounded border border-[oklch(0.72_0.14_145/_0.5)] bg-[oklch(0.72_0.14_145/_0.12)] px-2.5 py-1 text-[10px] tracking-[0.2em] uppercase text-[oklch(0.82_0.12_145)] transition-colors hover:bg-[oklch(0.72_0.14_145/_0.25)]"
                >
                  APPROVE
                </button>
                <button
                  type="button"
                  onClick={() => { onReject(pendingApproval.approvalId); }}
                  className="rounded border border-[oklch(0.65_0.22_30/_0.5)] bg-[oklch(0.65_0.22_30/_0.12)] px-2.5 py-1 text-[10px] tracking-[0.2em] uppercase text-[oklch(0.85_0.18_30)] transition-colors hover:bg-[oklch(0.65_0.22_30/_0.25)]"
                >
                  REJECT
                </button>
              </div>
            </div>
          </div>
        )}

        {/* ── Conversation ─────────────────────────────────────── */}
        <div
          ref={scrollRef}
          className="flex-1 overflow-y-auto overflow-x-hidden px-4 py-3"
        >
          <ConversationFlow />
        </div>

        {/* ── Input bar (terminal-style) ───────────────────────── */}
        <form
          onSubmit={handleSubmit}
          className="flex items-center gap-2 border-t border-[oklch(0.70_0.15_200/_0.2)] px-4 py-3"
        >
          <span className="select-none text-[14px] font-bold text-[oklch(0.78_0.16_200)]">{">"}</span>
          <input
            ref={inputRef}
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            disabled={sending}
            placeholder={sending ? "processing..." : "指令..."}
            spellCheck={false}
            autoComplete="off"
            className={[
              "min-w-0 flex-1 bg-transparent",
              "text-[13px] text-[oklch(0.95_0.03_200)] placeholder:text-[oklch(0.40_0.08_200)]",
              "outline-none ring-0",
              "caret-[oklch(0.78_0.18_200)]",
              "font-mono",
            ].join(" ")}
          />
          {onMicClick && (
            <button
              type="button"
              onClick={onMicClick}
              aria-label="Voice input"
              className="rounded p-1.5 text-[oklch(0.55_0.10_200)] transition-colors hover:bg-[oklch(0.75_0.18_200/_0.1)] hover:text-[oklch(0.82_0.14_200)]"
            >
              <Mic className="h-4 w-4" />
            </button>
          )}
          <button
            type="submit"
            disabled={!input.trim() || sending}
            aria-label="Send"
            className={[
              "rounded p-1.5 transition-colors",
              input.trim() && !sending
                ? "bg-[oklch(0.72_0.14_145/_0.2)] text-[oklch(0.82_0.12_145)] hover:bg-[oklch(0.72_0.14_145/_0.35)]"
                : "text-[oklch(0.35_0.06_200)]",
            ].join(" ")}
          >
            <Send className="h-4 w-4" />
          </button>
        </form>

        {/* Footer status ticker */}
        <div className="flex items-center justify-between border-t border-[oklch(0.70_0.15_200/_0.15)] bg-[oklch(0.12_0.02_60/_0.5)] px-3 py-1.5 text-[8px] tracking-[0.25em] uppercase text-[oklch(0.45_0.08_200)]">
          <span>SESSION · {typeof crypto !== "undefined" && typeof crypto.randomUUID === "function" ? "AUTH" : "OFFLINE"}</span>
          <span className="flex items-center gap-1">
            <span className="inline-block h-1 w-1 rounded-full bg-[oklch(0.72_0.14_145)]" />
            LINK STABLE
          </span>
          <button
            type="button"
            onClick={() => clearMessages()}
            className="text-[oklch(0.45_0.08_200)] transition-colors hover:text-[oklch(0.70_0.12_200)]"
          >
            CLEAR
          </button>
        </div>
      </div>

      <style>{PANEL_KEYFRAMES}</style>
    </div>
  );
}

const PANEL_KEYFRAMES = `
@keyframes jarvis-fadeIn {
  from { opacity: 0; }
  to   { opacity: 1; }
}
@keyframes jarvis-panelIn {
  from {
    opacity: 0;
    transform: scale(0.7) translate(20px, 20px);
  }
  to {
    opacity: 1;
    transform: scale(1) translate(0, 0);
  }
}
`;
