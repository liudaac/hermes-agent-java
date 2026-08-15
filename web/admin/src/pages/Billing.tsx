import { useEffect, useState } from "react";
import { adminApi } from "@/lib/api";

export default function Billing() {
  const [billing, setBilling] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    adminApi.getOrgCost()
      .then((res) => setBilling(res as Record<string, unknown>))
      .catch(() => null)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">计费</h1>
        <p className="mt-1 text-sm text-muted">用量统计、计费明细、成本分析</p>
      </div>

      {billing ? (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
          {Object.entries(billing).slice(0, 6).map(([k, v]) => (
            <div key={k} className="rounded-lg border border-border bg-surface p-4">
              <div className="text-xs text-muted">{k}</div>
              <div className="mt-1 text-lg font-bold tabular-nums">
                {typeof v === "number" ? v.toLocaleString() : String(v)}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="flex h-32 items-center justify-center rounded-lg border border-dashed border-border text-sm text-muted">
          暂无计费数据
        </div>
      )}

      <div className="rounded-lg border border-border bg-surface p-4">
        <h3 className="text-sm font-medium text-foreground">租户级计费</h3>
        <p className="mt-1 text-sm text-muted">
          选择租户查看详细计费信息，包括日/周/月用量、Token 消耗、请求次数。
        </p>
      </div>
    </div>
  );
}
