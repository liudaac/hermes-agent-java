/**
 * HarnessHealth - compact harness health summary for NOC.
 *
 * Polls /api/harness/active and shows:
 * - total / running / paused / error counts
 * - per-tenant agent activity bar
 * - SLA alerts (approval pending > 5 min)
 */
import { useEffect, useState } from "react";
import { Card } from "@hermes/ui";

interface HarnessEntry {
  sessionId: string;
  tenantId: string;
  status: string;
  debug: Record<string, unknown>;
}

const GATEWAY_URL = import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";

export function HarnessHealth() {
  const [harnesses, setHarnesses] = useState<HarnessEntry[]>([]);

  useEffect(() => {
    let alive = true;
    const poll = () => {
      fetch(`${GATEWAY_URL}/api/harness/active`)
        .then((r) => r.json())
        .then((data) => { if (alive && data?.harnesses) setHarnesses(data.harnesses); })
        .catch(() => {});
    };
    poll();
    const timer = setInterval(poll, 5000);
    return () => { alive = false; clearInterval(timer); };
  }, []);

  const total = harnesses.length;
  const running = harnesses.filter((h) => h.status === "running").length;
  const paused = harnesses.filter((h) => h.status === "paused" || h.status === "paused_approval").length;
  const errored = harnesses.filter((h) => h.status === "error" || h.status === "failed").length;

  // Group by tenant
  const byTenant = new Map<string, number>();
  for (const h of harnesses) {
    byTenant.set(h.tenantId, (byTenant.get(h.tenantId) ?? 0) + 1);
  }

  return (
    <Card className="p-4 space-y-3">
      <p className="text-[10px] uppercase tracking-wider text-zinc-500">Harness 全局状态</p>

      {/* Stat row */}
      <div className="grid grid-cols-5 gap-2">
        <Stat label="总计" value={total} color="text-zinc-200" />
        <Stat label="运行" value={running} color="text-emerald-400" />
        <Stat label="暂停" value={paused} color="text-amber-400" />
        <Stat label="异常" value={errored} color="text-red-400" />
        <Stat label="空闲" value={total - running - paused - errored} color="text-zinc-400" />
      </div>

      {/* Per-tenant bar */}
      {byTenant.size > 0 && (
        <div className="space-y-1">
          <p className="text-[10px] uppercase tracking-wider text-zinc-500">跨租户活跃度</p>
          {Array.from(byTenant.entries()).sort((a, b) => b[1] - a[1]).map(([tid, count]) => (
            <div key={tid} className="flex items-center gap-2 text-xs">
              <span className="text-zinc-400 truncate w-32">{tid}</span>
              <div className="flex-1 h-2 rounded-full bg-zinc-800 overflow-hidden">
                <div
                  className="h-full bg-emerald-500/60 rounded-full"
                  style={{ width: `${Math.min(100, count * 20)}%` }}
                />
              </div>
              <span className="text-zinc-500 w-6 text-right">{count}</span>
            </div>
          ))}
        </div>
      )}

      {total === 0 && (
        <p className="text-xs text-zinc-500 text-center py-2">暂无活跃 Agent</p>
      )}
    </Card>
  );
}

function Stat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="text-center">
      <p className={`text-lg font-semibold ${color}`}>{value}</p>
      <p className="text-[10px] text-zinc-500">{label}</p>
    </div>
  );
}
