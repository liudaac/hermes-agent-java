import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Sparkles, Layers, Users, ArrowRight, ArrowLeft, RefreshCw, Wand2, Clock } from "lucide-react";
import { api, type ScenarioTemplateRecord } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { useToast } from "@/hooks/useToast";
import { cn } from "@/lib/utils";
import { categoryLabel, colorsFor, iconFor } from "@/components/business/templateVisuals";

type FilterCategory = "all" | "hr" | "finance" | "assets" | "logistics" | "cross-domain";

const FILTERS: { id: FilterCategory; label: string }[] = [
  { id: "all", label: "全部" },
  { id: "hr", label: "人力资源" },
  { id: "finance", label: "财务" },
  { id: "assets", label: "固定资产" },
  { id: "logistics", label: "物流" },
  { id: "cross-domain", label: "跨域旗舰" },
];

export default function TemplateGalleryPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [templates, setTemplates] = useState<ScenarioTemplateRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<FilterCategory>("all");
  const [search, setSearch] = useState("");
  const [cloningId, setCloningId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api.listScenarioTemplates();
      setTemplates(res.items);
    } catch (e) {
      showToast(`加载场景模板失败：${String(e)}`, "error");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return templates.filter((t) => {
      if (filter !== "all" && t.category !== filter) return false;
      if (!q) return true;
      const hay = `${t.name} ${t.summary ?? ""} ${t.description ?? ""}`.toLowerCase();
      return hay.includes(q);
    });
  }, [templates, filter, search]);

  const handleClone = async (template: ScenarioTemplateRecord) => {
    if (cloningId) return;
    if (!confirm(`将以模板 "${template.name}" 创建新工作空间、团队、场景和提示词资产，确认继续？`)) return;
    setCloningId(template.templateId);
    try {
      const res = await api.cloneScenarioTemplate(template.templateId, { workspaceName: template.name });
      showToast(`已克隆到工作空间 ${res.workspaceId}`, "success");
      navigate(`/business-portal?workspaceId=${encodeURIComponent(res.workspaceId)}`);
    } catch (e) {
      showToast(`克隆失败：${String(e)}`, "error");
    } finally {
      setCloningId(null);
    }
  };

  return (
    <div className="space-y-5">
      <div className="aurora-bg flex flex-col gap-3 rounded-2xl border border-border/60 px-5 py-5 md:flex-row md:items-start md:justify-between md:px-7 md:py-6">
        <div>
          <div className="flex items-center gap-2 text-xs uppercase tracking-normal opacity-70 sm:tracking-[0.18em]">
            <Layers className="h-3.5 w-3.5" /> 场景模板库
          </div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight md:text-3xl">
            一键搭建<span className="bg-gradient-to-r from-foreground via-foreground/85 to-foreground/65 bg-clip-text text-transparent">行业级数字员工团队</span>
          </h1>
          <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
            每个模板预置了团队蓝图、提示词资产、协作时间线和关键指标。
            选好模板 → 一键克隆 → 30 秒后在 Portal 中跑通第一个真实任务。
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
        <CardContent className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
          <div className="flex flex-wrap gap-2">
            {FILTERS.map((f) => {
              const active = filter === f.id;
              return (
                <button
                  key={f.id}
                  onClick={() => setFilter(f.id)}
                  className={cn("rounded-full px-3 py-1 text-xs font-medium transition-colors",
                    active ? "bg-foreground text-background" : "bg-muted text-foreground hover:bg-muted/80")}
                >{f.label}</button>
              );
            })}
          </div>
          <div className="ml-auto w-full sm:max-w-xs">
            <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="搜索场景" className="h-9 text-sm" />
          </div>
        </CardContent>
      </Card>

      {loading ? (
        <div className="grid gap-4 md:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <Card key={i}>
              <CardContent className="space-y-3 p-5">
                <div className="h-12 w-12 rounded-xl bg-muted animate-pulse" />
                <div className="h-4 w-32 rounded bg-muted animate-pulse" />
                <div className="h-20 rounded bg-muted animate-pulse" />
              </CardContent>
            </Card>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center gap-3 py-10 text-center">
            <Sparkles className="h-8 w-8 text-muted-foreground/40" />
            <p className="text-sm text-muted-foreground">没有符合条件的场景模板</p>
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {filtered.map((t) => (
            <ScenarioCard key={t.templateId} template={t} onClone={handleClone} cloning={cloningId === t.templateId} />
          ))}
        </div>
      )}
    </div>
  );
}

