import { useEffect, useState } from "react";
import { GlassCard } from "@/components/GlassCard";
import { StatusPill } from "@/components/StatusPill";
import { threeLayerApi, type UserProfile, type UserPreferences, type UserCapability } from "@/api/three-layer";
import { portalApi } from "@/api/portal";
import { Home, Brain, Wrench, Settings, MessageSquare, Sparkles, Building2, Layers, ExternalLink } from "lucide-react";

const DEFAULT_USER = "default-user";

type Section = "profile" | "memory" | "capabilities" | "preferences" | "sessions" | "improvement";

export default function UserAdmin() {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [section, setSection] = useState<Section>("profile");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    threeLayerApi
      .getUserProfile(DEFAULT_USER)
      .then((res) => setProfile(res.profile))
      .catch(() => null)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-6 text-[var(--color-text-muted)]">加载中…</div>;
  if (!profile) return <div className="p-6 text-[var(--color-text-muted)]">无法加载用户画像</div>;

  return (
    <div className="mx-auto max-w-3xl space-y-4 p-6 pb-24">
      <h1 className="text-2xl font-bold text-[var(--color-text)]">我的</h1>

      {/* Section tabs */}
      <div className="flex gap-2 overflow-x-auto">
        {([
          { key: "profile", label: "画像", icon: Home },
          { key: "memory", label: "记忆", icon: Brain },
          { key: "capabilities", label: "能力", icon: Wrench },
          { key: "preferences", label: "偏好", icon: Settings },
          { key: "sessions", label: "会话", icon: MessageSquare },
          { key: "improvement", label: "自进化", icon: Sparkles },
        ] as const).map(({ key, label, icon: Icon }) => (
          <button key={key} onClick={() => setSection(key)}
            className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm transition-colors whitespace-nowrap ${
              section === key
                ? "bg-[oklch(0.78_0.16_70_/_0.2)] text-[oklch(0.88_0.12_70)]"
                : "bg-white/5 text-[var(--color-text-muted)] hover:text-[var(--color-text)]"
            }`}>
            <Icon className="h-3.5 w-3.5" />
            {label}
          </button>
        ))}
      </div>

      {section === "profile" && <ProfileSection profile={profile} />}
      {section === "memory" && <MemorySection />}
      {section === "capabilities" && <CapabilitiesSection capabilities={profile.capabilities} />}
      {section === "preferences" && <PreferencesSection profile={profile} onUpdated={setProfile} />}
      {section === "sessions" && <SessionsSection />}
      {section === "improvement" && <ImprovementSection />}

      {/* Admin links -> Ops */}
      <div className="flex gap-2 pt-2">
        <a href="/ops/index.html/spaces" className="flex flex-1 items-center gap-2 rounded-lg bg-white/5 p-3 text-sm transition-colors hover:bg-white/10">
          <Building2 className="h-4 w-4 text-[var(--color-accent)]" />
          空间管理
          <ExternalLink className="ml-auto h-3 w-3 opacity-50" />
        </a>
        <a href="/ops/index.html/org" className="flex flex-1 items-center gap-2 rounded-lg bg-white/5 p-3 text-sm transition-colors hover:bg-white/10">
          <Layers className="h-4 w-4 text-[var(--color-accent)]" />
          组织管理
          <ExternalLink className="ml-auto h-3 w-3 opacity-50" />
        </a>
      </div>
    </div>
  );
}

// ── Profile ──

function ProfileSection({ profile }: { profile: UserProfile }) {
  return (
    <GlassCard>
      <div className="space-y-3">
        <Row label="用户ID" value={profile.userId} />
        <Row label="显示名" value={profile.displayName} />
        <Row label="邮箱" value={profile.email ?? "未设置"} />
        <div>
          <span className="text-xs text-[var(--color-text-muted)]">渠道绑定</span>
          <div className="mt-1 flex flex-wrap gap-2">
            {Object.entries(profile.channelBindings).map(([ch, id]) => (
              <span key={ch} className="inline-flex items-center rounded-full bg-[oklch(0.78_0.16_70_/_0.18)] px-2 py-0.5 text-[11px]">{ch}: {id.substring(0, 12)}…</span>
            ))}
            {Object.keys(profile.channelBindings).length === 0 && (
              <span className="text-sm text-[var(--color-text-muted)]">未绑定</span>
            )}
          </div>
        </div>
        <div>
          <span className="text-xs text-[var(--color-text-muted)]">空间归属</span>
          <div className="mt-1 space-y-1">
            {profile.spaces.map((s) => (
              <div key={s.spaceId} className="flex items-center justify-between rounded-lg bg-white/5 p-2">
                <span className="text-sm font-medium text-[var(--color-text)]">{s.spaceName}</span>
                <StatusPill status={s.role} />
              </div>
            ))}
            {profile.spaces.length === 0 && (
              <span className="text-sm text-[var(--color-text-muted)]">未加入空间</span>
            )}
          </div>
        </div>
      </div>
    </GlassCard>
  );
}

// ── Memory ──

function MemorySection() {
  const [memories, setMemories] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    threeLayerApi.getImprovementAdaptations(DEFAULT_USER)
      .then((res) => {
        setMemories((res as { memories?: unknown[] }).memories ?? []);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  return (
    <GlassCard>
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-muted)]">个人记忆</h2>
      {loading ? (
        <p className="text-sm text-[var(--color-text-muted)]">加载中…</p>
      ) : memories.length === 0 ? (
        <p className="text-sm text-[var(--color-text-muted)]">暂无记忆</p>
      ) : (
        <div className="space-y-2">
          {memories.map((m, i) => {
            const entry = m as { content?: string; type?: string; category?: string; createdAt?: number };
            return (
              <div key={i} className="rounded-lg bg-white/5 p-2">
                <div className="flex items-center justify-between">
                  {entry.type && <StatusPill status={entry.type} />}
                  {entry.category && <span className="text-xs text-[var(--color-text-muted)]">{entry.category}</span>}
                </div>
                <p className="mt-1 text-sm text-[var(--color-text)]">{entry.content}</p>
              </div>
            );
          })}
        </div>
      )}
    </GlassCard>
  );
}

// ── Capabilities ──

function CapabilitiesSection({ capabilities }: { capabilities: UserCapability }) {
  return (
    <GlassCard>
      <div className="space-y-3">
        <div>
          <span className="text-xs text-[var(--color-text-muted)]">个人技能</span>
          <div className="mt-1 flex flex-wrap gap-2">
            {capabilities.personalSkills.length === 0 ? (
              <span className="text-sm text-[var(--color-text-muted)]">无</span>
            ) : (
              capabilities.personalSkills.map((s) => (
                <span key={s} className="rounded-full bg-white/10 px-2 py-0.5 text-[11px]">{s}</span>
              ))
            )}
          </div>
        </div>
        <div>
          <span className="text-xs text-[var(--color-text-muted)]">常用工具</span>
          <div className="mt-1 flex flex-wrap gap-2">
            {capabilities.frequentTools.length === 0 ? (
              <span className="text-sm text-[var(--color-text-muted)]">无</span>
            ) : (
              capabilities.frequentTools.map((t) => (
                <span key={t} className="rounded-full bg-[oklch(0.78_0.16_70_/_0.18)] px-2 py-0.5 text-[11px]">{t}</span>
              ))
            )}
          </div>
        </div>
        <div>
          <span className="text-xs text-[var(--color-text-muted)]">快捷指令</span>
          <div className="mt-1 space-y-1">
            {Object.keys(capabilities.shortcuts).length === 0 ? (
              <span className="text-sm text-[var(--color-text-muted)]">无</span>
            ) : (
              Object.entries(capabilities.shortcuts).map(([alias, cmd]) => (
                <div key={alias} className="flex justify-between rounded-lg bg-white/5 p-2 text-sm">
                  <span className="font-medium text-[var(--color-accent)]">{alias}</span>
                  <span className="text-[var(--color-text-muted)]">{cmd}</span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </GlassCard>
  );
}

// ── Preferences ──

function PreferencesSection({ profile, onUpdated }: { profile: UserProfile; onUpdated: (p: UserProfile) => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<UserPreferences>(profile.preferences);

  const save = () => {
    threeLayerApi.updateUserPreferences(profile.userId, draft).then((res) => {
      onUpdated({ ...profile, preferences: res.preferences });
      setEditing(false);
    });
  };

  return (
    <GlassCard>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-[var(--color-text-muted)]">偏好设置</h2>
        {editing ? (
          <button onClick={save} className="text-sm text-[var(--color-accent)]">保存</button>
        ) : (
          <button onClick={() => { setDraft(profile.preferences); setEditing(true); }} className="text-sm text-[var(--color-accent)]">编辑</button>
        )}
      </div>
      {editing ? (
        <div className="space-y-3">
          <SelectRow label="语言" value={draft.language} onChange={(v) => setDraft({ ...draft, language: v })}
            options={[{ value: "zh-CN", label: "中文" }, { value: "en", label: "English" }]} />
          <SelectRow label="回复风格" value={draft.responseStyle} onChange={(v) => setDraft({ ...draft, responseStyle: v })}
            options={[{ value: "concise", label: "简洁" }, { value: "detailed", label: "详细" }, { value: "structured", label: "结构化" }]} />
          <SelectRow label="语调" value={draft.tone} onChange={(v) => setDraft({ ...draft, tone: v })}
            options={[{ value: "casual", label: "随和" }, { value: "formal", label: "正式" }, { value: "technical", label: "技术" }]} />
          <label className="flex items-center justify-between">
            <span className="text-sm">自动批准安全操作</span>
            <input type="checkbox" checked={draft.autoApproveSafe} onChange={(e) => setDraft({ ...draft, autoApproveSafe: e.target.checked })} />
          </label>
        </div>
      ) : (
        <div className="space-y-2">
          <Row label="语言" value={profile.preferences.language} />
          <Row label="回复风格" value={profile.preferences.responseStyle} />
          <Row label="语调" value={profile.preferences.tone} />
          <Row label="自动批准" value={profile.preferences.autoApproveSafe ? "是" : "否"} />
        </div>
      )}
    </GlassCard>
  );
}

// ── Sessions ──

function SessionsSection() {
  const [sessions, setSessions] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    portalApi.getSessionAssets("default")
      .then((res) => {
        const r = res as { items?: unknown[] };
        setSessions(r.items ?? []);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  return (
    <GlassCard>
      <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-muted)]">会话资产</h2>
      {loading ? (
        <p className="text-sm text-[var(--color-text-muted)]">加载中…</p>
      ) : sessions.length === 0 ? (
        <p className="text-sm text-[var(--color-text-muted)]">暂无会话</p>
      ) : (
        <div className="space-y-2">
          {sessions.map((s, i) => {
            const item = s as { title?: string; summary?: string; status?: string; bookmarked?: boolean };
            return (
              <div key={i} className="rounded-lg bg-white/5 p-2">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium text-[var(--color-text)]">{item.title ?? "未命名"}</span>
                  {item.status && <StatusPill status={item.status} />}
                </div>
                {item.summary && <p className="mt-1 text-xs text-[var(--color-text-muted)] line-clamp-2">{item.summary}</p>}
              </div>
            );
          })}
        </div>
      )}
    </GlassCard>
  );
}

// ── Improvement ──

function ImprovementSection() {
  const [signals, setSignals] = useState<unknown[]>([]);
  const [proposals, setProposals] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      threeLayerApi.getImprovementSignals({ scope: "user", userId: DEFAULT_USER }).catch(() => ({ signals: [] })),
      threeLayerApi.getImprovementProposals("user").catch(() => ({ proposals: [] })),
    ]).then(([sig, prop]) => {
      setSignals((sig as { signals?: unknown[] }).signals ?? []);
      setProposals((prop as { proposals?: unknown[] }).proposals ?? []);
      setLoading(false);
    });
  }, []);

  return (
    <div className="space-y-4">
      <GlassCard>
        <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-muted)]">改进信号</h2>
        {loading ? (
          <p className="text-sm text-[var(--color-text-muted)]">加载中…</p>
        ) : signals.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">暂无信号</p>
        ) : (
          <div className="space-y-1">
            {signals.slice(0, 10).map((s, i) => {
              const sig = s as { type?: string; content?: string; timestamp?: number };
              return (
                <div key={i} className="flex items-center gap-2 rounded-lg bg-white/5 p-2 text-xs">
                  {sig.type && <StatusPill status={sig.type} />}
                  <span className="flex-1 text-[var(--color-text)]">{sig.content}</span>
                </div>
              );
            })}
          </div>
        )}
      </GlassCard>
      <GlassCard>
        <h2 className="mb-3 text-sm font-semibold text-[var(--color-text-muted)]">改进提案</h2>
        {proposals.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">暂无提案</p>
        ) : (
          <div className="space-y-1">
            {proposals.slice(0, 10).map((p, i) => {
              const prop = p as { title?: string; status?: string };
              return (
                <div key={i} className="flex items-center justify-between rounded-lg bg-white/5 p-2 text-xs">
                  <span className="text-[var(--color-text)]">{prop.title}</span>
                  {prop.status && <StatusPill status={prop.status} />}
                </div>
              );
            })}
          </div>
        )}
      </GlassCard>
    </div>
  );
}

// ── Shared ──

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-sm text-[var(--color-text-muted)]">{label}</span>
      <span className="text-sm font-medium text-[var(--color-text)]">{value}</span>
    </div>
  );
}

function SelectRow({ label, value, onChange, options }: {
  label: string; value: string; onChange: (v: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-sm text-[var(--color-text-muted)]">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}
        className="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-[var(--color-text)]">
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}
