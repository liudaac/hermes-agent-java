import { useEffect, useMemo, useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import {
  Users,
  Layers,
  Rocket,
  ArrowRight,
  Check,
  X,
  PartyPopper,
} from "lucide-react";
import { api, type BusinessTeamCard, type BusinessScenarioRecord } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

/**
 * FirstTimeOnboardingDrawer
 *
 * Triggered by `?firstTime=1&workspaceId=<id>` (e.g. from the H5 marketing
 * landing page → /api/v1/business/scenario-templates/{id}/clone → redirect).
 *
 * Shows a 3-step onboarding for a freshly cloned business workspace:
 *   1. 团队已就绪 — list the digital employees in this workspace
 *   2. 看看场景模板 — list scenarios cloned in (jumps to TemplateGallery)
 *   3. 跑第一笔任务 — execute the first scenario or jump to Playground
 *
 * Idempotency: per-workspace one-time, tracked via localStorage.
 */

const LS_PREFIX = "hermes:firstTime:";

type Step = 1 | 2 | 3;

export default function FirstTimeOnboardingDrawer() {
  const navigate = useNavigate();
  const location = useLocation();

  const params = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const isFirstTime = params.get("firstTime") === "1";
  const workspaceId = params.get("workspaceId") || "";

  const [open, setOpen] = useState(false);
  const [step, setStep] = useState<Step>(1);
  const [teams, setTeams] = useState<BusinessTeamCard[]>([]);
  const [scenarios, setScenarios] = useState<BusinessScenarioRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [executing, setExecuting] = useState(false);

  // Open guard: only when firstTime=1 AND we have a workspaceId AND not seen.
  useEffect(() => {
    if (!isFirstTime || !workspaceId) return;
    try {
      const seen = localStorage.getItem(LS_PREFIX + workspaceId);
      if (seen) {
        cleanupQuery();
        return;
      }
    } catch {
      /* localStorage unavailable — show anyway */
    }
    setOpen(true);
    setStep(1);
    setLoading(true);
    Promise.all([
      api.getBusinessTeams(workspaceId).catch(() => ({ teams: [] as BusinessTeamCard[] })),
      api.getBusinessScenarios(workspaceId).catch(() => ({ scenarios: [] as BusinessScenarioRecord[] })),
    ])
      .then(([t, s]) => {
        setTeams((t as { teams?: BusinessTeamCard[] }).teams ?? []);
        setScenarios((s as { scenarios?: BusinessScenarioRecord[] }).scenarios ?? []);
      })
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isFirstTime, workspaceId]);

  const cleanupQuery = () => {
    const next = new URLSearchParams(location.search);
    next.delete("firstTime");
    const qs = next.toString();
    navigate(`${location.pathname}${qs ? `?${qs}` : ""}`, { replace: true });
  };

  const dismiss = () => {
    try {
      if (workspaceId) localStorage.setItem(LS_PREFIX + workspaceId, String(Date.now()));
    } catch {
      /* ignore */
    }
    setOpen(false);
    cleanupQuery();
  };

  const goTo = (path: string) => {
    dismiss();
    navigate(path);
  };

  const tryRunFirstScenario = async () => {
    if (!workspaceId || scenarios.length === 0) {
      goTo(`/playground?workspaceId=${encodeURIComponent(workspaceId)}`);
      return;
    }
    const first = scenarios[0];
    setExecuting(true);
    try {
      const res = await api.executeBusinessScenario(
        workspaceId,
        first.scenarioId,
        "请帮我跑一次第一笔任务，看看团队是怎么干活的",
      );
      dismiss();
      const runId = (res as { runId?: string })?.runId;
      if (runId) {
        navigate(`/runs/${workspaceId}/${runId}`);
      } else {
        navigate(`/business-portal`);
      }
    } catch {
      // fall back: just send to playground with the workspace context
      goTo(`/playground?workspaceId=${encodeURIComponent(workspaceId)}`);
    } finally {
      setExecuting(false);
    }
  };

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[60] flex items-end justify-center bg-black/55 backdrop-blur-sm sm:items-center"
      onClick={dismiss}
      role="dialog"
      aria-modal="true"
      aria-labelledby="ftod-title"
    >
      <div
        className="relative w-full max-w-lg overflow-hidden rounded-t-2xl border border-border bg-background shadow-2xl sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="relative overflow-hidden bg-gradient-to-br from-orange-500/15 via-transparent to-amber-400/10 px-5 py-5">
          <button
            onClick={dismiss}
            aria-label="关闭"
            className="absolute right-3 top-3 inline-flex h-7 w-7 items-center justify-center rounded-full bg-background/70 text-muted-foreground hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
          <div className="flex items-center gap-2 text-xs uppercase tracking-normal sm:tracking-[0.18em] text-muted-foreground">
            <PartyPopper className="h-4 w-4 text-orange-500" />
            欢迎加入
          </div>
          <h2 id="ftod-title" className="mt-1.5 text-xl font-semibold tracking-tight md:text-2xl">
            你的数字员工团队，已经就位
          </h2>
          <p className="mt-1 text-sm text-muted-foreground">
            空间 <span className="font-mono">{workspaceId}</span> 已开通。花 30 秒看看怎么用。
          </p>
          {/* Step pills */}
          <div className="mt-4 flex items-center gap-1.5">
            {[1, 2, 3].map((n) => (
              <span
                key={n}
                className={cn(
                  "h-1.5 flex-1 rounded-full transition-colors",
                  step >= n ? "bg-orange-500" : "bg-muted",
                )}
              />
            ))}
          </div>
        </div>

        {/* Body */}
        <div className="max-h-[60vh] overflow-y-auto px-5 py-4">
          {step === 1 && <StepTeams teams={teams} loading={loading} />}
          {step === 2 && <StepScenarios scenarios={scenarios} loading={loading} />}
          {step === 3 && <StepRun scenarioName={scenarios[0]?.name} hasScenario={scenarios.length > 0} />}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between gap-2 border-t border-border bg-muted/30 px-5 py-3">
          <Button variant="ghost" size="sm" onClick={dismiss} className="text-muted-foreground">
            稍后再说
          </Button>
          <div className="flex items-center gap-2">
            {step > 1 && (
              <Button variant="outline" size="sm" onClick={() => setStep((step - 1) as Step)}>
                上一步
              </Button>
            )}
            {step < 3 ? (
              <Button size="sm" onClick={() => setStep((step + 1) as Step)}>
                下一步
                <ArrowRight className="ml-1 h-3.5 w-3.5" />
              </Button>
            ) : scenarios.length > 0 ? (
              <Button size="sm" onClick={tryRunFirstScenario} disabled={executing}>
                <Rocket className="mr-1 h-3.5 w-3.5" />
                {executing ? "启动中…" : "跑第一笔任务"}
              </Button>
            ) : (
              <Button size="sm" onClick={() => goTo(`/business-portal/templates`)}>
                <Layers className="mr-1 h-3.5 w-3.5" />
                先去挑模板
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/* ---------- Step views ---------- */

function StepTeams({ teams, loading }: { teams: BusinessTeamCard[]; loading: boolean }) {
  return (
    <div>
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold">
        <Users className="h-4 w-4 text-orange-500" />
        团队成员
        {teams.length > 0 && (
          <Badge variant="outline" className="font-mono text-xs">
            {teams.length} 支团队
          </Badge>
        )}
      </div>
      {loading ? (
        <SkeletonRows />
      ) : teams.length === 0 ? (
        <EmptyHint text="还没有团队。下一步可以从场景模板一键创建一支数字员工团队。" />
      ) : (
        <ul className="space-y-2">
          {teams.slice(0, 5).map((team) => (
            <li
              key={team.teamId}
              className="flex items-start gap-3 rounded-md border border-border/60 px-3 py-2"
            >
              <Check className="mt-0.5 h-3.5 w-3.5 text-emerald-500" />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{team.name || team.teamId}</div>
                <div className="truncate text-xs text-muted-foreground">
                  {team.scenario ? `场景：${team.scenario}` : "通用团队"}
                  {typeof team.versionCount === "number" && ` · v${team.activeVersion}/${team.versionCount}`}
                </div>
              </div>
              <Badge variant={team.status === "ACTIVE" ? "success" : "outline"} className="text-xs">
                {team.status || "READY"}
              </Badge>
            </li>
          ))}
          {teams.length > 5 && (
            <li className="text-center text-xs text-muted-foreground">
              还有 {teams.length - 5} 支团队…
            </li>
          )}
        </ul>
      )}
      <p className="mt-3 text-xs text-muted-foreground">
        每位"数字员工"都是预置好技能、工具与风险边界的角色，背后由 Hermes 编排执行。
      </p>
    </div>
  );
}

function StepScenarios({
  scenarios,
  loading,
}: {
  scenarios: BusinessScenarioRecord[];
  loading: boolean;
}) {
  return (
    <div>
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold">
        <Layers className="h-4 w-4 text-orange-500" />
        场景剧本
        {scenarios.length > 0 && (
          <Badge variant="outline" className="font-mono text-xs">
            {scenarios.length} 个场景
          </Badge>
        )}
      </div>
      {loading ? (
        <SkeletonRows />
      ) : scenarios.length === 0 ? (
        <EmptyHint text="还没有场景。前往「场景模板」一键克隆一个真实业务场景。" />
      ) : (
        <ul className="space-y-2">
          {scenarios.slice(0, 5).map((s) => (
            <li
              key={s.scenarioId}
              className="rounded-md border border-border/60 px-3 py-2"
            >
              <div className="flex items-center gap-2">
                <Check className="h-3.5 w-3.5 text-emerald-500" />
                <div className="min-w-0 flex-1 truncate text-sm font-medium">
                  {s.name || s.scenarioId}
                </div>
                {s.slaName && (
                  <Badge variant="outline" className="text-xs">
                    {s.slaName}
                  </Badge>
                )}
              </div>
              {s.description && (
                <p className="mt-1 line-clamp-2 pl-5 text-xs text-muted-foreground">
                  {s.description}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
      <p className="mt-3 text-xs text-muted-foreground">
        场景定义"业务怎么跑"——成功标准、审批规则、SLA、协作方式都是从模板里继承的。
      </p>
    </div>
  );
}

function StepRun({
  scenarioName,
  hasScenario,
}: {
  scenarioName?: string;
  hasScenario: boolean;
}) {
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-sm font-semibold">
        <Rocket className="h-4 w-4 text-orange-500" />
        跑第一笔任务
      </div>
      {hasScenario ? (
        <div className="rounded-md border border-orange-500/30 bg-orange-500/5 p-3 text-sm">
          <p className="font-medium">即将启动场景</p>
          <p className="mt-1 truncate text-orange-600 dark:text-orange-400">
            {scenarioName ?? "（未命名场景）"}
          </p>
          <p className="mt-2 text-xs text-muted-foreground">
            点击右下角"跑第一笔任务"，系统会执行一次完整的端到端流程，
            你会被带到 Run 详情页，看团队是怎么一步一步把活干完的。
          </p>
        </div>
      ) : (
        <div className="rounded-md border border-border/60 p-3 text-sm">
          <p className="font-medium">空间里还没有可执行的场景</p>
          <p className="mt-1 text-xs text-muted-foreground">
            可以先去「场景模板」一键克隆一个真实业务剧本，回来就能跑了。
          </p>
        </div>
      )}
      <ul className="space-y-1.5 text-xs text-muted-foreground">
        <li className="flex items-start gap-2">
          <Check className="mt-0.5 h-3 w-3 flex-shrink-0 text-emerald-500" />
          高风险动作会自动暂停，等你在「待审批」一键放行
        </li>
        <li className="flex items-start gap-2">
          <Check className="mt-0.5 h-3 w-3 flex-shrink-0 text-emerald-500" />
          每一步都有"故事化时间线"，不会把原始 JSON 甩你脸上
        </li>
        <li className="flex items-start gap-2">
          <Check className="mt-0.5 h-3 w-3 flex-shrink-0 text-emerald-500" />
          后续可以从「自进化」里看团队怎么自我打磨
        </li>
      </ul>
    </div>
  );
}

/* ---------- bits ---------- */

function SkeletonRows() {
  return (
    <div className="space-y-2">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="h-10 animate-pulse rounded-md bg-muted" />
      ))}
    </div>
  );
}

function EmptyHint({ text }: { text: string }) {
  return (
    <div className="rounded-md border border-dashed border-border/60 px-3 py-4 text-center text-xs text-muted-foreground">
      {text}
    </div>
  );
}
