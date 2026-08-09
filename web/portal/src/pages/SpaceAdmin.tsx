import { useEffect, useState, useCallback } from "react";
import { GlassCard } from "@/components/GlassCard";
import { StatusPill } from "@/components/StatusPill";
import { threeLayerApi, type SpaceOverview, type SpaceMember, type KnowledgeEntry, type SpacePolicy, type SpaceCapability } from "@/api/three-layer";

const SPACE = "default";

export default function SpaceAdmin() {
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

  if (loading) return <div className="p-6 text-[var(--color-text-muted)]">加载中…</div>;

  return (
    <div className="mx-auto max-w-3xl space-y-4 p-6 pb-24">
      <h1 className="text-2xl font-bold text-[var(--color-text)]">空间管理</h1>

      {/* Tab bar */}
      <div className="flex gap-2 overflow-x-auto">
        {(["overview", "knowledge", "members", "policy", "capabilities"] as const).map((t) => (
          <button key={t} onClick={() => setTab(t)}
            className={`rounded-lg px-3 py-1.5 text-sm transition-colors ${
              tab === t
                ? "bg-[oklch(0.78_0.16_70_/_0.2)] text-[oklch(0.88_0.12_70)]"
                : "bg-white/5 text-[var(--color-text-muted)] hover:text-[var(--color-text)]"
            }`}>
            {tabLabel(t)}
          </button>
        ))}
      </div>

      {tab === "overview" && <OverviewTab overview={overview} />}
      {tab === "knowledge" && <KnowledgeTab entries={knowledge} onReload={reload} />}
      {tab === "members" && <MembersTab members={members} onReload={reload} />}
      {tab === "policy" && <PolicyTab policy={policy} onReload={reload} />}
      {tab === "capabilities" && <CapabilitiesTab capabilities={capabilities} onReload={reload} />}
    </div>
  );
}

function tabLabel(t: string) {
  return { overview: "概览", knowledge: "知识库", members: "成员", policy: "策略", capabilities: "能力" }[t] ?? t;
}

// ── Overview ──

