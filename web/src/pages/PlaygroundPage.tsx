import { useCallback, useEffect, useRef, useState } from "react";
import {
  Send,
  Trash2,
  Bot,
  User,
  AlertCircle,
  ChevronDown,
  ChevronRight,
  Wrench,
  Zap,
  FileText,
  SlidersHorizontal,
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
  role: "user" | "assistant" | "system" | "error";
  content: string;
  timestamp: number;
  streaming?: boolean;
}

interface UsageInfo {
  promptTokens: number;
  completionTokens: number;
  cachedPromptTokens: number;
  reasoningTokens: number;
  totalTokens: number;
  lastModel?: string;
}

interface ToolCallInfo {
  tool: string;
  ok: boolean;
  durationMs: number;
  timestamp: number;
}

export default function PlaygroundPage() {
  const { showToast } = useToast();

  const [tenantId, setTenantId] = useState("default");
  const [sessionId, setSessionId] = useState("");
  const [systemPrompt, setSystemPrompt] = useState("");
  const [systemPromptOpen, setSystemPromptOpen] = useState(false);
  const [modelParamsOpen, setModelParamsOpen] = useState(false);
  const [temperature, setTemperature] = useState<number | "">("");
  const [maxTokens, setMaxTokens] = useState<number | "">("");
  const [topP, setTopP] = useState<number | "">("");
  const [reasoning, setReasoning] = useState(false);
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [usage, setUsage] = useState<UsageInfo | null>(null);
  const [toolCalls, setToolCalls] = useState<ToolCallInfo[]>([]);
  const [debugOpen, setDebugOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages]);

  const clearChat = useCallback(() => {
    abortRef.current?.();
    setMessages([]);
    setSessionId("");
    setUsage(null);
    setToolCalls([]);
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
    setUsage(null);
    setToolCalls([]);

    let currentSid = sessionId;

    try {
      const mParams: Record<string, number | boolean | string> = {};
      if (temperature !== "") mParams.temperature = temperature;
      if (maxTokens !== "") mParams.max_tokens = maxTokens;
      if (topP !== "") mParams.top_p = topP;
      if (reasoning) mParams.reasoning = true;

      await api.chatStream({
        message: text,
        tenant_id: tenantId,
        session_id: currentSid || undefined,
        system_prompt: systemPrompt || undefined,
        model_params: Object.keys(mParams).length > 0 ? mParams : undefined,
        onEvent: (event, data) => {
          const d = data as Record<string, unknown>;

          if (event === "session" && d.session_id) {
            currentSid = String(d.session_id);
            setSessionId(currentSid);
          }
          if (event === "message" || event === "delta") {
            const content = String(d.content ?? "");
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
          if (event === "usage") {
            setUsage({
              promptTokens: Number(d.promptTokens ?? 0),
              completionTokens: Number(d.completionTokens ?? 0),
              cachedPromptTokens: Number(d.cachedPromptTokens ?? 0),
              reasoningTokens: Number(d.reasoningTokens ?? 0),
              totalTokens: Number(d.totalTokens ?? 0),
              lastModel: d.lastModel ? String(d.lastModel) : undefined,
            });
          }
          if (event === "tool_chain" && Array.isArray(d.calls)) {
            setToolCalls(
              d.calls.map((tc: Record<string, unknown>) => ({
                tool: String(tc.tool ?? ""),
                ok: Boolean(tc.ok),
                durationMs: Number(tc.durationMs ?? 0),
                timestamp: Number(tc.timestamp ?? 0),
              })),
            );
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
            const errMsg = String(d.error ?? "Unknown error");
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
  }, [input, loading, tenantId, sessionId, systemPrompt, temperature, maxTokens, topP, reasoning, showToast]);

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
              <label className="text-xs opacity-70 block mb-1">Tenant ID</label>
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

          {/* Model Parameters */}
          <div className="border border-current/20 rounded-sm overflow-hidden">
            <button
              onClick={() => setModelParamsOpen(!modelParamsOpen)}
              className="w-full flex items-center justify-between px-3 py-2 text-xs tracking-wider opacity-70 hover:opacity-100 transition-opacity bg-current/5"
            >
              <span className="flex items-center gap-1.5">
                <SlidersHorizontal className="h-3 w-3" />
                Model Parameters
                {(temperature !== "" || maxTokens !== "" || topP !== "" || reasoning) && (
                  <Badge variant="outline" className="text-[10px] h-4 px-1">
                    Custom
                  </Badge>
                )}
              </span>
              {modelParamsOpen ? (
                <ChevronDown className="h-3 w-3" />
              ) : (
                <ChevronRight className="h-3 w-3" />
              )}
            </button>
            {modelParamsOpen && (
              <div className="p-3 space-y-3">
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label className="text-[10px] opacity-60 block mb-1">
                      Temperature
                    </label>
                    <Input
                      type="number"
                      min={0}
                      max={2}
                      step={0.1}
                      value={temperature}
                      onChange={(e) => {
                        const v = e.target.value;
                        setTemperature(v === "" ? "" : Number(v));
                      }}
                      placeholder="0.7"
                      className="h-7 text-xs"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] opacity-60 block mb-1">
                      Max Tokens
                    </label>
                    <Input
                      type="number"
                      min={1}
                      step={1}
                      value={maxTokens}
                      onChange={(e) => {
                        const v = e.target.value;
                        setMaxTokens(v === "" ? "" : Number(v));
                      }}
                      placeholder="4096"
                      className="h-7 text-xs"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] opacity-60 block mb-1">
                      Top P
                    </label>
                    <Input
                      type="number"
                      min={0}
                      max={1}
                      step={0.05}
                      value={topP}
                      onChange={(e) => {
                        const v = e.target.value;
                        setTopP(v === "" ? "" : Number(v));
                      }}
                      placeholder="1.0"
                      className="h-7 text-xs"
                    />
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    id="reasoning"
                    checked={reasoning}
                    onChange={(e) => setReasoning(e.target.checked)}
                    className="h-3.5 w-3.5 accent-midground"
                  />
                  <label htmlFor="reasoning" className="text-xs opacity-70 cursor-pointer">
                    Enable reasoning (extended thinking)
                  </label>
                </div>
                <div className="flex justify-end">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      setTemperature("");
                      setMaxTokens("");
                      setTopP("");
                      setReasoning(false);
                    }}
                    disabled={temperature === "" && maxTokens === "" && topP === "" && !reasoning}
                    className="h-6 text-[10px] px-2"
                  >
                    Reset Defaults
                  </Button>
                </div>
              </div>
            )}
          </div>

          {/* System Prompt */}
          <div className="border border-current/20 rounded-sm overflow-hidden">
            <button
              onClick={() => setSystemPromptOpen(!systemPromptOpen)}
              className="w-full flex items-center justify-between px-3 py-2 text-xs tracking-wider opacity-70 hover:opacity-100 transition-opacity bg-current/5"
            >
              <span className="flex items-center gap-1.5">
                <FileText className="h-3 w-3" />
                System Prompt
                {systemPrompt && (
                  <Badge variant="outline" className="text-[10px] h-4 px-1">
                    Custom
                  </Badge>
                )}
              </span>
              {systemPromptOpen ? (
                <ChevronDown className="h-3 w-3" />
              ) : (
                <ChevronRight className="h-3 w-3" />
              )}
            </button>
            {systemPromptOpen && (
              <div className="p-3">
                <textarea
                  value={systemPrompt}
                  onChange={(e) => setSystemPrompt(e.target.value)}
                  placeholder="Optional: override the default system prompt for this session"
                  rows={4}
                  className="w-full bg-black/30 border border-current/20 rounded-sm px-3 py-2 text-xs font-mono leading-relaxed resize-y focus:outline-none focus:border-midground/40"
                />
                <div className="flex items-center justify-between mt-2">
                  <span className="text-[10px] opacity-40">
                    Leave empty to use the default agent identity prompt.
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setSystemPrompt("")}
                    disabled={!systemPrompt}
                    className="h-6 text-[10px] px-2"
                  >
                    Reset
                  </Button>
                </div>
              </div>
            )}
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
                  {msg.role === "assistant" ? (
                    <div className="text-sm leading-relaxed">
                      <MarkdownRenderer content={msg.content} />
                      {msg.streaming && (
                        <span className="inline-block w-1.5 h-3.5 bg-midground/60 ml-0.5 animate-pulse" />
                      )}
                    </div>
                  ) : (
                    <pre className="whitespace-pre-wrap font-sans leading-relaxed">
                      {msg.content}
                      {msg.streaming && (
                        <span className="inline-block w-1.5 h-3.5 bg-midground/60 ml-0.5 animate-pulse" />
                      )}
                    </pre>
                  )}
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

          {/* Debug Panel */}
          {(usage || toolCalls.length > 0) && (
            <div className="border border-current/20 rounded-sm overflow-hidden">
              <button
                onClick={() => setDebugOpen(!debugOpen)}
                className="w-full flex items-center justify-between px-3 py-2 text-xs tracking-wider opacity-70 hover:opacity-100 transition-opacity bg-current/5"
              >
                <span className="flex items-center gap-1.5">
                  <Zap className="h-3 w-3" />
                  Debug Info
                  {usage && (
                    <Badge variant="outline" className="text-[10px] h-4 px-1">
                      {usage.totalTokens} tok
                    </Badge>
                  )}
                  {toolCalls.length > 0 && (
                    <Badge variant="outline" className="text-[10px] h-4 px-1">
                      {toolCalls.length} tool
                      {toolCalls.length > 1 ? "s" : ""}
                    </Badge>
                  )}
                </span>
                {debugOpen ? (
                  <ChevronDown className="h-3 w-3" />
                ) : (
                  <ChevronRight className="h-3 w-3" />
                )}
              </button>
              {debugOpen && (
                <div className="p-3 space-y-3 text-xs">
                  {usage && (
                    <div>
                      <h4 className="flex items-center gap-1 opacity-60 mb-1.5">
                        <Zap className="h-3 w-3" />
                        Token Usage
                        {usage.lastModel && (
                          <span className="opacity-50">· {usage.lastModel}</span>
                        )}
                      </h4>
                      <div className="grid grid-cols-5 gap-2">
                        {[
                          { label: "Prompt", value: usage.promptTokens },
                          { label: "Completion", value: usage.completionTokens },
                          { label: "Cached", value: usage.cachedPromptTokens },
                          { label: "Reasoning", value: usage.reasoningTokens },
                          { label: "Total", value: usage.totalTokens },
                        ].map(({ label, value }) => (
                          <div
                            key={label}
                            className="bg-current/5 border border-current/10 rounded-sm px-2 py-1.5 text-center"
                          >
                            <div className="text-[10px] opacity-50">{label}</div>
                            <div className="font-mono text-sm">{value}</div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  {toolCalls.length > 0 && (
                    <div>
                      <h4 className="flex items-center gap-1 opacity-60 mb-1.5">
                        <Wrench className="h-3 w-3" />
                        Tool Calls
                      </h4>
                      <div className="space-y-1">
                        {toolCalls.map((tc, i) => (
                          <div
                            key={i}
                            className={cn(
                              "flex items-center justify-between px-2 py-1 rounded-sm border",
                              tc.ok
                                ? "bg-green-900/10 border-green-900/20"
                                : "bg-red-900/10 border-red-900/20",
                            )}
                          >
                            <span className="flex items-center gap-1.5">
                              <Wrench className="h-3 w-3 opacity-60" />
                              {tc.tool}
                            </span>
                            <span className="opacity-50 font-mono">
                              {tc.durationMs}ms
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

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
