import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  MessageSquare,
  X,
  Sparkles,
  Send,
  ArrowRight,
  Loader2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { api, type BusinessRunRecord, type BusinessRunStep } from "@/lib/api";

/**
 * FloatingBusinessChat — multi-agent relay preview FAB (Step F).
 *
 * Three tabs inside the panel:
 *   1. Live   - typewriter relay of the latest Run's steps; polls every 8s
 *   2. Quick  - 4 quick prompts → /playground
 *   3. Say    - free-form textarea → /playground
 *
 * Empty workspace? Synthesized demo relay plays so the panel never feels
 * empty.
 */

type Tab = "live" | "quick" | "say";

const QUICK_PROMPTS = [
  "我有 50 份简历需要筛选",
  "我要处理一张供应商发票",
  "员工申请离职，启动流程",
  "新员工后天入职，开始准备",
];

interface RelayBubble {
  actor: string;
  text: string;
  tone: "neutral" | "success" | "danger" | "live";
  ts?: string;
}

const SYNTHETIC_RELAY: RelayBubble[] = [
  { actor: "你", text: "新同事张三下周一入职，开始准备", tone: "neutral" },
  { actor: "跨域协调员", text: "识别为跨域入职流程，分发 HR / 固资 / 财务 子任务", tone: "live" },
  { actor: "入离调转专员", text: "起草劳动合同 + 保密协议 + 入职登记表，发出电子签", tone: "success" },
  { actor: "资产管理员", text: "并行生成 4 个工单：电脑 / 工卡 / 座位 / 门禁", tone: "success" },
  { actor: "应付会计", text: "推送银行卡办理流程 + 五险一金材料清单", tone: "success" },
  { actor: "团队总结", text: "入职 SLA T+1 天 · 4 个工单已派发 · 电子签 1 已送达", tone: "success" },
];

