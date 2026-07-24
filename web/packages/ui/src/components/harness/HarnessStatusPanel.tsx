/**
 * HarnessStatusPanel - right-side real-time agent status panel.
 *
 * Shows: lifecycle status, iteration budget, token usage, current phase,
 * and a phase timeline.
 */
import type { HarnessState } from "../../types/agent-event";
import { cn } from "../../lib/cn";

interface Props {
  state: HarnessState;
  className?: string;
}

const STATUS_STYLES: Record<string, string> = {
  idle: "text-zinc-400",
  running: "text-emerald-400",
  paused_approval: "text-amber-400",
  paused_governance: "text-amber-400",
  stopped: "text-zinc-500",
  error: "text-red-400",
};

const STATUS_DOT: Record<string, string> = {
  idle: "bg-zinc-500",
  running: "bg-emerald-500 animate-pulse",
  paused_approval: "bg-amber-500",
  paused_governance: "bg-amber-500",
  stopped: "bg-zinc-600",
  error: "bg-red-500",
};

const PHASE_LABELS: Record<string, string> = {
  thinking: "思考中",
  acting: "执行工具",
  observing: "观察结果",
  idle: "空闲",
  approval_pending: "等待审批",
};

export function HarnessStatusPanel({ state, className }: Props) {
  const toolsDone = state.toolCalls.filter((t) => t.status === "done").length;
  const toolsTotal = state.toolCalls.length;

  return (
    <div className={cn("rounded-xl border border-zinc-800 bg-zinc-900/50 p-4 space-y-3", className)}>
      {/* Status header */}
      <div className="flex items-center gap-2">
        <span className={cn("h-2 w-2 rounded-full", STATUS_DOT[state.status])} />
        <span className={cn("text-sm font-medium", STATUS_STYLES[state.status])}>
          {state.status === "running" ? "运行中" :
           state.status === "paused_approval" ? "等待审批" :
           state.status === "error" ? "错误" :
           state.status === "idle" ? "空闲" : state.status}
        </span>
        {state.phase !== "idle" && (
          <span className="text-xs text-zinc-500">
            · {PHASE_LABELS[state.phase] ?? state.phase}
          </span>
        )}
      </div>

      {/* Metrics grid */}
      <div className="grid grid-cols-3 gap-3">
        <Metric label="迭代" value={`${state.iteration}/${state.maxIterations || "?"}`} />
        <Metric label="Tokens" value={formatTokens(state.tokensUsed)} />
        <Metric label="工具" value={`${toolsDone}/${toolsTotal}`} />
      </div>

      {/* Phase timeline */}
      {state.toolCalls.length > 0 && (
        <div className="space-y-1">
          <p className="text-[10px] uppercase tracking-wider text-zinc-500">工具调用</p>
          {state.toolCalls.map((tc, i) => (
            <div key={tc.callId || i} className="flex items-center gap-2 text-xs">
              <span className={cn(
                "h-1.5 w-1.5 rounded-full",
                tc.status === "done" && "bg-emerald-500",
                tc.status === "running" && "bg-blue-500 animate-pulse",
                tc.status === "error" && "bg-red-500",
                tc.status === "approval_needed" && "bg-amber-500",
              )} />
              <span className="text-zinc-300 truncate flex-1">{tc.name}</span>
              {tc.durationMs != null && (
                <span className="text-zinc-500">{(tc.durationMs / 1000).toFixed(1)}s</span>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Error */}
      {state.error && (
        <div className="rounded-lg bg-red-950/40 border border-red-900/50 px-3 py-2">
          <p className="text-xs text-red-400">{state.error}</p>
        </div>
      )}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="text-center">
      <p className="text-[10px] uppercase tracking-wider text-zinc-500">{label}</p>
      <p className="text-sm font-medium text-zinc-200">{value}</p>
    </div>
  );
}

function formatTokens(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}
