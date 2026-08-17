import { useEffect, useState, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
  Sparkles, Play, Loader2, Search, Rocket,
  Package, ScanLine, Layers, Zap, GraduationCap, UserPlus,
  MapPin, type LucideIcon,
} from "lucide-react";
import { portalApi } from "@/api/portal";
import type { BusinessScenariosResponse, BusinessScenarioRecord } from "@/api/types-portal";
import type { ScenarioTemplateRecord } from "@/api/types-templates";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { useWorkspace } from "@/hooks/useWorkspace";
import { useI18n } from "@/i18n";
import { cn } from "@hermes/ui";

/** Map YAML icon names to Lucide components. */
const ICON_MAP: Record<string, LucideIcon> = {
  "package": Package,
  "scan-line": ScanLine,
  "layers": Layers,
  "zap": Zap,
  "graduation-cap": GraduationCap,
  "user-plus": UserPlus,
  "map-pin": MapPin,
};

/** Map YAML color names to hex values. */
const COLOR_MAP: Record<string, string> = {
  blue: "#0071e3",
  green: "#34c759",
  orange: "#ff9500",
  yellow: "#ffcc00",
  red: "#ff3b30",
  purple: "#af52de",
  cyan: "#5ac8fa",
  pink: "#ff2d55",
};

function resolveIcon(name?: string): { Icon: LucideIcon; fallback: string } {
  if (name && ICON_MAP[name]) return { Icon: ICON_MAP[name], fallback: "" };
  return { Icon: Sparkles, fallback: "✨" };
}

function resolveColor(name?: string): string {
  if (name && COLOR_MAP[name]) return COLOR_MAP[name];
  return "#0071e3";
}

type Mode = "templates" | "mine";

