/**
 * ApprovalInline - inline approval request card.
 *
 * Shows when a tool call requires human approval.
 * Renders inside the chat stream, not a separate page.
 */
import type { ToolCallState } from "../../types/agent-event";
import { cn } from "../../lib/cn";

interface Props {
  approval: ToolCallState;
  onDecide: (approved: boolean, reason?: string) => void;
  busy?: boolean;
  className?: string;
}

const RISK_COLORS: Record<string, string> = {
  HIGH: "border-red-900/60 bg-red-950/30",
  MEDIUM: "border-amber-900/60 bg-amber-950/30",
  LOW: "border-blue-900/60 bg-blue-950/30",
};

export function ApprovalInline({ approval, onDecide, busy, className }: Props) {
  return (
    <div
      className={cn(
        "rounded-xl border p-4 space-y-3",
        RISK_COLORS.MEDIUM,
        className,
      )}
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 text-lg">⚖️</span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-zinc-200">
            工具审批请求
          </p>
          <p className="text-xs text-zinc-400 mt-0.5">
            {approval.name}
          </p>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={busy}
          onClick={() => onDecide(true)}
          className={cn(
            "flex-1 rounded-lg py-2 text-sm font-medium transition",
            "bg-emerald-600 text-white hover:bg-emerald-500",
            "disabled:opacity-50 disabled:cursor-not-allowed",
          )}
        >
          批准
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => onDecide(false)}
          className={cn(
            "flex-1 rounded-lg py-2 text-sm font-medium transition",
            "bg-zinc-700 text-zinc-300 hover:bg-zinc-600",
            "disabled:opacity-50 disabled:cursor-not-allowed",
          )}
        >
          驳回
        </button>
      </div>
    </div>
  );
}
