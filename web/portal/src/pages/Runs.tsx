import { useEffect, useState, useCallback, useMemo } from "react";
import { Link } from "react-router-dom";
import { portalApi } from "@/api/portal";
import type { BusinessRunsResponse, BusinessRunRecord } from "@/api/types-portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { StatusPill } from "@/components/StatusPill";
import { ErrorCard } from "@/components/ErrorCard";
import { SearchBar } from "@/components/SearchBar";
import { useI18n } from "@/i18n";
import { cn } from "@hermes/ui";
import { formatRelativeTime } from "@hermes/ui";
import { Activity as ActivityIcon, Inbox, Play } from "lucide-react";

const STATUS_FILTERS = [
  { key: "all", label: "全部" },
  { key: "running", label: "执行中" },
  { key: "succeeded", label: "已完成" },
  { key: "failed", label: "失败" },
  { key: "waiting_approval", label: "待审批" },
] as const;

export default function Runs() {
  const { t } = useI18n();
  const [data, setData] = useState<BusinessRunRecord[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("all");

  const loadData = useCallback(() => {
    setData(null);
    setError(null);
    portalApi
      .getBusinessRuns(undefined, 50)
      .then((res: BusinessRunsResponse) => setData(res.runs ?? []))
      .catch((e) => setError(String(e?.message ?? e)));
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // Auto-refresh every 30s
  useEffect(() => {
    const timer = setInterval(loadData, 30_000);
    return () => clearInterval(timer);
  }, [loadData]);

  // Filter + search
  const filtered = useMemo(() => {
    if (!data) return null;
    return data.filter((r) => {
      if (statusFilter !== "all" && (r.status ?? "").toLowerCase() !== statusFilter) return false;
      if (search.trim()) {
        const q = search.toLowerCase();
        const hay = `${r.taskTitle ?? ""} ${r.scenario ?? ""} ${r.runId ?? ""}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [data, search, statusFilter]);

  const grouped = filtered ? groupByDate(filtered) : null;

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-4">
          <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
            {t("nav.runs")}
          </h1>
          <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
            最近的运行记录
          </p>
        </header>

        {/* Search + filter */}
        <div className="mb-4 space-y-2">
          <SearchBar value={search} onChange={setSearch} placeholder="搜索运行..." />
          <div className="flex gap-1.5 overflow-x-auto scrollbar-none">
            {STATUS_FILTERS.map((f) => (
              <button
                key={f.key}
                onClick={() => setStatusFilter(f.key)}
                className={cn(
                  "shrink-0 rounded-full px-3 py-1.5 text-[12px] font-medium transition",
                  statusFilter === f.key
                    ? "bg-[var(--color-text-primary)] text-[var(--color-bg-0)]"
                    : "bg-[oklch(0.30_0.02_50_/_0.4)] text-[var(--color-text-secondary)]",
                )}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>

        {error && <ErrorCard message={error} onRetry={loadData} />}

        {!filtered && !error ? (
          <div className="space-y-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="shimmer h-16 rounded-2xl" />
            ))}
          </div>
        ) : filtered && filtered.length === 0 ? (
          <GlassCard tone="accent" grain className="flex flex-col items-center gap-3 py-10 text-center">
            <Inbox className="h-7 w-7 text-[var(--color-accent)]" />
            <p className="text-[14px] font-semibold text-[var(--color-text-primary)]">
              {search || statusFilter !== "all" ? "没有匹配的运行" : t("runs.empty")}
            </p>
            {!search && statusFilter === "all" && (
              <Link
                to="/templates"
                className="mt-1 inline-flex items-center gap-1.5 rounded-full bg-[var(--color-text-primary)] px-4 py-2 text-[12px] font-semibold text-[var(--color-bg-0)] active:scale-95 transition"
              >
                <Play className="h-3.5 w-3.5" />
                选个场景
              </Link>
            )}
          </GlassCard>
        ) : grouped ? (
          <div className="space-y-5">
            {grouped.map(({ label, runs }) => (
              <div key={label}>
                <h2 className="mb-2 px-0.5 text-[12px] font-semibold tracking-[0.15em] uppercase text-[var(--color-text-muted)]">
                  {label}
                </h2>
                <GlassCard padding="sm" className="divide-y divide-[oklch(0.30_0.015_50_/_0.4)]">
                  {runs.map((r) => (
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
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </AuroraBackground>
  );
}

function groupByDate(runs: BusinessRunRecord[]): { label: string; runs: BusinessRunRecord[] }[] {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(today.getTime() - 86400000);
  const weekAgo = new Date(today.getTime() - 7 * 86400000);

  const groups: Record<string, BusinessRunRecord[]> = {
    "今天": [],
    "昨天": [],
    "本周": [],
    "更早": [],
  };

  for (const r of runs) {
    const d = new Date(r.createdAt ?? 0);
    if (d >= today) groups["今天"].push(r);
    else if (d >= yesterday) groups["昨天"].push(r);
    else if (d >= weekAgo) groups["本周"].push(r);
    else groups["更早"].push(r);
  }

  return Object.entries(groups)
    .filter(([, runs]) => runs.length > 0)
    .map(([label, runs]) => ({ label, runs }));
}
