import { useEffect, useState } from "react";
import { threeLayerApi, type OrgOverview } from "@/lib/api";
import { adminApi } from "@/lib/api";

export default function OrgOverview() {
  const [overview, setOverview] = useState<OrgOverview | null>(null);
  const [orgSummary, setOrgSummary] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      threeLayerApi.getOrgOverview().catch(() => null),
      adminApi.getOrgSummary().catch(() => null),
    ]).then(([ov, sum]) => {
      setOverview(ov);
      setOrgSummary(sum as Record<string, unknown> | null);
      setLoading(false);
    });
  }, []);

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">组织总览</h1>
        <p className="mt-1 text-sm text-muted">三层架构总览、健康度、组织状态</p>
      </div>

      {/* Stat cards */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <StatCard label="空间数" value={overview?.spaces?.length ?? 0} />
        <StatCard label="用户数" value={overview?.users?.length ?? 0} />
        <StatCard label="模型数" value={orgSummary ? Object.keys(orgSummary).length : "-"} />
        <StatCard label="健康度" value="正常" color="text-success" />
      </div>

      {/* Spaces */}
      <section>
        <h2 className="mb-3 text-sm font-semibold text-foreground">空间列表</h2>
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
              <tr>
                <th className="px-4 py-2.5 text-left">空间名称</th>
                <th className="px-4 py-2.5 text-right">成员</th>
                <th className="px-4 py-2.5 text-right">技能</th>
                <th className="px-4 py-2.5 text-right">工具</th>
                <th className="px-4 py-2.5 text-right">知识</th>
                <th className="px-4 py-2.5 text-right">模板</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {overview?.spaces?.length === 0 ? (
                <tr><td colSpan={6} className="px-4 py-8 text-center text-muted">暂无空间</td></tr>
              ) : overview?.spaces?.map((s) => (
                <tr key={s.spaceId} className="hover:bg-surface-hover">
                  <td className="px-4 py-2.5 font-medium">{s.spaceName}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums">{s.memberCount}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums">{s.skillCount}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums">{s.toolCount}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums">{s.knowledgeCount}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums">{s.templateCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* Users */}
      <section>
        <h2 className="mb-3 text-sm font-semibold text-foreground">用户列表</h2>
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
              <tr>
                <th className="px-4 py-2.5 text-left">用户</th>
                <th className="px-4 py-2.5 text-left">ID</th>
                <th className="px-4 py-2.5 text-right">空间数</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {overview?.users?.length === 0 ? (
                <tr><td colSpan={3} className="px-4 py-8 text-center text-muted">暂无用户</td></tr>
              ) : overview?.users?.map((u) => (
                <tr key={u.userId} className="hover:bg-surface-hover">
                  <td className="px-4 py-2.5 font-medium">{u.displayName}</td>
                  <td className="px-4 py-2.5 font-mono text-xs text-muted">{u.userId}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums">{u.spaceCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function StatCard({ label, value, color }: { label: string; value: number | string; color?: string }) {
  return (
    <div className="rounded-lg border border-border bg-surface p-4">
      <div className={`text-2xl font-bold tabular-nums ${color ?? "text-foreground"}`}>{value}</div>
      <div className="mt-0.5 text-xs text-muted">{label}</div>
    </div>
  );
}
