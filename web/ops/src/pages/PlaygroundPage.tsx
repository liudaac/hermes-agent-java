import { useCallback, useEffect, useRef, useState } from "react";

// Web Speech API type shims (not in default lib)
interface SpeechRecognition extends EventTarget {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start(): void;
  stop(): void;
  onresult: ((this: SpeechRecognition, ev: SpeechRecognitionEvent) => void) | null;
  onerror: ((this: SpeechRecognition, ev: SpeechRecognitionErrorEvent) => void) | null;
  onend: ((this: SpeechRecognition, ev: Event) => void) | null;
}
interface SpeechRecognitionEvent extends Event {
  readonly resultIndex: number;
  readonly results: SpeechRecognitionResultList;
}
interface SpeechRecognitionErrorEvent extends Event {
  readonly error: string;
}
interface SpeechRecognitionStatic {
  new (): SpeechRecognition;
}
declare global {
  interface Window {
    SpeechRecognition?: SpeechRecognitionStatic;
    webkitSpeechRecognition?: SpeechRecognitionStatic;
  }
}

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
  Pencil,
  RotateCcw,
  Mic,
  MicOff,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Input } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { Select, SelectOption } from "@hermes/ui";
import { cn } from "@hermes/ui";
import { api } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import MarkdownRenderer from "@/components/MarkdownRenderer";
import { useI18n } from "@/i18n";

interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system" | "error";
  content: string;
  timestamp: number;
  streaming?: boolean;
  editing?: boolean;
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

const STORAGE_KEY = "hermes:playground";

function loadPlaygroundState(): Record<string, unknown> | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function savePlaygroundState(state: Record<string, unknown>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // ignore quota errors
  }
}

