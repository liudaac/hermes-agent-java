import { useEffect, useState, useCallback } from "react";
import { portalApi } from "@/api/portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { ErrorCard } from "@/components/ErrorCard";
import { RefreshCw, Brain, Layers, Clock, CheckCircle2, XCircle } from "lucide-react";
import { useI18n } from "@/i18n";
import { cn } from "@hermes/ui";

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

interface SkillItem {
  id: string;
  name: string;
  description: string;
  scope: string;
  type: string;
  enabled: boolean;
  currentVersion: number;
  versions: number;
  updatedAt: string | null;
}

const DEFAULT_TENANT = "default";

export default function Memory() {
  const { t } = useI18n();
  const [stats, setStats] = useState<MemoryStats | null>(null);
  const [skills, setSkills] = useState<SkillItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadData = useCallback(() => {
    setLoading(true);
    setError(null);
    Promise.all([
      portalApi.getMemoryStats(DEFAULT_TENANT).catch(() => null),
      portalApi.getSkills(DEFAULT_TENANT).catch(() => [] as SkillItem[]),
    ]).then(([memStats, skillList]) => {
      if (memStats) setStats(memStats);
      setSkills(skillList);
      setLoading(false);
    }).catch((e) => {
      setError(String(e?.message ?? e));
      setLoading(false);
    });
  }, []);

  useEffect(() => {
    loadData();
    const timer = setInterval(loadData, 30_000);
    return () => clearInterval(timer);
  }, [loadData]);

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5 flex items-center justify-between">
          <div>
            <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
              {t("memory.title")}
            </h1>
            <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
              {t("memory.subtitle")}
            </p>
          </div>
          <button
            onClick={loadData}
            className="glass flex h-9 w-9 items-center justify-center rounded-xl text-[var(--color-text-secondary)] transition-colors hover:text-[var(--color-text-primary)]"
            aria-label={t("memory.refresh")}
          >
            <RefreshCw className={cn("h-4 w-4", loading && "animate-spin")} />
          </button>
        </header>

        {error && <ErrorCard message={error} onRetry={loadData} />}

        {/* Decay Status Section */}
        <section className="mb-6">
          <h2 className="mb-3 flex items-center gap-2 text-[16px] font-semibold text-[var(--color-text-primary)]">
            <Brain className="h-4 w-4 text-[oklch(0.78_0.16_70)]" />
            {t("memory.decayTitle")}
          </h2>

          {loading && !stats ? (
            <div className="grid grid-cols-2 gap-3">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="shimmer h-20 rounded-2xl" />
              ))}
            </div>
          ) : stats ? (
            <>
              {/* Decay pipeline visualization */}
              <GlassCard className="mb-3" padding="md">
                <div className="flex items-center justify-between gap-2">
                  <DecayStage label={t("memory.fullStage")} count={stats.memory.sessionCount} color="oklch(0.78_0.16_70)" />
                  <DecayArrow />
                  <DecayStage label={t("memory.warmStage")} count={stats.memory.sessionCount} color="oklch(0.72_0.12_85)" />
                  <DecayArrow />
                  <DecayStage label={t("memory.coolStage")} count={0} color="oklch(0.65_0.08_250)" />
                  <DecayArrow />
                  <DecayStage label={t("memory.evictedStage")} count={stats.memory.longTermMemories} color="oklch(0.55_0.05_50)" />
                </div>
              </GlassCard>

              {/* Metric cards */}
              <div className="grid grid-cols-2 gap-3">
                <MetricCard
                  icon={<Layers className="h-4 w-4" />}
                  label={t("memory.totalMemories")}
                  value={stats.memory.longTermMemories}
                  tone="accent"
                />
                <MetricCard
                  icon={<Brain className="h-4 w-4" />}
                  label={t("memory.experiences")}
                  value={stats.memory.agentExperiences}
                  tone="default"
                />
                <MetricCard
                  icon={<Clock className="h-4 w-4" />}
                  label={t("memory.decayRuns")}
                  value={stats.memory.decayRuns}
                  tone="default"
                />
                <MetricCard
                  icon={<CheckCircle2 className="h-4 w-4" />}
                  label={t("memory.sessionCount")}
                  value={stats.memory.sessionCount}
                  tone="default"
                />
              </div>

              {stats.memory.lastDecayRun && (
                <p className="mt-3 text-center text-[11px] text-[var(--color-text-muted)]">
                  {t("memory.lastDecay")}: {formatTime(stats.memory.lastDecayRun)}
                </p>
              )}
            </>
          ) : (
            <GlassCard className="py-8 text-center text-[13px] text-[var(--color-text-muted)]">
              {t("memory.noData")}
            </GlassCard>
          )}
        </section>

        {/* Skill Management Section */}
        <section>
          <h2 className="mb-3 flex items-center gap-2 text-[16px] font-semibold text-[var(--color-text-primary)]">
            <Layers className="h-4 w-4 text-[oklch(0.78_0.16_70)]" />
            {t("memory.skillsTitle")}
            {stats && (
              <span className="ml-1 rounded-full bg-[oklch(0.78_0.16_70_/_0.18)] px-2 py-0.5 text-[11px] font-medium text-[oklch(0.88_0.12_70)]">
                {stats.skill.enabledSkills}/{stats.skill.totalSkills}
              </span>
            )}
          </h2>

          {loading && skills.length === 0 ? (
            <div className="space-y-2">
              {[0, 1].map((i) => (
                <div key={i} className="shimmer h-16 rounded-2xl" />
              ))}
            </div>
          ) : skills.length === 0 ? (
            <GlassCard className="flex flex-col items-center gap-3 py-8 text-center">
              <Layers className="h-6 w-6 text-[var(--color-text-muted)]" />
              <p className="text-[13px] text-[var(--color-text-secondary)]">{t("memory.noSkills")}</p>
            </GlassCard>
          ) : (
            <div className="space-y-2">
              {skills.map((skill) => (
                <SkillCard key={skill.id} skill={skill} />
              ))}
            </div>
          )}
        </section>
      </div>
    </AuroraBackground>
  );
}

