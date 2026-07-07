import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  Sparkles, ArrowRight, AlertTriangle, Inbox, Wand2, Plus, Activity as ActivityIcon,
  ChevronRight,
} from "lucide-react";
import { portalApi } from "@/api/portal";
import type {
  BusinessHomeResponse,
  BusinessTeamCard,
  BusinessInsightRecord,
  BusinessScenarioRecord,
  BusinessTeamsResponse,
  BusinessScenariosResponse,
  BusinessApprovalsResponse,
} from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { EmployeeCard } from "@/components/EmployeeCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { StatusPill } from "@/components/StatusPill";
import { useI18n } from "@/i18n";
import { cn } from "@hermes/ui";
import { formatNumber, formatRelativeTime } from "@hermes/ui";

export default function Home() {
  const { t } = useI18n();
  const [home, setHome] = useState<BusinessHomeResponse | null>(null);
  const [teams, setTeams] = useState<BusinessTeamCard[] | null>(null);
  const [scenarios, setScenarios] = useState<BusinessScenarioRecord[] | null>(null);
  const [pending, setPending] = useState<number>(0);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    const all = Promise.allSettled([
      portalApi.getBusinessHome(),
      portalApi.getBusinessTeams(),
      portalApi.getBusinessApprovals(undefined, "PENDING"),
    ]);
    all
      .then(([h, t, a]) => {
        if (!alive) return;
        if (h.status === "fulfilled") setHome(h.value);
        else setError(String((h.reason as Error)?.message ?? h.reason));
        if (t.status === "fulfilled") {
          const v = t.value as BusinessTeamsResponse;
          setTeams(v.teams ?? []);
        }
        if (a.status === "fulfilled") {
          const v = a.value as BusinessApprovalsResponse;
          setPending(v.approvals?.length ?? 0);
        }
      });
    return () => {
      alive = false;
    };
  }, []);

  // Once we know the workspaceId, load scenarios.
  useEffect(() => {
    const ws = home?.workspaceId ?? home?.workspaces?.[0]?.workspaceId;
    if (!ws) return;
    let alive = true;
    portalApi
      .getBusinessScenarios(ws)
      .then((res: BusinessScenariosResponse) => {
        if (alive) setScenarios(res.scenarios ?? []);
      })
      .catch(() => {
        if (alive) setScenarios([]);
      });
    return () => {
      alive = false;
    };
  }, [home?.workspaceId, home?.workspaces]);

  const greeting = pickGreeting();
  const summary = home?.summary;
  const insights = home?.insights ?? [];
  const recentRuns = home?.recentRuns ?? [];

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-8 sm:pt-12">
        {/* ── Hero ─────────────────────────────────────────── */}
        <header className="mb-6 sm:mb-8">
          <p className="text-[12px] font-medium tracking-[0.18em] text-[var(--color-text-muted)] uppercase">
            {greeting}
          </p>
          <h1 className="mt-1.5 font-display text-[34px] sm:text-[44px] leading-[1.05] font-medium text-[var(--color-text-primary)]">
            {t("home.heroTitle")}
          </h1>
          <p className="mt-2 text-[15px] text-[var(--color-text-secondary)]">
            {t("home.heroSubtitle")}
          </p>
        </header>

        {error && (
          <GlassCard tone="default" className="mb-4 border border-[oklch(0.68_0.20_25_/_0.35)]">
            <div className="flex items-start gap-3">
              <AlertTriangle className="mt-0.5 h-4 w-4 text-[oklch(0.75_0.18_25)]" />
              <div className="text-[13px] text-[var(--color-text-secondary)]">
                {t("common.error")}：{error}
              </div>
            </div>
          </GlassCard>
        )}

        {/* ── Today snapshot ──────────────────────────────── */}
        {summary && (
          <GlassCard tone="strong" grain className="mb-6">
            <div className="flex items-center justify-between">
              <h2 className="text-[12px] font-semibold tracking-[0.2em] uppercase text-[var(--color-text-muted)]">
                {t("home.sectionToday")}
              </h2>
              <Link
                to="/runs"
                className="inline-flex items-center gap-1 text-[11px] tracking-wide text-[var(--color-accent)] hover:underline"
              >
                {t("home.seeAll")}
                <ChevronRight className="h-3 w-3" />
              </Link>
            </div>
            <div className="mt-3 grid grid-cols-3 gap-3">
              <Stat label="运行" value={formatNumber(summary.runCount)} />
              <Stat
                label="待审批"
                value={formatNumber(pending > 0 ? pending : summary.pendingApprovals)}
                accent={pending > 0 || summary.pendingApprovals > 0}
              />
              <Stat label="洞察" value={formatNumber(summary.openInsights)} />
            </div>
          </GlassCard>
        )}

        {/* ── My digital team ─────────────────────────────── */}
        <section className="mb-6">
          <SectionHeader
            title={t("home.sectionTeams")}
            cta={
              <Link
                to="/templates"
                className="inline-flex items-center gap-1.5 rounded-full bg-[oklch(0.78_0.16_70_/_0.2)] border border-[oklch(0.55_0.10_65_/_0.4)] px-3 py-1.5 text-[12px] font-medium text-[oklch(0.88_0.10_70)] hover:bg-[oklch(0.78_0.16_70_/_0.28)] active:scale-95 transition"
              >
                <Plus className="h-3.5 w-3.5" />
                {t("home.quickAction")}
              </Link>
            }
          />
          {!teams ? (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {[0, 1].map((i) => (
                <div key={i} className="shimmer h-[110px] rounded-2xl" />
              ))}
            </div>
          ) : teams.length === 0 ? (
            <EmptyTeamHint />
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {teams.slice(0, 4).map((team) => (
                <EmployeeCard key={team.teamId} team={team} />
              ))}
            </div>
          )}
        </section>

        {/* ── Awaiting your decision ──────────────────────── */}
        {pending > 0 && (
          <section className="mb-6">
            <SectionHeader
              title={t("home.sectionApprovals")}
              cta={
                <Link
                  to="/approvals"
                  className="text-[11px] tracking-wide text-[var(--color-accent)] hover:underline"
                >
                  {t("home.seeAll")}
                </Link>
              }
            />
            <GlassCard tone="accent" className="flex items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[oklch(0.68_0.20_25_/_0.2)]">
                <Inbox className="h-4.5 w-4.5 text-[oklch(0.78_0.18_25)]" />
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-[14px] font-semibold text-[var(--color-text-primary)]">
                  {pending} 项需要你处理
                </p>
                <p className="text-[12px] text-[var(--color-text-secondary)]">
                  点开就能批，不用切到控制台
                </p>
              </div>
              <Link
                to="/approvals"
                className="flex h-9 w-9 items-center justify-center rounded-full bg-[var(--color-text-primary)] text-[var(--color-bg-0)] active:scale-95 transition"
                aria-label="查看待审批"
              >
                <ArrowRight className="h-4 w-4" />
              </Link>
            </GlassCard>
          </section>
        )}

        {/* ── Recent runs ─────────────────────────────────── */}
        {recentRuns.length > 0 && (
          <section className="mb-6">
            <SectionHeader title={t("home.sectionRuns")} />
            <GlassCard padding="sm" className="divide-y divide-[oklch(0.30_0.015_50_/_0.4)]">
              {recentRuns.slice(0, 4).map((run) => (
                <Link
                  key={run.runId}
                  to={`/runs/${run.workspaceId ?? "_"}/${run.runId}`}
                  className="flex items-center gap-3 px-2 py-3 active:bg-[oklch(0.30_0.02_50_/_0.2)] rounded-lg"
                >
                  <ActivityIcon className="h-4 w-4 text-[var(--color-text-muted)]" />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[13px] font-medium text-[var(--color-text-primary)]">
                      {run.taskTitle ?? run.scenario ?? "运行"}
                    </p>
                    <p className="text-[11px] text-[var(--color-text-muted)]">
                      {formatRelativeTime(run.createdAt ?? run.updatedAt)}
                    </p>
                  </div>
                  <StatusPill status={run.status} />
                </Link>
              ))}
            </GlassCard>
          </section>
        )}

        {/* ── Insights ────────────────────────────────────── */}
        {insights.length > 0 && (
          <section className="mb-6">
            <SectionHeader title={t("home.sectionAttention")} />
            <div className="space-y-3">
              {insights.slice(0, 3).map((ins) => (
                <InsightRow key={ins.insightId} insight={ins} />
              ))}
            </div>
          </section>
        )}

        {/* ── Recommended scenarios ───────────────────────── */}
        {scenarios && scenarios.length > 0 && (
          <section>
            <SectionHeader
              title={t("home.sectionTemplates")}
              cta={
                <Link
                  to="/templates"
                  className="text-[11px] tracking-wide text-[var(--color-accent)] hover:underline"
                >
                  {t("home.seeAll")}
                </Link>
              }
            />
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {scenarios.slice(0, 4).map((s) => (
                <Link key={s.scenarioId} to={`/templates`}>
                  <GlassCard interactive className="flex items-start gap-3">
                    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-[oklch(0.70_0.14_210_/_0.3)] to-[oklch(0.70_0.16_280_/_0.2)] text-[15px]">
                      ✨
                    </div>
                    <div className="min-w-0">
                      <p className="truncate text-[13px] font-semibold text-[var(--color-text-primary)]">
                        {s.name}
                      </p>
                      <p className="line-clamp-2 text-[11px] text-[var(--color-text-secondary)]">
                        {s.description}
                      </p>
                    </div>
                  </GlassCard>
                </Link>
              ))}
            </div>
          </section>
        )}
      </div>
    </AuroraBackground>
  );
}

