import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Search, Sparkles, ArrowLeft, RefreshCw } from "lucide-react";
import { api, type AgentTemplateRecord } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useToast } from "@/hooks/useToast";
import AgentRoleCard, {
  AgentRoleCardSkeleton,
  EmptyAgentRoleList,
} from "@/components/business/AgentRoleCard";
import AgentDetailSheet from "@/components/business/AgentDetailSheet";
import { CATEGORY_LABELS } from "@/components/business/templateVisuals";

type FilterCategory = "all" | "hr" | "finance" | "assets" | "logistics" | "general";

const FILTERS: { id: FilterCategory; label: string }[] = [
  { id: "all", label: "全部" },
  { id: "hr", label: "人力资源" },
  { id: "finance", label: "财务" },
  { id: "assets", label: "固定资产" },
  { id: "logistics", label: "物流" },
  { id: "general", label: "通用" },
];

export default function AgentMarketPage() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [templates, setTemplates] = useState<AgentTemplateRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<FilterCategory>("all");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<AgentTemplateRecord | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await api.listAgentTemplates();
      setTemplates(res.items);
    } catch (e) {
      showToast(`加载数字员工失败：${String(e)}`, "error");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const counts = useMemo(() => {
    const m: Record<string, number> = { all: templates.length };
    templates.forEach((t) => {
      m[t.category] = (m[t.category] ?? 0) + 1;
    });
    return m;
  }, [templates]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return templates.filter((t) => {
      if (filter !== "all" && t.category !== filter) return false;
      if (!q) return true;
      const hay = `${t.name} ${t.role} ${t.mission ?? ""} ${t.skills.join(" ")}`.toLowerCase();
      return hay.includes(q);
    });
  }, [templates, filter, search]);

  const grouped = useMemo(() => {
    if (filter !== "all") return null;
    const buckets: Record<string, AgentTemplateRecord[]> = {};
    filtered.forEach((t) => {
      const key = t.category ?? "general";
      (buckets[key] ??= []).push(t);
    });
    return buckets;
  }, [filtered, filter]);

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="aurora-bg flex flex-col gap-3 rounded-2xl border border-border/60 px-5 py-5 md:flex-row md:items-start md:justify-between md:px-7 md:py-6">
        <div>
          <div className="flex items-center gap-2 text-xs uppercase tracking-normal opacity-70 sm:tracking-[0.18em]">
            <Sparkles className="h-3.5 w-3.5" />
            数字员工市场
          </div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight md:text-3xl">
            为你的业务<span className="bg-gradient-to-r from-foreground via-foreground/85 to-foreground/65 bg-clip-text text-transparent">挑选数字员工</span>
          </h1>
          <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
            浏览 {templates.length} 位预置的数字员工角色，覆盖人力资源、财务、固资、物流等场景。
            点击卡片查看技能、指标、工作流和风险边界，然后从场景模板一键组建团队。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate("/portal")}>
            <ArrowLeft className="mr-1 h-3.5 w-3.5" />
            返回 Portal
          </Button>
          <Button variant="outline" size="sm" onClick={load} disabled={loading}>
            <RefreshCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} />
            刷新
          </Button>
        </div>
      </div>

      {/* Filter + search */}
      <Card>
        <CardContent className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
          <div className="flex flex-wrap gap-2">
            {FILTERS.map((f) => {
              const count = counts[f.id] ?? 0;
              const active = filter === f.id;
              return (
                <button
                  key={f.id}
                  onClick={() => setFilter(f.id)}
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium transition-colors",
                    active
                      ? "bg-foreground text-background"
                      : "bg-muted hover:bg-muted/80 text-foreground",
                  )}
                >
                  {f.label}
                  {count > 0 && (
                    <span className={cn("text-xs opacity-70 font-mono", active && "opacity-100")}>
                      {count}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
          <div className="ml-auto relative w-full sm:max-w-xs">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索角色 / 技能 / 关键词"
              className="pl-8 h-9 text-sm"
            />
          </div>
        </CardContent>
      </Card>

      {/* Grid */}
      {loading ? (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <AgentRoleCardSkeleton key={i} />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <EmptyAgentRoleList />
      ) : grouped ? (
        <div className="space-y-6">
          {Object.entries(grouped).map(([cat, items]) => (
            <section key={cat}>
              <div className="mb-2 flex items-center gap-2">
                <h2 className="text-sm font-semibold tracking-tight">
                  {CATEGORY_LABELS[cat] ?? cat}
                </h2>
                <Badge variant="outline" className="text-xs font-mono">
                  {items.length}
                </Badge>
              </div>
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                {items.map((t) => (
                  <AgentRoleCard
                    key={t.templateId}
                    template={t}
                    onClick={() => setSelected(t)}
                  />
                ))}
              </div>
            </section>
          ))}
        </div>
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((t) => (
            <AgentRoleCard
              key={t.templateId}
              template={t}
              onClick={() => setSelected(t)}
            />
          ))}
        </div>
      )}

      <AgentDetailSheet
        template={selected}
        open={!!selected}
        onClose={() => setSelected(null)}
      />
    </div>
  );
}
