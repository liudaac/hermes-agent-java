import { useCallback, useEffect, useRef, useState } from "react";
import { Send, Trash2, Bot, User, AlertCircle } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { api } from "@/lib/api";
import { useToast } from "@/hooks/useToast";

interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system" | "error";
  content: string;
  timestamp: number;
  streaming?: boolean;
}

export default function PlaygroundPage() {
  const { showToast } = useToast();

  const [tenantId, setTenantId] = useState("default");
  const [sessionId, setSessionId] = useState("");
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages]);

  const clearChat = useCallback(() => {
    abortRef.current?.();
    setMessages([]);
    setSessionId("");
  }, []);

  const sendMessage = useCallback(async () => {
    const text = input.trim();
    if (!text || loading) return;

    const userMsg: ChatMessage = {
      id: crypto.randomUUID(),
      role: "user",
      content: text,
      timestamp: Date.now(),
    };
    const assistantMsg: ChatMessage = {
      id: crypto.randomUUID(),
      role: "assistant",
      content: "",
      timestamp: Date.now(),
      streaming: true,
    };

    setMessages((prev) => [...prev, userMsg, assistantMsg]);
    setInput("");
    setLoading(true);

    let currentSid = sessionId;

    try {
      await api.chatStream({
        message: text,
        tenant_id: tenantId,
        session_id: currentSid || undefined,
        onEvent: (event, data) => {
          if (event === "session" && (data as Record<string, unknown>).session_id) {
            currentSid = String((data as Record<string, unknown>).session_id);
            setSessionId(currentSid);
          }
          if (event === "message" || event === "delta") {
            const content = String((data as Record<string, unknown>).content ?? "");
            setMessages((prev) => {
              const last = prev[prev.length - 1];
              if (last?.role === "assistant" && last.streaming) {
                return [
                  ...prev.slice(0, -1),
                  { ...last, content: last.content + content },
                ];
              }
              return prev;
            });
          }
          if (event === "done") {
            setMessages((prev) => {
              const last = prev[prev.length - 1];
              if (last?.role === "assistant") {
                return [...prev.slice(0, -1), { ...last, streaming: false }];
              }
              return prev;
            });
            setLoading(false);
          }
          if (event === "error") {
            const errMsg = String((data as Record<string, unknown>).error ?? "Unknown error");
            setMessages((prev) => [
              ...prev,
              {
                id: crypto.randomUUID(),
                role: "error",
                content: errMsg,
                timestamp: Date.now(),
              },
            ]);
            setLoading(false);
          }
        },
        onError: (err) => {
          showToast(err.message, "error");
          setLoading(false);
        },
      });
    } catch (err) {
      showToast(err instanceof Error ? err.message : String(err), "error");
      setLoading(false);
    }
  }, [input, loading, tenantId, sessionId, showToast]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base tracking-wide flex items-center gap-2">
              <Bot className="h-4 w-4" />
              Chat Playground
            </CardTitle>
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-xs">
                Tenant: {tenantId}
              </Badge>
              {sessionId && (
                <Badge variant="secondary" className="text-xs">
                  SID: {sessionId.slice(0, 8)}…
                </Badge>
              )}
              <Button
                variant="ghost"
                size="sm"
                onClick={clearChat}
                disabled={loading}
                className="h-7 px-2"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* Tenant & Session config */}
          <div className="flex gap-3">
            <div className="flex-1">
              <label className="text-xs opacity-70 block mb-1">
                Tenant ID
              </label>
              <Input
                value={tenantId}
                onChange={(e) => setTenantId(e.target.value)}
                placeholder="default"
                className="h-8 text-sm"
              />
            </div>
            <div className="flex-1">
              <label className="text-xs opacity-70 block mb-1">
                Session ID (optional)
              </label>
              <Input
                value={sessionId}
                onChange={(e) => setSessionId(e.target.value)}
                placeholder="Auto-generated"
                className="h-8 text-sm"
              />
            </div>
          </div>

          {/* Messages */}
          <div className="border border-current/20 rounded-sm min-h-[320px] max-h-[60vh] overflow-y-auto p-3 space-y-3 bg-black/30">
            {messages.length === 0 && (
              <div className="flex flex-col items-center justify-center h-48 opacity-40">
                <Bot className="h-8 w-8 mb-2" />
                <p className="text-sm">Start a conversation</p>
              </div>
            )}
            {messages.map((msg) => (
              <div
                key={msg.id}
                className={cn(
                  "flex gap-2",
                  msg.role === "user" ? "justify-end" : "justify-start",
                )}
              >
                {msg.role !== "user" && (
                  <div className="mt-1">
                    {msg.role === "error" ? (
                      <AlertCircle className="h-4 w-4 text-red-400" />
                    ) : (
                      <Bot className="h-4 w-4 opacity-60" />
                    )}
                  </div>
                )}
                <div
                  className={cn(
                    "max-w-[80%] rounded-sm px-3 py-2 text-sm",
                    msg.role === "user"
                      ? "bg-midground/10 text-midground"
                      : msg.role === "error"
                        ? "bg-red-900/20 text-red-300 border border-red-900/40"
                        : "bg-current/5 border border-current/10",
                  )}
                >
                  <pre className="whitespace-pre-wrap font-sans leading-relaxed">
                    {msg.content}
                    {msg.streaming && (
                      <span className="inline-block w-1.5 h-3.5 bg-midground/60 ml-0.5 animate-pulse" />
                    )}
                  </pre>
                </div>
                {msg.role === "user" && (
                  <div className="mt-1">
                    <User className="h-4 w-4 opacity-60" />
                  </div>
                )}
              </div>
            ))}
            <div ref={scrollRef} />
          </div>

          {/* Input */}
          <div className="flex gap-2">
            <Input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  sendMessage();
                }
              }}
              placeholder="Type a message…"
              disabled={loading}
              className="flex-1 h-10"
            />
            <Button
              onClick={sendMessage}
              disabled={!input.trim() || loading}
              className="h-10 px-4"
            >
              <Send className="h-4 w-4 mr-1.5" />
              Send
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
