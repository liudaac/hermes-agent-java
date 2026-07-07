import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

interface AuroraBackgroundProps {
  children: ReactNode;
  className?: string;
}

/**
 * AuroraBackground — fixed-position page background with two soft radial
 * glows (top hero glow + bottom counter-glow) and a fine grain. Mirrors the
 * public H5 site so portal reads as the same product.
 */
export function AuroraBackground({ children, className }: AuroraBackgroundProps) {
  return (
    <div className={cn("aurora grain relative min-h-screen w-full", className)}>
      <div className="relative z-10">{children}</div>
    </div>
  );
}