export default function PlaygroundPage() {
  const { showToast } = useToast();
  const { t } = useI18n();
  const saved = loadPlaygroundState();

  const [tenantId, setTenantId] = useState<string>(saved?.tenantId as string ?? "default");
  const [tenants, setTenants] = useState<string[]>(["default"]);
  const [sessionId, setSessionId] = useState<string>(saved?.sessionId as string ?? "");
  const [systemPrompt, setSystemPrompt] = useState<string>(saved?.systemPrompt as string ?? "");
  const [systemPromptOpen, setSystemPromptOpen] = useState(false);
  const [modelParamsOpen, setModelParamsOpen] = useState(false);
  const [temperature, setTemperature] = useState<number | "">(saved?.temperature as number ?? "");
  const [maxTokens, setMaxTokens] = useState<number | "">(saved?.maxTokens as number ?? "");
  const [topP, setTopP] = useState<number | "">(saved?.topP as number ?? "");
  const [reasoning, setReasoning] = useState<boolean>(saved?.reasoning as boolean ?? false);
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [usage, setUsage] = useState<UsageInfo | null>(null);
  const [toolCalls, setToolCalls] = useState<ToolCallInfo[]>([]);
  const [debugOpen, setDebugOpen] = useState(false);
  const [recording, setRecording] = useState(false);
  const [recSupported, setRecSupported] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<(() => void) | null>(null);
  const recRef = useRef<SpeechRecognition | null>(null);

  useEffect(() => {
    setRecSupported(!!window.SpeechRecognition || !!window.webkitSpeechRecognition);
  }, []);

  useEffect(() => {
    api.getTenants()
      .then((res) => {
        const ids = res.tenants.map((t) => t.tenantId);
        if (ids.length === 0) ids.push("default");
        setTenants(ids);
      })
      .catch(() => {
        setTenants(["default"]);
      });
  }, []);

  // Load message history when sessionId is known (on mount or when sessionId changes)
  useEffect(() => {
    if (!sessionId) return;
    api.getSessionMessages(sessionId)
      .then((resp) => {
        if (resp.messages && resp.messages.length > 0) {
          const loaded = resp.messages.map((msg) => ({
            id: crypto.randomUUID(),
            role: msg.role as "user" | "assistant" | "system" | "error",
            content: msg.content ?? "",
            timestamp: msg.timestamp ?? Date.now(),
          }));
          setMessages(loaded);
        }
      })
      .catch(() => {
        // Session may not exist yet; ignore
      });
  }, [sessionId]);

  // Persist state to localStorage (exclude messages, loading, usage, etc.)
  useEffect(() => {
    savePlaygroundState({
      tenantId,
      sessionId,
      systemPrompt,
      temperature,
      maxTokens,
      topP,
      reasoning,
    });
  }, [tenantId, sessionId, systemPrompt, temperature, maxTokens, topP, reasoning]);

  const startRecording = useCallback(() => {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;

    const rec = new SR();
    rec.lang = "zh-CN";
    rec.continuous = true;
    rec.interimResults = true;

    rec.onresult = (e) => {
      let final = "";
      let interim = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const transcript = e.results[i][0].transcript;
        if (e.results[i].isFinal) {
          final += transcript;
        } else {
          interim += transcript;
        }
      }
      setInput((prev) => {
        const base = prev.trim();
        const next = final || interim;
        if (!base) return next;
        if (final) return base + " " + final;
        return base + " " + interim;
      });
    };

    rec.onerror = (e) => {
      if (e.error !== "aborted") {
        showToast(t.playground.speechError.replace("{error}", e.error), "error");
      }
      setRecording(false);
    };

    rec.onend = () => {
      setRecording(false);
    };

    rec.start();
    recRef.current = rec;
    setRecording(true);
  }, [showToast, t]);

  const stopRecording = useCallback(() => {
    recRef.current?.stop();
    recRef.current = null;
    setRecording(false);
  }, []);

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

  const startEditMessage = useCallback((msgId: string) => {
    setMessages((prev) =>
      prev.map((m) => (m.id === msgId ? { ...m, editing: true } : m)),
    );
  }, []);

  const cancelEditMessage = useCallback((msgId: string) => {
    setMessages((prev) =>
      prev.map((m) => (m.id === msgId ? { ...m, editing: false } : m)),
    );
  }, []);

  const doSend = useCallback(
    async (text: string, baseMessages?: ChatMessage[]) => {
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

      if (baseMessages) {
        setMessages([...baseMessages, userMsg, assistantMsg]);
      } else {
        setMessages((prev) => [...prev, userMsg, assistantMsg]);
      }
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
    },
    [loading, tenantId, sessionId, systemPrompt, temperature, maxTokens, topP, reasoning, showToast],
  );

  const sendMessage = useCallback(async () => {
    const text = input.trim();
    if (!text) return;
    await doSend(text);
  }, [input, doSend]);

  const commitEditMessage = useCallback(
    (msgId: string, newText: string) => {
      setMessages((prev) => {
        const idx = prev.findIndex((m) => m.id === msgId);
        if (idx < 0) return prev;
        const truncated = prev.slice(0, idx + 1);
        truncated[idx] = { ...truncated[idx], content: newText, editing: false };
        setTimeout(() => doSend(newText, truncated), 0);
        return truncated;
      });
    },
    [doSend],
  );

  const regenerateMessage = useCallback(
    (msgId: string) => {
      setMessages((prev) => {
        const idx = prev.findIndex((m) => m.id === msgId);
        if (idx < 0) return prev;
        const keep = prev.slice(0, idx);
        const lastUser = keep.findLast((m) => m.role === "user");
        if (!lastUser) return prev;
        setTimeout(() => doSend(lastUser.content, keep), 0);
        return keep;
      });
    },
    [doSend],
  );

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base tracking-wide flex items-center gap-2">
              <Bot className="h-4 w-4" />
              {t.playground.title}
            </CardTitle>
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-xs">
                {t.common.form}: {tenantId}
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
              <label className="text-xs opacity-70 block mb-1">{t.playground.tenantId}</label>
              <Select
                value={tenantId}
                onValueChange={(v) => setTenantId(v)}
                className="h-8"
              >
                {tenants.map((id) => (
                  <SelectOption key={id} value={id}>{id}</SelectOption>
                ))}
              </Select>
            </div>
            <div className="flex-1">
              <label className="text-xs opacity-70 block mb-1">
                {t.playground.sessionId}
              </label>
              <Input
                value={sessionId}
                onChange={(e) => setSessionId(e.target.value)}
                placeholder={t.playground.sessionIdPlaceholder}
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
                {t.playground.modelParams}
                {(temperature !== "" || maxTokens !== "" || topP !== "" || reasoning) && (
                  <Badge variant="outline" className="text-[10px] h-4 px-1">
                    {t.playground.custom}
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
                      {t.playground.temperature}
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
                      placeholder={t.playground.temperaturePlaceholder}
                      className="h-7 text-xs"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] opacity-60 block mb-1">
                      {t.playground.maxTokens}
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
                      placeholder={t.playground.maxTokensPlaceholder}
                      className="h-7 text-xs"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] opacity-60 block mb-1">
                      {t.playground.topP}
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
                      placeholder={t.playground.topPPlaceholder}
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
                    {t.playground.enableReasoning}
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
                    {t.playground.resetDefaults}
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
                {t.playground.systemPrompt}
                {systemPrompt && (
                  <Badge variant="outline" className="text-[10px] h-4 px-1">
                    {t.playground.custom}
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
                  placeholder={t.playground.systemPromptPlaceholder}
                  rows={4}
                  className="w-full bg-black/30 border border-current/20 rounded-sm px-3 py-2 text-xs font-mono leading-relaxed resize-y focus:outline-none focus:border-midground/40"
                />
                <div className="flex items-center justify-between mt-2">
                  <span className="text-[10px] opacity-40">
                    {t.playground.systemPromptHint}
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setSystemPrompt("")}
                    disabled={!systemPrompt}
                    className="h-6 text-[10px] px-2"
                  >
                    {t.playground.reset}
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
                <p className="text-sm">{t.playground.startConversation}</p>
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
                    "max-w-[80%] rounded-sm px-3 py-2 text-sm group relative",
                    msg.role === "user"
                      ? "bg-midground/10 text-midground"
                      : msg.role === "error"
                        ? "bg-red-900/20 text-red-300 border border-red-900/40"
                        : "bg-current/5 border border-current/10",
                  )}
                >
                  {msg.editing ? (
                    <div className="space-y-2">
                      <textarea
                        defaultValue={msg.content}
                        autoFocus
                        className="w-full bg-black/50 border border-current/20 rounded-sm px-2 py-1 text-xs resize-y focus:outline-none focus:border-midground/40"
                        rows={2}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" && e.metaKey) {
                            const target = e.target as HTMLTextAreaElement;
                            commitEditMessage(msg.id, target.value);
                          }
                          if (e.key === "Escape") {
                            cancelEditMessage(msg.id);
                          }
                        }}
                      />
                      <div className="flex justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => cancelEditMessage(msg.id)}
                          className="h-5 text-[10px] px-1.5"
                        >
                          {t.playground.cancel}
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={(e) => {
                            const textarea = (e.currentTarget.parentElement?.previousElementSibling as HTMLTextAreaElement);
                            commitEditMessage(msg.id, textarea.value);
                          }}
                          className="h-5 text-[10px] px-1.5"
                        >
                          {t.playground.saveAndSend}
                        </Button>
                      </div>
                    </div>
                  ) : msg.role === "assistant" ? (
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

                  {/* Action buttons */}
                  {!msg.streaming && !msg.editing && (
                    <div className="flex items-center gap-1.5 mt-1.5 opacity-0 group-hover:opacity-100 transition-opacity">
                      {msg.role === "user" && (
                        <button
                          onClick={() => startEditMessage(msg.id)}
                          className="flex items-center gap-0.5 text-[10px] opacity-50 hover:opacity-100 transition-opacity"
                        >
                          <Pencil className="h-2.5 w-2.5" />
                          {t.playground.edit}
                        </button>
                      )}
                      {msg.role === "assistant" && (
                        <button
                          onClick={() => regenerateMessage(msg.id)}
                          className="flex items-center gap-0.5 text-[10px] opacity-50 hover:opacity-100 transition-opacity"
                        >
                          <RotateCcw className="h-2.5 w-2.5" />
                          {t.playground.retry}
                        </button>
                      )}
                    </div>
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
                  {t.playground.debugInfo}
                  {usage && (
                    <Badge variant="outline" className="text-[10px] h-4 px-1">
                      {usage.totalTokens} tok
                    </Badge>
                  )}
                  {toolCalls.length > 0 && (
                    <Badge variant="outline" className="text-[10px] h-4 px-1">
                      {toolCalls.length} {t.common.tools}
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
                        {t.playground.tokenUsage}
                        {usage.lastModel && (
                          <span className="opacity-50">· {usage.lastModel}</span>
                        )}
                      </h4>
                      <div className="grid grid-cols-5 gap-2">
                        {[
                          { label: t.playground.prompt, value: usage.promptTokens },
                          { label: t.playground.completion, value: usage.completionTokens },
                          { label: t.playground.cached, value: usage.cachedPromptTokens },
                          { label: t.playground.reasoning, value: usage.reasoningTokens },
                          { label: t.playground.total, value: usage.totalTokens },
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
                        {t.playground.toolCalls}
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
              placeholder={recording ? t.playground.listening : t.playground.typeMessage}
              disabled={loading || recording}
              className="flex-1 h-10"
            />
            {recSupported && (
              <Button
                variant={recording ? "destructive" : "ghost"}
                size="sm"
                onClick={recording ? stopRecording : startRecording}
                disabled={loading}
                className="h-10 w-10 p-0"
                title={recording ? t.playground.stopRecording : t.playground.voiceInput}
              >
                {recording ? (
                  <MicOff className="h-4 w-4 animate-pulse" />
                ) : (
                  <Mic className="h-4 w-4" />
                )}
              </Button>
            )}
            <Button
              onClick={sendMessage}
              disabled={!input.trim() || loading || recording}
              className="h-10 px-4"
            >
              <Send className="h-4 w-4 mr-1.5" />
              {t.playground.send}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