function SectionHeader({ title, cta }: { title: string; cta?: React.ReactNode }) {
  return (
    <div className="mb-3 flex items-center justify-between px-0.5">
      <h2 className="text-[13px] font-semibold tracking-[0.15em] uppercase text-[var(--color-text-secondary)]">
        {title}
      </h2>
      {cta}
    </div>
  );
}

function Stat({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div className="flex flex-col">
      <span
        className={cn(
          "font-display text-[28px] leading-none",
          accent ? "text-[oklch(0.85_0.16_70)]" : "text-[var(--color-text-primary)]",
        )}
      >
        {value}
      </span>
      <span className="mt-1 text-[11px] tracking-wide text-[var(--color-text-muted)]">
        {label}
      </span>
    </div>
  );
}

function EmptyTeamHint() {
  const { t } = useI18n();
  return (
    <GlassCard tone="accent" grain className="flex flex-col items-start gap-3">
      <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-[oklch(0.78_0.16_70_/_0.25)]">
        <Sparkles className="h-5 w-5 text-[oklch(0.88_0.12_70)]" />
      </div>
      <div>
        <p className="text-[15px] font-semibold text-[var(--color-text-primary)]">
          {t("home.emptyTeams")}
        </p>
        <p className="mt-1 text-[12px] leading-relaxed text-[var(--color-text-secondary)]">
          {t("home.emptyTeamsHint")}
        </p>
      </div>
      <Link
        to="/templates"
        className="mt-1 inline-flex items-center gap-1.5 rounded-full bg-[var(--color-text-primary)] px-4 py-2 text-[12px] font-semibold text-[var(--color-bg-0)] active:scale-95 transition"
      >
        <Wand2 className="h-3.5 w-3.5" />
        选个场景
      </Link>
    </GlassCard>
  );
}

function InsightRow({ insight }: { insight: BusinessInsightRecord }) {
  const sev = (insight.severity ?? "low").toLowerCase();
  const tone =
    sev === "high"
      ? "border-[oklch(0.68_0.20_25_/_0.4)] bg-[oklch(0.68_0.20_25_/_0.08)]"
      : sev === "medium"
        ? "border-[oklch(0.78_0.16_85_/_0.4)] bg-[oklch(0.78_0.16_85_/_0.08)]"
        : "border-[oklch(0.30_0.015_50_/_0.4)]";

  return (
    <GlassCard padding="md" className={cn("border", tone)}>
      <p className="text-[13px] font-semibold text-[var(--color-text-primary)]">
        {insight.title}
      </p>
      {insight.finding && (
        <p className="mt-1 text-[12px] leading-relaxed text-[var(--color-text-secondary)]">
          {insight.finding}
        </p>
      )}
      {insight.suggestedAction && (
        <p className="mt-2 text-[11px] text-[var(--color-accent)]">
          建议：{insight.suggestedAction}
        </p>
      )}
    </GlassCard>
  );
}

function pickGreeting(): string {
  const h = new Date().getHours();
  if (h < 11) return "早上好";
  if (h < 18) return "下午好";
  return "晚上好";
}
