import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Sparkles, Play, Loader2 } from "lucide-react";
import { portalApi } from "@/api/portal";
import type { BusinessScenariosResponse, BusinessScenarioRecord } from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { useWorkspace } from "@/hooks/useWorkspace";
import { useI18n } from "@/i18n";

export default function Templates() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { workspaceId } = useWorkspace();
  const [data, setData] = useState<BusinessScenarioRecord[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [launchingId, setLaunchingId] = useState<string | null>(null);

  useEffect(() => {
    if (!workspaceId) return;
    let alive = true;
    portalApi
      .getBusinessScenarios(workspaceId)
      .then((res: BusinessScenariosResponse) => {
        if (alive) setData(res.scenarios ?? []);
      })
      .catch((e) => {
        if (alive) setError(String(e?.message ?? e));
      });
    return () => {
      alive = false;
    };
  }, [workspaceId]);

  const launchScenario = async (scenario: BusinessScenarioRecord) => {
    if (!workspaceId || !scenario.scenarioId) return;
    setLaunchingId(scenario.scenarioId);
    try {
      const res = await portalApi.executeBusinessScenario(
        workspaceId,
        scenario.scenarioId,
        "",
      );
      // Navigate to run detail
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

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
            {t("templates.title")}
          </h1>
          <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
            {t("templates.subtitle")}
          </p>
        </header>

        {error && (
          <GlassCard className="mb-3 border border-[oklch(0.68_0.20_25_/_0.35)]">
            <p className="text-[12px] text-[var(--color-text-secondary)]">{error}</p>
          </GlassCard>
        )}

        {!data ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="shimmer h-32 rounded-2xl" />
            ))}
          </div>
        ) : data.length === 0 ? (
          <GlassCard tone="accent" grain className="flex flex-col items-center gap-3 py-10 text-center">
            <Sparkles className="h-7 w-7 text-[var(--color-accent)]" />
            <p className="text-[14px] font-semibold text-[var(--color-text-primary)]">
              暂无场景模板
            </p>
            <p className="text-[12px] text-[var(--color-text-secondary)]">
              场景模板会在工作区创建后自动生成
            </p>
          </GlassCard>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {data.map((s) => (
              <GlassCard
                key={s.scenarioId}
                tone="default"
                interactive
                padding="md"
                className="flex flex-col gap-3"
              >
                <div className="flex items-start gap-3">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-[oklch(0.70_0.14_210_/_0.3)] to-[oklch(0.70_0.16_280_/_0.2)] text-[15px]">
                    ✨
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[14px] font-semibold text-[var(--color-text-primary)]">
                      {s.name}
                    </p>
                    <p className="line-clamp-2 text-[11px] leading-relaxed text-[var(--color-text-secondary)]">
                      {s.description}
                    </p>
                  </div>
                </div>
                <div className="flex items-center justify-between">
                  {s.metadata?.category ? (
                    <span className="rounded-full bg-[oklch(0.30_0.02_50_/_0.5)] px-2.5 py-0.5 text-[10px] tracking-wide text-[var(--color-text-muted)]">
                      {String(s.metadata.category)}
                    </span>
                  ) : null}
                  <button
                    type="button"
                    onClick={() => launchScenario(s)}
                    disabled={launchingId === s.scenarioId}
                    className="ml-auto inline-flex items-center gap-1.5 rounded-full bg-[var(--color-text-primary)] px-4 py-2 text-[12px] font-semibold text-[var(--color-bg-0)] active:scale-95 transition disabled:opacity-60"
                  >
                    {launchingId === s.scenarioId ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Play className="h-3.5 w-3.5" />
                    )}
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
