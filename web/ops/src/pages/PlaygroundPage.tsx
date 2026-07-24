import { useCallback, useEffect, useRef, useState } from "react";
import {
  Send, Trash2, Bot, User, AlertCircle,
  Wrench,
  SlidersHorizontal, Pencil, RotateCcw, Mic, MicOff,
} from "lucide-react";
import { Card, Button, Input, Select, SelectOption } from "@hermes/ui";
import {
  useHarnessStream,
  HarnessStatusPanel,
  ApprovalInline,
  ToolCallTimeline,
} from "@hermes/ui";
import type { ToolCallState } from "@hermes/ui";
import { api } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import MarkdownRenderer from "@/components/MarkdownRenderer";

// ── Web Speech API shim ──
interface SpeechRecognition extends EventTarget {
  lang: string; continuous: boolean; interimResults: boolean;
  start(): void; stop(): void;
  onresult: ((e: any) => void) | null;
  onerror: ((e: any) => void) | null;
  onend: ((e: any) => void) | null;
}
declare global { interface Window {
  SpeechRecognition?: { new (): SpeechRecognition };
  webkitSpeechRecognition?: { new (): SpeechRecognition };
} }

interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system" | "error";
  content: string;
  timestamp: number;
  streaming?: boolean;
  editing?: boolean;
  toolCalls?: ToolCallState[];
}

const STORAGE_KEY = "hermes:playground";

function loadSaved(): Record<string, unknown> | null {
  try { const r = localStorage.getItem(STORAGE_KEY); return r ? JSON.parse(r) : null; } catch { return null; }
}
function saveSaved(s: Record<string, unknown>) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(s)); } catch {}
}

