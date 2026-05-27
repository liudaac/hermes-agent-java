import { useCallback, useEffect, useRef, useState } from "react";
import {
  Send,
  Bot,
  User,
  AlertCircle,
  ArrowLeftRight,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { api } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import MarkdownRenderer from "@/components/MarkdownRenderer";

interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "error";
  content: string;
  streaming?: boolean;
}

interface SideState {
  tenantId: string;
  sessionId: string;
  messages: ChatMessage[];
  loading: boolean;
}

function createSideState(tenantId: string): SideState {
  return {
    tenantId,
    sessionId: "",
    messages: [],
    loading: false,
  };
}

export default function ComparePage() {
  const { showToast } = useToast();

  const [left, setLeft] = useState<SideState>(() => createSideState("default"));
  const [right, setRight] = useState<SideState>(() => createSideState("tenant-b"));
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);

  const leftScrollRef = useRef<HTMLDivElement>(null);
  const rightScrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    leftScrollRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [left.messages]);

  useEffect(() => {
    rightScrollRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [right.messages]);

  const updateSide = useCallback(
    (side: "left" | "right", updater: (prev: SideState) => SideState) => {
      if (side === "left") {
        setLeft(updater);
      } else {
        setRight(updater);
      }
    },
    [],
  );

  const sendToSide = useCallback(
    async (side: "left" | "right", text: string, otherSideText: string) => {
      const state = side === "left" ? left : right;
      const setState = side === "left" ? setLeft : setRight;

      const userMsg: ChatMessage = {
        id: crypto.randomUUID(),
        role: "user",
        content: otherSideText || text, // show the shared input on both sides
      };
      const assistantMsg: ChatMessage = {
        id: crypto.randomUUID(),
        role: "assistant",
        content: "",
        streaming: true,
      };

      setState((prev) => ({
        ...prev,
        messages: [...prev.messages, userMsg, assistantMsg],
        loading: true,
      }));

      let currentSid = state.sessionId;

      try {
        await api.chatStream({
          message: text,
          tenant_id: state.tenantId,
          session_id: currentSid || undefined,
          onEvent: (event, data) => {
            const d = data as Record<string, unknown>;

            if (event === "session" && d.session_id) {
              currentSid = String(d.session_id);
              setState((prev) => ({ ...prev, sessionId: currentSid }));
            }
            if (event === "message" || event === "delta") {
              const content = String(d.content ?? "");
              setState((prev) => {
                const last = prev.messages[prev.messages.length - 1];
                if (last?.role === "assistant" && last.streaming) {
                  const updated = [...prev.messages];
                  updated[updated.length - 1] = {
                    ...last,
                    content: last.content + content,
                  };
                  return { ...prev, messages: updated };
                }
                return prev;
              });
            }
            if (event === "done") {
              setState((prev) => {
                const last = prev.messages[prev.messages.length - 1];
                if (last?.role === "assistant") {
                  const updated = [...prev.messages];
                  updated[updated.length - 1] = { ...last, streaming: false };
                  return { ...prev, messages: updated, loading: false };
                }
                return { ...prev, loading: false };
              });
            }
            if (event === "error") {
              const errMsg = String(d.error ?? "Unknown error");
              setState((prev) => ({
                ...prev,
                messages: [
                  ...prev.messages,
                  { id: crypto.randomUUID(), role: "error", content: errMsg },
                ],
                loading: false,
              }));
            }
          },
          onError: (err) => {
            showToast(`${side}: ${err.message}`, "error");
            setState((prev) => ({ ...prev, loading: false }));
          },
        });
      } catch (err) {
        showToast(
          `${side}: ${err instanceof Error ? err.message : String(err)}`,
          "error",
        );
        setState((prev) => ({ ...prev, loading: false }));
      }
    },
    [left, right, showToast],
  );

  const sendMessage = useCallback(async () => {
    const text = input.trim();
    if (!text || sending) return;

    setSending(true);
    setInput("");

    // Fire both sides in parallel
    await Promise.all([
      sendToSide("left", text, text),
      sendToSide("right", text, text),
    ]);

    setSending(false);
  }, [input, sending, sendToSide]);

  const renderPanel = (
    side: "left" | "right",
    state: SideState,
    scrollRef: React.RefObject<HTMLDivElement | null>,
  ) => (
    <div className="flex flex-col h-full min-h-0">
      <div className="flex items-center gap-2 mb-2">
        <Input
          value={state.tenantId}
          onChange={(e) =>
            updateSide(side, (prev) => ({ ...prev, tenantId: e.target.value }))
          }
          placeholder="Tenant ID"
          className="h-7 text-xs flex-1"
        />
        {state.sessionId && (
          <Badge variant="secondary" className="text-[10px] h-5 shrink-0">
            {state.sessionId.slice(0, 6)}…
          </Badge>
        )}
      </div>
      <div className="flex-1 border border-current/20 rounded-sm overflow-y-auto p-2 space-y-2 bg-black/30 min-h-0">
        {state.messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-32 opacity-30">
            <Bot className="h-6 w-6 mb-1" />
            <p className="text-xs">Waiting…</p>
          </div>
        )}
        {state.messages.map((msg) => (
          <div
            key={msg.id}
            className={cn(
              "flex gap-1.5",
              msg.role === "user" ? "justify-end" : "justify-start",
            )}
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
        <div ref={scrollRef} />
      </div>
    </div>
  );

  return (
    <div className="space-y-4 h-[calc(100vh-8rem)] flex flex-col">
      <Card className="flex-1 flex flex-col min-h-0">
        <CardHeader className="pb-2 shrink-0">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base tracking-wide flex items-center gap-2">
              <ArrowLeftRight className="h-4 w-4" />
              Tenant Comparison
            </CardTitle>
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-xs">
                {left.tenantId}
              </Badge>
              <span className="opacity-40 text-xs">vs</span>
              <Badge variant="outline" className="text-xs">
                {right.tenantId}
              </Badge>
            </div>
          </div>
        </CardHeader>
        <CardContent className="flex-1 flex gap-3 min-h-0 pb-2">
          {/* Left panel */}
          <div className="flex-1 min-w-0">
            {renderPanel("left", left, leftScrollRef)}
          </div>
          {/* Divider */}
          <div className="w-px bg-current/10 shrink-0" />
          {/* Right panel */}
          <div className="flex-1 min-w-0">
            {renderPanel("right", right, rightScrollRef)}
          </div>
        </CardContent>
      </Card>

      {/* Shared input */}
      <div className="flex gap-2 shrink-0">
        <Input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              sendMessage();
            }
          }}
          placeholder="Ask both tenants the same question…"
          disabled={sending}
          className="flex-1 h-10"
        />
        <Button
          onClick={sendMessage}
          disabled={!input.trim() || sending}
          className="h-10 px-4"
        >
          <Send className="h-4 w-4 mr-1.5" />
          Send
        </Button>
      </div>
    </div>
  );
}
