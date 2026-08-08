import { useEffect, useState, useCallback } from "react";
import { portalApi } from "@/api/portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { ErrorCard } from "@/components/ErrorCard";
import { Bookmark, Star, Clock, ChevronRight } from "lucide-react";
import { cn } from "@hermes/ui";

const DEFAULT_TENANT = "default";

interface SessionAssetItem {
  id: string;
  tenantId: string;
  userId: string;
  sessionId: string;
  title: string;
  summary: string;
  status: string;
  bookmarked: boolean;
  rating: number;
  userComment: string;
  tags: string[];
  createdAt: number;
  updatedAt: number;
  completedAt: number | null;
}

export default function Sessions() {
  const [sessions, setSessions] = useState<SessionAssetItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [bookmarkedOnly, setBookmarkedOnly] = useState(false);

  const loadData = useCallback(() => {
    setLoading(true);
    setError(null);
    portalApi
      .getSessionAssets(DEFAULT_TENANT, { bookmarked: bookmarkedOnly || undefined, size: 50 })
      .then((r) => {
        setSessions(r.items || []);
        setLoading(false);
      })
      .catch((e) => {
        setError(String(e?.message ?? e));
        setLoading(false);
      });
  }, [bookmarkedOnly]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="font-display text-[28px] font-medium leading-tight text-[var(--color-text-primary)]">
            会话历史
          </h1>
          <p className="mt-1 text-[13px] text-[var(--color-text-secondary)]">
            查看你的历史会话，收藏和评分
          </p>
        </header>

        {error && <ErrorCard message={error} onRetry={loadData} />}

        {/* Filter bar */}
        <div className="mb-4 flex gap-2">
          <button
            onClick={() => setBookmarkedOnly(!bookmarkedOnly)}
            className={cn(
              "rounded-full px-4 py-1.5 text-[12px] font-medium transition-all",
              bookmarkedOnly
                ? "bg-[oklch(0.78_0.16_70)] text-[oklch(0.15_0.02_70)]"
                : "glass text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)]"
            )}
          >
            <Bookmark className="mr-1 inline h-3 w-3" />
            {bookmarkedOnly ? "已收藏" : "全部"}
          </button>
        </div>

        {/* Session list */}
        {loading ? (
          <div className="space-y-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="shimmer h-24 rounded-2xl" />
            ))}
          </div>
        ) : sessions.length === 0 ? (
          <GlassCard className="py-12 text-center">
            <Clock className="mx-auto mb-2 h-6 w-6 opacity-40" />
            <p className="text-[13px] text-[var(--color-text-muted)]">暂无会话记录</p>
          </GlassCard>
        ) : (
          <div className="space-y-2">
            {sessions.map((session) => (
              <SessionCard key={session.id} session={session} />
            ))}
          </div>
        )}
      </div>
    </AuroraBackground>
  );
}

function SessionCard({ session }: { session: SessionAssetItem }) {
  const [expanded, setExpanded] = useState(false);

  const statusColors: Record<string, string> = {
    ACTIVE: "oklch(0.72_0.12_160)",
    COMPLETED: "oklch(0.65_0.08_250)",
    ARCHIVED: "oklch(0.55_0.05_50)",
  };

  return (
    <GlassCard padding="md">
      <div
        className="cursor-pointer"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              {session.bookmarked && (
                <Bookmark className="h-3.5 w-3.5 fill-[oklch(0.78_0.16_70)] text-[oklch(0.78_0.16_70)]" />
              )}
              <h3 className="truncate text-[14px] font-semibold text-[var(--color-text-primary)]">
                {session.title || "未命名会话"}
              </h3>
              <span
                className="shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium"
                style={{
                  background: `oklch(from ${statusColors[session.status] || "oklch(0.5_0_0)"} l c h / 0.15)`,
                  color: statusColors[session.status] || "inherit",
                }}
              >
                {session.status === "ACTIVE" ? "活跃" : session.status === "COMPLETED" ? "已完成" : "已归档"}
              </span>
            </div>

            {session.summary && (
              <p className="mt-1 line-clamp-2 text-[12px] text-[var(--color-text-secondary)]">
                {session.summary}
              </p>
            )}

            <div className="mt-2 flex items-center gap-3 text-[10px] text-[var(--color-text-muted)]">
              <span>{formatTime(new Date(session.updatedAt).toISOString())}</span>
              {session.rating > 0 && (
                <span className="flex items-center gap-0.5">
                  <Star className="h-3 w-3 fill-[oklch(0.78_0.16_70)] text-[oklch(0.78_0.16_70)]" />
                  {session.rating}
                </span>
              )}
              {session.tags.length > 0 && (
                <div className="flex gap-1">
                  {session.tags.slice(0, 3).map((tag) => (
                    <span key={tag} className="rounded-full bg-[oklch(0.3_0.02_70_/_0.4)] px-2 py-0.5">
                      {tag}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>
          <ChevronRight
            className={cn(
              "h-4 w-4 shrink-0 text-[var(--color-text-muted)] transition-transform",
              expanded && "rotate-90"
            )}
          />
        </div>
      </div>

      {expanded && (
        <div className="mt-3 border-t border-[oklch(0.3_0.02_70_/_0.3)] pt-3">
          {session.userComment && (
            <p className="mb-2 text-[12px] text-[var(--color-text-secondary)] italic">
              "{session.userComment}"
            </p>
          )}
          {session.summary && (
            <p className="text-[12px] text-[var(--color-text-primary)] leading-relaxed">
              {session.summary}
            </p>
          )}
          <div className="mt-2 flex items-center gap-3 text-[10px] text-[var(--color-text-muted)]">
            <span>ID: {session.sessionId}</span>
            <span>创建: {formatTime(new Date(session.createdAt).toISOString())}</span>
          </div>
        </div>
      )}
    </GlassCard>
  );
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return "刚刚";
    if (diffMin < 60) return `${diffMin} 分钟前`;
    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr} 小时前`;
    const diffDay = Math.floor(diffHr / 24);
    if (diffDay < 30) return `${diffDay} 天前`;
    return d.toLocaleDateString();
  } catch {
    return iso;
  }
}
