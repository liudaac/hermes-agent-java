import { AlertTriangle, RotateCw } from "lucide-react";
import { GlassCard } from "@/components/GlassCard";

interface ErrorCardProps {
  message: string;
  onRetry?: () => void;
}

export function ErrorCard({ message, onRetry }: ErrorCardProps) {
  return (
    <GlassCard className="mb-3 border-destructive/30">
      <div className="flex items-center gap-3">
        <AlertTriangle className="h-4 w-4 shrink-0 text-destructive" />
        <p className="min-w-0 flex-1 text-[12px] text-muted-foreground">
          {message}
        </p>
        {onRetry && (
          <button
            onClick={onRetry}
            className="inline-flex items-center gap-1 rounded-full bg-secondary px-3 py-1 text-[11px] font-medium text-primary hover:bg-secondary/80 active:scale-95 transition"
          >
            <RotateCw className="h-3 w-3" />
            重试
          </button>
        )}
      </div>
    </GlassCard>
  );
}
