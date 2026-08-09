import { useEffect, useState } from "react";
import { GlassCard } from "@/components/GlassCard";
import { StatusPill } from "@/components/StatusPill";
import { threeLayerApi, type SpaceOverview, type SpaceMember, type KnowledgeEntry } from "@/api/three-layer";

const DEFAULT_SPACE = "default";

export default function SpaceAdmin() {
  
  const [overview, setOverview] = useState<SpaceOverview | null>(null);
  const [members, setMembers] = useState<SpaceMember[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      threeLayerApi.getSpaceOverview(DEFAULT_SPACE).catch(() => null),
      threeLayerApi.getSpaceMembers(DEFAULT_SPACE).catch(() => ({ members: [] })),
      threeLayerApi.getSpaceKnowledge(DEFAULT_SPACE).catch(() => ({ entries: [] })),
    ]).then(([ov, mem, know]) => {
      setOverview(ov?.overview ?? null);
      setMembers(mem?.members ?? []);
      setKnowledge(know?.entries ?? []);
      setLoading(false);
    });
  }, []);

  if (loading) return <div className="p-6 text-[var(--color-text-muted)]">加载中…</div>;

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6 pb-24">
      <h1 className="text-2xl font-bold text-[var(--color-text)]">空间管理</h1>

      {/* Overview */}
      <GlassCard>
        <h2 className="mb-4 text-lg font-semibold">概览</h2>
        {overview ? (
          <div className="grid grid-cols-3 gap-4">
            <Metric label="成员" value={overview.memberCount} />
            <Metric label="知识条目" value={overview.knowledgeCount} />
            <Metric label="技能" value={overview.skillCount} />
            <Metric label="工具" value={overview.toolCount} />
            <Metric label="模板" value={overview.templateCount} />
          </div>
        ) : (
          <p className="text-sm text-[var(--color-text-muted)]">暂无数据</p>
        )}
      </GlassCard>

      {/* Members */}
      <GlassCard>
        <h2 className="mb-4 text-lg font-semibold">成员</h2>
        {members.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">暂无成员</p>
        ) : (
          <div className="space-y-2">
            {members.map((m) => (
              <div key={m.userId} className="flex items-center justify-between rounded-lg bg-white/5 p-3">
                <div>
                  <span className="font-medium text-[var(--color-text)]">{m.displayName}</span>
                  <span className="ml-2 text-xs text-[var(--color-text-muted)]">{m.userId}</span>
                </div>
                <StatusPill status={m.role} />
              </div>
            ))}
          </div>
        )}
      </GlassCard>

      {/* Knowledge */}
      <GlassCard>
        <h2 className="mb-4 text-lg font-semibold">团队知识库</h2>
        {knowledge.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">暂无知识条目</p>
        ) : (
          <div className="space-y-2">
            {knowledge.map((k) => (
              <div key={k.id} className="rounded-lg bg-white/5 p-3">
                <div className="flex items-center justify-between">
                  <span className="font-medium text-[var(--color-text)]">{k.title}</span>
                  <StatusPill status={k.category} />
                </div>
                <p className="mt-1 text-sm text-[var(--color-text-muted)] line-clamp-2">{k.content}</p>
              </div>
            ))}
          </div>
        )}
      </GlassCard>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="text-center">
      <div className="text-2xl font-bold text-[var(--color-accent)]">{value}</div>
      <div className="text-xs text-[var(--color-text-muted)]">{label}</div>
    </div>
  );
}
