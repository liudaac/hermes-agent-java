import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Sparkles, Layers, ArrowRight, Activity, TrendingUp, ShieldAlert,
  AlertTriangle, Users, Briefcase, Bot, Zap,
} from "lucide-react";
import { api, type BusinessHomeResponse, type BusinessHomeTrendPoint } from "@/lib/api";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useToast } from "@/hooks/useToast";
import MetricCardWithSparkline from "@/components/business/MetricCardWithSparkline";
import ResponseDistribution from "@/components/business/ResponseDistribution";
import LiveRunsTicker from "@/components/business/LiveRunsTicker";

type BadgeVariant = "default" | "success" | "warning" | "destructive" | "outline" | "info" | "live";

export default function BusinessPortalHome() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [home, setHome] = useState<BusinessHomeResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    api.getBusinessHome()
      .then((res) => { if (alive) setHome(res); })
      .catch((e) => showToast(`加载首页失败：${String(e)}`, "error"))
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [showToast]);

  const summary = home?.summary;
  const today = home?.today;
  const trends = home?.trends;
  const risk = home?.risk;

  return (
    <div className="space-y-5">
      <section className="aurora-bg relative overflow-hidden rounded-2xl border border-border/60 px-5 py-6 md:px-7 md:py-9">
        <div className="relative flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <div className="max-w-xl">
            <div className="flex items-center gap-2 text-xs uppercase tracking-normal sm:tracking-[0.18em] opacity-70">
              <Briefcase className="h-3.5 w-3.5" />
              <span>Business Portal · 首页</span>
              <span className="hidden h-1 w-1 rounded-full bg-current sm:inline-block" />
              <span className="hidden sm:inline">数字员工总控室</span>
            </div>
            <h1 className="mt-2 text-2xl font-semibold leading-tight tracking-tight md:text-3xl lg:text-4xl">
              你的数字员工团队，
              <span className="bg-gradient-to-r from-foreground via-foreground/85 to-foreground/65 bg-clip-text text-transparent">
                今天表现如何
              </span>
            </h1>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground md:text-base">
              {loading ? "加载中…" : summary && summary.workspaceCount > 0
                ? `${summary.workspaceCount} 个工作空间 · ${summary.teamCount} 支团队 · 今日 ${today?.processedTasks ?? 0} 个任务`
                : "还没有数据。先到「场景模板」一键搭建一个团队。"}
            </p>
            {risk && summary && summary.workspaceCount > 0 && (
              <div className="mt-3 inline-flex items-center gap-2 rounded-full border border-border/80 bg-background/55 px-3 py-1 backdrop-blur">
                <span className={cn("relative h-2 w-2 rounded-full",
                  risk.level === "HIGH" ? "bg-rose-500"
                    : risk.level === "MEDIUM" ? "bg-amber-500" : "bg-emerald-500")}>
                  {risk.level !== "LOW" && (
                    <span className={cn("status-pulse absolute inset-0 rounded-full",
                      risk.level === "HIGH" ? "text-rose-500" : "text-amber-500")} />
                  )}
                </span>
                <span className="font-mono text-xs">
                  健康度：<span className={cn("ml-1 font-semibold",
                    risk.level === "HIGH" ? "text-rose-500"
                      : risk.level === "MEDIUM" ? "text-amber-500" : "text-emerald-500")}>
                    {risk.level === "HIGH" ? "需关注" : risk.level === "MEDIUM" ? "良好" : "优秀"}
                  </span>
                </span>
              </div>
            )}
            {summary && summary.workspaceCount > 0 && (
              <div className="mt-2">
                <LiveRunsTicker />
              </div>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="default" size="sm" onClick={() => navigate("/business-portal/templates")}>
              <Layers className="mr-1.5 h-3.5 w-3.5" /> 从模板开始 <ArrowRight className="ml-1 h-3.5 w-3.5" />
            </Button>
            <Button variant="outline" size="sm" onClick={() => navigate("/business-portal/agents")}>
              <Sparkles className="mr-1.5 h-3.5 w-3.5" /> 浏览数字员工
            </Button>
            <Button variant="outline" size="sm" onClick={() => navigate("/business-portal/approvals")}>
              <ShieldAlert className="mr-1.5 h-3.5 w-3.5" /> 待审批
              {(summary?.pendingApprovals ?? 0) > 0 && (
                <Badge variant="warning" className="ml-1.5 px-1.5 py-0 text-xs font-mono">
                  {summary?.pendingApprovals}
                </Badge>
              )}
            </Button>
          </div>
        </div>
      </section>

      <section className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <MetricCardWithSparkline label="今日任务" value={today?.processedTasks ?? "—"}
          icon={Activity} trend={trends?.tasks} delta={pctDelta(trends?.tasks)} hint="vs 昨日" />
        <MetricCardWithSparkline label="完成任务"
          value={trends?.completions?.[trends.completions.length - 1]?.value ?? "—"}
          icon={TrendingUp} trend={trends?.completions} tone="success"
          delta={pctDelta(trends?.completions)} hint="vs 昨日" />
        <MetricCardWithSparkline label="失败任务" value={today?.failedRuns ?? 0}
          icon={AlertTriangle} trend={trends?.failures}
          tone={(today?.failedRuns ?? 0) > 0 ? "danger" : "default"}
          delta={pctDelta(trends?.failures)} hint="vs 昨日" />
        <MetricCardWithSparkline label="自动完成率"
          value={`${today?.autoCompletionRate ?? 0}%`}
          icon={Zap} tone={(today?.autoCompletionRate ?? 0) >= 70 ? "success" : "default"}
          hint="自动 ÷ 总任务" />
      </section>

      <section className="grid gap-3 md:grid-cols-3">
        <div className="md:col-span-2">
          <ResponseDistribution data={home?.responseDistribution} />
        </div>
        <NeedsAttentionCard home={home} loading={loading} onNavigate={navigate} />
      </section>

      <section>
        <div className="mb-2 flex items-center justify-between">
          <h2 className="flex items-center gap-1.5 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
            <Bot className="h-3.5 w-3.5" /> 最近运行
          </h2>
          <Button variant="ghost" size="sm" onClick={() => navigate("/business-portal")}>
            进入工作台 <ArrowRight className="ml-1 h-3.5 w-3.5" />
          </Button>
        </div>
        <Card>
          <CardContent className="space-y-2 p-3">
            {(home?.recentRuns ?? []).length === 0 ? (
              <p className="py-8 text-center text-xs text-muted-foreground">
                还没有运行记录。试着用「场景模板」克隆一个团队，跑第一笔任务。
              </p>
            ) : (
              (home?.recentRuns ?? []).map((run) => (
                <div key={run.runId}
                  className="flex flex-col gap-1 rounded-md border border-border/60 p-2 hover:border-border cursor-pointer transition-colors md:flex-row md:items-center md:gap-3"
                  onClick={() => run.workspaceId && navigate(`/runs/${run.workspaceId}/${run.runId}`)}>
                  <Badge variant={runStatusVariant(run.status)} className="self-start text-xs">
                    {run.status ?? "?"}
                  </Badge>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm">{run.taskTitle ?? run.runId}</div>
                    <div className="truncate text-xs text-muted-foreground">{run.resultSummary}</div>
                  </div>
                  <div className="text-right">
                    <div className="font-mono text-xs text-muted-foreground">{run.teamId}</div>
                    <div className="font-mono text-xs text-muted-foreground">{formatTime(run.createdAt)}</div>
                  </div>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </section>
    </div>
  );
}

function NeedsAttentionCard({ home, loading, onNavigate }: {
  home: BusinessHomeResponse | null; loading: boolean; onNavigate: (path: string) => void;
}) {
  const items = home?.needsAttention ?? [];
  return (
    <Card>
      <CardContent className="p-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            <ShieldAlert className="h-3.5 w-3.5" /> 需要关注
          </div>
          {(home?.summary?.teamCount ?? 0) > 0 && (
            <Badge variant="outline" className="font-mono text-xs">
              <Users className="mr-1 h-3 w-3" /> {home?.summary?.teamCount} 团队
            </Badge>
          )}
        </div>
        {loading ? (
          <div className="mt-3 space-y-2">
            {Array.from({ length: 3 }).map((_, i) => <div key={i} className="h-10 rounded bg-muted animate-pulse" />)}
          </div>
        ) : items.length === 0 ? (
          <p className="mt-4 text-xs text-muted-foreground">一切顺利 ✨<br />没有待处理的紧急事项</p>
        ) : (
          <ul className="mt-3 space-y-2">
            {items.slice(0, 5).map((item) => (
              <li key={item.id}
                onClick={() => onNavigate("/business-portal/approvals")}
                className="cursor-pointer rounded-md border border-border/60 p-2 text-sm hover:border-border transition-colors">
                <p className="font-medium leading-snug">{item.title}</p>
                <p className="mt-0.5 text-xs text-muted-foreground">{item.description}</p>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function pctDelta(points?: BusinessHomeTrendPoint[]): number | undefined {
  if (!points || points.length < 2) return undefined;
  const last = points[points.length - 1].value;
  const prev = points[points.length - 2].value;
  if (prev === 0) return last > 0 ? 100 : 0;
  return ((last - prev) / prev) * 100;
}

function runStatusVariant(status?: string): BadgeVariant {
  const s = (status ?? "").toUpperCase();
  if (["COMPLETED", "SUCCESS", "DONE"].includes(s)) return "success";
  if (["RUNNING", "PROCESSING"].includes(s)) return "info";
  if (["FAILED", "ERROR"].includes(s)) return "destructive";
  if (["PENDING", "AWAITING_APPROVAL"].includes(s)) return "warning";
  return "outline";
}

function formatTime(raw?: string): string {
  if (!raw) return "";
  try {
    const d = new Date(raw);
    if (Number.isNaN(d.getTime())) return raw;
    return d.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
  } catch {
    return raw;
  }
}
