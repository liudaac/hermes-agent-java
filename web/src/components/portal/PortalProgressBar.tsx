/**
 * PortalProgressBar — the 7-step Business Portal journey visualization.
 *
 * Renders at the top of /portal pages. Drives the user from template
 * selection through knowledge accumulation:
 *
 *   1 模板 → 2 工作空间 → 3 团队 → 4 场景 → 5 运行 → 6 审批 → 7 沉淀
 *
 * - Done steps show a checkmark and can be navigated to.
 * - The active step pulses to draw the eye.
 * - Future steps are dimmed.
 * - Empty state ("还没有选模板") shows on every step before the first done.
 *
 * Aligned with the three-space refactor (Portal default page).
 */

import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Check, ChevronRight, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";
import { portalApi } from "@/lib/api/portal";
import type {
  BusinessProgressResponse,
  BusinessProgressStep,
  StepStatus,
} from "@/lib/api/types/portal";

interface ProgressBarProps {
  workspaceId?: string;
  /** Override the active workspace from the parent; otherwise inferred from API. */
  currentWorkspaceId?: string;
  className?: string;
}

export function PortalProgressBar({ workspaceId, currentWorkspaceId, className }: ProgressBarProps) {
  const [data, setData] = useState<BusinessProgressResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    portalApi
      .getBusinessProgress(workspaceId)
      .then((resp) => {
        if (!cancelled) setData(resp);
      })
      .catch((e) => {
        if (!cancelled) setError(String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId]);

  const stepHref = useMemo(() => buildStepHref(currentWorkspaceId), [currentWorkspaceId]);

  if (error) {
    // Silent failure — the progress bar is non-essential. Don't block the page.
    return null;
  }

  if (!data || !data.steps || data.steps.length === 0) {
    return <ProgressBarSkeleton className={className} />;
  }

  const allMissing = data.steps.every((s) => s.status === "missing");

  return (
    <div
      className={cn(
        "rounded-2xl border border-current/15 bg-background-base/40 backdrop-blur-sm",
        "p-4 sm:p-5",
        className,
      )}
      data-testid="portal-progress-bar"
    >
      {allMissing && <EmptyStateHint />}

      <div className="flex items-start gap-1.5 sm:gap-3 overflow-x-auto scrollbar-none">
        {data.steps.map((step, idx) => (
          <ProgressStep
            key={step.key}
            step={step}
            isActive={step.step === data.activeStep}
            isLast={idx === data.steps.length - 1}
            href={stepHref[step.key]}
          />
        ))}
      </div>

      <PendingBanner pending={data.pendingApprovals} highRisk={data.highRiskApprovals} />
    </div>
  );
}

// ── Step cell ──────────────────────────────────────────────────

function ProgressStep({
  step,
  isActive,
  isLast,
  href,
}: {
  step: BusinessProgressStep;
  isActive: boolean;
  isLast: boolean;
  href?: string;
}) {
  const navigate = useNavigate();
  const navigable = step.status === "done" && href;

  const onClick = () => {
    if (navigable) navigate(href);
  };

  return (
    <>
      <button
        type="button"
        onClick={onClick}
        disabled={!navigable}
        className={cn(
          "group flex flex-col items-center gap-1.5 min-w-[64px] sm:min-w-[80px] flex-shrink-0",
          "transition-all duration-200",
          navigable ? "cursor-pointer hover:opacity-100" : "cursor-default",
          isActive ? "opacity-100" : step.status === "done" ? "opacity-80" : "opacity-40",
        )}
        title={step.status === "done" ? `查看 ${step.label}` : `${step.label} (${statusLabel(step.status)})`}
      >
        <div
          className={cn(
            "flex h-8 w-8 sm:h-9 sm:w-9 items-center justify-center rounded-full",
            "border-2 transition-all",
            step.status === "done"
              ? "border-emerald-400/70 bg-emerald-400/15 text-emerald-300"
              : isActive
                ? "border-amber-300/80 bg-amber-300/15 text-amber-200 status-pulse"
                : "border-current/30 text-current/60",
          )}
        >
          {step.status === "done" ? (
            <Check className="h-4 w-4" />
          ) : isActive ? (
            <Sparkles className="h-4 w-4" />
          ) : (
            <span className="font-mondwest text-[0.7rem]">{step.step}</span>
          )}
        </div>
        <div className="flex flex-col items-center gap-0.5">
          <span
            className={cn(
              "text-[0.6rem] sm:text-[0.7rem] tracking-[0.12em]",
              isActive ? "text-amber-200" : "",
            )}
          >
            {step.step} {step.label}
          </span>
          {step.status === "done" && (step.total !== undefined || step.members !== undefined || step.count !== undefined) ? (
            <span className="text-[0.55rem] opacity-50">
              {step.total !== undefined ? `${step.total} runs` : ""}
              {step.members !== undefined ? `${step.members} members` : ""}
              {step.count !== undefined && step.total === undefined && step.members === undefined
                ? `${step.count} ready`
                : ""}
            </span>
          ) : null}
        </div>
      </button>
      {!isLast && (
        <ChevronRight
          className={cn(
            "h-3.5 w-3.5 self-center flex-shrink-0",
            "opacity-30 mt-3.5",
            step.status === "done" ? "text-emerald-300" : "",
          )}
        />
      )}
    </>
  );
}

// ── Empty state ────────────────────────────────────────────────

function EmptyStateHint() {
  return (
    <div className="mb-3 rounded-lg border border-amber-300/30 bg-amber-300/5 px-3 py-2 text-[0.7rem] tracking-[0.08em] text-amber-200/90">
      还没有行业模板 —{" "}
      <a href="/portal/templates" className="underline underline-offset-2 hover:text-amber-100">
        选一个模板开始
      </a>
    </div>
  );
}

// ── Pending approvals banner ───────────────────────────────────

function PendingBanner({ pending, highRisk }: { pending: number; highRisk: number }) {
  if (pending <= 0) return null;
  const tone = highRisk > 0 ? "border-rose-400/40 bg-rose-400/10 text-rose-200" : "border-amber-300/30 bg-amber-300/5 text-amber-200";
  return (
    <div
      className={cn(
        "mt-3 rounded-lg border px-3 py-2 text-[0.7rem] tracking-[0.08em]",
        "flex items-center justify-between gap-3",
        tone,
      )}
    >
      <span>
        {pending} 个待审批
        {highRisk > 0 && <span className="ml-2">· {highRisk} 个高风险</span>}
      </span>
      <a href="/portal/approvals" className="underline underline-offset-2 hover:opacity-100">
        立即查看 →
      </a>
    </div>
  );
}

// ── Skeleton ───────────────────────────────────────────────────

function ProgressBarSkeleton({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "rounded-2xl border border-current/10 bg-background-base/30 p-4 sm:p-5",
        "animate-pulse h-[88px]",
        className,
      )}
    />
  );
}

// ── Helpers ────────────────────────────────────────────────────

function statusLabel(status: StepStatus): string {
  switch (status) {
    case "done":
      return "已完成";
    case "partial":
      return "进行中";
    case "active":
      return "待处理";
    case "missing":
    default:
      return "未开始";
  }
}

function buildStepHref(wsId?: string) {
  const ws = wsId ? `/${wsId}` : "";
  return {
    template: "/portal/templates",
    workspace: "/portal/workspaces",
    team: "/portal/agents",
    scenario: ws ? `/portal/runs${ws}/placeholder` : "/portal/templates",
    run: ws ? `/portal/runs${ws}/latest` : "/portal/templates",
    approval: "/portal/approvals",
    knowledge: "/portal/evolution",
  };
}