function DecayStage({ label, count, color }: { label: string; count: number; color: string }) {
  return (
    <div className="flex flex-col items-center gap-1">
      <div
        className="flex h-12 w-12 items-center justify-center rounded-full text-[14px] font-bold"
        style={{
          background: `oklch(from ${color} l c h / 0.15)`,
          color: color,
          border: `1px solid oklch(from ${color} l c h / 0.3)`,
        }}
      >
        {count}
      </div>
      <span className="text-[10px] text-[var(--color-text-muted)]">{label}</span>
    </div>
  );
}

function DecayArrow() {
  return (
    <div className="flex items-center text-[var(--color-text-muted)]">
      <svg width="16" height="8" viewBox="0 0 16 8" fill="none">
        <path d="M0 4h14m0 0L11 1m3 3L11 7" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </div>
  );
}

function MetricCard({
  icon,
  label,
  value,
  tone,
}: {
  icon: React.ReactNode;
  label: string;
  value: number;
  tone: "default" | "accent";
}) {
  return (
    <GlassCard tone={tone} padding="sm">
      <div className="flex items-center gap-2">
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[oklch(0.78_0.16_70_/_0.12)] text-[oklch(0.88_0.12_70)]">
          {icon}
        </div>
        <div className="min-w-0">
          <p className="text-[20px] font-bold leading-none text-[var(--color-text-primary)]">
            {value}
          </p>
          <p className="mt-0.5 truncate text-[10px] text-[var(--color-text-muted)]">{label}</p>
        </div>
      </div>
    </GlassCard>
  );
}

function SkillCard({ skill }: { skill: SkillItem }) {
  const scopeColors: Record<string, string> = {
    PRIVATE: "oklch(0.65_0.08_250)",
    INSTALLED: "oklch(0.72_0.12_160)",
    SHARED: "oklch(0.78_0.16_70)",
    SYSTEM: "oklch(0.68_0.20_25)",
  };
  const scopeColor = scopeColors[skill.scope] ?? "oklch(0.55_0.05_50)";

  return (
    <GlassCard interactive padding="sm">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h3 className="truncate text-[14px] font-semibold text-[var(--color-text-primary)]">
              {skill.name}
            </h3>
            {skill.enabled ? (
              <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-[oklch(0.72_0.12_160)]" />
            ) : (
              <XCircle className="h-3.5 w-3.5 shrink-0 text-[oklch(0.55_0.05_50)]" />
            )}
          </div>
          {skill.description && (
            <p className="mt-0.5 line-clamp-2 text-[11px] text-[var(--color-text-secondary)]">
              {skill.description}
            </p>
          )}
          <div className="mt-1.5 flex flex-wrap items-center gap-2 text-[10px] text-[var(--color-text-muted)]">
            <span
              className="rounded-full px-1.5 py-0.5 font-medium"
              style={{
                background: `oklch(from ${scopeColor} l c h / 0.12)`,
                color: scopeColor,
              }}
            >
              {skill.scope}
            </span>
            <span className="rounded-full bg-[oklch(0.30_0.015_50_/_0.3)] px-1.5 py-0.5 font-medium">
              {skill.type}
            </span>
            <span>v{skill.currentVersion}{skill.versions > 1 ? ` (${skill.versions})` : ""}</span>
          </div>
        </div>
      </div>
    </GlassCard>
  );
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return "刚刚";
    if (diffMin < 60) return `${diffMin} 分钟前`;
    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr} 小时前`;
    const diffDay = Math.floor(diffHr / 24);
    if (diffDay < 30) return `${diffDay} 天前`;
    return d.toLocaleDateString();
  } catch {
    return iso;
  }
}
