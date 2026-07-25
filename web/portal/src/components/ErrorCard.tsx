/**
 * ErrorCard - reusable error display with retry button.
 */
import { AlertTriangle, RotateCw } from "lucide-react";
import { GlassCard } from "@/components/GlassCard";

interface ErrorCardProps {
  message: string;
  onRetry?: () => void;
}

export function ErrorCard({ message, onRetry }: ErrorCardProps) {
  return (
    <GlassCard className="mb-3 border border-[oklch(0.68_0.20_25_/_0.35)]">
      <div className="flex items-center gap-3">
        <AlertTriangle className="h-4 w-4 shrink-0 text-[oklch(0.75_0.18_25)]" />
        <p className="min-w-0 flex-1 text-[12px] text-[var(--color-text-secondary)]">
          {message}
        </p>
        {onRetry && (
          <button
            onClick={onRetry}
            className="inline-flex items-center gap-1 rounded-full bg-[oklch(0.30_0.02_50_/_0.5)] px-3 py-1 text-[11px] font-medium text-[var(--color-accent)] hover:bg-[oklch(0.30_0.02_50_/_0.7)] active:scale-95 transition"
          >
            <RotateCw className="h-3 w-3" />
            重试
          </button>
        )}
      </div>
    </GlassCard>
  );
}
