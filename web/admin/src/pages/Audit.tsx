import { useEffect, useState } from "react";
import { adminApi } from "@/lib/api";

export default function Audit() {
  const [audit, setAudit] = useState<Array<Record<string, unknown>>>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    adminApi.getControlAudit()
      .then((res) => setAudit((res as { entries?: Array<Record<string, unknown>> }).entries ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">审计日志</h1>
        <p className="mt-1 text-sm text-muted">审计日志、合规报告</p>
      </div>

      <div className="overflow-hidden rounded-lg border border-border">
        <table className="w-full text-sm">
          <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
            <tr>
              <th className="px-4 py-2.5 text-left">时间</th>
              <th className="px-4 py-2.5 text-left">操作</th>
              <th className="px-4 py-2.5 text-left">操作者</th>
              <th className="px-4 py-2.5 text-left">详情</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {audit.length === 0 ? (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-muted">暂无审计记录</td></tr>
            ) : audit.map((e, i) => (
              <tr key={i} className="hover:bg-surface-hover">
                <td className="px-4 py-2.5 text-xs text-muted">
                  {e.timestamp ? new Date(Number(e.timestamp)).toLocaleString() : "-"}
                </td>
                <td className="px-4 py-2.5 font-medium">{String(e.action ?? "-")}</td>
                <td className="px-4 py-2.5 font-mono text-xs">{String(e.actor ?? "-")}</td>
                <td className="px-4 py-2.5 text-xs text-muted">{String(e.details ?? "-")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
