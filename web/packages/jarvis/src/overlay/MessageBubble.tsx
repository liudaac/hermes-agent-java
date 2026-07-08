/**
 * MessageBubble — JARVIS HUD style message line. Not a chat bubble — a
 * labeled, timestamped line in monospace, with a role tag prefix:
 *
 *   [USER]     12:04:32    帮我看下今天有多少待审批
 *   [JARVIS]   12:04:33    工作区 default 有 3 条 PENDING 审批...
 *   [TOOL]     12:04:34    调用 list_approvals
 *   [APPROVAL] 12:04:35    ⚠ 需要批准: 发送邮件 (MEDIUM)
 *
 * Pending thinking messages pulse. Error messages are red.
 */
import { useMemo } from "react";
import type { JarvisMessage } from "../hooks/useJarvisStore";

interface MessageBubbleProps {
  message: JarvisMessage;
}

const ROLE_STYLES: Record<string, { tag: string; color: string; bg?: string }> = {
  user:     { tag: "USER",     color: "oklch(0.85 0.05 200)" },
  jarvis:   { tag: "JARVIS",   color: "oklch(0.88 0.12 200)" },
  tool:     { tag: "TOOL",     color: "oklch(0.70 0.10 260)" },
  approval: { tag: "APPROVAL", color: "oklch(0.78 0.16 85)" },
};

export function MessageBubble({ message }: MessageBubbleProps) {
  const style = ROLE_STYLES[message.role] ?? ROLE_STYLES.jarvis;

  const time = useMemo(() => {
    const d = new Date(message.timestamp);
    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}:${String(d.getSeconds()).padStart(2, "0")}`;
  }, [message.timestamp]);

  const isError = message.error === true;
  const isPending = message.pending === true;

  return (
    <div
      className={[
        "group flex gap-3 py-1.5 text-[12px] leading-relaxed",
        "border-l-2 border-transparent pl-2",
        isPending ? "animate-pulse" : "",
        message.role === "jarvis" ? "border-l-[oklch(0.70_0.12_200/_0.3)]" : "",
        isError ? "border-l-[oklch(0.65_0.22_30)] text-[oklch(0.82_0.18_30)]" : "",
      ].join(" ")}
    >
      {/* Role tag — fixed-width */}
      <span
        className="w-[72px] shrink-0 text-right text-[9px] tracking-[0.2em] pt-0.5"
        style={{ color: style.color, opacity: isError ? 1 : 0.85 }}
      >
        [{style.tag}]
      </span>
      {/* Timestamp — fixed-width */}
      <span className="w-[60px] shrink-0 text-[9px] tracking-[0.15em] text-[oklch(0.45_0.08_200)] pt-0.5">
        {time}
      </span>
      {/* Message body */}
      <span
        className={[
          "min-w-0 flex-1 whitespace-pre-wrap break-words",
          isPending ? "text-[oklch(0.55_0.08_200)]" : "",
        ].join(" ")}
        style={isError ? { color: "oklch(0.82 0.18 30)" } : undefined}
      >
        {isPending && !message.text
          ? "processing..."
          : renderText(message.text, message.role)}
      </span>
    </div>
  );
}

/** Render plain text with minimal formatting — bold **x**, inline code. */
function renderText(text: string, _role: string) {
  if (!text) return null;
  // Split by **bold** markers and inline `code`. For HUD-style we keep it
  // light — monospace already does most of the work.
  const parts: React.ReactNode[] = [];
  const segments = text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g);
  segments.forEach((seg, i) => {
    if (!seg) return;
    if (seg.startsWith("**") && seg.endsWith("**")) {
      parts.push(
        <strong key={i} className="text-[oklch(0.92_0.08_200)]">
          {seg.slice(2, -2)}
        </strong>,
      );
    } else if (seg.startsWith("`") && seg.endsWith("`")) {
      parts.push(
        <code key={i} className="rounded bg-[oklch(0.75_0.18_200/_0.12)] px-1 text-[11px] text-[oklch(0.85_0.12_200)]">
          {seg.slice(1, -1)}
        </code>,
      );
    } else {
      parts.push(<span key={i}>{seg}</span>);
    }
  });
  return parts;
}
