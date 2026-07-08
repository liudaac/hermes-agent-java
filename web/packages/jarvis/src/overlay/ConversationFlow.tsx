/**
 * ConversationFlow — list of messages rendered as HUD lines.
 * Adds a startup greeting if the conversation is empty.
 */
import { useJarvisStore } from "../hooks/useJarvisStore";
import { MessageBubble } from "./MessageBubble";

export function ConversationFlow() {
  const messages = useJarvisStore((s) => s.messages);

  if (messages.length === 0) {
    return (
      <div className="space-y-2 text-[12px] leading-relaxed">
        <div className="text-[oklch(0.55_0.10_200)]">
          {"// Jarvis online. Awaiting instructions."}
        </div>
        <div className="text-[10px] tracking-[0.15em] uppercase text-[oklch(0.40_0.08_200)]">
          SHORTCUTS · ⌘K TOGGLE · ESC CLOSE
        </div>
        <div className="mt-3 space-y-1 text-[11px] text-[oklch(0.55_0.08_200)]">
          <div>{">"} 查看待审批</div>
          <div>{">"} 最近的运行记录</div>
          <div>{">"} 数字员工团队</div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-0">
      {messages.map((m) => (
        <MessageBubble key={m.id} message={m} />
      ))}
    </div>
  );
}
