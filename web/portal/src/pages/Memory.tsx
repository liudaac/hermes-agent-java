import { useEffect, useState, useCallback } from "react";
import { portalApi } from "@/api/portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { ErrorCard } from "@/components/ErrorCard";
import { StatusPill } from "@/components/StatusPill";
import { useI18n } from "@/i18n";
import { useWorkspace } from "@/hooks/useWorkspace";
import { cn, formatRelativeTime } from "@hermes/ui";
import {
  Brain, Search, Sparkles, Check, X, Edit2, Trash2, RefreshCw,
  ArrowRight, Database, Zap, Clock, Activity,
} from "lucide-react";

type Tab = "decay" | "memory" | "improvement";

interface MemoryStats {
  memory: {
    totalMemories: number;
    sessionCount: number;
    longTermMemories: number;
    agentExperiences: number;
    decayRuns: number;
    lastDecayRun: string | null;
  };
  skill: {
    totalSkills: number;
    enabledSkills: number;
    byScope: Record<string, number>;
    byType: Record<string, number>;
  };
}

interface MemoryEntry {
  id: string;
  type: string;
  content: string;
  category: string;
  userId: string;
  source: string;
  createdAt: number;
}

interface Proposal {
  id: string;
  title: string;
  finding: string;
  proposedChange: string;
  expectedBenefit: string;
  status: string;
  confidence: number;
  evidence: string;
  createdAt: number;
}

const DECAY_STAGES = [
  { key: "full", labelKey: "memory.fullStage", color: "oklch(0.72_0.14_145)", bg: "oklch(0.72_0.14_145_/_0.18)" },
  { key: "warm", labelKey: "memory.warmStage", color: "oklch(0.78_0.16_85)", bg: "oklch(0.78_0.16_85_/_0.18)" },
  { key: "cool", labelKey: "memory.coolStage", color: "oklch(0.70_0.16_280)", bg: "oklch(0.70_0.16_280_/_0.18)" },
  { key: "evicted", labelKey: "memory.evictedStage", color: "oklch(0.68_0.20_25)", bg: "oklch(0.68_0.20_25_/_0.18)" },
];

