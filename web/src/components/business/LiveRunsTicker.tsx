import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronRight, Loader2 } from "lucide-react";
import { api, type BusinessRunRecord } from "@/lib/api";
import { cn } from "@/lib/utils";

/**
 * LiveRunsTicker
 *
 * Hero-area "right now happening" ribbon for the Business Portal home page.
 * - Polls /business/runs every ~20s
 * - Prefers RUNNING / PROCESSING runs; falls back to the most recent finished run
 *   so the ribbon is never empty as long as the workspace has any activity.
 * - Cycles through up to 5 items every ~4s with a fade transition.
 * - Click → jump to Run detail.
 *
 * Visually: a thin pill that reads like a real-time activity strip — to
 * counter the "static report" feeling of the previous static hero.
 */

type Tone = "live" | "recent";

interface TickerItem {
  runId: string;
  workspaceId?: string;
  text: string;
  tone: Tone;
  ts: number;
}

const POLL_MS = 20_000;
const CYCLE_MS = 4_000;
const MAX_ITEMS = 5;

export default function LiveRunsTicker({ workspaceId }: { workspaceId?: string }) {
  const navigate = useNavigate();
  const [items, setItems] = useState<TickerItem[]>([]);
  const [cursor, setCursor] = useState(0);
  const [loading, setLoading] = useState(true);
  const [fade, setFade] = useState(false);
  const timerRef = useRef<number | null>(null);

  // Fetch + poll
  useEffect(() => {
    let alive = true;
    const fetchRuns = async () => {
      try {
        const res = await api.getBusinessRuns(workspaceId, 20);
        if (!alive) return;
        const all = res.runs ?? [];
        const running = all.filter((r) => isLiveStatus(r.status));
        const candidates = (running.length > 0 ? running : all).slice(0, MAX_ITEMS);
        const mapped: TickerItem[] = candidates.map((r) => ({
          runId: r.runId,
          workspaceId: r.workspaceId,
          text: formatLine(r),
          tone: isLiveStatus(r.status) ? "live" : "recent",
          ts: Date.parse(r.createdAt || "") || Date.now(),
        }));
        setItems(mapped);
        setCursor(0);
      } catch {
        /* swallow; keep last good state */
      } finally {
        if (alive) setLoading(false);
      }
    };
    fetchRuns();
    const id = window.setInterval(fetchRuns, POLL_MS);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [workspaceId]);

  // Cycle
  useEffect(() => {
    if (items.length <= 1) return;
    timerRef.current = window.setInterval(() => {
      setFade(true);
      window.setTimeout(() => {
        setCursor((c) => (c + 1) % items.length);
        setFade(false);
      }, 200);
    }, CYCLE_MS);
    return () => {
      if (timerRef.current) window.clearInterval(timerRef.current);
    };
  }, [items.length]);

  const current = items[cursor];

  // Hide entirely on first-load if there is truly nothing.
  if (!loading && items.length === 0) return null;

  const isLive = current?.tone === "live";

  return (
    <button
      type="button"
      onClick={() => {
        if (current?.workspaceId && current?.runId) {
          navigate(`/runs/${current.workspaceId}/${current.runId}`);
        }
      }}
      className={cn(
        "group mt-3 inline-flex max-w-full items-center gap-2 rounded-full border px-3 py-1.5 backdrop-blur",
        "border-border/80 bg-background/60 transition-colors hover:border-foreground/40",
      )}
      aria-label="跳转到当前运行的 Run"
    >
      {/* status dot */}
      <span
        className={cn(
          "relative inline-flex h-2 w-2 flex-shrink-0 rounded-full",
          isLive ? "bg-orange-500" : "bg-muted-foreground/70",
        )}
      >
        {isLive && (
          <span className="absolute inset-0 inline-flex h-full w-full animate-ping rounded-full bg-orange-500/60" />
        )}
      </span>

      {/* label */}
      <span className="hidden font-mono text-[10px] uppercase tracking-[0.15em] text-muted-foreground sm:inline">
        {isLive ? "进行中" : "最近"}
      </span>

      {/* current line */}
      <span
        className={cn(
          "min-w-0 max-w-[min(60vw,28rem)] truncate text-xs text-foreground transition-opacity duration-200",
          fade && "opacity-0",
        )}
      >
        {loading ? (
          <span className="inline-flex items-center gap-1 text-muted-foreground">
            <Loader2 className="h-3 w-3 animate-spin" />
            正在加载团队运行情况…
          </span>
        ) : (
          current?.text
        )}
      </span>

      {/* indicators */}
      {items.length > 1 && (
        <span className="ml-1 hidden gap-0.5 sm:inline-flex">
          {items.map((_, i) => (
            <span
              key={i}
              className={cn(
                "h-1 w-1 rounded-full transition-colors",
                i === cursor ? "bg-foreground" : "bg-muted-foreground/40",
              )}
            />
          ))}
        </span>
      )}

      <ChevronRight className="h-3 w-3 flex-shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5" />
    </button>
  );
}

/* ---------- helpers ---------- */

function isLiveStatus(s?: string): boolean {
  if (!s) return false;
  const u = s.toUpperCase();
  return u === "RUNNING" || u === "PROCESSING" || u === "PENDING";
}

function formatLine(r: BusinessRunRecord): string {
  const title = r.taskTitle || r.scenario || r.runId;
  const status = (r.status || "").toUpperCase();
  if (isLiveStatus(r.status)) {
    // "<team> · 正在跑：<title>"
    if (r.teamId) return `${r.teamId} · 正在跑：${title}`;
    return `正在跑：${title}`;
  }
  // Recent / finished: show a one-line conclusion
  const verb =
    status === "COMPLETED" || status === "SUCCESS" || status === "DONE"
      ? "已完成"
      : status === "FAILED" || status === "ERROR"
        ? "已失败"
        : "已停止";
  if (r.resultSummary) return `${verb}：${title} — ${r.resultSummary}`.slice(0, 160);
  return `${verb}：${title}`;
}
