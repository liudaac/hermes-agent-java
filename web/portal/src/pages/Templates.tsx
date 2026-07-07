import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Sparkles } from "lucide-react";
import { portalApi } from "@/api/portal";
import type { BusinessScenariosResponse, BusinessScenarioRecord } from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { useI18n } from "@/i18n";

export default function Templates() {
  const { t } = useI18n();
  const [data, setData] = useState<BusinessScenarioRecord[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const home = await portalApi.getBusinessHome();
        if (!alive) return;
        const ws = home.workspaceId ?? home.workspaces?.[0]?.workspaceId;
        if (!ws) {
          setError("找不到工作区");
          return;
        }
        const res: BusinessScenariosResponse = await portalApi.getBusinessScenarios(ws);
        if (alive) setData(res.scenarios ?? []);
      } catch (e: any) {
        if (alive) setError(String(e?.message ?? e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

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
          <GlassCard tone="default" className="text-center text-[13px] text-[var(--color-text-muted)]">
            暂无场景
          </GlassCard>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {data.map((s) => (
              <TemplateCard key={s.scenarioId} s={s} />
            ))}
          </div>
        )}
      </div>
    </AuroraBackground>
  );
}

function TemplateCard({ s }: { s: BusinessScenarioRecord }) {
  return (
    <Link to="/teams">
      <GlassCard interactive className="flex h-full flex-col gap-3">
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-[oklch(0.78_0.16_70_/_0.3)] to-[oklch(0.55_0.10_60_/_0.2)] text-[16px]">
            ✨
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="truncate text-[14px] font-semibold text-[var(--color-text-primary)]">
              {s.name}
            </h3>
            <p className="text-[11px] text-[var(--color-text-muted)]">
              {s.collaborationPattern ?? "场景"}
            </p>
          </div>
        </div>
        <p className="line-clamp-2 text-[12px] leading-relaxed text-[var(--color-text-secondary)]">
          {s.description}
        </p>
        <div className="mt-auto flex items-center justify-between text-[11px] text-[var(--color-text-muted)]">
          <span className="inline-flex items-center gap-1">
            <Sparkles className="h-3 w-3" />
            {s.status ?? "可用"}
          </span>
          <span className="text-[var(--color-accent)]">使用此模板 →</span>
        </div>
      </GlassCard>
    </Link>
  );
}
