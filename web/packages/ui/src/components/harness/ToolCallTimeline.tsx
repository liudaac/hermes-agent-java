/**
 * ToolCallTimeline - real-time tool call list.
 *
 * Shows each tool call as it happens (not batch after completion).
 * Used in the chat stream below the assistant message.
 */
import type { ToolCallState } from "../../types/agent-event";
import { cn } from "../../lib/cn";

interface Props {
  calls: ToolCallState[];
  className?: string;
}

const STATUS_ICON: Record<string, string> = {
  running: "🔄",
  done: "✅",
  error: "❌",
  approval_needed: "⏸",
};

export function ToolCallTimeline({ calls, className }: Props) {
  if (calls.length === 0) return null;

  return (
    <div className={cn("space-y-1.5", className)}>
      {calls.map((tc, i) => (
        <div
          key={tc.callId || i}
          className="flex items-center gap-2 rounded-lg bg-zinc-900/40 px-3 py-1.5 text-xs"
        >
          <span>{STATUS_ICON[tc.status] ?? "•"}</span>
          <code className="text-zinc-300 font-mono">{tc.name}</code>
          {tc.durationMs != null && (
            <span className="text-zinc-500 ml-auto">
              {(tc.durationMs / 1000).toFixed(1)}s
            </span>
          )}
          {tc.status === "running" && (
            <span className="text-blue-400 ml-auto">执行中...</span>
          )}
          {tc.status === "approval_needed" && (
            <span className="text-amber-400 ml-auto">等待审批</span>
          )}
        </div>
      ))}
    </div>
  );
}
