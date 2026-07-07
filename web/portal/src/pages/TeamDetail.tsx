import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { portalApi } from "@/api/portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { StatusPill } from "@/components/StatusPill";
import { Users, Activity as ActivityIcon } from "lucide-react";

export default function TeamDetail() {
  const { teamId } = useParams();
  const [blueprint, setBlueprint] = useState<any | null>(null);
  const [team, setTeam] = useState<any | null>(null);
  const [recentRuns, setRecentRuns] = useState<any[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!teamId) return;
    let alive = true;
    let mountedWorkspace: string | null = null;

    (async () => {
      try {
        const home = await portalApi.getBusinessHome();
        if (!alive) return;
        const ws = home.workspaceId ?? home.workspaces?.[0]?.workspaceId;
        if (!ws) {
          setError("找不到工作区");
          return;
        }
        mountedWorkspace = ws;

        // Team basic info from list.
        const teamsRes = await portalApi.getBusinessTeams(ws);
        if (alive) {
          setTeam((teamsRes.teams ?? []).find((t) => t.teamId === teamId) ?? null);
        }

        // Full blueprint (members, etc.).
        const bp = await portalApi.getBusinessTeamBlueprint(ws, teamId);
        if (alive) setBlueprint(bp);

        // Recent runs for this team.
        const runsRes = await portalApi.getBusinessRuns(ws, 10);
        if (alive) {
          setRecentRuns(
            (runsRes.runs ?? []).filter((r) => r.teamId === teamId).slice(0, 6),
          );
        }
      } catch (e: any) {
        if (alive) setError(String(e?.message ?? e));
      }
    })();

    return () => {
      alive = false;
      void mountedWorkspace;
    };
  }, [teamId]);

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        {error && (
          <GlassCard className="mb-3 border border-[oklch(0.68_0.20_25_/_0.35)]">
            <p className="text-[12px] text-[var(--color-text-secondary)]">{error}</p>
          </GlassCard>
        )}

        {!team ? (
          <div className="shimmer h-40 rounded-2xl" />
        ) : (
          <>
            <GlassCard tone="strong" grain className="mb-4">
              <div className="flex items-start gap-4">
                <div className="flex h-16 w-16 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-[oklch(0.78_0.16_70_/_0.4)] to-[oklch(0.55_0.10_60_/_0.25)] text-[22px] font-semibold">
                  {(team.name ?? "·").slice(0, 2)}
                </div>
                <div className="min-w-0 flex-1">
                  <h2 className="font-display text-[24px] font-medium leading-tight">
                    {team.name}
                  </h2>
                  <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
                    {team.scenario ?? "数字员工"}
                  </p>
                  <div className="mt-2 flex items-center gap-2">
                    <span className="status-dot online" />
                    <span className="text-[12px] text-[var(--color-text-secondary)]">就绪</span>
                    <StatusPill status={team.status} />
                  </div>
                </div>
              </div>

              <div className="mt-4 grid grid-cols-3 gap-3 border-t border-[oklch(0.30_0.015_50_/_0.4)] pt-3">
                <Stat label="活跃版本" value={`v${team.activeVersion}`} />
                <Stat label="版本数" value={team.versionCount ?? 0} />
                <Stat label="状态" value={team.status ?? "—"} />
              </div>
            </GlassCard>

            {Array.isArray(blueprint?.agents) && blueprint.agents.length > 0 && (
              <section className="mb-4">
                <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-[var(--color-text-muted)]">
                  成员
                </h3>
                <div className="space-y-2">
                  {blueprint.agents.map((m: any) => (
                    <GlassCard key={m.agentId ?? m.name} padding="sm" className="flex items-center gap-3">
                      <Users className="h-4 w-4 text-[var(--color-text-muted)]" />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-[13px] font-medium text-[var(--color-text-primary)]">
                          {m.name}
                        </p>
                        <p className="text-[11px] text-[var(--color-text-muted)]">
                          {m.role ?? m.description}
                        </p>
                      </div>
                    </GlassCard>
                  ))}
                </div>
              </section>
            )}

            {Array.isArray(recentRuns) && recentRuns.length > 0 && (
              <section>
                <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-[var(--color-text-muted)]">
                  最近运行
                </h3>
                <GlassCard padding="sm" className="divide-y divide-[oklch(0.30_0.015_50_/_0.4)]">
                  {recentRuns.map((r) => (
                    <div key={r.runId} className="flex items-center gap-3 px-2 py-2.5">
                      <ActivityIcon className="h-4 w-4 text-[var(--color-text-muted)]" />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-[12px] font-medium">{r.taskTitle ?? r.scenario ?? "运行"}</p>
                      </div>
                      <StatusPill status={r.status} />
                    </div>
                  ))}
                </GlassCard>
              </section>
            )}
          </>
        )}
      </div>
    </AuroraBackground>
  );
}

function Stat({ label, value }: { label: string; value: any }) {
  return (
    <div className="flex flex-col">
      <span className="font-display text-[22px] leading-none text-[var(--color-text-primary)]">
        {String(value)}
      </span>
      <span className="mt-0.5 text-[10px] tracking-wider text-[var(--color-text-muted)]">
        {label}
      </span>
    </div>
  );
}
