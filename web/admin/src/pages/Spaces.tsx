import { useEffect, useState, useCallback } from "react";
import { threeLayerApi, type SpaceOverview, type SpaceMember, type KnowledgeEntry, type SpacePolicy, type SpaceCapability } from "@/lib/api";

const SPACE = "default";

const APPROVAL_MODES = ["AUTO", "PROMPT", "REQUIRE", "DENY"] as const;
const DECAY_POLICIES = ["aggressive", "standard", "longRunning", "archival"] as const;
const MEMBER_ROLES = ["admin", "member", "viewer"] as const;

const inputCls =
  "w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm text-foreground placeholder:text-muted/60 focus:outline-none focus:ring-1 focus:ring-ring";
const btnPrimary =
  "rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground transition-colors hover:opacity-90 disabled:opacity-50";
const btnSecondary =
  "rounded-md border border-border bg-surface px-3 py-1.5 text-sm text-foreground transition-colors hover:bg-surface-hover disabled:opacity-50";
const btnDanger =
  "rounded-md px-2 py-1 text-xs text-destructive transition-colors hover:bg-destructive/10 disabled:opacity-50";

export default function Spaces() {
  const [overview, setOverview] = useState<SpaceOverview | null>(null);
  const [members, setMembers] = useState<SpaceMember[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeEntry[]>([]);
  const [policy, setPolicy] = useState<SpacePolicy | null>(null);
  const [capabilities, setCapabilities] = useState<SpaceCapability | null>(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<"overview" | "knowledge" | "members" | "policy" | "capabilities">("overview");

  // write-op state
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // members form
  const [showMemberForm, setShowMemberForm] = useState(false);
  const [memberForm, setMemberForm] = useState({ userId: "", displayName: "", role: "member" });

  // knowledge form (also used for editing)
  const [showKnowledgeForm, setShowKnowledgeForm] = useState(false);
  const [editingKnowledgeId, setEditingKnowledgeId] = useState<string | null>(null);
  const [knowledgeForm, setKnowledgeForm] = useState({ title: "", category: "", content: "" });

  // policy editing
  const [policyDraft, setPolicyDraft] = useState<SpacePolicy | null>(null);

  // capabilities
  const [skillInput, setSkillInput] = useState("");

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

  /** Run a write op with busy/error/notice handling, then reload. */
  const run = useCallback(async (label: string, fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await fn();
      setNotice(label);
      reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [reload]);

  // ── member actions ──
  const submitMember = () => {
    const userId = memberForm.userId.trim();
    if (!userId) { setError("请输入用户 ID"); return; }
    void run("成员已添加", () =>
      threeLayerApi.addSpaceMember(SPACE, userId, memberForm.displayName.trim() || undefined, memberForm.role),
    );
    setShowMemberForm(false);
    setMemberForm({ userId: "", displayName: "", role: "member" });
  };

  const removeMember = (userId: string) => {
    if (!confirm(`确定移除成员 ${userId} 吗？`)) return;
    void run("成员已移除", () => threeLayerApi.removeSpaceMember(SPACE, userId));
  };

  // ── knowledge actions ──
  const openKnowledgeCreate = () => {
    setEditingKnowledgeId(null);
    setKnowledgeForm({ title: "", category: "", content: "" });
    setShowKnowledgeForm(true);
  };

  const openKnowledgeEdit = (k: KnowledgeEntry) => {
    setEditingKnowledgeId(k.id);
    setKnowledgeForm({ title: k.title, category: k.category, content: k.content });
    setShowKnowledgeForm(true);
  };

  const submitKnowledge = () => {
    const title = knowledgeForm.title.trim();
    if (!title) { setError("请输入标题"); return; }
    const payload = {
      title,
      category: knowledgeForm.category.trim() || "general",
      content: knowledgeForm.content,
    };
    if (editingKnowledgeId) {
      void run("知识已更新", () => threeLayerApi.updateSpaceKnowledge(SPACE, editingKnowledgeId, payload));
    } else {
      void run("知识已添加", () => threeLayerApi.addSpaceKnowledge(SPACE, payload));
    }
    setShowKnowledgeForm(false);
    setEditingKnowledgeId(null);
  };

  const deleteKnowledge = (entryId: string) => {
    if (!confirm("确定删除该知识条目吗？")) return;
    void run("知识已删除", () => threeLayerApi.deleteSpaceKnowledge(SPACE, entryId));
  };

  // ── policy actions ──
  const startPolicyEdit = () => {
    if (!policy) return;
    setPolicyDraft({ ...policy, approvalModes: { ...policy.approvalModes } });
    setError(null);
    setNotice(null);
  };

  const savePolicy = () => {
    if (!policyDraft) return;
    void run("策略已保存", async () => {
      await threeLayerApi.updateSpacePolicy(SPACE, policyDraft);
      setPolicyDraft(null);
    });
  };

  // ── capability actions ──
  const installSkill = () => {
    const skillId = skillInput.trim();
    if (!skillId) { setError("请输入技能 ID"); return; }
    void run(`技能 ${skillId} 已安装`, () => threeLayerApi.installSpaceSkill(SPACE, skillId));
    setSkillInput("");
  };

  const uninstallSkill = (skillId: string) => {
    void run(`技能 ${skillId} 已卸载`, () => threeLayerApi.uninstallSpaceSkill(SPACE, skillId));
  };

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

      {/* Status banners for write ops */}
      {error && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/10 px-4 py-2.5 text-sm text-destructive">
          操作失败：{error}
        </div>
      )}
      {notice && !error && (
        <div className="rounded-lg border border-success/40 bg-success/10 px-4 py-2.5 text-sm text-success">
          {notice}
        </div>
      )}

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
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium text-muted">知识条目 ({knowledge.length})</h3>
            {showKnowledgeForm ? (
              <button className={btnSecondary} onClick={() => { setShowKnowledgeForm(false); setEditingKnowledgeId(null); }}>
                取消
              </button>
            ) : (
              <button className={btnPrimary} onClick={openKnowledgeCreate}>添加知识</button>
            )}
          </div>

          {showKnowledgeForm && (
            <div className="space-y-3 rounded-lg border border-border bg-card p-4">
              <div className="text-sm font-medium">{editingKnowledgeId ? "编辑知识" : "新建知识"}</div>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="space-y-1">
                  <span className="text-xs text-muted">标题</span>
                  <input
                    className={inputCls}
                    value={knowledgeForm.title}
                    onChange={(e) => setKnowledgeForm((f) => ({ ...f, title: e.target.value }))}
                    placeholder="知识标题"
                  />
                </label>
                <label className="space-y-1">
                  <span className="text-xs text-muted">分类</span>
                  <input
                    className={inputCls}
                    value={knowledgeForm.category}
                    onChange={(e) => setKnowledgeForm((f) => ({ ...f, category: e.target.value }))}
                    placeholder="general"
                  />
                </label>
              </div>
              <label className="block space-y-1">
                <span className="text-xs text-muted">内容</span>
                <textarea
                  className={`${inputCls} min-h-24 resize-y`}
                  value={knowledgeForm.content}
                  onChange={(e) => setKnowledgeForm((f) => ({ ...f, content: e.target.value }))}
                  placeholder="知识内容…"
                />
              </label>
              <div className="flex justify-end gap-2">
                <button className={btnSecondary} onClick={() => { setShowKnowledgeForm(false); setEditingKnowledgeId(null); }}>
                  取消
                </button>
                <button className={btnPrimary} disabled={busy} onClick={submitKnowledge}>
                  {editingKnowledgeId ? "保存" : "添加"}
                </button>
              </div>
            </div>
          )}

          {knowledge.length === 0 && !showKnowledgeForm ? (
            <p className="text-sm text-muted">暂无知识条目</p>
          ) : knowledge.map((k) => (
            <div key={k.id} className="rounded-lg border border-border bg-surface p-3">
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{k.title}</span>
                  <span className="rounded bg-surface-hover px-2 py-0.5 text-xs text-muted">{k.category}</span>
                </div>
                <div className="flex shrink-0 gap-1">
                  <button className={btnDanger} onClick={() => openKnowledgeEdit(k)}>编辑</button>
                  <button className={btnDanger} disabled={busy} onClick={() => deleteKnowledge(k.id)}>删除</button>
                </div>
              </div>
              <p className="mt-1 text-sm text-muted line-clamp-2">{k.content}</p>
            </div>
          ))}
        </div>
      )}

      {/* Members tab */}
      {tab === "members" && (
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium text-muted">成员 ({members.length})</h3>
            <button className={showMemberForm ? btnSecondary : btnPrimary} onClick={() => setShowMemberForm(!showMemberForm)}>
              {showMemberForm ? "取消" : "添加成员"}
            </button>
          </div>

          {showMemberForm && (
            <div className="space-y-3 rounded-lg border border-border bg-card p-4">
              <div className="text-sm font-medium">新成员</div>
              <div className="grid gap-3 sm:grid-cols-3">
                <label className="space-y-1">
                  <span className="text-xs text-muted">用户 ID</span>
                  <input
                    className={inputCls}
                    value={memberForm.userId}
                    onChange={(e) => setMemberForm((f) => ({ ...f, userId: e.target.value }))}
                    placeholder="u_xxx"
                  />
                </label>
                <label className="space-y-1">
                  <span className="text-xs text-muted">显示名称</span>
                  <input
                    className={inputCls}
                    value={memberForm.displayName}
                    onChange={(e) => setMemberForm((f) => ({ ...f, displayName: e.target.value }))}
                    placeholder="可选"
                  />
                </label>
                <label className="space-y-1">
                  <span className="text-xs text-muted">角色</span>
                  <select
                    className={inputCls}
                    value={memberForm.role}
                    onChange={(e) => setMemberForm((f) => ({ ...f, role: e.target.value }))}
                  >
                    {MEMBER_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                  </select>
                </label>
              </div>
              <div className="flex justify-end">
                <button className={btnPrimary} disabled={busy} onClick={submitMember}>添加</button>
              </div>
            </div>
          )}

          <div className="overflow-hidden rounded-lg border border-border">
            <table className="w-full text-sm">
              <thead className="bg-surface text-xs uppercase tracking-wider text-muted">
                <tr>
                  <th className="px-4 py-2.5 text-left">姓名</th>
                  <th className="px-4 py-2.5 text-left">ID</th>
                  <th className="px-4 py-2.5 text-left">角色</th>
                  <th className="px-4 py-2.5 text-right">最后活跃</th>
                  <th className="px-4 py-2.5 text-right">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {members.length === 0 ? (
                  <tr><td colSpan={5} className="px-4 py-8 text-center text-muted">暂无成员</td></tr>
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
                    <td className="px-4 py-2.5 text-right">
                      <button className={btnDanger} disabled={busy} onClick={() => removeMember(m.userId)}>移除</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Policy tab */}
      {tab === "policy" && policy && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium text-muted">审批模式</h3>
            {policyDraft ? (
              <div className="flex gap-2">
                <button className={btnSecondary} onClick={() => setPolicyDraft(null)}>取消</button>
                <button className={btnPrimary} disabled={busy} onClick={savePolicy}>保存</button>
              </div>
            ) : (
              <button className={btnPrimary} onClick={startPolicyEdit}>编辑策略</button>
            )}
          </div>

          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {Object.entries((policyDraft ?? policy).approvalModes).map(([k, v]) => (
              <div key={k} className="rounded-lg border border-border bg-surface p-3">
                <div className="text-xs text-muted">{k}</div>
                {policyDraft ? (
                  <select
                    className={`${inputCls} mt-1`}
                    value={v}
                    onChange={(e) =>
                      setPolicyDraft((d) => d ? { ...d, approvalModes: { ...d.approvalModes, [k]: e.target.value } } : d)
                    }
                  >
                    {APPROVAL_MODES.map((m) => <option key={m} value={m}>{m}</option>)}
                  </select>
                ) : (
                  <div className="mt-1 font-mono text-sm font-medium">{v}</div>
                )}
              </div>
            ))}
          </div>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-lg border border-border bg-surface p-3">
              <div className="text-xs text-muted">沙箱</div>
              {policyDraft ? (
                <label className="mt-2 flex cursor-pointer items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={policyDraft.sandboxEnforced}
                    onChange={(e) => setPolicyDraft((d) => d ? { ...d, sandboxEnforced: e.target.checked } : d)}
                    className="h-4 w-4 accent-primary"
                  />
                  {policyDraft.sandboxEnforced ? "启用" : "关闭"}
                </label>
              ) : (
                <div className={`mt-1 text-sm font-medium ${policy.sandboxEnforced ? "text-success" : "text-muted"}`}>
                  {policy.sandboxEnforced ? "启用" : "关闭"}
                </div>
              )}
            </div>
            <div className="rounded-lg border border-border bg-surface p-3">
              <div className="text-xs text-muted">最大并发</div>
              {policyDraft ? (
                <input
                  type="number"
                  min={1}
                  className={`${inputCls} mt-1`}
                  value={policyDraft.maxConcurrentRuns}
                  onChange={(e) =>
                    setPolicyDraft((d) => d ? { ...d, maxConcurrentRuns: Math.max(1, Number(e.target.value) || 1) } : d)
                  }
                />
              ) : (
                <div className="mt-1 text-sm font-medium tabular-nums">{policy.maxConcurrentRuns}</div>
              )}
            </div>
            <div className="rounded-lg border border-border bg-surface p-3">
              <div className="text-xs text-muted">衰减策略</div>
              {policyDraft ? (
                <select
                  className={`${inputCls} mt-1`}
                  value={policyDraft.decayPolicy}
                  onChange={(e) => setPolicyDraft((d) => d ? { ...d, decayPolicy: e.target.value } : d)}
                >
                  {DECAY_POLICIES.map((p) => <option key={p} value={p}>{p}</option>)}
                </select>
              ) : (
                <div className="mt-1 text-sm font-medium">{policy.decayPolicy}</div>
              )}
            </div>
            <div className="rounded-lg border border-border bg-surface p-3">
              <div className="text-xs text-muted">用户覆盖</div>
              {policyDraft ? (
                <label className="mt-2 flex cursor-pointer items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={policyDraft.allowUserOverride}
                    onChange={(e) => setPolicyDraft((d) => d ? { ...d, allowUserOverride: e.target.checked } : d)}
                    className="h-4 w-4 accent-primary"
                  />
                  {policyDraft.allowUserOverride ? "允许" : "禁止"}
                </label>
              ) : (
                <div className={`mt-1 text-sm font-medium ${policy.allowUserOverride ? "text-success" : "text-muted"}`}>
                  {policy.allowUserOverride ? "允许" : "禁止"}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Capabilities tab */}
      {tab === "capabilities" && capabilities && (
        <div className="space-y-4">
          <div>
            <h3 className="mb-2 text-sm font-medium text-muted">已安装技能 ({capabilities.installedSkills.length})</h3>
            <div className="mb-3 flex gap-2">
              <input
                className={inputCls}
                value={skillInput}
                onChange={(e) => setSkillInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") installSkill(); }}
                placeholder="输入技能 ID，如 web-search"
              />
              <button className={`${btnPrimary} shrink-0`} disabled={busy} onClick={installSkill}>安装技能</button>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {capabilities.installedSkills.length === 0 ? (
                <span className="text-sm text-muted">暂无</span>
              ) : capabilities.installedSkills.map((s) => (
                <span key={s} className="group inline-flex items-center gap-1 rounded-md bg-surface-hover px-2.5 py-1 text-xs">
                  {s}
                  <button
                    aria-label={`卸载 ${s}`}
                    title={`卸载 ${s}`}
                    disabled={busy}
                    onClick={() => uninstallSkill(s)}
                    className="ml-0.5 inline-flex h-3.5 w-3.5 items-center justify-center rounded-full text-muted transition-colors hover:bg-destructive/20 hover:text-destructive"
                  >
                    ×
                  </button>
                </span>
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
