/**
 * JarvisOverlay — the 360×420 floating overlay (design.md §8).
 *
 * 4 vertical regions:
 *   - Top: title / close / settings
 *   - Particle area: small 80×80 form
 *   - Message flow
 *   - Input bar
 *
 * Mounts only when `overlay === "summoned"`. Click-outside or Esc
 * dismisses (handled in useKeyShortcuts).
 */
import { useEffect, useRef } from "react";
import { X, Settings } from "lucide-react";
import { useJarvisStore, setOverlay } from "../hooks/useJarvisStore";
import { useJarvisCore } from "../core/JarvisCore";
import { ConversationFlow } from "./ConversationFlow";

interface JarvisOverlayProps {
  onSubmit: (text: string) => void;
  onApprove?: (approvalId: string) => void;
  onReject?: (approvalId: string) => void;
}

export function JarvisOverlay({ onSubmit, onApprove, onReject }: JarvisOverlayProps) {
  const overlay = useJarvisStore((s) => s.overlay);
  const form = useJarvisStore((s) => s.form);
  const { mountParticleCanvas } = useJarvisCore();

  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    if (overlay === "summoned" && canvasRef.current) {
      mountParticleCanvas(canvasRef.current, 80, form, { mini: true });
    }
  }, [overlay, form, mountParticleCanvas]);

  if (overlay !== "summoned") return null;

  return (
    <div
      className="fixed bottom-24 right-6 z-50 flex h-[420px] w-[360px] flex-col overflow-hidden rounded-2xl border border-[oklch(0.30_0.015_50_/_0.4)] bg-[oklch(0.18_0.015_55_/_0.85)] shadow-2xl backdrop-blur-md"
      role="dialog"
      aria-label="Jarvis"
    >
      {/* Top */}
      <div className="flex shrink-0 items-center justify-between border-b border-[oklch(0.30_0.015_50_/_0.4)] px-3 py-2">
        <div className="flex items-center gap-2">
          <canvas ref={canvasRef} width={32} height={32} className="rounded-full" />
          <div>
            <p className="text-[12px] font-semibold tracking-[0.18em] uppercase text-[var(--color-text-primary)]">
              Jarvis
            </p>
            <p className="text-[10px] text-[var(--color-text-muted)]">
              形态：{form}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            className="flex h-7 w-7 items-center justify-center rounded-full text-[var(--color-text-muted)] hover:bg-[oklch(0.30_0.02_50_/_0.4)] active:scale-95 transition"
            title="设置"
          >
            <Settings className="h-3.5 w-3.5" />
          </button>
          <button
            type="button"
            onClick={() => setOverlay("hidden")}
            className="flex h-7 w-7 items-center justify-center rounded-full text-[var(--color-text-muted)] hover:bg-[oklch(0.30_0.02_50_/_0.4)] active:scale-95 transition"
            title="关闭 (Esc)"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {/* Message flow + input */}
      <div className="min-h-0 flex-1">
        <ConversationFlow
          onSubmit={onSubmit}
          onApprove={onApprove}
          onReject={onReject}
        />
      </div>
    </div>
  );
}
