import { useEffect, useState } from "react";
import { GlassCard } from "@/components/GlassCard";
import { StatusPill } from "@/components/StatusPill";
import { threeLayerApi, type UserProfile, type UserPreferences } from "@/api/three-layer";

export default function UserAdmin() {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [editingPrefs, setEditingPrefs] = useState(false);
  const [prefsDraft, setPrefsDraft] = useState<UserPreferences | null>(null);

  // Use a default user ID for now; in production this comes from the session
  const userId = "default-user";

  useEffect(() => {
    threeLayerApi
      .getUserProfile(userId)
      .then((res) => setProfile(res.profile))
      .catch(() => null)
      .finally(() => setLoading(false));
  }, []);

  const startEditPrefs = () => {
    if (profile) {
      setPrefsDraft({ ...profile.preferences });
      setEditingPrefs(true);
    }
  };

  const savePrefs = () => {
    if (!prefsDraft) return;
    threeLayerApi.updateUserPreferences(userId, prefsDraft).then((res) => {
      if (profile) {
        setProfile({ ...profile, preferences: res.preferences });
      }
      setEditingPrefs(false);
    });
  };

  if (loading) return <div className="p-6 text-[var(--color-text-muted)]">加载中…</div>;
  if (!profile) return <div className="p-6 text-[var(--color-text-muted)]">无法加载用户画像</div>;

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6 pb-24">
      <h1 className="text-2xl font-bold text-[var(--color-text)]">我的画像</h1>

      {/* Identity */}
      <GlassCard>
        <h2 className="mb-4 text-lg font-semibold">身份</h2>
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
        </div>
      </GlassCard>

      {/* Spaces */}
      <GlassCard>
        <h2 className="mb-4 text-lg font-semibold">空间归属</h2>
        {profile.spaces.length === 0 ? (
          <p className="text-sm text-[var(--color-text-muted)]">未加入任何空间</p>
        ) : (
          <div className="space-y-2">
            {profile.spaces.map((s) => (
              <div key={s.spaceId} className="flex items-center justify-between rounded-lg bg-white/5 p-3">
                <span className="font-medium text-[var(--color-text)]">{s.spaceName}</span>
                <StatusPill status={s.role} />
              </div>
            ))}
          </div>
        )}
      </GlassCard>

      {/* Capabilities */}
      <GlassCard>
        <h2 className="mb-4 text-lg font-semibold">个人能力</h2>
        <div className="space-y-3">
          <div>
            <span className="text-xs text-[var(--color-text-muted)]">个人技能</span>
            <div className="mt-1 flex flex-wrap gap-2">
              {profile.capabilities.personalSkills.length === 0 ? (
                <span className="text-sm text-[var(--color-text-muted)]">无</span>
              ) : (
                profile.capabilities.personalSkills.map((s) => (
                  <span key={s} className="inline-flex items-center rounded-full bg-white/10 px-2 py-0.5 text-[11px]">{s}</span>
                ))
              )}
            </div>
          </div>
          <div>
            <span className="text-xs text-[var(--color-text-muted)]">常用工具</span>
            <div className="mt-1 flex flex-wrap gap-2">
              {profile.capabilities.frequentTools.length === 0 ? (
                <span className="text-sm text-[var(--color-text-muted)]">无</span>
              ) : (
                profile.capabilities.frequentTools.map((t) => (
                  <span key={t} className="inline-flex items-center rounded-full bg-[oklch(0.78_0.16_70_/_0.18)] px-2 py-0.5 text-[11px]">{t}</span>
                ))
              )}
            </div>
          </div>
        </div>
      </GlassCard>

      {/* Preferences */}
      <GlassCard>
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold">偏好设置</h2>
          {!editingPrefs ? (
            <button onClick={startEditPrefs} className="text-sm text-[var(--color-accent)]">编辑</button>
          ) : (
            <button onClick={savePrefs} className="text-sm text-[var(--color-accent)]">保存</button>
          )}
        </div>
        {editingPrefs && prefsDraft ? (
          <div className="space-y-3">
            <SelectRow label="语言" value={prefsDraft.language} onChange={(v) => setPrefsDraft({ ...prefsDraft, language: v })}
              options={[{ value: "zh-CN", label: "中文" }, { value: "en", label: "English" }]} />
            <SelectRow label="回复风格" value={prefsDraft.responseStyle} onChange={(v) => setPrefsDraft({ ...prefsDraft, responseStyle: v })}
              options={[{ value: "concise", label: "简洁" }, { value: "detailed", label: "详细" }, { value: "structured", label: "结构化" }]} />
            <SelectRow label="语调" value={prefsDraft.tone} onChange={(v) => setPrefsDraft({ ...prefsDraft, tone: v })}
              options={[{ value: "casual", label: "随和" }, { value: "formal", label: "正式" }, { value: "technical", label: "技术" }]} />
          </div>
        ) : (
          <div className="space-y-3">
            <Row label="语言" value={profile.preferences.language} />
            <Row label="回复风格" value={profile.preferences.responseStyle} />
            <Row label="语调" value={profile.preferences.tone} />
            <Row label="自动批准安全操作" value={profile.preferences.autoApproveSafe ? "是" : "否"} />
          </div>
        )}
      </GlassCard>
    </div>
  );
}

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