function ScenarioCard({ template, onClone, cloning }: { template: ScenarioTemplateRecord; onClone: (t: ScenarioTemplateRecord) => void; cloning: boolean }) {
  const Icon = iconFor(template.icon);
  const c = colorsFor(template.color);
  return (
    <article className="glass-card glass-card-interactive group relative flex flex-col overflow-hidden">
      <div aria-hidden className={cn("pointer-events-none absolute -right-16 -top-16 h-40 w-40 rounded-full opacity-50 blur-3xl transition-opacity duration-300 group-hover:opacity-80", c.bg)} />
      <CardHeader className="relative">
        <div className="flex items-start gap-3">
          <div className={cn("flex h-12 w-12 shrink-0 items-center justify-center rounded-xl ring-1 transition-transform duration-200 group-hover:scale-105", c.bg, c.ring)}>
            <Icon className={cn("h-6 w-6", c.text)} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle className="truncate text-base">{template.name}</CardTitle>
              <Badge variant="outline" className="text-xs uppercase tracking-wider">
                {template.industryTag ?? categoryLabel(template.category)}
              </Badge>
            </div>
            {template.summary && (
              <CardDescription className="mt-1.5 text-sm leading-relaxed">{template.summary}</CardDescription>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent className="relative flex flex-1 flex-col gap-4">
        {template.metrics.length > 0 && (
          <div className="grid grid-cols-3 gap-1.5 sm:gap-2">
            {template.metrics.slice(0, 3).map((m) => (
              <div key={m.label} className="rounded-md border border-border/50 bg-background/40 px-1.5 py-1.5 text-center backdrop-blur-sm">
                <div className={cn("metric-number text-sm font-semibold", c.text)}>
                  {m.value}
                  {m.unit && <span className="ml-0.5 text-[0.65rem] opacity-60">{m.unit}</span>}
                </div>
                <div className="mt-0.5 truncate text-[0.65rem] uppercase tracking-wider text-muted-foreground">{m.label}</div>
              </div>
            ))}
          </div>
        )}

        {template.involvedAgents.length > 0 && (
          <div>
            <p className="mb-1.5 flex items-center gap-1.5 text-xs uppercase tracking-wider text-muted-foreground">
              <Users className="h-3 w-3" /> 涉及数字员工 · {template.involvedAgents.length}
            </p>
            <div className="flex flex-wrap gap-1.5">
              {template.involvedAgents.map((a) => (
                <span key={a.templateId} className={cn("rounded-full px-2 py-0.5 text-xs", c.chip)}>
                  {a.templateId}
                </span>
              ))}
            </div>
          </div>
        )}

        {template.workflowTimeline.length > 0 && (
          <div>
            <p className="mb-1.5 flex items-center gap-1.5 text-xs uppercase tracking-wider text-muted-foreground">
              <Clock className="h-3 w-3" /> 协作时间线 · {template.workflowTimeline.length} 步
            </p>
            <ol className="space-y-1 text-xs">
              {template.workflowTimeline.slice(0, 4).map((step, i) => (
                <li key={i} className="flex items-start gap-2 text-muted-foreground">
                  <code className="rounded bg-foreground/5 px-1 font-mono text-xs">{step.t}</code>
                  <span className="font-medium text-foreground">{step.actor}</span>
                  <span className="opacity-50">→</span>
                  <span className="truncate">{step.action}</span>
                </li>
              ))}
              {template.workflowTimeline.length > 4 && (
                <li className="text-xs text-muted-foreground/70">…还有 {template.workflowTimeline.length - 4} 步</li>
              )}
            </ol>
          </div>
        )}

        <Button className="mt-auto" onClick={() => onClone(template)} disabled={cloning}>
          {cloning ? (
            <><RefreshCw className="mr-1.5 h-3.5 w-3.5 animate-spin" /> 克隆中…</>
          ) : (
            <><Wand2 className="mr-1.5 h-3.5 w-3.5" /> 一键克隆到工作空间 <ArrowRight className="ml-1.5 h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5" /></>
          )}
        </Button>
      </CardContent>
    </article>
  );
}