export default function Templates() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { workspaceId } = useWorkspace();
  const [mode, setMode] = useState<Mode>("templates");
  const [templates, setTemplates] = useState<ScenarioTemplateRecord[]>([]);
  const [myScenarios, setMyScenarios] = useState<BusinessScenarioRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [launchingId, setLaunchingId] = useState<string | null>(null);
  const [selectedScenario, setSelectedScenario] = useState<BusinessScenarioRecord | null>(null);
  const [userInput, setUserInput] = useState("");
  const [search, setSearch] = useState("");

  // Load scenario templates (available for clone)
  const loadTemplates = useCallback(() => {
    setLoading(true);
    setError(null);
    portalApi
      .listScenarioTemplates()
      .then((res) => setTemplates(res.items ?? []))
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, []);

  // Load my workspace scenarios (already created)
  const loadMyScenarios = useCallback(() => {
    if (!workspaceId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    portalApi
      .getBusinessScenarios(workspaceId)
      .then((res: BusinessScenariosResponse) => setMyScenarios(res.scenarios ?? []))
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [workspaceId]);

  useEffect(() => {
    if (mode === "templates") loadTemplates();
    else loadMyScenarios();
  }, [mode, loadTemplates, loadMyScenarios]);

  // ── Clone a template into the workspace, then execute it ──
  const cloneAndLaunch = async (tmpl: ScenarioTemplateRecord) => {
    setLaunchingId(tmpl.templateId);
    setError(null);
    try {
      // Step 1: Clone the template -> creates workspace + team + scenario
      const cloneRes = await portalApi.cloneScenarioTemplate(tmpl.templateId, {
        workspaceId: workspaceId ?? undefined,
      });

      if (!cloneRes.scenarioId || !cloneRes.workspaceId) {
        // No scenario created - just go to runs list
        navigate(cloneRes.workspaceId ? `/runs/${cloneRes.workspaceId}` : "/runs");
        return;
      }

      // Step 2: Execute the scenario to create a run
      try {
        const execRes = await portalApi.executeBusinessScenario(
          cloneRes.workspaceId,
          cloneRes.scenarioId,
          tmpl.summary ?? tmpl.description ?? "",
        );
        // Navigate to the run detail
        const runId = (execRes as { runId?: string; run?: { runId?: string } }).runId
          ?? (execRes as { run?: { runId?: string } }).run?.runId;
        if (runId) {
          navigate(`/runs/${cloneRes.workspaceId}/${runId}`);
        } else {
          navigate(`/runs/${cloneRes.workspaceId}`);
        }
      } catch (e: any) {
        // Clone succeeded but execution failed - show the scenario in "mine" tab
        const msg = e?.message ?? String(e);
        if (msg.includes("NEEDS_APPROVAL") || msg.includes("approval")) {
          // Needs approval - go to approvals page
          navigate("/approvals");
        } else {
          setError(`场景已创建但启动失败: ${msg}`);
          setMode("mine");
        }
      }
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setLaunchingId(null);
    }
  };

  // ── Execute an existing workspace scenario ──
  const launchScenario = async () => {
    if (!workspaceId || !selectedScenario?.scenarioId) return;
    setLaunchingId(selectedScenario.scenarioId);
    try {
      const res = await portalApi.executeBusinessScenario(
        workspaceId,
        selectedScenario.scenarioId,
        userInput.trim(),
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
      setLaunchingId(null);
    }
  };

  // ── Launch modal for existing scenario ──
  if (selectedScenario) {
    return (
      <AuroraBackground>
        <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
          <GlassCard className="mb-4">
            <div className="flex items-start gap-3">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-primary/10 text-primary">
                <Sparkles className="h-6 w-6" />
              </div>
              <div className="min-w-0 flex-1">
                <h2 className="text-[22px] font-medium leading-tight text-foreground">
                  {selectedScenario.name}
                </h2>
                {selectedScenario.description && (
                  <p className="mt-1 text-[13px] leading-relaxed text-muted-foreground">
                    {selectedScenario.description}
                  </p>
                )}
              </div>
            </div>
          </GlassCard>

          <GlassCard className="mb-4">
            <label className="mb-2 block text-[13px] font-semibold text-foreground">
              描述你的任务
            </label>
            <p className="mb-3 text-[12px] text-muted-foreground">
              告诉数字员工你具体需要做什么，越详细越好
            </p>
            <textarea
              value={userInput}
              onChange={(e) => setUserInput(e.target.value)}
              placeholder="例如：帮我分析上周的销售数据，找出下滑的原因..."
              rows={4}
              className="w-full resize-none rounded-xl border border-border bg-muted px-4 py-3 text-[14px] text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
              autoFocus
            />
          </GlassCard>

          {error && (
            <GlassCard className="mb-3 border border-destructive/30">
              <div className="flex items-center justify-between">
                <p className="text-[12px] text-destructive">{error}</p>
                <button
                  onClick={() => { setError(null); launchScenario(); }}
                  className="text-[12px] font-medium text-primary hover:underline"
                >
                  重试
                </button>
              </div>
            </GlassCard>
          )}

          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => { setSelectedScenario(null); setUserInput(""); setLaunchingId(null); }}
              className="rounded-xl px-5 py-3 text-[14px] font-medium text-muted-foreground hover:bg-muted transition"
            >
              取消
            </button>
            <button
              type="button"
              onClick={launchScenario}
              disabled={launchingId === selectedScenario.scenarioId}
              className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-primary py-3 text-[14px] font-semibold text-primary-foreground active:scale-[0.98] transition disabled:opacity-60"
            >
              {launchingId === selectedScenario.scenarioId ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Play className="h-4 w-4" />
              )}
              {launchingId === selectedScenario.scenarioId ? "启动中..." : "启动"}
            </button>
          </div>
        </div>
      </AuroraBackground>
    );
  }

  // ── Main list ──
  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-4">
          <h1 className="text-[28px] font-medium leading-tight text-foreground">
            {t("templates.title")}
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            {t("templates.subtitle")}
          </p>
        </header>

        {/* Mode switcher */}
        <div className="mb-4 flex gap-2">
          <button
            onClick={() => setMode("templates")}
            className={cn(
              "rounded-full px-4 py-2 text-[13px] font-medium transition active:scale-95",
              mode === "templates"
                ? "bg-primary/10 text-primary"
                : "bg-muted/60 text-muted-foreground hover:text-foreground",
            )}
          >
            场景模板库
          </button>
          <button
            onClick={() => setMode("mine")}
            className={cn(
              "rounded-full px-4 py-2 text-[13px] font-medium transition active:scale-95",
              mode === "mine"
                ? "bg-primary/10 text-primary"
                : "bg-muted/60 text-muted-foreground hover:text-foreground",
            )}
          >
            我的场景
            {myScenarios.length > 0 && (
              <span className="ml-1.5 rounded-full bg-primary/20 px-1.5 py-0.5 text-[10px] text-primary">
                {myScenarios.length}
              </span>
            )}
          </button>
        </div>

        {/* Search */}
        {(mode === "templates" ? templates : myScenarios).length > 0 && (
          <div className="mb-4 relative">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索场景..."
              className="w-full rounded-xl bg-muted py-2.5 pl-9 pr-4 text-[13px] text-foreground placeholder:text-muted-foreground outline-none focus:ring-1 focus:ring-primary"
            />
          </div>
        )}

        {error && (
          <GlassCard className="mb-3 border border-destructive/30">
            <div className="flex items-center justify-between">
              <p className="text-[12px] text-destructive">{error}</p>
              <button
                onClick={() => { setError(null); mode === "templates" ? loadTemplates() : loadMyScenarios(); }}
                className="text-[12px] font-medium text-primary hover:underline"
              >
                重试
              </button>
            </div>
          </GlassCard>
        )}

        {loading ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="shimmer h-32 rounded-2xl" />
            ))}
          </div>
        ) : mode === "templates" ? (
          /* ── Template gallery ── */
          templates.length === 0 ? (
            <GlassCard className="flex flex-col items-center gap-3 py-10 text-center">
              <Sparkles className="h-7 w-7 text-primary" />
              <p className="text-[14px] font-semibold text-foreground">
                模板加载中...
              </p>
              <p className="text-[12px] text-muted-foreground">
                如果持续为空，请检查后端 scenario-templates 是否已注册
              </p>
            </GlassCard>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {templates
                .filter((s) => {
                  if (!search.trim()) return true;
                  const q = search.toLowerCase();
                  return `${s.name ?? ""} ${s.summary ?? ""} ${s.description ?? ""} ${s.category ?? ""}`.toLowerCase().includes(q);
                })
                .map((tmpl) => {
                  const { Icon } = resolveIcon(tmpl.icon);
                  const color = resolveColor(tmpl.color);
                  return (
                  <GlassCard key={tmpl.templateId} interactive padding="md" className="flex flex-col gap-3">
                    <div className="flex items-start gap-3">
                      <div
                        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl"
                        style={{
                          backgroundColor: `${color}1a`,
                          color: color,
                        }}
                      >
                        <Icon className="h-5 w-5" />
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <p className="truncate text-[14px] font-semibold text-foreground">
                            {tmpl.name}
                          </p>
                          {tmpl.status && tmpl.status !== "STABLE" && (
                            <span className="shrink-0 rounded-full bg-amber-500/15 px-2 py-0.5 text-[10px] font-medium text-amber-500">
                              {tmpl.status}
                            </span>
                          )}
                        </div>
                        <p className="line-clamp-2 text-[11px] leading-relaxed text-muted-foreground">
                          {tmpl.summary ?? tmpl.description ?? "-"}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center justify-between">
                      {tmpl.industryTag && (
                        <span className="rounded-full bg-muted px-2.5 py-0.5 text-[10px] tracking-wide text-muted-foreground">
                          {tmpl.industryTag}
                        </span>
                      )}
                      <button
                        type="button"
                        onClick={() => cloneAndLaunch(tmpl)}
                        disabled={launchingId === tmpl.templateId}
                        className="ml-auto inline-flex items-center gap-1.5 rounded-full bg-primary px-4 py-2 text-[12px] font-semibold text-primary-foreground active:scale-95 transition disabled:opacity-60"
                      >
                        {launchingId === tmpl.templateId ? (
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        ) : (
                          <Rocket className="h-3.5 w-3.5" />
                        )}
                        {launchingId === tmpl.templateId ? "创建中..." : "创建并启动"}
                      </button>
                    </div>
                  </GlassCard>
                  );
                })}
            </div>
          )
        ) : (
          /* ── My scenarios ── */
          myScenarios.length === 0 ? (
            <GlassCard className="flex flex-col items-center gap-3 py-10 text-center">
              <Sparkles className="h-7 w-7 text-primary" />
              <p className="text-[14px] font-semibold text-foreground">
                暂无场景
              </p>
              <p className="text-[12px] text-muted-foreground">
                从「场景模板库」选一个模板，一键创建你的第一个场景
              </p>
              <button
                onClick={() => setMode("templates")}
                className="mt-1 inline-flex items-center gap-1.5 rounded-full bg-primary px-4 py-2 text-[12px] font-semibold text-primary-foreground active:scale-95 transition"
              >
                <Sparkles className="h-3.5 w-3.5" />
                去模板库
              </button>
            </GlassCard>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {myScenarios
                .filter((s) => {
                  if (!search.trim()) return true;
                  const q = search.toLowerCase();
                  return `${s.name ?? ""} ${s.description ?? ""}`.toLowerCase().includes(q);
                })
                .map((s) => (
                <GlassCard key={s.scenarioId} interactive padding="md" className="flex flex-col gap-3">
                  <div className="flex items-start gap-3">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
                      <Sparkles className="h-5 w-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[14px] font-semibold text-foreground">
                        {s.name}
                      </p>
                      <p className="line-clamp-2 text-[11px] leading-relaxed text-muted-foreground">
                        {s.description}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center justify-between">
                    {s.metadata?.category ? (
                      <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-[10px] tracking-wide text-primary">
                        {String(s.metadata.category)}
                      </span>
                    ) : null}
                    <button
                      type="button"
                      onClick={() => { setSelectedScenario(s); setError(null); }}
                      className="ml-auto inline-flex items-center gap-1.5 rounded-full bg-primary px-4 py-2 text-[12px] font-semibold text-primary-foreground active:scale-95 transition"
                    >
                      <Play className="h-3.5 w-3.5" />
                      启动
                    </button>
                  </div>
                </GlassCard>
              ))}
            </div>
          )
        )}
      </div>
    </AuroraBackground>
  );
}
