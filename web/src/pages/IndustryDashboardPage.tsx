import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  BarChart3, ArrowLeft, RefreshCw, TrendingUp, AlertTriangle, Users, Zap,
  X, ChevronRight,
} from "lucide-react";
import { api } from "@/lib/api";
import type { IndustryDashboardResponse, IndustryDrillDownRun } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useToast } from "@/hooks/useToast";
import { categoryLabel, colorsFor } from "@/components/business/templateVisuals";
import MetricCardWithSparkline from "@/components/business/MetricCardWithSparkline";

const FILTERS: { id: string; label: string }[] = [
  { id: "", label: "全部" },
  { id: "hr", label: "人力资源" },
  { id: "finance", label: "财务" },
  { id: "assets", label: "固定资产" },
  { id: "logistics", label: "物流" },
  { id: "cross-domain", label: "跨域" },
];

export default function IndustryDashboardPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [data, setData] = useState<IndustryDashboardResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [category, setCategory] = useState("");
  const [drillFilter, setDrillFilter] = useState<{ category?: string; status?: string } | null>(null);
  const [drillRuns, setDrillRuns] = useState<IndustryDrillDownRun[]>([]);
  const [drillLoading, setDrillLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api.getIndustryDashboard(category || undefined, 500);
      setData(res);
    } catch (e) {
      showToast(`加载失败：${String(e)}`, "error");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); }, [category]);

  const openDrill = async (filter: { category?: string; status?: string }) => {
    setDrillFilter(filter);
    setDrillLoading(true);
    try {
      const res = await api.getIndustryDashboardRuns({ ...filter, limit: 50 });
      setDrillRuns(res.items ?? []);
    } catch (e) {
      showToast(`加载下钻数据失败：${String(e)}`, "error");
      setDrillRuns([]);
    } finally {
      setDrillLoading(false);
    }
  };
  const closeDrill = () => { setDrillFilter(null); setDrillRuns([]); };

  const totals = useMemo(() => {
    if (!data) return { total: 0, completed: 0, failed: 0, avgLatency: 0 };
    return data.byCategory.reduce((acc, b) => ({
      total: acc.total + b.total,
      completed: acc.completed + b.completed,
      failed: acc.failed + b.failed,
      avgLatency: Math.max(acc.avgLatency, b.avgLatencyMs),
    }), { total: 0, completed: 0, failed: 0, avgLatency: 0 });
  }, [data]);

  const trendTasks = data?.trend.map(t => ({ date: t.date, value: t.tasks })) ?? [];
  const trendFailures = data?.trend.map(t => ({ date: t.date, value: t.failures })) ?? [];
  const successRate = totals.total > 0 ? Math.round((totals.completed / totals.total) * 1000) / 10 : 0;

  return (
    <div className="space-y-5">
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div>
          <div className="flex items-center gap-2 text-xs uppercase tracking-[0.18em] opacity-60">
            <BarChart3 className="h-4 w-4" /> 行业看板
          </div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">分行业经营仪表盘</h1>
          <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
            按行业切换观察任务量、成功率、响应时长和 Top 数字员工，
            掌握每个领域的真实运营节奏。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate("/business-portal")}>
            <ArrowLeft className="mr-1 h-3.5 w-3.5" /> 返回 Portal
          </Button>
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} /> 刷新
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="flex flex-wrap items-center gap-2 p-3">
          {FILTERS.map((f) => {
            const active = category === f.id;
            return (
              <button key={f.id} onClick={() => setCategory(f.id)}
                className={cn("rounded-full px-3 py-1 text-xs font-medium transition-colors",
                  active ? "bg-foreground text-background" : "bg-muted text-foreground hover:bg-muted/80")}>
                {f.label}
              </button>
            );
          })}
        </CardContent>
      </Card>

      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <MetricCardWithSparkline label="任务总量" value={totals.total} icon={TrendingUp}
          trend={trendTasks} hint="近 7 天" />
        <MetricCardWithSparkline label="成功率" value={`${successRate}%`} icon={Zap}
          tone={successRate >= 80 ? "success" : successRate >= 60 ? "default" : "warning"} hint="完成 / 总数" />
        <MetricCardWithSparkline label="失败任务" value={totals.failed} icon={AlertTriangle}
          trend={trendFailures} tone={totals.failed > 0 ? "danger" : "default"} hint="近 7 天" />
        <MetricCardWithSparkline label="峰值响应" value={formatMs(totals.avgLatency)} icon={Users} hint="行业最高均值" />
      </div>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">分行业表现</CardTitle>
        </CardHeader>
        <CardContent>
          {!data || data.byCategory.length === 0 ? (
            <p className="py-6 text-center text-xs text-muted-foreground">还没有任务数据</p>
          ) : (
            <div className="space-y-3">
              {data.byCategory.map((b) => {
                const c = colorsFor(catColor(b.category));
                return (
                  <div key={b.category}
                    onClick={() => openDrill({ category: b.category })}
                    className="rounded-md border border-border/60 p-3 cursor-pointer transition-colors hover:border-border hover:bg-muted/30">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <span className={cn("inline-flex h-2 w-2 rounded-full", c.bg)} />
                        <span className="font-medium">{categoryLabel(b.category)}</span>
                        <Badge variant="outline" className="text-[0.6rem]">{b.total} 任务</Badge>
                        <ChevronRight className="h-3.5 w-3.5 opacity-30" />
                      </div>
                      <div className="flex items-center gap-3 text-xs text-muted-foreground">
                        <span className={cn("font-mono", b.successRate >= 80 ? "text-emerald-500" : "text-amber-500")}>
                          {b.successRate}% 成功
                        </span>
                        {b.failed > 0 && (
                          <button
                            onClick={(e) => { e.stopPropagation(); openDrill({ category: b.category, status: "FAILED" }); }}
                            className="font-mono text-rose-500 hover:underline">
                            {b.failed} 失败
                          </button>
                        )}
                        <span className="font-mono">{formatMs(b.avgLatencyMs)}</span>
                      </div>
                    </div>
                    {b.total > 0 && (
                      <div className="mt-2 flex h-1.5 overflow-hidden rounded bg-muted">
                        <div className="bg-emerald-500" style={{ width: `${(b.completed / b.total) * 100}%` }} />
                        <div className="bg-rose-500" style={{ width: `${(b.failed / b.total) * 100}%` }} />
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">Top 数字员工（按任务量）</CardTitle>
        </CardHeader>
        <CardContent>
          {!data || data.topAgents.length === 0 ? (
            <p className="py-6 text-center text-xs text-muted-foreground">还没有数据</p>
          ) : (
            <div className="space-y-2">
              {data.topAgents.map((a, i) => {
                const max = data.topAgents[0]?.tasks || 1;
                const pct = (a.tasks / max) * 100;
                return (
                  <div key={a.agent} className="flex items-center gap-3">
                    <span className="w-6 text-center font-mono text-[0.65rem] text-muted-foreground">{i + 1}</span>
                    <span className="w-40 truncate text-sm">{a.agent}</span>
                    <div className="flex-1 h-1.5 overflow-hidden rounded bg-muted">
                      <div className="h-full bg-sky-500" style={{ width: `${pct}%` }} />
                    </div>
                    <span className="font-mono text-[0.65rem] text-muted-foreground">{a.tasks}</span>
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>

      {drillFilter && (
        <div className="fixed inset-0 z-50 flex">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={closeDrill} aria-hidden />
          <aside className="relative ml-auto h-full w-full max-w-2xl overflow-y-auto bg-background shadow-2xl">
            <header className="sticky top-0 z-10 border-b border-border bg-background/95 px-5 py-4 backdrop-blur">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <p className="text-[0.65rem] uppercase tracking-wider text-muted-foreground">下钻 · 运行明细</p>
                  <h3 className="text-base font-semibold tracking-tight">
                    {drillFilter.category ? categoryLabel(drillFilter.category) : "全部"}
                    {drillFilter.status && (
                      <span className="ml-2 text-rose-500 font-mono text-sm">{drillFilter.status}</span>
                    )}
                  </h3>
                </div>
                <Button variant="ghost" size="icon" onClick={closeDrill}>
                  <X className="h-4 w-4" />
                </Button>
              </div>
            </header>
            <div className="space-y-2 p-4">
              {drillLoading ? (
                Array.from({ length: 4 }).map((_, i) => (
                  <div key={i} className="h-12 rounded bg-muted animate-pulse" />
                ))
              ) : drillRuns.length === 0 ? (
                <p className="py-10 text-center text-xs text-muted-foreground">无匹配运行记录</p>
              ) : (
                drillRuns.map((r) => (
                  <div key={r.runId}
                    onClick={() => r.workspaceId && navigate(`/runs/${r.workspaceId}/${r.runId}`)}
                    className="cursor-pointer rounded-md border border-border/60 p-2.5 hover:border-border transition-colors">
                    <div className="flex items-center justify-between gap-2">
                      <Badge variant={runVariant(r.status)} className="text-[0.6rem] uppercase tracking-wider">
                        {r.status ?? "?"}
                      </Badge>
                      <span className="font-mono text-[0.65rem] text-muted-foreground">
                        {r.createdAt ? new Date(r.createdAt).toLocaleString() : ""}
                      </span>
                    </div>
                    <p className="mt-1 truncate text-sm">{r.taskTitle ?? r.runId}</p>
                    {r.resultSummary && (
                      <p className="truncate text-[0.7rem] text-muted-foreground">{r.resultSummary}</p>
                    )}
                  </div>
                ))
              )}
            </div>
          </aside>
        </div>
      )}
    </div>
  );
}

function catColor(cat: string): string {
  const map: Record<string, string> = {
    hr: "orange",
    finance: "green",
    assets: "yellow",
    logistics: "blue",
    "cross-domain": "purple",
  };
  return map[cat] ?? "teal";
}

function formatMs(ms: number): string {
  if (!ms || ms < 0) return "—";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

function runVariant(status?: string): "outline" | "success" | "warning" | "destructive" | "info" {
  const s = (status ?? "").toUpperCase();
  if (["COMPLETED", "SUCCESS", "DONE"].includes(s)) return "success";
  if (["FAILED", "ERROR"].includes(s)) return "destructive";
  if (["RUNNING", "PROCESSING"].includes(s)) return "info";
  if (["PENDING", "AWAITING_APPROVAL"].includes(s)) return "warning";
  return "outline";
}
