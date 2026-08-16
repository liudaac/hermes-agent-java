import { useEffect, useState, useCallback } from "react";
import { portalApi } from "@/api/portal";
import { GlassCard } from "@/components/GlassCard";
import { AuroraBackground } from "@/components/AuroraBackground";
import { ErrorCard } from "@/components/ErrorCard";
import { StatusPill } from "@/components/StatusPill";
import { useI18n } from "@/i18n";
import { useWorkspace } from "@/hooks/useWorkspace";
import { cn, formatRelativeTime } from "@hermes/ui";
import { Star, Bookmark, MessageSquare, ChevronLeft, ChevronRight } from "lucide-react";

interface SessionItem {
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

type Filter = "all" | "bookmarked" | "active" | "completed" | "archived";

const FILTERS: { key: Filter; labelKey: string }[] = [
  { key: "all", labelKey: "sessions.all" },
  { key: "bookmarked", labelKey: "sessions.bookmarked" },
  { key: "active", labelKey: "sessions.active" },
  { key: "completed", labelKey: "sessions.completed" },
  { key: "archived", labelKey: "sessions.archived" },
];

export default function Sessions() {
  const { t } = useI18n();
  const { workspaceId } = useWorkspace();
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [total, setTotal] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const tenantId = workspaceId ?? "default";
  const PAGE_SIZE = 20;

  const loadSessions = useCallback(() => {
    setLoading(true);
    setError(null);
    const opts: Parameters<typeof portalApi.getSessionAssets>[1] = {
      page,
      size: PAGE_SIZE,
    };
    if (filter === "bookmarked") opts.bookmarked = true;
    if (filter === "active") opts.status = "active";
    if (filter === "completed") opts.status = "completed";
    if (filter === "archived") opts.status = "archived";

    portalApi
      .getSessionAssets(tenantId, opts)
      .then((res) => {
        setSessions(res.items ?? []);
        setHasNext(res.hasNext ?? false);
        setTotal(res.total ?? 0);
      })
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [tenantId, filter, page]);

  useEffect(() => {
    loadSessions();
  }, [loadSessions]);

  const handleBookmark = async (session: SessionItem) => {
    setBusyId(session.id);
    try {
      // Toggle bookmark via fetch
      const res = await fetch(`/api/session-assets/${encodeURIComponent(tenantId)}/${encodeURIComponent(session.id)}/bookmark`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ bookmarked: !session.bookmarked }),
      });
      if (res.ok) {
        setSessions((cur) =>
          cur.map((s) => (s.id === session.id ? { ...s, bookmarked: !s.bookmarked } : s)),
        );
      }
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setBusyId(null);
    }
  };

  const handleRating = async (session: SessionItem, rating: number) => {
    setBusyId(session.id);
    try {
      const res = await fetch(`/api/session-assets/${encodeURIComponent(tenantId)}/${encodeURIComponent(session.id)}/rate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ rating }),
      });
      if (res.ok) {
        setSessions((cur) =>
          cur.map((s) => (s.id === session.id ? { ...s, rating } : s)),
        );
      }
    } catch (e: any) {
      setError(String(e?.message ?? e));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <AuroraBackground>
      <div className="page-in mx-auto max-w-3xl px-4 pb-24 pt-6">
        <header className="mb-5">
          <h1 className="text-[28px] font-medium leading-tight text-foreground">
            {t("sessions.title")}
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            {t("sessions.subtitle")}
          </p>
        </header>

        {/* Filter chips */}
        <div className="mb-4 flex gap-2 overflow-x-auto pb-1">
          {FILTERS.map(({ key, labelKey }) => (
            <button
              key={key}
              onClick={() => { setFilter(key); setPage(0); }}
              className={cn(
                "rounded-full px-3 py-1.5 text-[12px] font-medium whitespace-nowrap transition active:scale-95",
                filter === key
                  ? "bg-primary/10 text-primary"
                  : "bg-muted/60 text-muted-foreground",
              )}
            >
              {t(labelKey)}
            </button>
          ))}
        </div>

        {error && <ErrorCard message={error} onRetry={loadSessions} />}

        {loading ? (
          <div className="space-y-3">
            {[0, 1, 2].map((i) => (
              <div key={i} className="shimmer h-24 rounded-2xl" />
            ))}
          </div>
        ) : sessions.length === 0 ? (
          <GlassCard className="flex flex-col items-center gap-2 py-10 text-center">
            <MessageSquare className="h-6 w-6 text-muted-foreground" />
            <p className="text-[13px] text-muted-foreground">{t("sessions.noSessions")}</p>
          </GlassCard>
        ) : (
          <>
            <div className="space-y-3">
              {sessions.map((s) => (
                <GlassCard
                  key={s.id}
                  padding="sm"
                  interactive
                  onClick={() => setExpandedId(expandedId === s.id ? null : s.id)}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <h3 className="truncate text-[14px] font-semibold text-foreground">
                          {s.title || "未命名"}
                        </h3>
                        <StatusPill status={s.status} />
                      </div>
                      {s.summary && (
                        <p className="mt-1 line-clamp-2 text-[12px] leading-relaxed text-muted-foreground">
                          {s.summary}
                        </p>
                      )}
                      <div className="mt-1.5 flex items-center gap-2 text-[11px] text-muted-foreground">
                        <span>{formatRelativeTime(s.createdAt)}</span>
                        {s.tags.length > 0 && (
                          <>
                            <span>·</span>
                            <div className="flex gap-1">
                              {s.tags.slice(0, 3).map((tag) => (
                                <span key={tag} className="rounded-full bg-muted/60 px-1.5 py-0.5">
                                  {tag}
                                </span>
                              ))}
                            </div>
                          </>
                        )}
                      </div>
                    </div>
                    {/* Bookmark */}
                    <button
                      onClick={(e) => { e.stopPropagation(); handleBookmark(s); }}
                      disabled={busyId === s.id}
                      className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg active:scale-95 transition"
                    >
                      <Bookmark
                        className={cn(
                          "h-4 w-4 transition",
                          s.bookmarked
                            ? "fill-primary text-primary"
                            : "text-muted-foreground",
                        )}
                      />
                    </button>
                  </div>

                  {/* Rating */}
                  <div className="mt-2 flex items-center gap-1">
                    {[1, 2, 3, 4, 5].map((star) => (
                      <button
                        key={star}
                        onClick={(e) => { e.stopPropagation(); handleRating(s, star); }}
                        disabled={busyId === s.id}
                        className="active:scale-95 transition"
                      >
                        <Star
                          className={cn(
                            "h-3.5 w-3.5",
                            star <= s.rating
                              ? "fill-primary text-primary"
                              : "text-muted-foreground",
                          )}
                        />
                      </button>
                    ))}
                  </div>

                  {/* Expanded details */}
                  {expandedId === s.id && (
                    <div className="mt-3 space-y-1.5 border-t border-white/5 pt-3 text-[12px] text-muted-foreground">
                      {s.userComment && (
                        <p><span className="font-medium text-muted-foreground">评论: </span>{s.userComment}</p>
                      )}
                      <p><span className="font-medium text-muted-foreground">Session ID: </span>{s.sessionId}</p>
                      {s.completedAt && (
                        <p><span className="font-medium text-muted-foreground">完成时间: </span>{formatRelativeTime(s.completedAt)}</p>
                      )}
                    </div>
                  )}
                </GlassCard>
              ))}
            </div>

            {/* Pagination */}
            <div className="mt-4 flex items-center justify-between">
              <span className="text-[12px] text-muted-foreground">
                共 {total} 条
              </span>
              <div className="flex gap-2">
                <button
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="flex h-9 w-9 items-center justify-center rounded-xl bg-muted/60 text-muted-foreground disabled:opacity-40 active:scale-95 transition"
                >
                  <ChevronLeft className="h-4 w-4" />
                </button>
                <button
                  onClick={() => setPage((p) => p + 1)}
                  disabled={!hasNext}
                  className="flex h-9 w-9 items-center justify-center rounded-xl bg-muted/60 text-muted-foreground disabled:opacity-40 active:scale-95 transition"
                >
                  <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </AuroraBackground>
  );
}