export default function Memory() {
  const { t } = useI18n();
  const { workspaceId } = useWorkspace();
  const [tab, setTab] = useState<Tab>("decay");
  const [stats, setStats] = useState<MemoryStats | null>(null);
  const [memories, setMemories] = useState<MemoryEntry[]>([]);
  const [proposals, setProposals] = useState<Proposal[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editContent, setEditContent] = useState("");

  const tenantId = workspaceId ?? "default";

  const loadStats = useCallback(() => {
    portalApi.getMemoryStats(tenantId).then(setStats).catch(() => {});
  }, [tenantId]);

  const loadMemories = useCallback(() => {
    setLoading(true);
    setError(null);
    portalApi
      .getMemoryOverview(tenantId)
      .then((res) => {
        setMemories(res.recentMemories ?? []);
      })
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [tenantId]);

  const loadProposals = useCallback(() => {
    portalApi
      .getImprovementProposals(tenantId)
      .then((res) => setProposals(res.proposals ?? []))
      .catch(() => {});
  }, [tenantId]);

  useEffect(() => {
    loadStats();
    loadMemories();
    loadProposals();
  }, [loadStats, loadMemories, loadProposals]);

  // Auto-refresh stats every 30s
  useEffect(() => {
    const timer = setInterval(loadStats, 30_000);
    return () => clearInterval(timer);
  }, [loadStats]);

  const handleSearch = () => {
    if (!searchQuery.trim()) {
      loadMemories();
      return;
    }
    setLoading(true);
    portalApi
      .searchMemories(tenantId, searchQuery)
      .then((res) => setMemories(res.results ?? []))
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  };

  const handleEdit = async (id: string) => {
    if (!editContent.trim()) return;
    setBusyId(id);
    try {
      await portalApi.editMemory(tenantId, id, editContent);
      setMemories((cur) =>
        cur.map((m) => (m.id === id ? { ...m, content: editContent } : m)),
      );
      setEditingId(null);
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setBusyId(null);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm(t("memory.deleteConfirm"))) return;
    setBusyId(id);
    try {
      await portalApi.deleteMemory(tenantId, id);
      setMemories((cur) => cur.filter((m) => m.id !== id));
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setBusyId(null);
    }
  };

  const handleProposal = async (id: string, action: "accept" | "reject") => {
    setBusyId(id);
    try {
      if (action === "accept") {
        await portalApi.acceptProposal(tenantId, id);
      } else {
        await portalApi.rejectProposal(tenantId, id);
      }
      setProposals((cur) =>
        cur.map((p) => (p.id === id ? { ...p, status: action === "accept" ? "applied" : "rejected" } : p)),
      );
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setBusyId(null);
    }
  };

  const tabs: { key: Tab; label: string; icon: typeof Brain }[] = [
    { key: "decay", label: t("memory.tabDecay"), icon: Activity },
    { key: "memory", label: t("memory.tabMemory"), icon: Brain },
    { key: "improvement", label: t("memory.tabImprovement"), icon: Sparkles },
  ];

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
            {t("memory.title")}
          </h1>
          <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
            {t("memory.subtitle")}
          </p>
        </header>

        {/* Tab pills */}
        <div className="mb-5 flex gap-2 overflow-x-auto pb-1">
          {tabs.map(({ key, label, icon: Icon }) => (
            <button
              key={key}
              onClick={() => setTab(key)}
              className={cn(
                "flex items-center gap-1.5 rounded-full px-4 py-2 text-[13px] font-medium whitespace-nowrap transition active:scale-95",
                tab === key
                  ? "bg-[oklch(0.78_0.16_70_/_0.2)] text-[oklch(0.88_0.12_70)]"
                  : "bg-white/5 text-[var(--color-text-muted)] hover:text-[var(--color-text)]",
              )}
            >
              <Icon className="h-3.5 w-3.5" />
              {label}
            </button>
          ))}
        </div>

        {error && <ErrorCard message={error} onRetry={() => { setError(null); loadMemories(); }} />}

        {/* ── Decay Pipeline ── */}
        {tab === "decay" && (
          <div className="space-y-4">
            <GlassCard>
              <div className="mb-4 flex items-center justify-between">
                <h2 className="text-sm font-semibold text-[var(--color-text-muted)]">
                  {t("memory.decayTitle")}
                </h2>
                <button onClick={loadStats} className="text-[var(--color-accent)] active:scale-95 transition">
                  <RefreshCw className="h-3.5 w-3.5" />
                </button>
              </div>

              {/* Stage flow */}
              <div className="flex items-center justify-between gap-1 overflow-x-auto py-4">
                {DECAY_STAGES.map((stage, i) => (
                  <div key={stage.key} className="flex items-center gap-1 whitespace-nowrap">
                    <div className="flex flex-col items-center gap-1.5">
                      <div
                        className="flex h-14 w-14 items-center justify-center rounded-full text-[11px] font-bold"
                        style={{ background: `var(--${stage.bg}, ${stage.bg})`, color: stage.color }}
                      >
                        <span style={{ background: stage.bg, color: stage.color }} className="flex h-14 w-14 items-center justify-center rounded-full">
                          {t(stage.labelKey)}
                        </span>
                      </div>
                    </div>
                    {i < DECAY_STAGES.length - 1 && (
                      <ArrowRight className="h-4 w-4 shrink-0 text-[var(--color-text-muted)]" />
                    )}
                  </div>
                ))}
              </div>
            </GlassCard>

            {/* Stats grid */}
            <div className="grid grid-cols-2 gap-3">
              <StatCard icon={Database} label={t("memory.totalMemories")} value={stats?.memory.longTermMemories ?? "-"} />
              <StatCard icon={Activity} label={t("memory.sessionCount")} value={stats?.memory.sessionCount ?? "-"} />
              <StatCard icon={Zap} label={t("memory.experiences")} value={stats?.memory.agentExperiences ?? "-"} />
              <StatCard icon={Clock} label={t("memory.decayRuns")} value={stats?.memory.decayRuns ?? "-"} />
            </div>

            {stats?.memory.lastDecayRun && (
              <GlassCard padding="sm">
                <div className="flex items-center gap-2 text-[12px] text-[var(--color-text-muted)]">
                  <Clock className="h-3.5 w-3.5" />
                  {t("memory.lastDecay")}: {formatRelativeTime(stats.memory.lastDecayRun)}
                </div>
              </GlassCard>
            )}

            {/* Skill summary */}
            {stats?.skill && (
              <GlassCard>
                <h3 className="mb-3 text-sm font-semibold text-[var(--color-text-muted)]">
                  {t("memory.skillsTitle")}
                </h3>
                <div className="flex items-center justify-between text-[13px]">
                  <span className="text-[var(--color-text-secondary)]">
                    {stats.skill.totalSkills} {t("memory.skillsTitle").toLowerCase()}
                  </span>
                  <span className="font-medium text-[oklch(0.78_0.12_145)]">
                    {stats.skill.enabledSkills} {t("memory.enabled")}
                  </span>
                </div>
                {Object.keys(stats.skill.byScope ?? {}).length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-2">
                    {Object.entries(stats.skill.byScope).map(([scope, count]) => (
                      <span key={scope} className="rounded-full bg-white/5 px-2 py-0.5 text-[11px] text-[var(--color-text-muted)]">
                        {t(`skills.scope.${scope}`)}: {count}
                      </span>
                    ))}
                  </div>
                )}
              </GlassCard>
            )}
          </div>
        )}

        {/* ── My Memory ── */}
        {tab === "memory" && (
          <div className="space-y-3">
            {/* Search bar */}
            <div className="flex gap-2">
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                placeholder={t("memory.searchPlaceholder")}
                className="flex-1 rounded-xl bg-white/10 px-4 py-2.5 text-[13px] text-[var(--color-text-primary)] placeholder:text-[var(--color-text-muted)] outline-none focus:ring-1 focus:ring-[var(--color-accent)]"
              />
              <button
                onClick={handleSearch}
                className="flex h-[42px] w-[42px] items-center justify-center rounded-xl bg-[oklch(0.78_0.16_70_/_0.2)] text-[oklch(0.88_0.12_70)] active:scale-95 transition"
              >
                <Search className="h-4 w-4" />
              </button>
            </div>

            {loading ? (
              <div className="space-y-2">
                {[0, 1, 2].map((i) => (
                  <div key={i} className="shimmer h-20 rounded-2xl" />
                ))}
              </div>
            ) : memories.length === 0 ? (
              <GlassCard className="flex flex-col items-center gap-2 py-10 text-center">
                <Brain className="h-6 w-6 text-[var(--color-text-muted)]" />
                <p className="text-[13px] text-[var(--color-text-secondary)]">{t("memory.noMemory")}</p>
              </GlassCard>
            ) : (
              memories.map((m) => (
                <GlassCard key={m.id} padding="sm">
                  {editingId === m.id ? (
                    <div className="space-y-2">
                      <textarea
                        value={editContent}
                        onChange={(e) => setEditContent(e.target.value)}
                        rows={3}
                        className="w-full rounded-lg bg-white/10 p-2 text-[13px] text-[var(--color-text-primary)] outline-none focus:ring-1 focus:ring-[var(--color-accent)]"
                      />
                      <div className="flex gap-2">
                        <button
                          onClick={() => handleEdit(m.id)}
                          disabled={busyId === m.id}
                          className="rounded-lg bg-[oklch(0.72_0.14_145_/_0.85)] px-3 py-1.5 text-[12px] font-semibold text-[oklch(0.18_0.04_145)] active:scale-95 transition"
                        >
                          <Check className="mr-1 inline h-3 w-3" />
                          {t("common.confirm")}
                        </button>
                        <button
                          onClick={() => setEditingId(null)}
                          className="rounded-lg bg-white/5 px-3 py-1.5 text-[12px] text-[var(--color-text-muted)] active:scale-95 transition"
                        >
                          {t("common.cancel")}
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div>
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <StatusPill status={m.type} />
                          {m.category && (
                            <span className="text-[11px] text-[var(--color-text-muted)]">{m.category}</span>
                          )}
                        </div>
                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => { setEditingId(m.id); setEditContent(m.content); }}
                            className="flex h-7 w-7 items-center justify-center rounded-lg text-[var(--color-text-muted)] hover:text-[var(--color-accent)] active:scale-95 transition"
                          >
                            <Edit2 className="h-3 w-3" />
                          </button>
                          <button
                            onClick={() => handleDelete(m.id)}
                            disabled={busyId === m.id}
                            className="flex h-7 w-7 items-center justify-center rounded-lg text-[var(--color-text-muted)] hover:text-[oklch(0.75_0.18_25)] active:scale-95 transition"
                          >
                            <Trash2 className="h-3 w-3" />
                          </button>
                        </div>
                      </div>
                      <p className="mt-1.5 text-[13px] leading-relaxed text-[var(--color-text-primary)]">
                        {m.content}
                      </p>
                      <div className="mt-1.5 flex items-center gap-2 text-[11px] text-[var(--color-text-muted)]">
                        <span>{m.source}</span>
                        <span>·</span>
                        <span>{formatRelativeTime(m.createdAt)}</span>
                      </div>
                    </div>
                  )}
                </GlassCard>
              ))
            )}
          </div>
        )}

        {/* ── Self-Improvement ── */}
        {tab === "improvement" && (
          <div className="space-y-3">
            {proposals.length === 0 ? (
              <GlassCard className="flex flex-col items-center gap-2 py-10 text-center">
                <Sparkles className="h-6 w-6 text-[var(--color-text-muted)]" />
                <p className="text-[13px] text-[var(--color-text-secondary)]">{t("memory.noProposals")}</p>
              </GlassCard>
            ) : (
              proposals.map((p) => {
                const isPending = p.status === "pending" || p.status === "require_confirm";
                return (
                  <GlassCard key={p.id} padding="sm">
                    <div className="flex items-start justify-between gap-2">
                      <h3 className="text-[14px] font-semibold text-[var(--color-text-primary)]">
                        {p.title}
                      </h3>
                      <StatusPill status={p.status} />
                    </div>
                    <div className="mt-2 space-y-1.5 text-[12px] leading-relaxed">
                      {p.finding && (
                        <p className="text-[var(--color-text-secondary)]">
                          <span className="font-medium text-[var(--color-text-muted)]">发现: </span>
                          {p.finding}
                        </p>
                      )}
                      {p.proposedChange && (
                        <p className="text-[var(--color-text-secondary)]">
                          <span className="font-medium text-[var(--color-text-muted)]">建议: </span>
                          {p.proposedChange}
                        </p>
                      )}
                      {p.expectedBenefit && (
                        <p className="text-[var(--color-text-secondary)]">
                          <span className="font-medium text-[var(--color-text-muted)]">预期收益: </span>
                          {p.expectedBenefit}
                        </p>
                      )}
                    </div>
                    <div className="mt-2 flex items-center gap-2">
                      <span className="text-[11px] text-[var(--color-text-muted)]">
                        {t("memory.proposalConfidence")}: {Math.round(p.confidence * 100)}%
                      </span>
                      <span className="text-[11px] text-[var(--color-text-muted)]">·</span>
                      <span className="text-[11px] text-[var(--color-text-muted)]">
                        {formatRelativeTime(p.createdAt)}
                      </span>
                    </div>
                    {isPending && (
                      <div className="mt-3 flex gap-2">
                        <button
                          onClick={() => handleProposal(p.id, "accept")}
                          disabled={busyId === p.id}
                          className="flex-1 inline-flex items-center justify-center gap-1.5 rounded-xl py-2 text-[12px] font-semibold bg-[oklch(0.72_0.14_145_/_0.85)] text-[oklch(0.18_0.04_145)] active:scale-95 transition disabled:opacity-60"
                        >
                          <Check className="h-3.5 w-3.5" />
                          {t("memory.proposalAccept")}
                        </button>
                        <button
                          onClick={() => handleProposal(p.id, "reject")}
                          disabled={busyId === p.id}
                          className="flex-1 inline-flex items-center justify-center gap-1.5 rounded-xl py-2 text-[12px] font-semibold bg-[oklch(0.30_0.02_50_/_0.6)] text-[var(--color-text-secondary)] active:scale-95 transition disabled:opacity-60"
                        >
                          <X className="h-3.5 w-3.5" />
                          {t("memory.proposalReject")}
                        </button>
                      </div>
                    )}
                  </GlassCard>
                );
              })
            )}
          </div>
        )}
      </div>
    </AuroraBackground>
  );
}

function StatCard({ icon: Icon, label, value }: { icon: typeof Database; label: string; value: number | string }) {
  return (
    <GlassCard padding="sm">
      <div className="flex items-center gap-2.5">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-[oklch(0.78_0.16_70_/_0.12)] text-[oklch(0.78_0.12_70)]">
          <Icon className="h-4 w-4" />
        </div>
        <div>
          <p className="text-[11px] text-[var(--color-text-muted)]">{label}</p>
          <p className="text-[18px] font-bold text-[var(--color-text-primary)]">{value}</p>
        </div>
      </div>
    </GlassCard>
  );
}