export default function PlaygroundPage() {
  const { showToast } = useToast();
  const saved = loadSaved();

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
  const [debugOpen, setDebugOpen] = useState(false);
  const [recording, setRecording] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const recRef = useRef<SpeechRecognition | null>(null);

  // Harness stream - subscribes when sessionId changes
  const { state: harness, delta, events } = useHarnessStream(sessionId ? sessionId : null, { tenantId });

  // Accumulate delta into last assistant message
  const lastDeltaRef = useRef("");
  useEffect(() => {
    if (!delta) return;
    // Only append if different from what we last appended
    if (delta === lastDeltaRef.current) return;
    lastDeltaRef.current = delta;
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last?.role === "assistant" && last.streaming) {
        return [...prev.slice(0, -1), { ...last, content: delta }];
      }
      return prev;
    });
  }, [delta]);

  // Update tool calls on the current assistant message
  useEffect(() => {
    if (harness.toolCalls.length === 0) return;
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last?.role === "assistant" && last.streaming) {
        return [...prev.slice(0, -1), { ...last, toolCalls: harness.toolCalls }];
      }
      return prev;
    });
  }, [harness.toolCalls]);

  // Mark assistant message as done when loop ends
  useEffect(() => {
    if (harness.status === "idle" || harness.status === "error") {
      setMessages((prev) => {
        const last = prev[prev.length - 1];
        if (last?.role === "assistant" && last.streaming) {
          return [...prev.slice(0, -1), { ...last, streaming: false }];
        }
        return prev;
      });
      setLoading(false);
    }
  }, [harness.status]);

  // Persist settings
  useEffect(() => {
    saveSaved({ tenantId, sessionId, systemPrompt, temperature, maxTokens, topP, reasoning });
  }, [tenantId, sessionId, systemPrompt, temperature, maxTokens, topP, reasoning]);

  useEffect(() => {
    api.getTenants().then((res) => {
      const ids = res.tenants.map((t) => t.tenantId);
      if (ids.length === 0) ids.push("default");
      setTenants(ids);
    }).catch(() => setTenants(["default"]));
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [messages]);

  // ── Voice input ──
  const startRec = useCallback(() => {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;
    const rec = new SR();
    rec.lang = "zh-CN"; rec.continuous = true; rec.interimResults = true;
    rec.onresult = (e) => {
      let final = "", interim = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const tr = e.results[i][0].transcript;
        if (e.results[i].isFinal) final += tr; else interim += tr;
      }
      setInput((prev) => {
        const base = prev.trim();
        const next = final || interim;
        return !base ? next : final ? base + " " + final : base + " " + interim;
      });
    };
    rec.onerror = (e) => { if (e.error !== "aborted") showToast(`语音错误: ${e.error}`, "error"); setRecording(false); };
    rec.onend = () => setRecording(false);
    rec.start();
    recRef.current = rec;
    setRecording(true);
  }, [showToast]);

  const stopRec = useCallback(() => { recRef.current?.stop(); recRef.current = null; setRecording(false); }, []);

  const clearChat = useCallback(() => {
    setMessages([]); setSessionId(""); setLoading(false);
    lastDeltaRef.current = "";
  }, []);

  // ── Send message ──
  const send = useCallback(async (text: string, baseMessages?: ChatMessage[]) => {
    if (!text || loading) return;
    lastDeltaRef.current = "";

    const userMsg: ChatMessage = { id: crypto.randomUUID(), role: "user", content: text, timestamp: Date.now() };
    const assistantMsg: ChatMessage = { id: crypto.randomUUID(), role: "assistant", content: "", timestamp: Date.now(), streaming: true };

    setMessages(baseMessages ? [...baseMessages, userMsg, assistantMsg] : (prev) => [...prev, userMsg, assistantMsg]);
    setInput("");
    setLoading(true);

    try {
      const mParams: Record<string, number | boolean | string> = {};
      if (temperature !== "") mParams.temperature = temperature;
      if (maxTokens !== "") mParams.max_tokens = maxTokens;
      if (topP !== "") mParams.top_p = topP;
      if (reasoning) mParams.reasoning = true;

      // Use the existing chat stream API to trigger the agent
      // The harness SSE stream (already subscribed via useHarnessStream)
      // will deliver structured events
      await api.chatStream({
        message: text,
        tenant_id: tenantId,
        session_id: sessionId || undefined,
        system_prompt: systemPrompt || undefined,
        model_params: Object.keys(mParams).length > 0 ? mParams : undefined,
        onEvent: (event, data) => {
          const d = data as Record<string, unknown>;
          if (event === "session" && d.session_id) {
            setSessionId(String(d.session_id));
          }
          if (event === "message" || event === "delta") {
            const content = String(d.content ?? "");
            // Fallback: if harness stream isn't delivering deltas, use the chat stream
            setMessages((prev) => {
              const last = prev[prev.length - 1];
              if (last?.role === "assistant" && last.streaming) {
                return [...prev.slice(0, -1), { ...last, content: last.content + content }];
              }
              return prev;
            });
          }
          if (event === "done") {
            setMessages((prev) => {
              const last = prev[prev.length - 1];
              if (last?.role === "assistant") return [...prev.slice(0, -1), { ...last, streaming: false }];
              return prev;
            });
            setLoading(false);
          }
          if (event === "error") {
            const errMsg = String(d.error ?? "Unknown error");
            setMessages((prev) => [...prev, { id: crypto.randomUUID(), role: "error", content: errMsg, timestamp: Date.now() }]);
            setLoading(false);
          }
        },
        onError: (err) => { showToast(err.message, "error"); setLoading(false); },
      });
    } catch (err) {
      showToast(err instanceof Error ? err.message : String(err), "error");
      setLoading(false);
    }
  }, [loading, tenantId, sessionId, systemPrompt, temperature, maxTokens, topP, reasoning, showToast]);

  // ── Approval ──
  const handleApprove = useCallback(async (approved: boolean) => {
    if (!sessionId || !harness.pendingApproval) return;
    try {
      await fetch(`${import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080"}/api/harness/${sessionId}/approve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          tool_call_id: harness.pendingApproval.callId,
          decision: approved ? "approve" : "reject",
          reason: approved ? "approved" : "rejected",
        }),
      });
    } catch (e) {
      showToast(String(e), "error");
    }
  }, [sessionId, harness.pendingApproval, showToast]);

  const regen = useCallback((msgId: string) => {
    setMessages((prev) => {
      const idx = prev.findIndex((m) => m.id === msgId);
      if (idx < 0) return prev;
      const keep = prev.slice(0, idx);
      const lastUser = keep.findLast((m) => m.role === "user");
      if (lastUser) setTimeout(() => send(lastUser.content, keep), 0);
      return keep;
    });
  }, [send]);

  return (
    <div className="flex h-[calc(100vh-3.5rem)] gap-4 p-4">
      {/* ── Left: Chat ── */}
      <div className="flex flex-1 flex-col min-w-0">
        {/* Toolbar */}
        <div className="mb-3 flex items-center gap-2">
          <Select value={tenantId} onValueChange={(v) => setTenantId(v)}>
            {tenants.map((t) => <SelectOption key={t} value={t}>{t}</SelectOption>)}
          </Select>
          <Button variant="ghost" size="sm" onClick={() => setSystemPromptOpen(!systemPromptOpen)}>
            <SlidersHorizontal className="h-4 w-4" />
            Prompt
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setModelParamsOpen(!modelParamsOpen)}>
            <SlidersHorizontal className="h-4 w-4" />
            Params
          </Button>
          <Button variant="ghost" size="sm" onClick={clearChat}>
            <Trash2 className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setDebugOpen(!debugOpen)}>
            <Wrench className="h-4 w-4" />
          </Button>
        </div>

        {/* System prompt */}
        {systemPromptOpen && (
          <Card className="mb-3 p-3">
            <textarea
              className="w-full bg-transparent text-sm outline-none resize-none"
              rows={2}
              placeholder="System prompt override..."
              value={systemPrompt}
              onChange={(e) => setSystemPrompt(e.target.value)}
            />
          </Card>
        )}

        {/* Model params */}
        {modelParamsOpen && (
          <Card className="mb-3 flex items-center gap-4 p-3">
            <ParamField label="Temp" value={temperature} onChange={setTemperature} step={0.1} />
            <ParamField label="MaxTokens" value={maxTokens} onChange={setMaxTokens} step={100} />
            <ParamField label="TopP" value={topP} onChange={setTopP} step={0.05} />
            <label className="flex items-center gap-1 text-xs">
              <input type="checkbox" checked={reasoning} onChange={(e) => setReasoning(e.target.checked)} />
              Reasoning
            </label>
          </Card>
        )}

        {/* Messages */}
        <div className="flex-1 overflow-y-auto space-y-3 pr-2">
          {messages.length === 0 && (
            <div className="flex h-full items-center justify-center text-zinc-500">
              <p className="text-sm">输入消息开始对话</p>
            </div>
          )}
          {messages.map((msg) => (
            <div key={msg.id}>
              <MessageRow msg={msg} onRegen={regen} onEdit={(id, text) => {
                setMessages((prev) => {
                  const idx = prev.findIndex((m) => m.id === id);
                  if (idx < 0) return prev;
                  const truncated = prev.slice(0, idx + 1);
                  truncated[idx] = { ...truncated[idx], content: text, editing: false };
                  setTimeout(() => send(text, truncated.slice(0, idx)), 0);
                  return truncated;
                });
              }} />
              {/* Tool calls inline */}
              {msg.role === "assistant" && msg.streaming && msg.toolCalls && msg.toolCalls.length > 0 && (
                <ToolCallTimeline calls={msg.toolCalls} className="mt-2 ml-8" />
              )}
              {/* Approval inline */}
              {msg.role === "assistant" && msg.streaming && harness.pendingApproval && (
                <ApprovalInline
                  approval={harness.pendingApproval}
                  onDecide={handleApprove}
                  className="mt-2 ml-8"
                />
              )}
            </div>
          ))}
          <div ref={scrollRef} />
        </div>

        {/* Input */}
        <div className="mt-3 flex items-center gap-2">
          {window.SpeechRecognition || window.webkitSpeechRecognition ? (
            <Button variant="ghost" size="icon" onClick={recording ? stopRec : startRec}>
              {recording ? <MicOff className="h-4 w-4 text-red-400" /> : <Mic className="h-4 w-4" />}
            </Button>
          ) : null}
          <Input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(input.trim()); } }}
            placeholder="输入消息..."
            disabled={loading}
            className="flex-1"
          />
          <Button onClick={() => send(input.trim())} disabled={loading || !input.trim()}>
            <Send className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {/* ── Right: Harness status panel ── */}
      <div className="w-80 shrink-0 space-y-3">
        <HarnessStatusPanel state={harness} />

        {/* Debug: raw events */}
        {debugOpen && (
          <Card className="p-3 max-h-64 overflow-y-auto">
            <p className="text-[10px] uppercase tracking-wider text-zinc-500 mb-2">Raw Events ({events.length})</p>
            {events.slice(-20).map((e, i) => (
              <div key={i} className="text-[11px] text-zinc-400 font-mono">
                {e.type} {Object.keys(e.data).length > 0 && JSON.stringify(e.data).slice(0, 80)}
              </div>
            ))}
          </Card>
        )}
      </div>
    </div>
  );
}

// ── Sub components ──

function MessageRow({ msg, onRegen, onEdit }: {
  msg: ChatMessage;
  onRegen: (id: string) => void;
  onEdit: (id: string, text: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [editText, setEditText] = useState(msg.content);

  if (msg.role === "user") {
    return (
      <div className="flex gap-3">
        <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-600/20">
          <User className="h-4 w-4 text-blue-400" />
        </div>
        <div className="min-w-0 flex-1">
          {editing ? (
            <div className="space-y-1">
              <textarea className="w-full bg-transparent text-sm outline-none resize-none" rows={2} value={editText} onChange={(e) => setEditText(e.target.value)} />
              <div className="flex gap-1">
                <Button size="sm" onClick={() => { onEdit(msg.id, editText); setEditing(false); }}>Save</Button>
                <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>Cancel</Button>
              </div>
            </div>
          ) : (
            <p className="text-sm text-zinc-200 whitespace-pre-wrap">{msg.content}</p>
          )}
          <div className="mt-1 flex gap-1">
            <button onClick={() => { setEditText(msg.content); setEditing(true); }} className="text-[10px] text-zinc-500 hover:text-zinc-300">
              <Pencil className="h-3 w-3" />
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (msg.role === "error") {
    return (
      <div className="flex gap-3">
        <AlertCircle className="h-4 w-4 text-red-400 mt-0.5" />
        <p className="text-sm text-red-400">{msg.content}</p>
      </div>
    );
  }

  // assistant
  return (
    <div className="flex gap-3">
      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-emerald-600/20">
        <Bot className="h-4 w-4 text-emerald-400" />
      </div>
      <div className="min-w-0 flex-1">
        {msg.content ? (
          <MarkdownRenderer content={msg.content} />
        ) : msg.streaming ? (
          <div className="flex items-center gap-1 text-zinc-500">
            <span className="h-1.5 w-1.5 rounded-full bg-zinc-500 animate-pulse" />
            <span className="text-xs">thinking...</span>
          </div>
        ) : null}
        {!msg.streaming && (
          <button onClick={() => onRegen(msg.id)} className="mt-1 text-[10px] text-zinc-500 hover:text-zinc-300">
            <RotateCcw className="h-3 w-3" />
          </button>
        )}
      </div>
    </div>
  );
}

function ParamField({ label, value, onChange, step }: {
  label: string;
  value: number | "";
  onChange: (v: number | "") => void;
  step: number;
}) {
  return (
    <label className="flex items-center gap-1 text-xs">
      <span className="text-zinc-500">{label}</span>
      <input
        type="number"
        step={step}
        value={value}
        onChange={(e) => onChange(e.target.value === "" ? "" : Number(e.target.value))}
        className="w-16 rounded bg-zinc-800 px-1.5 py-0.5 text-xs outline-none"
      />
    </label>
  );
}
