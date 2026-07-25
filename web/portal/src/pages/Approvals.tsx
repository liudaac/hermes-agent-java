import { useEffect, useState } from "react";
import { portalApi } from "@/api/portal";
import type {
  BusinessApprovalRecord,
  BusinessApprovalsResponse,
} from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { Inbox, Check, X } from "lucide-react";
import { useI18n } from "@/i18n";
import { formatRelativeTime } from "@hermes/ui";
import { cn } from "@hermes/ui";
import { useWorkspace } from "@/hooks/useWorkspace";

export default function Approvals() {
  const { t } = useI18n();
  const { workspaceId } = useWorkspace();
  const [data, setData] = useState<BusinessApprovalRecord[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    if (!workspaceId) return;
    let alive = true;
    portalApi
      .getBusinessApprovals(workspaceId, "PENDING")
      .then((res) => {
        if (!alive) return;
        const v = res as BusinessApprovalsResponse;
        setData(v.approvals ?? []);
      })
      .catch((e) => {
        if (alive) setError(String(e?.message ?? e));
      });
    return () => {
      alive = false;
    };
  }, [workspaceId]);

  const decide = async (approval: BusinessApprovalRecord, decision: "approve" | "reject") => {
    if (!approval.approvalId || !workspaceId) return;
    setBusyId(approval.approvalId);
    try {
      if (decision === "approve") {
        await portalApi.approveBusinessApproval(workspaceId, approval.approvalId, {
          reason: "已批准",
        });
      } else {
        await portalApi.rejectBusinessApproval(workspaceId, approval.approvalId, {
          reason: "已驳回",
        });
      }
      setData((cur) => (cur ?? []).filter((a) => a.approvalId !== approval.approvalId));
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
            {t("nav.approvals")}
          </h1>
          <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
            需要你处理的审批事项
          </p>
        </header>

        {error && (
          <GlassCard className="mb-3 border border-[oklch(0.68_0.20_25_/_0.35)]">
            <p className="text-[12px] text-[var(--color-text-secondary)]">{error}</p>
          </GlassCard>
        )}

        {!data ? (
          <div className="space-y-3">
            {[0, 1].map((i) => (
              <div key={i} className="shimmer h-24 rounded-2xl" />
            ))}
          </div>
        ) : data.length === 0 ? (
          <GlassCard tone="default" className="flex flex-col items-center gap-3 py-10 text-center">
            <Inbox className="h-7 w-7 text-[var(--color-text-muted)]" />
            <p className="text-[14px] text-[var(--color-text-secondary)]">{t("approvals.empty")}</p>
            <p className="text-[12px] text-[var(--color-text-muted)]">
              没有待处理审批，喝杯咖啡 ☕
            </p>
          </GlassCard>
        ) : (
          <div className="space-y-3">
            {data.map((a) => (
              <GlassCard key={a.approvalId} className="flex flex-col gap-3">
                <div className="flex items-start gap-3">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[oklch(0.70_0.16_280_/_0.18)] text-[oklch(0.78_0.14_280)]">
                    ⚖
                  </div>
                  <div className="min-w-0 flex-1">
                    <h3 className="text-[14px] font-semibold text-[var(--color-text-primary)]">
                      {a.title}
                    </h3>
                    <p className="text-[11px] text-[var(--color-text-muted)]">
                      {a.riskLevel} · {formatRelativeTime(a.createdAt)}
                    </p>
                    {a.summary && (
                      <p className="mt-1.5 text-[12px] leading-relaxed text-[var(--color-text-secondary)]">
                        {a.summary}
                      </p>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    disabled={busyId === a.approvalId}
                    onClick={() => decide(a, "approve")}
                    className={cn(
                      "flex-1 inline-flex items-center justify-center gap-1.5 rounded-xl py-2.5 text-[13px] font-semibold",
                      "bg-[oklch(0.72_0.14_145_/_0.85)] text-[oklch(0.18_0.04_145)] active:scale-95 transition",
                      busyId === a.approvalId && "opacity-60",
                    )}
                  >
                    <Check className="h-4 w-4" />
                    {t("approvals.approve")}
                  </button>
                  <button
                    type="button"
                    disabled={busyId === a.approvalId}
                    onClick={() => decide(a, "reject")}
                    className={cn(
                      "flex-1 inline-flex items-center justify-center gap-1.5 rounded-xl py-2.5 text-[13px] font-semibold",
                      "bg-[oklch(0.30_0.02_50_/_0.6)] text-[var(--color-text-secondary)] active:scale-95 transition",
                      busyId === a.approvalId && "opacity-60",
                    )}
                  >
                    <X className="h-4 w-4" />
                    {t("approvals.reject")}
                  </button>
                </div>
              </GlassCard>
            ))}
          </div>
        )}
      </div>
    </AuroraBackground>
  );
}
