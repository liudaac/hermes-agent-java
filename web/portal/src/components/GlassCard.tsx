import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

interface GlassCardProps extends HTMLAttributes<HTMLDivElement> {
  children: ReactNode;
  /** Visual weight — `strong` for top-of-page, default for body cards, `accent` for hero/CTA. */
  tone?: "default" | "strong" | "accent";
  /** Add the grain texture overlay. */
  grain?: boolean;
  /** Add card-lift hover/press micro-interaction. */
  interactive?: boolean;
  padding?: "none" | "sm" | "md" | "lg";
}

const TONE = {
  default: "glass",
  strong: "glass-strong",
  accent: "glass-accent",
} as const;

const PADDING = {
  none: "",
  sm: "p-3",
  md: "p-4 sm:p-5",
  lg: "p-5 sm:p-7",
} as const;

/**
 * GlassCard — the atomic surface for portal. Always rounded, always translucent,
 * always with a subtle border. H5-friendly: generous radius, comfortable padding,
 * and a card-lift press feedback on tap.
 */
export function GlassCard({
  children,
  tone = "default",
  grain = false,
  interactive = false,
  padding = "md",
  className,
  ...rest
}: GlassCardProps) {
  return (
    <div
      {...rest}
      className={cn(
        "relative rounded-2xl",
        TONE[tone],
        PADDING[padding],
        interactive && "card-lift cursor-pointer select-none",
        grain && "grain",
        className,
      )}
    >
      {children}
    </div>
  );
}
