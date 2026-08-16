import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { portalApi } from "@/api/portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { StatusPill } from "@/components/StatusPill";
import { useWorkspace } from "@/hooks/useWorkspace";
import { useActiveHarnesses } from "@/hooks/useActiveHarnesses";
import { Users, Activity as ActivityIcon, Play, Loader2 } from "lucide-react";
import { formatRelativeTime } from "@hermes/ui";

export default function TeamDetail() {
  const { teamId } = useParams();
  const navigate = useNavigate();
  const { workspaceId } = useWorkspace();
  const { findForTeam } = useActiveHarnesses();
  const [blueprint, setBlueprint] = useState<any | null>(null);
  const [team, setTeam] = useState<any | null>(null);
  const [recentRuns, setRecentRuns] = useState<any[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [launching, setLaunching] = useState(false);

  useEffect(() => {
    if (!teamId || !workspaceId) return;
    let alive = true;

    (async () => {
      try {
        const teamsRes = await portalApi.getBusinessTeams(workspaceId);
        if (alive) {
          setTeam((teamsRes.teams ?? []).find((t) => t.teamId === teamId) ?? null);
        }
        const bp = await portalApi.getBusinessTeamBlueprint(workspaceId, teamId);
        if (alive) setBlueprint(bp);
        const runsRes = await portalApi.getBusinessRuns(workspaceId, 10);
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
    };
  }, [teamId, workspaceId]);

  const launchTask = async () => {
    if (!workspaceId || !team?.scenarioId) {
      // No scenario linked - go to templates to pick one
      navigate("/templates");
      return;
    }
    setLaunching(true);
    try {
      const res = await portalApi.executeBusinessScenario(
        workspaceId,
        team.scenarioId,
        "",
      );
      const runId = (res as { runId?: string; run?: { runId?: string } }).runId
        ?? (res as { run?: { runId?: string } }).run?.runId;
      if (runId) {
        navigate(`/runs/${workspaceId}/${runId}`);
      } else {
        navigate("/runs");
      }
    } catch (e: any) {
      setError(String(e?.message ?? e));
      setLaunching(false);
    }
  };

  const harness = teamId ? findForTeam(teamId) : undefined;

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        {error && (
          <GlassCard className="mb-3 border border-border">
            <p className="text-[12px] text-muted-foreground">{error}</p>
          </GlassCard>
        )}

        {!team ? (
          <div className="shimmer h-40 rounded-2xl" />
        ) : (
          <>
            {/* Header card */}
            <GlassCard grain className="mb-4">
              <div className="flex items-start gap-4">
                <div className="flex h-16 w-16 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-primary/40 to-primary/20 text-[22px] font-semibold">
                  {(team.name ?? "·").slice(0, 2)}
                </div>
                <div className="min-w-0 flex-1">
                  <h2 className="text-[24px] font-medium leading-tight">
                    {team.name}
                  </h2>
                  <p className="mt-1 text-[13px] text-muted-foreground">
                    {team.scenario ?? "数字员工"}
                  </p>
                  <div className="mt-2 flex items-center gap-2">
                    <span className="status-dot online" />
                    <span className="text-[12px] text-muted-foreground">就绪</span>
                    <StatusPill status={harness?.status ?? team.status} />
                  </div>
                </div>
              </div>

              <div className="mt-4 grid grid-cols-3 gap-3 border-t border-border pt-3">
                <Stat label="活跃版本" value={`v${team.activeVersion}`} />
                <Stat label="版本数" value={team.versionCount ?? 0} />
                <Stat label="状态" value={harness?.status ?? team.status ?? "-"} />
              </div>

              {/* Launch task button */}
              <button
                type="button"
                onClick={launchTask}
                disabled={launching}
                className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl bg-primary py-3 text-[14px] font-semibold text-primary-foreground active:scale-[0.98] transition disabled:opacity-60"
              >
                {launching ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Play className="h-4 w-4" />
                )}
                {launching ? "启动中..." : "启动任务"}
              </button>
            </GlassCard>

            {/* Team members */}
            {Array.isArray(blueprint?.agents) && blueprint.agents.length > 0 && (
              <section className="mb-4">
                <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-muted-foreground">
                  成员
                </h3>
                <div className="space-y-2">
                  {blueprint.agents.map((m: any) => (
                    <GlassCard key={m.agentId ?? m.name} padding="sm" className="flex items-center gap-3">
                      <Users className="h-4 w-4 text-muted-foreground" />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-[13px] font-medium text-foreground">
                          {m.name}
                        </p>
                        <p className="text-[11px] text-muted-foreground">
                          {m.role ?? m.description}
                        </p>
                      </div>
                    </GlassCard>
                  ))}
                </div>
              </section>
            )}

            {/* Recent runs - now clickable */}
            {Array.isArray(recentRuns) && recentRuns.length > 0 && (
              <section>
                <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-muted-foreground">
                  最近运行
                </h3>
                <GlassCard padding="sm" className="divide-y divide-border">
                  {recentRuns.map((r) => (
                    <Link
                      key={r.runId}
                      to={`/runs/${r.workspaceId ?? workspaceId ?? "_"}/${r.runId}`}
                      className="flex items-center gap-3 px-2 py-2.5 active:bg-primary/10 rounded-lg"
                    >
                      <ActivityIcon className="h-4 w-4 text-muted-foreground" />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-[12px] font-medium text-foreground">
                          {r.taskTitle ?? r.scenario ?? "运行"}
                        </p>
                        <p className="text-[10px] text-muted-foreground">
                          {formatRelativeTime(r.createdAt)}
                        </p>
                      </div>
                      <StatusPill status={r.status} />
                    </Link>
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

// Need to import useParams
import { useParams } from "react-router-dom";

function Stat({ label, value }: { label: string; value: any }) {
  return (
    <div className="flex flex-col">
      <span className="text-[22px] leading-none text-foreground">
        {String(value)}
      </span>
      <span className="mt-0.5 text-[10px] tracking-wider text-muted-foreground">
        {label}
      </span>
    </div>
  );
}
