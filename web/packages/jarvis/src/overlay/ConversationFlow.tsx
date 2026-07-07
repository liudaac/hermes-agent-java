/**
 * ConversationFlow — message list + input bar.
 *
 * Per design.md §8 (召唤态布局), the 360×420 overlay is split into
 * 4 vertical regions:
 *   1. Top — title / close / settings
 *   2. Particle area — small form (handled in JarvisOverlay)
 *   3. Message flow
 *   4. Input bar
 */
import { useEffect, useRef, useState } from "react";
import { useJarvisStore, type JarvisMessage } from "../hooks/useJarvisStore";
import { MessageBubble } from "./MessageBubble";

export function ConversationFlow({
  onSubmit,
  onApprove,
  onReject,
}: {
  onSubmit: (text: string) => void;
  onApprove?: (approvalId: string) => void;
  onReject?: (approvalId: string) => void;
}) {
  const messages = useJarvisStore((s) => s.messages);
  const [draft, setDraft] = useState("");
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [messages.length]);

  const submit = () => {
    const v = draft.trim();
    if (!v) return;
    setDraft("");
    onSubmit(v);
  };

  return (
    <div className="flex h-full flex-col">
      <div
        ref={listRef}
        className="flex-1 space-y-2 overflow-y-auto scrollbar-none px-3 py-2"
      >
        {messages.length === 0 ? (
          <p className="py-6 text-center text-[11px] text-[var(--color-text-muted)]">
            问我任何事 —— 团队、审批、告警、技能
          </p>
        ) : (
          messages.map((m) => (
            <MessageRow key={m.id} msg={m} onApprove={onApprove} onReject={onReject} />
          ))
        )}
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
        className="flex items-center gap-2 border-t border-[oklch(0.30_0.015_50_/_0.4)] px-3 py-2"
      >
        <input
          type="text"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="问 Jarvis ..."
          className="flex-1 bg-transparent text-[12px] text-[var(--color-text-primary)] placeholder:text-[var(--color-text-muted)] outline-none"
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
        />
        <button
          type="submit"
          className="rounded-full bg-[oklch(0.78_0.16_70_/_0.85)] px-3 py-1.5 text-[11px] font-semibold text-[oklch(0.18_0.04_60)] active:scale-95 transition"
        >
          发送
        </button>
      </form>
    </div>
  );
}

function MessageRow({
  msg,
  onApprove,
  onReject,
}: {
  msg: JarvisMessage;
  onApprove?: (id: string) => void;
  onReject?: (id: string) => void;
}) {
  return <MessageBubble msg={msg} onApprove={onApprove} onReject={onReject} />;
}
