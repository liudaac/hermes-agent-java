import { useEffect, useState, useCallback } from "react";
import { portalApi } from "@/api/portal";
import type {
  BusinessApprovalRecord,
  BusinessApprovalsResponse,
  DelegatedTask,
} from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { ErrorCard } from "@/components/ErrorCard";
import { Inbox, Check, X, ClipboardList, Send, Play, Search } from "lucide-react";
import { useI18n } from "@/i18n";
import { formatRelativeTime, cn } from "@hermes/ui";
import { useWorkspace } from "@/hooks/useWorkspace";

type Tab = "approvals" | "delegated";

export default function Approvals() {
  const { t } = useI18n();
  const { workspaceId } = useWorkspace();
  const [tab, setTab] = useState<Tab>("approvals");
  const [data, setData] = useState<BusinessApprovalRecord[] | null>(null);
  const [tasks, setTasks] = useState<DelegatedTask[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const loadData = useCallback(() => {
    if (!workspaceId) return;
    setData(null);
    setError(null);
    portalApi
      .getBusinessApprovals(workspaceId, "PENDING")
      .then((res) => {
        const v = res as BusinessApprovalsResponse;
        setData(v.approvals ?? []);
      })
      .catch((e) => {
        setError(String(e?.message ?? e));
      });
  }, [workspaceId]);

  const loadTasks = useCallback(() => {
    portalApi
      .getDelegatedTasks()
      .then((res) => setTasks(res.tasks ?? []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    loadData();
    loadTasks();
  }, [loadData, loadTasks]);

  // Auto-refresh every 30s
  useEffect(() => {
    const timer = setInterval(() => {
      loadData();
      loadTasks();
    }, 30_000);
    return () => clearInterval(timer);
  }, [loadData, loadTasks]);

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

  const handleTaskAction = async (
    task: DelegatedTask,
    action: "submit" | "verify" | "execute",
  ) => {
    setBusyId(task.taskId);
    try {
      if (action === "submit") {
        const result = prompt("提交结果:");
        if (!result) return;
        await portalApi.submitDelegatedTask(task.tenantId, task.taskId, result);
      } else if (action === "verify") {
        await portalApi.verifyDelegatedTask(task.tenantId, task.taskId);
      } else {
        await portalApi.executeDelegatedTask(task.tenantId, task.taskId);
      }
      // Refresh tasks
      loadTasks();
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setBusyId(null);
    }
  };

  const tabs: { key: Tab; label: string; icon: typeof Inbox }[] = [
    { key: "approvals", label: t("nav.approvals"), icon: Inbox },
    { key: "delegated", label: t("nav.delegatedTasks"), icon: ClipboardList },
  ];

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
            {t("me.approvals")}
          </h1>
          <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
            {t("me.approvalsHint")}
          </p>
        </header>

        {/* Tab pills */}
        <div className="mb-5 flex gap-2">
          {tabs.map(({ key, label, icon: Icon }) => (
            <button
              key={key}
              onClick={() => setTab(key)}
              className={cn(
                "flex items-center gap-1.5 rounded-full px-4 py-2 text-[13px] font-medium transition active:scale-95",
                tab === key
                  ? "bg-[oklch(0.78_0.16_70_/_0.2)] text-[oklch(0.88_0.12_70)]"
                  : "bg-white/5 text-[var(--color-text-muted)]",
              )}
            >
              <Icon className="h-3.5 w-3.5" />
              {label}
            </button>
          ))}
        </div>

        {error && <ErrorCard message={error} onRetry={loadData} />}

        {/* ── Approvals Tab ── */}
        {tab === "approvals" && (
          !data ? (
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
          )
        )}

        {/* ── Delegated Tasks Tab ── */}
        {tab === "delegated" && (
          tasks.length === 0 ? (
            <GlassCard className="flex flex-col items-center gap-3 py-10 text-center">
              <ClipboardList className="h-7 w-7 text-[var(--color-text-muted)]" />
              <p className="text-[14px] text-[var(--color-text-secondary)]">{t("delegated.empty")}</p>
            </GlassCard>
          ) : (
            <div className="space-y-3">
              {tasks.map((task) => (
                <GlassCard key={task.taskId} className="flex flex-col gap-3">
                  <div className="flex items-start gap-3">
                    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[oklch(0.78_0.16_85_/_0.18)] text-[oklch(0.85_0.14_85)]">
                      <ClipboardList className="h-4 w-4" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <h3 className="text-[14px] font-semibold text-[var(--color-text-primary)]">
                        {task.title}
                      </h3>
                      <p className="text-[11px] text-[var(--color-text-muted)]">
                        {task.tenantId} · {task.status}
                      </p>
                      {task.description && (
                        <p className="mt-1.5 line-clamp-2 text-[12px] leading-relaxed text-[var(--color-text-secondary)]">
                          {task.description}
                        </p>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      disabled={busyId === task.taskId}
                      onClick={() => handleTaskAction(task, "submit")}
                      className={cn(
                        "flex-1 inline-flex items-center justify-center gap-1.5 rounded-xl py-2.5 text-[13px] font-semibold",
                        "bg-[oklch(0.72_0.14_145_/_0.85)] text-[oklch(0.18_0.04_145)] active:scale-95 transition",
                        busyId === task.taskId && "opacity-60",
                      )}
                    >
                      <Send className="h-3.5 w-3.5" />
                      {t("delegated.submit")}
                    </button>
                    <button
                      type="button"
                      disabled={busyId === task.taskId}
                      onClick={() => handleTaskAction(task, "verify")}
                      className={cn(
                        "flex-1 inline-flex items-center justify-center gap-1.5 rounded-xl py-2.5 text-[13px] font-semibold",
                        "bg-[oklch(0.70_0.16_280_/_0.6)] text-[oklch(0.78_0.14_280)] active:scale-95 transition",
                        busyId === task.taskId && "opacity-60",
                      )}
                    >
                      <Search className="h-3.5 w-3.5" />
                      {t("delegated.verify")}
                    </button>
                    <button
                      type="button"
                      disabled={busyId === task.taskId}
                      onClick={() => handleTaskAction(task, "execute")}
                      className={cn(
                        "flex-1 inline-flex items-center justify-center gap-1.5 rounded-xl py-2.5 text-[13px] font-semibold",
                        "bg-[oklch(0.30_0.02_50_/_0.6)] text-[var(--color-text-secondary)] active:scale-95 transition",
                        busyId === task.taskId && "opacity-60",
                      )}
                    >
                      <Play className="h-3.5 w-3.5" />
                      {t("delegated.execute")}
                    </button>
                  </div>
                </GlassCard>
              ))}
            </div>
          )
        )}
      </div>
    </AuroraBackground>
  );
}
