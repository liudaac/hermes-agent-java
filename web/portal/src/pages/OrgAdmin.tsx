import { useEffect, useState } from "react";
import { GlassCard } from "@/components/GlassCard";
import { threeLayerApi, type OrgOverview } from "@/api/three-layer";

export default function OrgAdmin() {
  const [overview, setOverview] = useState<OrgOverview | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    threeLayerApi
      .getOrgOverview()
      .then((res) => setOverview(res))
      .catch(() => null)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-6 text-[var(--color-text-muted)]">加载中…</div>;
  if (!overview) return <div className="p-6 text-[var(--color-text-muted)]">无法加载组织概览</div>;

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6 pb-24">
      <h1 className="text-2xl font-bold text-[var(--color-text)]">组织管理</h1>

      {/* Spaces */}
      <GlassCard>
        <h2 className="mb-4 text-lg font-semibold">空间列表</h2>
        {overview.spaces.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">暂无空间</p>
        ) : (
          <div className="space-y-2">
            {overview.spaces.map((s) => (
              <div key={s.spaceId} className="rounded-lg bg-white/5 p-3">
                <div className="flex items-center justify-between">
                  <span className="font-medium text-[var(--color-text)]">{s.spaceName}</span>
                  <span className="inline-flex items-center rounded-full bg-[oklch(0.78_0.16_70_/_0.18)] px-2 py-0.5 text-[11px]">{s.memberCount} 成员</span>
                </div>
                <div className="mt-2 flex gap-4 text-xs text-[var(--color-text-muted)]">
                  <span>技能 {s.skillCount}</span>
                  <span>工具 {s.toolCount}</span>
                  <span>知识 {s.knowledgeCount}</span>
                  <span>模板 {s.templateCount}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </GlassCard>

      {/* Users */}
      <GlassCard>
        <h2 className="mb-4 text-lg font-semibold">用户列表</h2>
        {overview.users.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">暂无用户</p>
        ) : (
          <div className="space-y-2">
            {overview.users.map((u) => (
              <div key={u.userId} className="flex items-center justify-between rounded-lg bg-white/5 p-3">
                <span className="font-medium text-[var(--color-text)]">{u.displayName}</span>
                <span className="inline-flex items-center rounded-full bg-white/10 px-2 py-0.5 text-[11px]">{u.spaceCount} 空间</span>
              </div>
            ))}
          </div>
        )}
      </GlassCard>
    </div>
  );
}
