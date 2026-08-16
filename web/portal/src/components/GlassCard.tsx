import { cn } from "@hermes/ui";
import type { ReactNode } from "react";

interface GlassCardProps {
  children: ReactNode;
  className?: string;
  padding?: "sm" | "md" | "default" | "none" | "lg";
  onClick?: () => void;
  interactive?: boolean;
  /** @deprecated ignored - kept for backward compatibility */
  tone?: string;
  /** @deprecated ignored - kept for backward compatibility */
  grain?: boolean;
}

const PADDING: Record<string, string> = {
  none: "",
  sm: "p-3",
  md: "p-4 sm:p-5",
  default: "p-5",
  lg: "p-5 sm:p-7",
};

export function GlassCard({
  children,
  className,
  padding = "md",
  onClick,
  interactive,
  tone: _tone,
  grain: _grain,
}: GlassCardProps) {
  return (
    <div
      onClick={onClick}
      className={cn(
        "relative rounded-2xl border border-border bg-card",
        PADDING[padding] ?? "p-4",
        interactive && "transition-all hover:shadow-md hover:border-primary/30 cursor-pointer",
        className,
      )}
    >
      {children}
    </div>
  );
}
