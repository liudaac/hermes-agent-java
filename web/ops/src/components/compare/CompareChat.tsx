import { useRef, useEffect } from "react";
import { Send, Bot, User, AlertCircle } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Input } from "@hermes/ui";
import { cn } from "@hermes/ui";
import MarkdownRenderer from "@/components/MarkdownRenderer";
import { useI18n } from "@/i18n";

export interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "error" | "tool";
  content: string;
  streaming?: boolean;
}

export interface ParticipantState {
  id: string;
  tenantId: string;
  sessionId: string;
  messages: ChatMessage[];
  loading: boolean;
}

interface CompareChatProps {
  participants: ParticipantState[];
  tenants: string[];
  input: string;
  sending: boolean;
  autoRunning: boolean;
  onInputChange: (v: string) => void;
  onSend: () => void;
  onResetParticipant: (id: string, tenantId: string) => void;
  onRemoveParticipant: (id: string) => void;
}

export function CompareChat({
  participants,
  tenants,
  input,
  sending,
  autoRunning,
  onInputChange,
  onSend,
  onResetParticipant,
  onRemoveParticipant,
}: CompareChatProps) {
  const { t } = useI18n();
  const scrollRefs = useRef<Record<string, HTMLDivElement | null>>({});

  useEffect(() => {
    participants.forEach((p) => {
      scrollRefs.current[p.id]?.scrollIntoView({ behavior: "smooth", block: "end" });
    });
  }, [participants]);

  const renderPanel = (state: ParticipantState, index: number) => (
    <div className="flex flex-col h-full min-h-[22rem]">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-[10px] font-medium text-muted-foreground shrink-0">
          #{index + 1}
        </span>
        <select
          value={state.tenantId}
          onChange={(e) => onResetParticipant(state.id, e.target.value)}
          className="h-7 text-xs flex-1 bg-transparent border border-border rounded px-1"
          disabled={autoRunning}
        >
          {tenants.map((id) => (
            <option key={id} value={id}>{id}</option>
          ))}
        </select>
        {state.sessionId && (
          <span className="text-[10px] text-muted-foreground shrink-0 font-mono-ui">
            {state.sessionId.slice(0, 6)}…
          </span>
        )}
        <Button
          variant="ghost"
          size="sm"
          onClick={() => onRemoveParticipant(state.id)}
          disabled={autoRunning || participants.length <= 2}
          className="h-6 px-1.5"
          title={t.compare.removeParticipant}
        >
          <span className="text-xs">×</span>
        </Button>
      </div>
      <div className="flex-1 border border-current/20 rounded-sm overflow-y-auto p-2 space-y-2 bg-black/30 min-h-0">
        {state.messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-32 opacity-30">
            <Bot className="h-6 w-6 mb-1" />
            <p className="text-xs">{t.compare.waiting}</p>
          </div>
        )}
        {state.messages.map((msg) => (
          <div
            key={msg.id}
            className={cn("flex gap-1.5", msg.role === "user" ? "justify-end" : "justify-start")}
          >
            {msg.role !== "user" && (
              <div className="mt-0.5">
                {msg.role === "error" ? (
                  <AlertCircle className="h-3 w-3 text-red-400" />
                ) : (
                  <Bot className="h-3 w-3 opacity-60" />
                )}
              </div>
            )}
            <div
              className={cn(
                "max-w-[90%] rounded-sm px-2 py-1.5 text-xs",
                msg.role === "user"
                  ? "bg-midground/10 text-midground"
                  : msg.role === "error"
                    ? "bg-red-900/20 text-red-300 border border-red-900/40"
                    : msg.role === "tool"
                      ? "bg-blue-900/20 text-blue-200 border border-blue-900/40 font-mono"
                      : "bg-current/5 border border-current/10",
              )}
            >
              {msg.role === "assistant" ? (
                <div className="leading-relaxed">
                  <MarkdownRenderer content={msg.content} />
                  {msg.streaming && (
                    <span className="inline-block w-1 h-3 bg-midground/60 ml-0.5 animate-pulse" />
                  )}
                </div>
              ) : (
                <pre className="whitespace-pre-wrap font-sans leading-relaxed">
                  {msg.content}
                  {msg.streaming && (
                    <span className="inline-block w-1 h-3 bg-midground/60 ml-0.5 animate-pulse" />
                  )}
                </pre>
              )}
            </div>
            {msg.role === "user" && (
              <div className="mt-0.5">
                <User className="h-3 w-3 opacity-60" />
              </div>
            )}
          </div>
        ))}
        <div ref={(el) => { scrollRefs.current[state.id] = el; }} />
      </div>
    </div>
  );

  return (
    <>
      <Card className="flex-1 flex flex-col min-h-0">
        <CardHeader className="pb-2 shrink-0">
          <CardTitle className="text-base tracking-wide flex items-center gap-2">
            <span className="flex items-center gap-1.5">
              <span className="text-lg">⇄</span>
              {t.compare.title}
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent className="flex-1 min-h-0 pb-2">
          <div className="grid gap-3 min-h-0" style={{ gridTemplateColumns: `repeat(${Math.min(participants.length, 3)}, minmax(0, 1fr))` }}>
            {participants.map((participant, index) => (
              <div key={participant.id} className="min-w-0">
                {renderPanel(participant, index)}
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      <div className="flex gap-2 shrink-0">
        <Input
          value={input}
          onChange={(e) => onInputChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              onSend();
            }
          }}
          placeholder={t.compare.askBoth}
          disabled={sending || autoRunning}
          className="flex-1 h-10"
        />
        <Button
          onClick={onSend}
          disabled={!input.trim() || sending || autoRunning || participants.length === 0}
          className="h-10 px-4"
        >
          <Send className="h-4 w-4 mr-1.5" />
          {t.compare.send}
        </Button>
      </div>
    </>
  );
}
