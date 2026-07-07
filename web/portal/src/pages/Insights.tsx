import { useEffect, useState } from "react";
import { portalApi } from "@/api/portal";
import type { BusinessInsightsResponse, BusinessInsightRecord } from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { Lightbulb } from "lucide-react";
import { useI18n } from "@/i18n";
import { cn } from "@/lib/cn";

export default function Insights() {
  const { t } = useI18n();
  const [data, setData] = useState<BusinessInsightRecord[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    portalApi
      .getBusinessInsights()
      .then((res: BusinessInsightsResponse) => alive && setData(res.insights ?? []))
      .catch((e) => alive && setError(String(e?.message ?? e)));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
            {t("insights.title")}
          </h1>
          <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
            {t("insights.subtitle")}
          </p>
        </header>

        {error && (
          <GlassCard className="mb-3 border border-[oklch(0.68_0.20_25_/_0.35)]">
            <p className="text-[12px] text-[var(--color-text-secondary)]">{error}</p>
          </GlassCard>
        )}

        {!data ? (
          <div className="space-y-3">
            {[0, 1].map((i) => (
              <div key={i} className="shimmer h-24 rounded-2xl" />
            ))}
          </div>
        ) : data.length === 0 ? (
          <GlassCard tone="default" className="flex flex-col items-center gap-3 py-10 text-center">
            <Lightbulb className="h-7 w-7 text-[var(--color-text-muted)]" />
            <p className="text-[14px] text-[var(--color-text-secondary)]">{t("insights.empty")}</p>
          </GlassCard>
        ) : (
          <div className="space-y-3">
            {data.map((ins) => (
              <InsightCard key={ins.insightId} insight={ins} />
            ))}
          </div>
        )}
      </div>
    </AuroraBackground>
  );
}

function InsightCard({ insight }: { insight: BusinessInsightRecord }) {
  const sev = (insight.severity ?? "low").toLowerCase();
  const tone =
    sev === "high"
      ? "border-[oklch(0.68_0.20_25_/_0.4)]"
      : sev === "medium"
        ? "border-[oklch(0.78_0.16_85_/_0.4)]"
        : "border-[oklch(0.30_0.015_50_/_0.4)]";

  return (
    <GlassCard className={cn("border", tone)}>
      <div className="flex items-start gap-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[oklch(0.78_0.16_70_/_0.18)]">
          <Lightbulb className="h-4 w-4 text-[oklch(0.88_0.12_70)]" />
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="text-[14px] font-semibold text-[var(--color-text-primary)]">
            {insight.title}
          </h3>
          {insight.finding && (
            <p className="mt-1.5 text-[12px] leading-relaxed text-[var(--color-text-secondary)]">
              {insight.finding}
            </p>
          )}
          {insight.recommendation && (
            <div className="mt-2 rounded-lg border border-[oklch(0.55_0.10_65_/_0.3)] bg-[oklch(0.78_0.16_70_/_0.08)] px-3 py-2 text-[12px] text-[oklch(0.88_0.10_70)]">
              {insight.recommendation}
            </div>
          )}
          {insight.expectedBenefit && (
            <p className="mt-2 text-[11px] text-[var(--color-text-muted)]">
              预期收益：{insight.expectedBenefit}
            </p>
          )}
        </div>
      </div>
    </GlassCard>
  );
}
