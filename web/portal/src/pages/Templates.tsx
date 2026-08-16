import { useEffect, useState, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { Sparkles, Play, Loader2 } from "lucide-react";
import { portalApi } from "@/api/portal";
import type { BusinessScenariosResponse, BusinessScenarioRecord } from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { SearchBar } from "@/components/SearchBar";
import { useWorkspace } from "@/hooks/useWorkspace";
import { useI18n } from "@/i18n";

export default function Templates() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { workspaceId } = useWorkspace();
  const [data, setData] = useState<BusinessScenarioRecord[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [launchingId, setLaunchingId] = useState<string | null>(null);
  const [selectedScenario, setSelectedScenario] = useState<BusinessScenarioRecord | null>(null);
  const [userInput, setUserInput] = useState("");
  const [search, setSearch] = useState("");

  const loadData = useCallback(() => {
    if (!workspaceId) return;
    setData(null);
    setError(null);
    portalApi
      .getBusinessScenarios(workspaceId)
      .then((res: BusinessScenariosResponse) => {
        setData(res.scenarios ?? []);
      })
      .catch((e) => {
        setError(String(e?.message ?? e));
      });
  }, [workspaceId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

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

  // ── Launch modal ──
  if (selectedScenario) {
    return (
      <AuroraBackground>
        <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
          <GlassCard grain className="mb-4">
            <div className="flex items-start gap-3">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-primary/40 to-primary/20 text-[18px]">
                ✨
              </div>
              <div className="min-w-0 flex-1">
                <h2 className="text-[22px] font-medium leading-tight">
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
              className="w-full resize-none rounded-xl border border-border bg-primary/10 px-4 py-3 text-[14px] text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
              autoFocus
            />
          </GlassCard>

          {error && (
            <GlassCard className="mb-3 border border-border">
              <div className="flex items-center justify-between">
                <p className="text-[12px] text-muted-foreground">{error}</p>
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
              className="rounded-xl px-5 py-3 text-[14px] font-medium text-muted-foreground hover:bg-primary/10 transition"
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

  // ── Template list ──
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

        {data && data.length > 0 && (
          <div className="mb-4">
            <SearchBar value={search} onChange={setSearch} placeholder="搜索场景..." />
          </div>
        )}

        {error && (
          <GlassCard className="mb-3 border border-border">
            <div className="flex items-center justify-between">
              <p className="text-[12px] text-muted-foreground">{error}</p>
              <button
                onClick={loadData}
                className="text-[12px] font-medium text-primary hover:underline"
              >
                重试
              </button>
            </div>
          </GlassCard>
        )}

        {!data ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="shimmer h-32 rounded-2xl" />
            ))}
          </div>
        ) : data.length === 0 ? (
          <GlassCard grain className="flex flex-col items-center gap-3 py-10 text-center">
            <Sparkles className="h-7 w-7 text-primary" />
            <p className="text-[14px] font-semibold text-foreground">
              暂无场景模板
            </p>
            <p className="text-[12px] text-muted-foreground">
              场景模板会在工作区创建后自动生成
            </p>
          </GlassCard>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {data
              .filter((s) => {
                if (!search.trim()) return true;
                const q = search.toLowerCase();
                return `${s.name ?? ""} ${s.description ?? ""}`.toLowerCase().includes(q);
              })
              .map((s) => (
              <GlassCard
                key={s.scenarioId}
                tone="default"
                interactive
                padding="md"
                className="flex flex-col gap-3"
              >
                <div className="flex items-start gap-3">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary/40 to-primary/20 text-[15px]">
                    ✨
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
                    <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-[10px] tracking-wide text-muted-foreground">
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
        )}
      </div>
    </AuroraBackground>
  );
}