function OverviewTab({ overview }: { overview: SpaceOverview | null }) {
  if (!overview) return <p className="text-sm text-[var(--color-text-muted)]">暂无数据</p>;
  return (
    <GlassCard>
      <div className="grid grid-cols-3 gap-4">
        <Metric label="成员" value={overview.memberCount} />
        <Metric label="知识条目" value={overview.knowledgeCount} />
        <Metric label="技能" value={overview.skillCount} />
        <Metric label="工具" value={overview.toolCount} />
        <Metric label="模板" value={overview.templateCount} />
      </div>
    </GlassCard>
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

// ── Knowledge CRUD ──

function KnowledgeTab({ entries, onReload }: { entries: KnowledgeEntry[]; onReload: () => void }) {
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<KnowledgeEntry | null>(null);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [category, setCategory] = useState("domain");

  const startAdd = () => {
    setEditing(null); setTitle(""); setContent(""); setCategory("domain"); setShowForm(true);
  };
  const startEdit = (e: KnowledgeEntry) => {
    setEditing(e); setTitle(e.title); setContent(e.content); setCategory(e.category); setShowForm(true);
  };
  const save = () => {
    if (!title.trim() || !content.trim()) return;
    const payload = { title, content, category, tags: [] as string[] };
    if (editing) {
      threeLayerApi.updateSpaceKnowledge(SPACE, editing.id, payload).then(() => { setShowForm(false); onReload(); });
    } else {
      threeLayerApi.addSpaceKnowledge(SPACE, payload).then(() => { setShowForm(false); onReload(); });
    }
  };
  const remove = (id: string) => {
    if (confirm("确定删除？")) threeLayerApi.deleteSpaceKnowledge(SPACE, id).then(() => onReload());
  };

  return (
    <GlassCard>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">团队知识库</h2>
        <button onClick={startAdd} className="rounded-lg bg-[oklch(0.78_0.16_70_/_0.2)] px-3 py-1.5 text-sm text-[oklch(0.88_0.12_70)]">+ 新增</button>
      </div>

      {showForm && (
        <div className="mb-4 space-y-3 rounded-lg bg-white/5 p-4">
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="标题"
            className="w-full rounded-lg bg-white/10 px-3 py-2 text-sm text-[var(--color-text)]" />
          <textarea value={content} onChange={(e) => setContent(e.target.value)} placeholder="内容" rows={4}
            className="w-full rounded-lg bg-white/10 px-3 py-2 text-sm text-[var(--color-text)]" />
          <select value={category} onChange={(e) => setCategory(e.target.value)}
            className="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-[var(--color-text)]">
            <option value="sop">SOP</option>
            <option value="faq">FAQ</option>
            <option value="domain">领域知识</option>
            <option value="experience">经验</option>
          </select>
          <div className="flex gap-2">
            <button onClick={save} className="rounded-lg bg-[oklch(0.78_0.16_70)] px-4 py-1.5 text-sm text-white">保存</button>
            <button onClick={() => setShowForm(false)} className="rounded-lg bg-white/10 px-4 py-1.5 text-sm">取消</button>
          </div>
        </div>
      )}

      {entries.length === 0 ? (
        <p className="text-sm text-[var(--color-text-muted)]">暂无知识条目</p>
      ) : (
        <div className="space-y-2">
          {entries.map((k) => (
            <div key={k.id} className="rounded-lg bg-white/5 p-3">
              <div className="flex items-center justify-between">
                <span className="font-medium text-[var(--color-text)]">{k.title}</span>
                <div className="flex items-center gap-2">
                  <StatusPill status={k.category} />
                  <button onClick={() => startEdit(k)} className="text-xs text-[var(--color-accent)]">编辑</button>
                  <button onClick={() => remove(k.id)} className="text-xs text-red-400">删除</button>
                </div>
              </div>
              <p className="mt-1 text-sm text-[var(--color-text-muted)] line-clamp-2">{k.content}</p>
            </div>
          ))}
        </div>
      )}
    </GlassCard>
  );
}

// ── Members ──

function MembersTab({ members, onReload }: { members: SpaceMember[]; onReload: () => void }) {
  const [userId, setUserId] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState("member");

  const addMember = () => {
    if (!userId.trim()) return;
    threeLayerApi.addSpaceMember(SPACE, userId, displayName || userId, role).then(() => {
      setUserId(""); setDisplayName(""); setRole("member"); onReload();
    });
  };
  const removeMember = (uid: string) => {
    threeLayerApi.removeSpaceMember(SPACE, uid).then(() => onReload());
  };

  return (
    <GlassCard>
      <h2 className="mb-4 text-lg font-semibold">成员</h2>
      <div className="mb-4 flex gap-2">
        <input value={userId} onChange={(e) => setUserId(e.target.value)} placeholder="用户ID"
          className="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-[var(--color-text)]" />
        <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="显示名"
          className="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-[var(--color-text)]" />
        <select value={role} onChange={(e) => setRole(e.target.value)}
          className="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-[var(--color-text)]">
          <option value="admin">管理员</option>
          <option value="member">成员</option>
          <option value="viewer">观察者</option>
        </select>
        <button onClick={addMember} className="rounded-lg bg-[oklch(0.78_0.16_70)] px-3 py-1.5 text-sm text-white">添加</button>
      </div>
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
              <div className="flex items-center gap-2">
                <StatusPill status={m.role} />
                <button onClick={() => removeMember(m.userId)} className="text-xs text-red-400">移除</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </GlassCard>
  );
}

// ── Policy Editor ──

function PolicyTab({ policy, onReload }: { policy: SpacePolicy | null; onReload: () => void }) {
  const [draft, setDraft] = useState<SpacePolicy | null>(policy);
  const [saving, setSaving] = useState(false);

  useEffect(() => { setDraft(policy); }, [policy]);
  if (!draft) return <p className="text-sm text-[var(--color-text-muted)]">暂无策略</p>;

  const update = (patch: Partial<SpacePolicy>) => setDraft({ ...draft, ...patch });
  const updateMode = (key: string, value: string) => {
    const modes = { ...draft.approvalModes, [key]: value };
    setDraft({ ...draft, approvalModes: modes });
  };
  const save = () => {
    setSaving(true);
    threeLayerApi.updateSpacePolicy(SPACE, draft).then(() => { setSaving(false); onReload(); });
  };

  const opTypes = ["terminal_command", "file_write", "file_delete", "code_execution", "browser_action", "subagent_spawn"];

  return (
    <GlassCard>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">空间策略</h2>
        <button onClick={save} disabled={saving}
          className="rounded-lg bg-[oklch(0.78_0.16_70)] px-4 py-1.5 text-sm text-white disabled:opacity-50">
          {saving ? "保存中…" : "保存"}
        </button>
      </div>

      <div className="space-y-4">
        <div>
          <h3 className="mb-2 text-sm font-medium text-[var(--color-text-muted)]">审批模式</h3>
          <div className="space-y-2">
            {opTypes.map((op) => (
              <div key={op} className="flex items-center justify-between">
                <span className="text-sm text-[var(--color-text)]">{op}</span>
                <select value={draft.approvalModes[op] ?? "prompt"} onChange={(e) => updateMode(op, e.target.value)}
                  className="rounded-lg bg-white/10 px-2 py-1 text-xs text-[var(--color-text)]">
                  <option value="auto">自动</option>
                  <option value="prompt">询问</option>
                  <option value="require">必须审批</option>
                  <option value="deny">禁止</option>
                </select>
              </div>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-medium text-[var(--color-text-muted)]">其他</h3>
          <div className="space-y-2">
            <label className="flex items-center justify-between">
              <span className="text-sm">沙箱强制</span>
              <input type="checkbox" checked={draft.sandboxEnforced} onChange={(e) => update({ sandboxEnforced: e.target.checked })} />
            </label>
            <label className="flex items-center justify-between">
              <span className="text-sm">允许用户覆盖</span>
              <input type="checkbox" checked={draft.allowUserOverride} onChange={(e) => update({ allowUserOverride: e.target.checked })} />
            </label>
            <div className="flex items-center justify-between">
              <span className="text-sm">最大并发</span>
              <input type="number" value={draft.maxConcurrentRuns} onChange={(e) => update({ maxConcurrentRuns: +e.target.value })}
                className="w-20 rounded-lg bg-white/10 px-2 py-1 text-sm text-[var(--color-text)]" />
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm">衰减策略</span>
              <select value={draft.decayPolicy} onChange={(e) => update({ decayPolicy: e.target.value })}
                className="rounded-lg bg-white/10 px-2 py-1 text-xs text-[var(--color-text)]">
                <option value="aggressive">激进</option>
                <option value="standard">标准</option>
                <option value="longRunning">长任务</option>
                <option value="archival">归档</option>
              </select>
            </div>
          </div>
        </div>
      </div>
    </GlassCard>
  );
}

// ── Capabilities ──

function CapabilitiesTab({ capabilities, onReload }: { capabilities: SpaceCapability | null; onReload: () => void }) {
  const [newSkill, setNewSkill] = useState("");
  if (!capabilities) return <p className="text-sm text-[var(--color-text-muted)]">暂无数据</p>;

  const install = () => {
    if (!newSkill.trim()) return;
    threeLayerApi.installSpaceSkill(SPACE, newSkill).then(() => { setNewSkill(""); onReload(); });
  };
  const uninstall = (skill: string) => {
    threeLayerApi.uninstallSpaceSkill(SPACE, skill).then(() => onReload());
  };

  return (
    <GlassCard>
      <h2 className="mb-4 text-lg font-semibold">能力注册表</h2>

      <div className="mb-4">
        <h3 className="mb-2 text-sm font-medium text-[var(--color-text-muted)]">已安装技能</h3>
        <div className="mb-2 flex gap-2">
          <input value={newSkill} onChange={(e) => setNewSkill(e.target.value)} placeholder="技能ID"
            className="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-[var(--color-text)]" />
          <button onClick={install} className="rounded-lg bg-[oklch(0.78_0.16_70)] px-3 py-1.5 text-sm text-white">安装</button>
        </div>
        {capabilities.installedSkills.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">无</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {capabilities.installedSkills.map((s) => (
              <span key={s} className="inline-flex items-center gap-1 rounded-full bg-white/10 px-2 py-1 text-xs">
                {s}
                <button onClick={() => uninstall(s)} className="text-red-400">×</button>
              </span>
            ))}
          </div>
        )}
      </div>

      <div>
        <h3 className="mb-2 text-sm font-medium text-[var(--color-text-muted)]">已启用工具</h3>
        {capabilities.enabledTools.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">无</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {capabilities.enabledTools.map((t) => (
              <span key={t} className="inline-flex items-center rounded-full bg-[oklch(0.78_0.16_70_/_0.18)] px-2 py-1 text-xs">{t}</span>
            ))}
          </div>
        )}
      </div>
    </GlassCard>
  );
}
