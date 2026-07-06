/**
 * DataStateBadge — explicit three-state convention for portal data.
 *
 * Aligns the dashboard's data presentation with the three-space refactor:
 * a page or card can be in one of three states, and the UI must make it
 * obvious which:
 *
 *   - `production`  real data, no badge shown
 *   - `empty`       no records yet; surface a soft CTA ("X 还没有")
 *   - `sample`      demo data is on screen; show a violet "示例数据" badge
 *                   with an optional "清空示例" affordance
 *
 * The portal refuses to show sample data without an explicit opt-in
 * (`?sample=1`) so first-time onboarding never lands the user on a
 * dashboard that looks like a real production workspace.
 */

import type { ReactNode } from "react";
import { Circle, Sparkles, X } from "lucide-react";
import { cn } from "@/lib/utils";

export type DataState = "production" | "empty" | "sample";

export interface DataStateBadgeProps {
  state: DataState;
  /** What the data is about — used in copy ("3 个 workspace"). */
  entity?: string;
  /** When state="empty", the CTA verb. Default: "创建". */
  ctaLabel?: string;
  /** When state="empty", the CTA target href. */
  ctaHref?: string;
  /** When state="sample", callback to clear sample data. */
  onClearSample?: () => void;
  className?: string;
  children?: ReactNode;
}

export function DataStateBadge({
  state,
  entity,
  ctaLabel = "创建",
  ctaHref,
  onClearSample,
  className,
  children,
}: DataStateBadgeProps) {
  if (state === "production") {
    return children ? <>{children}</> : null;
  }

  if (state === "empty") {
    return (
      <div
        className={cn(
          "inline-flex items-center gap-2 rounded-lg border border-current/20",
          "bg-background-base/40 px-3 py-1.5 text-[0.7rem] tracking-[0.1em] opacity-70",
          className,
        )}
        data-state="empty"
      >
        <Circle className="h-3 w-3 opacity-50" />
        <span>
          {entity ? `还没有 ${entity}` : "暂无数据"}
        </span>
        {ctaHref && (
          <a
            href={ctaHref}
            className="ml-1 underline underline-offset-2 hover:opacity-100"
          >
            {ctaLabel} →
          </a>
        )}
        {children}
      </div>
    );
  }

  // state === "sample"
  return (
    <div
      className={cn(
        "inline-flex items-center gap-2 rounded-lg border border-violet-400/30",
        "bg-violet-500/10 px-3 py-1.5 text-[0.7rem] tracking-[0.1em] text-violet-200",
        className,
      )}
      data-state="sample"
    >
      <Sparkles className="h-3 w-3" />
      <span>示例数据{entity ? ` · ${entity}` : ""}</span>
      {onClearSample && (
        <button
          type="button"
          onClick={onClearSample}
          className="ml-1 inline-flex items-center gap-1 underline underline-offset-2 hover:text-violet-100"
        >
          <X className="h-2.5 w-2.5" />
          清空示例
        </button>
      )}
      {children}
    </div>
  );
}

/**
 * Helper — wrap a content block with the state badge as a corner label.
 * Use when the entire card/section is in a non-production state.
 */
export function DataStateWrapper({
  state,
  entity,
  ctaLabel,
  ctaHref,
  onClearSample,
  children,
}: DataStateBadgeProps) {
  if (state === "production") return <>{children}</>;
  return (
    <div className="space-y-3">
      <DataStateBadge
        state={state}
        entity={entity}
        ctaLabel={ctaLabel}
        ctaHref={ctaHref}
        onClearSample={onClearSample}
      />
      {children}
    </div>
  );
}

/**
 * State machine — derive a DataState from record count + sample flag.
 * Use this at the call site so the convention is consistent.
 */
export function inferDataState(opts: {
  count: number;
  sampleFlag?: boolean;
}): DataState {
  if (opts.sampleFlag) return "sample";
  if (opts.count === 0) return "empty";
  return "production";
}
