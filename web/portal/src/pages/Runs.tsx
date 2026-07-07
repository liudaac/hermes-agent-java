import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { portalApi } from "@/api/portal";
import type { BusinessRunsResponse, BusinessRunRecord } from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { StatusPill } from "@/components/StatusPill";
import { useI18n } from "@/i18n";
import { formatRelativeTime } from "@hermes/ui";
import { Activity as ActivityIcon, Inbox } from "lucide-react";

export default function Runs() {
  const { t } = useI18n();
  const [data, setData] = useState<BusinessRunRecord[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    portalApi
      .getBusinessRuns(undefined, 30)
      .then((res: BusinessRunsResponse) => alive && setData(res.runs ?? []))
      .catch((e) => alive && setError(String(e?.message ?? e)));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        {error && (
          <GlassCard className="mb-3 border border-[oklch(0.68_0.20_25_/_0.35)]">
            <p className="text-[12px] text-[var(--color-text-secondary)]">{error}</p>
          </GlassCard>
        )}

        {!data ? (
          <div className="space-y-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="shimmer h-16 rounded-2xl" />
            ))}
          </div>
        ) : data.length === 0 ? (
          <GlassCard tone="default" className="flex flex-col items-center gap-3 py-10 text-center">
            <Inbox className="h-7 w-7 text-[var(--color-text-muted)]" />
            <p className="text-[14px] text-[var(--color-text-secondary)]">{t("runs.empty")}</p>
          </GlassCard>
        ) : (
          <GlassCard padding="sm" className="divide-y divide-[oklch(0.30_0.015_50_/_0.4)]">
            {data.map((r) => (
              <Link
                key={r.runId}
                to={`/runs/${r.workspaceId ?? "_"}/${r.runId}`}
                className="flex items-center gap-3 px-2 py-3 active:bg-[oklch(0.30_0.02_50_/_0.2)] rounded-lg"
              >
                <ActivityIcon className="h-4 w-4 text-[var(--color-text-muted)]" />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13px] font-medium text-[var(--color-text-primary)]">
                    {r.taskTitle ?? r.scenario ?? "运行"}
                  </p>
                  <p className="text-[11px] text-[var(--color-text-muted)]">
                    {formatRelativeTime(r.createdAt)}
                  </p>
                </div>
                <StatusPill status={r.status} />
              </Link>
            ))}
          </GlassCard>
        )}
      </div>
    </AuroraBackground>
  );
}
