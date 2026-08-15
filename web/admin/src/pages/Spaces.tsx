import { useEffect, useState, useCallback } from "react";
import { threeLayerApi, type SpaceOverview, type SpaceMember, type KnowledgeEntry, type SpacePolicy, type SpaceCapability } from "@/lib/api";

const SPACE = "default";

export default function Spaces() {
  const [overview, setOverview] = useState<SpaceOverview | null>(null);
  const [members, setMembers] = useState<SpaceMember[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeEntry[]>([]);
  const [policy, setPolicy] = useState<SpacePolicy | null>(null);
  const [capabilities, setCapabilities] = useState<SpaceCapability | null>(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<"overview" | "knowledge" | "members" | "policy" | "capabilities">("overview");

  const reload = useCallback(() => {
    Promise.all([
      threeLayerApi.getSpaceOverview(SPACE).catch(() => null),
      threeLayerApi.getSpaceMembers(SPACE).catch(() => ({ members: [] as SpaceMember[] })),
      threeLayerApi.getSpaceKnowledge(SPACE).catch(() => ({ entries: [] as KnowledgeEntry[] })),
      threeLayerApi.getSpacePolicy(SPACE).catch(() => null),
      threeLayerApi.getSpaceCapabilities(SPACE).catch(() => null),
    ]).then(([ov, mem, know, pol, cap]) => {
      setOverview(ov?.overview ?? null);
      setMembers(mem?.members ?? []);
      setKnowledge(know?.entries ?? []);
      setPolicy(pol?.policy ?? null);
      setCapabilities(cap?.capabilities ?? null);
      setLoading(false);
    });
  }, []);

  useEffect(() => { reload(); }, [reload]);

  if (loading) return <div className="p-6 text-muted">加载中…</div>;

  const tabs = [
    { key: "overview", label: "概览" },
    { key: "knowledge", label: "知识库" },
    { key: "members", label: "成员" },
    { key: "policy", label: "策略" },
    { key: "capabilities", label: "能力" },
  ] as const;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">空间管理</h1>
        <p className="mt-1 text-sm text-muted">空间 CRUD、成员、策略、知识库</p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-border">
        {tabs.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`border-b-2 px-4 py-2 text-sm transition-colors ${
              tab === t.key
                ? "border-accent text-accent-foreground"
                : "border-transparent text-muted hover:text-foreground"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Overview tab */}
      {tab === "overview" && overview && (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-5">
          {[
            { label: "成员", value: overview.memberCount },
            { label: "知识", value: overview.knowledgeCount },
            { label: "技能", value: overview.skillCount },
            { label: "工具", value: overview.toolCount },
            { label: "模板", value: overview.templateCount },
          ].map((m) => (
            <div key={m.label} className="rounded-lg border border-border bg-surface p-4 text-center">
              <div className="text-2xl font-bold text-accent">{m.value}</div>
              <div className="text-xs text-muted">{m.label}</div>
            </div>
          ))}
        </div>
      )}

      {/* Knowledge tab */}
      {tab === "knowledge" && (
        <div className="space-y-2">
          {knowledge.length === 0 ? (
            <p className="text-sm text-muted">暂无知识条目</p>
          ) : knowledge.map((k) => (
            <div key={k.id} className="rounded-lg border border-border bg-surface p-3">
              <div className="flex justify-between">
                <span className="font-medium">{k.title}</span>
                <span className="rounded bg-surface-hover px-2 py-0.5 text-xs text-muted">{k.category}</span>
              </div>
              <p className="mt-1 text-sm text-muted line-clamp-2">{k.content}</p>
            </div>
          ))}
        </div>
      )}

      {/* Members tab */}
      {tab === "members" && (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
              <tr>
                <th className="px-4 py-2.5 text-left">姓名</th>
                <th className="px-4 py-2.5 text-left">ID</th>
                <th className="px-4 py-2.5 text-left">角色</th>
                <th className="px-4 py-2.5 text-right">最后活跃</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {members.length === 0 ? (
                <tr><td colSpan={4} className="px-4 py-8 text-center text-muted">暂无成员</td></tr>
              ) : members.map((m) => (
                <tr key={m.userId} className="hover:bg-surface-hover">
                  <td className="px-4 py-2.5 font-medium">{m.displayName}</td>
                  <td className="px-4 py-2.5 font-mono text-xs text-muted">{m.userId}</td>
                  <td className="px-4 py-2.5">
                    <span className="rounded bg-surface-hover px-2 py-0.5 text-xs">{m.role}</span>
                  </td>
                  <td className="px-4 py-2.5 text-right text-xs text-muted">
                    {m.lastActiveAt ? new Date(m.lastActiveAt).toLocaleDateString() : "-"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Policy tab */}
      {tab === "policy" && policy && (
        <div className="space-y-4">
          <div>
            <h3 className="mb-2 text-sm font-medium text-muted">审批模式</h3>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              {Object.entries(policy.approvalModes).map(([k, v]) => (
                <div key={k} className="rounded-lg border border-border bg-surface p-3">
                  <div className="text-xs text-muted">{k}</div>
                  <div className="mt-1 font-mono text-sm font-medium">{v}</div>
                </div>
              ))}
            </div>
          </div>
          <div className="flex flex-wrap gap-3">
            <span className="rounded-lg border border-border bg-surface px-3 py-1.5 text-sm">
              沙箱: <span className={policy.sandboxEnforced ? "text-green-600" : "text-muted"}>{policy.sandboxEnforced ? "启用" : "关闭"}</span>
            </span>
            <span className="rounded-lg border border-border bg-surface px-3 py-1.5 text-sm">
              衰减策略: <span className="font-medium">{policy.decayPolicy}</span>
            </span>
            <span className="rounded-lg border border-border bg-surface px-3 py-1.5 text-sm">
              最大并发: <span className="font-medium">{policy.maxConcurrentRuns}</span>
            </span>
          </div>
        </div>
      )}

      {/* Capabilities tab */}
      {tab === "capabilities" && capabilities && (
        <div className="space-y-4">
          <div>
            <h3 className="mb-2 text-sm font-medium text-muted">已安装技能 ({capabilities.installedSkills.length})</h3>
            <div className="flex flex-wrap gap-1.5">
              {capabilities.installedSkills.length === 0 ? (
                <span className="text-sm text-muted">暂无</span>
              ) : capabilities.installedSkills.map((s) => (
                <span key={s} className="rounded-md bg-surface-hover px-2.5 py-1 text-xs">{s}</span>
              ))}
            </div>
          </div>
          <div>
            <h3 className="mb-2 text-sm font-medium text-muted">已启用工具 ({capabilities.enabledTools.length})</h3>
            <div className="flex flex-wrap gap-1.5">
              {capabilities.enabledTools.length === 0 ? (
                <span className="text-sm text-muted">暂无</span>
              ) : capabilities.enabledTools.map((t) => (
                <span key={t} className="rounded-md bg-accent/10 px-2.5 py-1 text-xs text-accent-foreground">{t}</span>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
