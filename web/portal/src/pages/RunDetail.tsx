import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { portalApi } from "@/api/portal";
import type { BusinessRunRecord, BusinessRunStep } from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { StatusPill } from "@/components/StatusPill";
import { formatRelativeTime, useHarnessStream } from "@hermes/ui";
import { ToolCallTimeline, ApprovalInline } from "@hermes/ui";
import { Clock, Zap, Users, CheckCircle2, Circle, Loader2, AlertTriangle } from "lucide-react";

const GATEWAY_URL = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";

export default function RunDetail() {
  const { workspaceId, runId } = useParams();
  const [run, setRun] = useState<BusinessRunRecord | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Subscribe to harness stream when run is active
  const isActive = run?.status === "running" || run?.status === "queued";
  const { state: harness } = useHarnessStream(
    isActive && run?.runId ? run.runId : null,
    { tenantId: workspaceId },
  );

  useEffect(() => {
    if (!workspaceId || !runId) return;
    let alive = true;
    portalApi
      .getBusinessRun(workspaceId, runId)
      .then((res) => { if (alive) setRun(res.run); })
      .catch((e) => alive && setError(String(e?.message ?? e)));
    return () => { alive = false; };
  }, [workspaceId, runId]);

  // Auto-refresh when running
  useEffect(() => {
    if (!isActive || !workspaceId || !runId) return;
    const timer = setInterval(() => {
      portalApi.getBusinessRun(workspaceId, runId)
        .then((res) => setRun(res.run))
        .catch(() => {});
    }, 5000);
    return () => clearInterval(timer);
  }, [isActive, workspaceId, runId]);

  const handleApprove = async (approved: boolean) => {
    if (!run?.runId || !harness.pendingApproval) return;
    try {
      await fetch(`${GATEWAY_URL}/api/harness/${run.runId}/approve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          tool_call_id: harness.pendingApproval.callId,
          decision: approved ? "approve" : "reject",
        }),
      });
    } catch (e) {
      setError(String(e));
    }
  };

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        {error && (
          <GlassCard className="mb-3 border border-[oklch(0.68_0.20_25_/_0.35)]">
            <p className="text-[12px] text-[var(--color-text-secondary)]">{error}</p>
          </GlassCard>
        )}

        {!run ? (
          <div className="shimmer h-40 rounded-2xl" />
        ) : (
          <>
            {/* Header card */}
            <GlassCard tone="strong" grain className="mb-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <h2 className="font-display text-[22px] font-medium leading-tight">
                    {run.taskTitle}
                  </h2>
                  <p className="mt-1 text-[12px] text-[var(--color-text-muted)]">
                    {run.runId}
                  </p>
                </div>
                <StatusPill status={run.status} />
              </div>

              {run.resultSummary && (
                <p className="mt-3 text-[13px] leading-relaxed text-[var(--color-text-secondary)]">
                  {run.resultSummary}
                </p>
              )}

              <div className="mt-4 grid grid-cols-3 gap-3 border-t border-[oklch(0.30_0.015_50_/_0.4)] pt-3">
                <Metric icon={Clock} label="开始" value={formatRelativeTime(run.createdAt)} />
                <Metric icon={Zap} label="代币" value={run.tokensUsed ?? "-"} />
                <Metric icon={Users} label="团队" value={run.teamId ?? "-"} />
              </div>

              {/* Live metrics when running */}
              {isActive && (
                <div className="mt-3 grid grid-cols-3 gap-3">
                  <LiveMetric label="迭代" value={`${harness.iteration}/${harness.maxIterations || "?"}`} />
                  <LiveMetric label="Tokens" value={formatTokens(harness.tokensUsed)} />
                  <LiveMetric label="工具" value={`${harness.toolCalls.filter(t => t.status === "done").length}/${harness.toolCalls.length}`} />
                </div>
              )}
            </GlassCard>

            {/* Real-time execution flow (when active) */}
            {isActive && harness.toolCalls.length > 0 && (
              <section className="mb-4">
                <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-[var(--color-text-muted)]">
                  实时执行
                </h3>
                <GlassCard className="space-y-2">
                  <ToolCallTimeline calls={harness.toolCalls} />
                </GlassCard>
              </section>
            )}

            {/* Inline approval */}
            {isActive && harness.pendingApproval && (
              <div className="mb-4">
                <ApprovalInline
                  approval={harness.pendingApproval}
                  onDecide={handleApprove}
                />
              </div>
            )}

            {/* Steps */}
            {Array.isArray(run.steps) && run.steps.length > 0 && (
              <section>
                <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-[var(--color-text-muted)]">
                  执行步骤
                </h3>
                <GlassCard className="space-y-3">
                  {run.steps.map((step, idx) => (
                    <StepRow key={step.stepId ?? idx} idx={idx} step={step} livePhase={isActive ? harness.phase : "idle"} liveIdx={harness.iteration} />
                  ))}
                </GlassCard>
              </section>
            )}
          </>
        )}
      </div>
    </AuroraBackground>
  );
}

function StepRow({ idx, step, livePhase, liveIdx }: { idx: number; step: BusinessRunStep; livePhase: string; liveIdx: number }) {
  const isCurrent = livePhase !== "idle" && idx === liveIdx - 1;
  const isDone = step.status === "completed" || step.status === "succeeded";
  const isError = step.status === "failed" || step.status === "error";

  return (
    <div className="flex items-start gap-3">
      <div className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[10px]">
        {isDone ? (
          <CheckCircle2 className="h-5 w-5 text-emerald-400" />
        ) : isError ? (
          <AlertTriangle className="h-5 w-5 text-red-400" />
        ) : isCurrent ? (
          <Loader2 className="h-5 w-5 text-blue-400 animate-spin" />
        ) : (
          <Circle className="h-5 w-5 text-zinc-600" />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-medium text-[var(--color-text-primary)]">
          {step.title ?? step.actor ?? "步骤"}
        </p>
        {step.summary && (
          <p className="mt-0.5 text-[12px] leading-relaxed text-[var(--color-text-secondary)]">
            {step.summary}
          </p>
        )}
      </div>
    </div>
  );
}

function Metric({ icon: Icon, label, value }: { icon: any; label: string; value: any }) {
  return (
    <div className="flex flex-col">
      <span className="inline-flex items-center gap-1 text-[10px] tracking-wider text-[var(--color-text-muted)] uppercase">
        <Icon className="h-3 w-3" />
        {label}
      </span>
      <span className="mt-1 text-[13px] font-medium text-[var(--color-text-primary)]">
        {String(value)}
      </span>
    </div>
  );
}

function LiveMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col">
      <span className="text-[10px] tracking-wider text-[oklch(0.78_0.16_70)] uppercase">{label}</span>
      <span className="mt-1 text-[13px] font-medium text-[oklch(0.88_0.12_70)]">{value}</span>
    </div>
  );
}

function formatTokens(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}
