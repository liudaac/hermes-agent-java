import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { portalApi } from "@/api/portal";
import type { BusinessRunRecord, BusinessRunStep } from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { StatusPill } from "@/components/StatusPill";
import { formatRelativeTime } from "@hermes/ui";
import { Clock, Zap, Users } from "lucide-react";

export default function RunDetail() {
  const { workspaceId, runId } = useParams();
  const [run, setRun] = useState<BusinessRunRecord | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!workspaceId || !runId) return;
    let alive = true;
    portalApi
      .getBusinessRun(workspaceId, runId)
      .then((res) => {
        if (alive) setRun(res.run);
      })
      .catch((e) => alive && setError(String(e?.message ?? e)));
    return () => {
      alive = false;
    };
  }, [workspaceId, runId]);

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
                <Metric icon={Zap} label="代币" value={run.tokensUsed ?? "—"} />
                <Metric icon={Users} label="团队" value={run.teamId ?? "—"} />
              </div>
            </GlassCard>

            {Array.isArray(run.steps) && run.steps.length > 0 && (
              <section>
                <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-[var(--color-text-muted)]">
                  执行步骤
                </h3>
                <GlassCard className="space-y-3">
                  {run.steps.map((step, idx) => (
                    <StepRow key={step.stepId ?? idx} idx={idx} step={step} />
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

function StepRow({ idx, step }: { idx: number; step: BusinessRunStep }) {
  return (
    <div className="flex items-start gap-3">
      <div className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[oklch(0.78_0.16_70_/_0.2)] text-[10px] text-[oklch(0.88_0.12_70)]">
        {idx + 1}
      </div>
      <div className="min-w-0">
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
