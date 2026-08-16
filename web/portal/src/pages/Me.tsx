import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { useI18n } from "@/i18n";
import { threeLayerApi, type UserProfile, type UserPreferences } from "@/api/three-layer";
import {
  Brain, Wrench, MessageSquare, ShieldCheck, Activity, Settings,
  ChevronRight, Building2, Layers, ExternalLink, User as UserIcon,
} from "lucide-react";

const DEFAULT_USER = "default-user";

interface NavCard {
  to: string;
  icon: typeof Brain;
  titleKey: string;
  hintKey: string;
}

const NAV_CARDS: NavCard[] = [
  { to: "/memory", icon: Brain, titleKey: "me.memory", hintKey: "me.memoryHint" },
  { to: "/skills", icon: Wrench, titleKey: "me.skills", hintKey: "me.skillsHint" },
  { to: "/sessions", icon: MessageSquare, titleKey: "me.sessions", hintKey: "me.sessionsHint" },
  { to: "/approvals", icon: ShieldCheck, titleKey: "me.approvals", hintKey: "me.approvalsHint" },
  { to: "/runs", icon: Activity, titleKey: "me.runs", hintKey: "me.runsHint" },
];

export default function Me() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [editingPrefs, setEditingPrefs] = useState(false);
  const [draft, setDraft] = useState<UserPreferences | null>(null);

  useEffect(() => {
    threeLayerApi
      .getUserProfile(DEFAULT_USER)
      .then((res) => {
        setProfile(res.profile);
        setDraft(res.profile.preferences);
      })
      .catch(() => null)
      .finally(() => setLoading(false));
  }, []);

  const savePrefs = () => {
    if (!profile || !draft) return;
    threeLayerApi.updateUserPreferences(profile.userId, draft).then((res) => {
      setProfile({ ...profile, preferences: res.preferences });
      setEditingPrefs(false);
    });
  };

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="text-[28px] font-medium leading-tight text-foreground">
            {t("nav.profile")}
          </h1>
        </header>

        {loading ? (
          <div className="space-y-3">
            <div className="shimmer h-24 rounded-2xl" />
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="shimmer h-16 rounded-2xl" />
            ))}
          </div>
        ) : (
          <>
            {/* Profile card */}
            <GlassCard className="mb-4">
              <div className="flex items-center gap-3">
                <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-primary/10 text-primary">
                  <UserIcon className="h-5 w-5" />
                </div>
                <div className="min-w-0 flex-1">
                  <h2 className="truncate text-[16px] font-semibold text-foreground">
                    {profile?.displayName ?? "用户"}
                  </h2>
                  <p className="truncate text-[12px] text-muted-foreground">
                    {profile?.userId ?? "—"}
                  </p>
                  {profile?.spaces?.[0] && (
                    <p className="truncate text-[11px] text-muted-foreground">
                      {profile.spaces[0].spaceName}
                    </p>
                  )}
                </div>
              </div>
            </GlassCard>

            {/* Navigation cards */}
            <div className="space-y-2.5">
              {NAV_CARDS.map(({ to, icon: Icon, titleKey, hintKey }) => (
                <GlassCard
                  key={to}
                  interactive
                  padding="sm"
                  onClick={() => navigate(to)}
                >
                  <div className="flex items-center gap-3">
                    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
                      <Icon className="h-4 w-4" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <h3 className="text-[14px] font-medium text-foreground">
                        {t(titleKey)}
                      </h3>
                      <p className="truncate text-[11px] text-muted-foreground">
                        {t(hintKey)}
                      </p>
                    </div>
                    <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                  </div>
                </GlassCard>
              ))}
            </div>

            {/* Preferences inline */}
            <GlassCard className="mt-4">
              <div className="mb-3 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Settings className="h-4 w-4 text-primary" />
                  <h3 className="text-[14px] font-semibold text-foreground">
                    {t("me.preferences")}
                  </h3>
                </div>
                {editingPrefs ? (
                  <button
                    onClick={savePrefs}
                    className="text-[13px] font-medium text-primary active:scale-95 transition"
                  >
                    {t("common.confirm")}
                  </button>
                ) : (
                  <button
                    onClick={() => { setDraft(profile?.preferences ?? null); setEditingPrefs(true); }}
                    className="text-[13px] font-medium text-primary active:scale-95 transition"
                  >
                    {t("memory.edit")}
                  </button>
                )}
              </div>
              {editingPrefs && draft ? (
                <div className="space-y-3">
                  <PrefRow label={t("me.preferences")} >
                    <select
                      value={draft.language}
                      onChange={(e) => setDraft({ ...draft, language: e.target.value })}
                      className="rounded-lg bg-muted px-3 py-1.5 text-[13px] text-foreground"
                    >
                      <option value="zh-CN">中文</option>
                      <option value="en">English</option>
                    </select>
                  </PrefRow>
                  <PrefRow label="回复风格">
                    <select
                      value={draft.responseStyle}
                      onChange={(e) => setDraft({ ...draft, responseStyle: e.target.value })}
                      className="rounded-lg bg-muted px-3 py-1.5 text-[13px] text-foreground"
                    >
                      <option value="concise">简洁</option>
                      <option value="detailed">详细</option>
                      <option value="structured">结构化</option>
                    </select>
                  </PrefRow>
                  <PrefRow label="语调">
                    <select
                      value={draft.tone}
                      onChange={(e) => setDraft({ ...draft, tone: e.target.value })}
                      className="rounded-lg bg-muted px-3 py-1.5 text-[13px] text-foreground"
                    >
                      <option value="casual">随和</option>
                      <option value="formal">正式</option>
                      <option value="technical">技术</option>
                    </select>
                  </PrefRow>
                  <label className="flex items-center justify-between">
                    <span className="text-[13px] text-muted-foreground">自动批准安全操作</span>
                    <input
                      type="checkbox"
                      checked={draft.autoApproveSafe}
                      onChange={(e) => setDraft({ ...draft, autoApproveSafe: e.target.checked })}
                      className="h-4 w-4"
                    />
                  </label>
                </div>
              ) : (
                <div className="space-y-2">
                  <PrefDisplay label="语言" value={profile?.preferences.language ?? "-"} />
                  <PrefDisplay label="回复风格" value={profile?.preferences.responseStyle ?? "-"} />
                  <PrefDisplay label="语调" value={profile?.preferences.tone ?? "-"} />
                  <PrefDisplay
                    label="自动批准"
                    value={profile?.preferences.autoApproveSafe ? t("common.yes") : t("common.no")}
                  />
                </div>
              )}
            </GlassCard>

            {/* Admin links */}
            <div className="mt-4 flex gap-2">
              <a
                href="/ops/index.html/spaces"
                className="flex flex-1 items-center gap-2 rounded-2xl bg-muted/60 p-3 text-[13px] transition hover:bg-muted active:scale-95"
              >
                <Building2 className="h-4 w-4 text-primary" />
                空间管理
                <ExternalLink className="ml-auto h-3 w-3 opacity-50" />
              </a>
              <a
                href="/ops/index.html/org"
                className="flex flex-1 items-center gap-2 rounded-2xl bg-muted/60 p-3 text-[13px] transition hover:bg-muted active:scale-95"
              >
                <Layers className="h-4 w-4 text-primary" />
                组织管理
                <ExternalLink className="ml-auto h-3 w-3 opacity-50" />
              </a>
            </div>
          </>
        )}
      </div>
    </AuroraBackground>
  );
}

function PrefRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-[13px] text-muted-foreground">{label}</span>
      {children}
    </div>
  );
}

function PrefDisplay({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-[13px] text-muted-foreground">{label}</span>
      <span className="text-[13px] font-medium text-foreground">{value}</span>
    </div>
  );
}
