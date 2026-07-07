/**
 * JarvisFullscreen — the full-screen overlay (design.md §9).
 *
 * Layout: 100vw × 100vh, particle area on the right ~40%, message
 * flow on the left ~60%. This is the immersive mode for long
 * conversations.
 */
import { useEffect, useRef } from "react";
import { X } from "lucide-react";
import { useJarvisStore, setOverlay } from "../hooks/useJarvisStore";
import { useJarvisCore } from "../core/JarvisCore";
import { ConversationFlow } from "./ConversationFlow";

interface JarvisFullscreenProps {
  onSubmit: (text: string) => void;
  onApprove?: (id: string) => void;
  onReject?: (id: string) => void;
}

export function JarvisFullscreen({ onSubmit, onApprove, onReject }: JarvisFullscreenProps) {
  const overlay = useJarvisStore((s) => s.overlay);
  const form = useJarvisStore((s) => s.form);
  const { mountParticleCanvas } = useJarvisCore();
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    if (overlay === "fullscreen" && canvasRef.current) {
      mountParticleCanvas(canvasRef.current, 800, form);
    }
  }, [overlay, form, mountParticleCanvas]);

  if (overlay !== "fullscreen") return null;

  return (
    <div className="fixed inset-0 z-50 flex bg-[oklch(0.12_0.015_55_/_0.95)] backdrop-blur-md">
      <div className="flex w-[60%] flex-col border-r border-[oklch(0.30_0.015_50_/_0.4)]">
        <div className="flex shrink-0 items-center justify-between border-b border-[oklch(0.30_0.015_50_/_0.4)] px-6 py-3">
          <h2 className="font-display text-[24px] font-medium text-[var(--color-text-primary)]">
            Jarvis
          </h2>
          <button
            type="button"
            onClick={() => setOverlay("hidden")}
            className="rounded-full p-2 text-[var(--color-text-muted)] hover:bg-[oklch(0.30_0.02_50_/_0.4)]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="min-h-0 flex-1">
          <ConversationFlow
            onSubmit={onSubmit}
            onApprove={onApprove}
            onReject={onReject}
          />
        </div>
      </div>
      <div className="flex flex-1 items-center justify-center">
        <canvas
          ref={canvasRef}
          width={800}
          height={800}
          className="h-[80vh] w-[80vh] max-w-full max-h-full"
        />
      </div>
    </div>
  );
}