export default function FloatingBusinessChat({
  workspaceId,
  scenarioId,
}: {
  workspaceId?: string;
  scenarioId?: string;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<Tab>("live");
  const [draft, setDraft] = useState("");

  const [run, setRun] = useState<BusinessRunRecord | null>(null);
  const [runLoading, setRunLoading] = useState(true);
  const [typedCount, setTypedCount] = useState(0);
  const [typing, setTyping] = useState<string>("");
  const typingTimerRef = useRef<number | null>(null);

  useEffect(() => {
    if (!open) return;
    if (tab !== "live") return;
    let alive = true;
    const fetchRun = async () => {
      try {
        setRunLoading(true);
        const res = await api.getBusinessRuns(workspaceId, 1);
        if (!alive) return;
        const latest = res.runs?.[0] ?? null;
        setRun((prev) => {
          if (prev?.runId !== latest?.runId) {
            setTypedCount(0);
            setTyping("");
          }
          return latest;
        });
      } catch {
        if (alive) setRun(null);
      } finally {
        if (alive) setRunLoading(false);
      }
    };
    fetchRun();
    const id = window.setInterval(fetchRun, 8000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [open, tab, workspaceId]);

  const bubbles = useMemo<RelayBubble[]>(() => {
    if (run && run.steps && run.steps.length > 0) {
      const list: RelayBubble[] = [];
      list.push({
        actor: "你",
        text: run.taskTitle || run.taskInput || `${run.scenario ?? "任务"} 已下发`,
        tone: "neutral",
        ts: run.createdAt,
      });
      run.steps.forEach((s) => list.push(stepToBubble(s)));
      if (run.resultSummary) {
        list.push({
          actor: "团队总结",
          text: run.resultSummary,
          tone: isLive(run.status) ? "live" : isFailed(run.status) ? "danger" : "success",
        });
      }
      return list;
    }
    return SYNTHETIC_RELAY;
  }, [run]);

  useEffect(() => {
    if (!open || tab !== "live") return;
    if (typedCount >= bubbles.length) return;
    const target = bubbles[typedCount].text;
    let i = 0;
    setTyping("");
    const tick = () => {
      i = Math.min(target.length, i + Math.max(1, Math.floor(target.length / 28)));
      setTyping(target.slice(0, i));
      if (i >= target.length) {
        typingTimerRef.current = window.setTimeout(() => setTypedCount((c) => c + 1), 380);
      } else {
        typingTimerRef.current = window.setTimeout(tick, 24);
      }
    };
    tick();
    return () => {
      if (typingTimerRef.current) window.clearTimeout(typingTimerRef.current);
    };
  }, [typedCount, bubbles, open, tab]);

  const send = (prompt: string) => {
    const text = prompt.trim();
    if (!text) return;
    const params = new URLSearchParams();
    if (workspaceId) params.set("workspaceId", workspaceId);
    if (scenarioId) params.set("scenarioId", scenarioId);
    params.set("prompt", text);
    navigate(`/playground?${params.toString()}`);
  };

  return (
    <>
      {/* FAB */}
      <button
        onClick={() => setOpen((o) => !o)}
        className={cn(
          "fixed bottom-5 right-5 z-40 flex h-14 w-14 items-center justify-center rounded-full text-white shadow-lg ring-2 ring-background transition-all",
          "bg-gradient-to-br from-orange-500 to-amber-500 hover:from-orange-400 hover:to-amber-400",
          open && "scale-90",
        )}
        aria-label="打开 AI 助手"
      >
        {!open && (
          <span aria-hidden className="status-pulse pointer-events-none absolute inset-0 -z-10 rounded-full text-orange-500" />
        )}
        {open ? <X className="h-6 w-6" /> : <MessageSquare className="h-6 w-6" />}
      </button>

      {open && (
        <div className={cn(
          "glass-card fixed bottom-24 right-5 z-40 flex max-h-[80vh] w-[calc(100vw-2.5rem)] max-w-sm flex-col overflow-hidden shadow-2xl",
          "animate-in slide-in-from-bottom-4 duration-200",
        )}>
          {/* Header */}
          <div className="aurora-bg relative border-b border-border/60 px-4 py-3">
            <div className="flex items-center gap-2">
              <div className="relative">
                <div className="flex h-8 w-8 items-center justify-center rounded-full bg-orange-500/20 text-orange-600 dark:text-orange-400">
                  <Sparkles className="h-4 w-4" />
                </div>
                <span aria-hidden className="status-pulse absolute inset-0 rounded-full text-orange-500" />
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-semibold tracking-tight">数字员工助理</p>
                <p className="truncate text-xs text-muted-foreground">
                  {workspaceId ? `当前空间：${workspaceId}` : "随时开口，把工作交给团队"}
                </p>
              </div>
            </div>
            <div className="mt-3 flex gap-1 text-xs">
              <TabButton active={tab === "live"} onClick={() => setTab("live")}>团队实况</TabButton>
              <TabButton active={tab === "quick"} onClick={() => setTab("quick")}>快捷指令</TabButton>
              <TabButton active={tab === "say"} onClick={() => setTab("say")}>直接说话</TabButton>
            </div>
          </div>

          {/* Body */}
          <div className="flex-1 overflow-y-auto px-3 py-3">
            {tab === "live" && (
              <LiveRelay
                bubbles={bubbles}
                typedCount={typedCount}
                typing={typing}
                loading={runLoading && !run}
                isSynthetic={!run}
                onSeeAll={() => {
                  if (run?.workspaceId && run?.runId) {
                    setOpen(false);
                    navigate(`/runs/${run.workspaceId}/${run.runId}`);
                  }
                }}
              />
            )}
            {tab === "quick" && (
              <div className="space-y-2">
                <p className="text-xs uppercase tracking-wider text-muted-foreground">快捷指令</p>
                <div className="flex flex-col gap-1.5">
                  {QUICK_PROMPTS.map((prompt) => (
                    <button
                      key={prompt}
                      onClick={() => send(prompt)}
                      className="group flex items-center justify-between rounded-md border border-border/70 bg-background/50 px-3 py-2 text-left text-sm backdrop-blur-sm transition-colors hover:border-border hover:bg-foreground/[0.03]"
                    >
                      <span>{prompt}</span>
                      <ArrowRight className="h-3 w-3 opacity-40 transition-all group-hover:translate-x-0.5 group-hover:opacity-80" />
                    </button>
                  ))}
                </div>
              </div>
            )}
            {tab === "say" && (
              <div className="flex h-full flex-col">
                <textarea
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && !e.shiftKey) {
                      e.preventDefault();
                      send(draft);
                    }
                  }}
                  placeholder="告诉我你想做什么…(Enter 提交，Shift+Enter 换行)"
                  rows={6}
                  className="w-full flex-1 resize-none rounded-md border border-border/70 bg-background/50 px-3 py-2 text-sm backdrop-blur-sm focus:border-border focus:outline-none focus:ring-1 focus:ring-orange-500/40"
                />
                <Button className="mt-2 w-full" onClick={() => send(draft)} disabled={!draft.trim()}>
                  <Send className="mr-1.5 h-3.5 w-3.5" /> 发送到工作台
                </Button>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
}

/* ---------- subviews ---------- */

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={[
        "rounded-md border px-2.5 py-1 transition-colors",
        active
          ? "border-orange-500/40 bg-orange-500/10 text-orange-600 dark:text-orange-300"
          : "border-border/60 bg-background/40 text-muted-foreground hover:text-foreground",
      ].join(" ")}
    >
      {children}
    </button>
  );
}

function LiveRelay({
  bubbles,
  typedCount,
  typing,
  loading,
  isSynthetic,
  onSeeAll,
}: {
  bubbles: RelayBubble[];
  typedCount: number;
  typing: string;
  loading: boolean;
  isSynthetic: boolean;
  onSeeAll: () => void;
}) {
  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-12 text-xs text-muted-foreground">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        正在加载团队动态…
      </div>
    );
  }

  return (
    <div className="space-y-2.5">
      {isSynthetic && (
        <div className="rounded-md border border-dashed border-border/60 bg-background/30 px-3 py-2 text-[0.7rem] text-muted-foreground">
          <span className="font-mono text-amber-500">DEMO · </span>
          还没有真实运行记录。这是一段示例对话，展示团队是怎么接力工作的。
        </div>
      )}

      {bubbles.slice(0, typedCount).map((b, i) => (
        <BubbleView key={i} bubble={b} />
      ))}

      {/* Currently typing bubble */}
      {typedCount < bubbles.length && (
        <BubbleView
          bubble={{ ...bubbles[typedCount], text: typing || "​" }}
          typing
        />
      )}

      {/* Footer */}
      {typedCount >= bubbles.length && !isSynthetic && onSeeAll && (
        <button
          onClick={onSeeAll}
          className="group mt-2 inline-flex items-center gap-1.5 rounded-full border border-border/60 bg-background/50 px-3 py-1.5 text-xs text-muted-foreground backdrop-blur-sm transition-colors hover:text-foreground"
        >
          看完整 Run 故事
          <ArrowRight className="h-3 w-3 transition-transform group-hover:translate-x-0.5" />
        </button>
      )}
    </div>
  );
}

function BubbleView({ bubble, typing }: { bubble: RelayBubble; typing?: boolean }) {
  const isUser = bubble.actor === "你";
  const toneClass =
    bubble.tone === "success"
      ? "border-emerald-500/30 bg-emerald-500/5"
      : bubble.tone === "danger"
        ? "border-rose-500/30 bg-rose-500/5"
        : bubble.tone === "live"
          ? "border-sky-500/30 bg-sky-500/5"
          : "border-border/60 bg-background/40";
  return (
    <div className={cn("flex flex-col gap-1", isUser && "items-end")}>
      <div className="flex items-center gap-1.5 text-[0.65rem] uppercase tracking-wider text-muted-foreground">
        {!isUser && bubble.tone === "live" && (
          <span className="inline-flex h-1.5 w-1.5 items-center justify-center">
            <span className="absolute h-1.5 w-1.5 rounded-full bg-sky-500" />
            <span className="status-pulse absolute h-1.5 w-1.5 rounded-full text-sky-500" />
          </span>
        )}
        <span>{bubble.actor}</span>
      </div>
      <div
        className={cn(
          "max-w-[88%] rounded-2xl border px-3 py-2 text-sm leading-relaxed backdrop-blur-sm",
          toneClass,
          isUser ? "rounded-tr-sm" : "rounded-tl-sm",
        )}
      >
        {bubble.text}
        {typing && (
          <span className="ml-0.5 inline-block h-3 w-[2px] animate-pulse bg-current align-middle" />
        )}
      </div>
    </div>
  );
}

/* ---------- helpers ---------- */

function isLive(s?: string): boolean {
  const u = (s ?? "").toUpperCase();
  return u === "RUNNING" || u === "PROCESSING" || u === "PENDING" || u === "IN_PROGRESS";
}

function isFailed(s?: string): boolean {
  const u = (s ?? "").toUpperCase();
  return u === "FAILED" || u === "ERROR";
}

function stepToBubble(s: BusinessRunStep): RelayBubble {
  const actor = s.actor ?? s.agentId ?? "数字员工";
  const text = s.summary ?? s.title ?? "—";
  const status = (s.status ?? "").toUpperCase();
  let tone: RelayBubble["tone"] = "neutral";
  if (["COMPLETED", "SUCCESS", "DONE", "OK"].includes(status)) tone = "success";
  else if (["FAILED", "ERROR"].includes(status)) tone = "danger";
  else if (["RUNNING", "PROCESSING", "IN_PROGRESS", "PENDING"].includes(status)) tone = "live";
  return { actor, text, tone, ts: s.timestamp };
}
