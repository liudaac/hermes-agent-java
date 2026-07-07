/**
 * MessageBubble — the 4 message kinds (design.md §10).
 *
 *   1. user            right-aligned, primary tint
 *   2. jarvis          left-aligned, glass, with sparkles icon
 *   3. tool            dim, monospace, with chevron
 *   4. approval        amber border, prominent gate button
 */

import { Sparkles, Wrench, ShieldAlert, Send } from "lucide-react";
import type { JarvisMessage } from "../hooks/useJarvisStore";

interface MessageBubbleProps {
  msg: JarvisMessage;
  onApprove?: (approvalId: string) => void;
  onReject?: (approvalId: string) => void;
}

export function MessageBubble({ msg, onApprove, onReject }: MessageBubbleProps) {
  if (msg.role === "user") {
    return (
      <div className="flex justify-end">
        <div className="max-w-[80%] rounded-2xl rounded-tr-sm bg-[oklch(0.78_0.16_70_/_0.85)] px-3 py-2 text-[12px] leading-relaxed text-[oklch(0.18_0.04_60)]">
          {msg.text}
        </div>
      </div>
    );
  }

  if (msg.role === "tool") {
    return (
      <div className="flex items-start gap-2 rounded-lg border border-[oklch(0.30_0.015_50_/_0.4)] bg-[oklch(0.22_0.015_55_/_0.5)] px-3 py-2 font-mono text-[11px] text-[var(--color-text-muted)]">
        <Wrench className="mt-0.5 h-3 w-3 shrink-0 text-[oklch(0.78_0.12_200)]" />
        <span className="break-all">{msg.text}</span>
      </div>
    );
  }

  if (msg.role === "approval") {
    const approvalId = (msg.meta?.approvalId as string) ?? "";
    return (
      <div className="rounded-xl border border-[oklch(0.78_0.16_85_/_0.4)] bg-[oklch(0.78_0.16_85_/_0.08)] p-3">
        <div className="flex items-start gap-2">
          <ShieldAlert className="mt-0.5 h-4 w-4 text-[oklch(0.85_0.16_85)]" />
          <div className="min-w-0 flex-1">
            <p className="text-[12px] font-semibold text-[oklch(0.95_0.10_85)]">
              {msg.text}
            </p>
            <div className="mt-2 flex gap-2">
              <button
                type="button"
                onClick={() => onApprove?.(approvalId)}
                className="flex-1 inline-flex items-center justify-center gap-1.5 rounded-lg bg-[oklch(0.72_0.14_145_/_0.85)] px-2 py-1.5 text-[11px] font-semibold text-[oklch(0.18_0.04_145)] active:scale-95 transition"
              >
                批准
              </button>
              <button
                type="button"
                onClick={() => onReject?.(approvalId)}
                className="flex-1 rounded-lg bg-[oklch(0.30_0.02_50_/_0.6)] px-2 py-1.5 text-[11px] font-semibold text-[var(--color-text-secondary)] active:scale-95 transition"
              >
                驳回
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // default: jarvis reply
  return (
    <div className="flex items-start gap-2">
      <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[oklch(0.78_0.12_200_/_0.2)] text-[oklch(0.92_0.10_200)]">
        <Sparkles className="h-3 w-3" />
      </div>
      <div className="max-w-[85%] rounded-2xl rounded-tl-sm border border-[oklch(0.30_0.015_50_/_0.4)] bg-[oklch(0.22_0.015_55_/_0.6)] px-3 py-2 text-[12px] leading-relaxed text-[var(--color-text-primary)]">
        {msg.text}
      </div>
    </div>
  );
}

void Send;
