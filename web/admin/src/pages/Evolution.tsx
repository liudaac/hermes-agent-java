import { useEffect, useState } from "react";
import { adminApi } from "@/lib/api";

export default function Evolution() {
  const [signals, setSignals] = useState<unknown[]>([]);
  const [proposals, setProposals] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      adminApi.getImprovementSignals().catch(() => ({ signals: [] })),
      adminApi.getImprovementProposals().catch(() => ({ proposals: [] })),
    ]).then(([s, p]) => {
      setSignals(s.signals ?? []);
      setProposals(p.proposals ?? []);
      setLoading(false);
    });
  }, []);

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">进化中心</h1>
        <p className="mt-1 text-sm text-muted">进化提案、失败模式、改进信号</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
        <div className="rounded-lg border border-border bg-surface p-4">
          <div className="text-2xl font-bold tabular-nums">{signals.length}</div>
          <div className="text-xs text-muted">改进信号</div>
        </div>
        <div className="rounded-lg border border-border bg-surface p-4">
          <div className="text-2xl font-bold tabular-nums">{proposals.length}</div>
          <div className="text-xs text-muted">进化提案</div>
        </div>
        <div className="rounded-lg border border-border bg-surface p-4">
          <div className="text-2xl font-bold tabular-nums text-green-600">自动</div>
          <div className="text-xs text-muted">调度模式</div>
        </div>
      </div>

      {/* Proposals */}
      <section>
        <h2 className="mb-3 text-sm font-semibold text-foreground">进化提案</h2>
        <div className="space-y-2">
          {proposals.length === 0 ? (
            <div className="flex h-32 items-center justify-center rounded-lg border border-dashed border-border text-sm text-muted">
              暂无提案，EvolutionScheduler 每日扫描失败 run 后自动生成
            </div>
          ) : (
            proposals.map((p, i) => {
              const proposal = p as Record<string, unknown>;
              return (
                <div key={i} className="rounded-lg border border-border bg-surface p-3">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">{String(proposal.title ?? proposal.type ?? `提案 #${i + 1}`)}</span>
                    <span className={`rounded px-2 py-0.5 text-xs ${
                      proposal.status === "accepted" ? "bg-green-50 text-green-700"
                      : proposal.status === "rejected" ? "bg-red-50 text-red-700"
                      : "bg-amber-50 text-amber-700"
                    }`}>
                      {String(proposal.status ?? "pending")}
                    </span>
                  </div>
                  {typeof proposal.description === "string" && (
                    <p className="mt-1 text-sm text-muted">{proposal.description}</p>
                  )}
                </div>
              );
            })
          )}
        </div>
      </section>

      {/* Signals */}
      <section>
        <h2 className="mb-3 text-sm font-semibold text-foreground">改进信号</h2>
        <div className="space-y-2">
          {signals.length === 0 ? (
            <div className="flex h-32 items-center justify-center rounded-lg border border-dashed border-border text-sm text-muted">
              暂无信号，SignalCollector 会在 Agent 运行过程中自动采集
            </div>
          ) : (
            signals.map((s, i) => {
              const signal = s as Record<string, unknown>;
              return (
                <div key={i} className="rounded-lg border border-border bg-surface p-3">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs">{String(signal.type ?? "signal")}</span>
                    <span className="text-xs text-muted">{String(signal.scope ?? "")}</span>
                  </div>
                  {typeof signal.description === "string" && (
                    <p className="mt-1 text-sm text-muted">{signal.description}</p>
                  )}
                </div>
              );
            })
          )}
        </div>
      </section>
    </div>
  );
}
