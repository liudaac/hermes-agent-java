import { useCallback, useEffect, useRef, useState } from "react";
import {
  Send,
  Bot,
  User,
  AlertCircle,
  ArrowLeftRight,
  Play,
  Square,
  RotateCcw,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Select, SelectOption } from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { api } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import MarkdownRenderer from "@/components/MarkdownRenderer";
import { useI18n } from "@/i18n";

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
  systemPrompt: string;
}

function createSideState(tenantId: string): SideState {
  return { tenantId, sessionId: "", messages: [], loading: false, systemPrompt: "" };
}

export default function ComparePage() {
  const { showToast } = useToast();
  const { t } = useI18n();

  const [left, setLeft] = useState<SideState>(() => createSideState("default"));
  const [right, setRight] = useState<SideState>(() => createSideState("tenant-b"));
  const [tenants, setTenants] = useState<string[]>(["default"]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);

  // Auto-chat state
  const [autoRunning, setAutoRunning] = useState(false);
  const [autoTopic, setAutoTopic] = useState("");
  const [autoRounds, setAutoRounds] = useState(3);
  const [autoModeOpen, setAutoModeOpen] = useState(false);
  const abortAutoRef = useRef(false);

  const leftScrollRef = useRef<HTMLDivElement>(null);
  const rightScrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    leftScrollRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [left.messages]);

  useEffect(() => {
    rightScrollRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [right.messages]);

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

  const updateSide = useCallback(
    (side: "left" | "right", updater: (prev: SideState) => SideState) => {
      if (side === "left") setLeft(updater);
      else setRight(updater);
    },
    [],
  );

  /**
   * Send a message to one side and return a promise that resolves with
   * the final assistant response text once streaming is done.
   */
  const sendToSideAuto = useCallback(
    (side: "left" | "right", text: string): Promise<string> => {
      return new Promise((resolve, reject) => {
        const state = side === "left" ? left : right;
        const setState = side === "left" ? setLeft : setRight;
        let currentSid = state.sessionId;
        let finalResponse = "";

        const userMsg: ChatMessage = {
          id: crypto.randomUUID(),
          role: "user",
          content: text,
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

        api.chatStream({
          message: text,
          tenant_id: state.tenantId,
          session_id: currentSid || undefined,
          system_prompt: state.systemPrompt || undefined,
          onEvent: (event, data) => {
            const d = data as Record<string, unknown>;
            if (event === "session" && d.session_id) {
              currentSid = String(d.session_id);
              setState((prev) => ({ ...prev, sessionId: currentSid }));
            }
            if (event === "message" || event === "delta") {
              const content = String(d.content ?? "");
              finalResponse += content;
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
              resolve(finalResponse);
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
              reject(new Error(errMsg));
            }
          },
          onError: (err) => {
            showToast(`${side}: ${err.message}`, "error");
            setState((prev) => ({ ...prev, loading: false }));
            reject(err);
          },
        }).catch(reject);
      });
    },
    [left, right, showToast],
  );

  const sendToSide = useCallback(
    async (side: "left" | "right", text: string) => {
      await sendToSideAuto(side, text);
    },
    [sendToSideAuto],
  );

  const sendMessage = useCallback(async () => {
    const text = input.trim();
    if (!text || sending) return;
    setSending(true);
    setInput("");
    await Promise.all([sendToSide("left", text), sendToSide("right", text)]);
    setSending(false);
  }, [input, sending, sendToSide]);

  const runAutoChat = useCallback(async () => {
    const topic = autoTopic.trim();
    if (!topic) return;
    abortAutoRef.current = false;
    setAutoRunning(true);

    try {
      let currentMsg = topic;
      let currentSide: "left" | "right" = "left";

      for (let round = 0; round < autoRounds; round++) {
        for (let turn = 0; turn < 2; turn++) {
          if (abortAutoRef.current) break;
          const response = await sendToSideAuto(currentSide, currentMsg);
          if (abortAutoRef.current) break;
          currentSide = currentSide === "left" ? "right" : "left";
          currentMsg = response;
        }
        if (abortAutoRef.current) break;
      }
    } catch (err) {
      showToast(
        t.compare.autoChatStopped.replace("{error}", err instanceof Error ? err.message : String(err)),
        "error",
      );
    } finally {
      setAutoRunning(false);
    }
  }, [autoTopic, autoRounds, sendToSideAuto, showToast, t]);

  const stopAutoChat = useCallback(() => {
    abortAutoRef.current = true;
    setAutoRunning(false);
  }, []);

  const clearAll = useCallback(() => {
    setLeft(createSideState(left.tenantId));
    setRight(createSideState(right.tenantId));
  }, [left.tenantId, right.tenantId]);

  const [sysPromptOpen, setSysPromptOpen] = useState<{ left: boolean; right: boolean }>({
    left: false,
    right: false,
  });

  const renderPanel = (
    side: "left" | "right",
    state: SideState,
    scrollRef: React.RefObject<HTMLDivElement | null>,
  ) => (
    <div className="flex flex-col h-full min-h-0">
      <div className="flex items-center gap-2 mb-2">
        <Select
          value={state.tenantId}
          onValueChange={(v) =>
            updateSide(side, (prev) => ({ ...prev, tenantId: v }))
          }
          className="h-7 text-xs flex-1"
          disabled={autoRunning}
        >
          {tenants.map((id) => (
            <SelectOption key={id} value={id}>{id}</SelectOption>
          ))}
        </Select>
        <Button
          variant="ghost"
          size="sm"
          onClick={() =>
            setSysPromptOpen((prev) => ({ ...prev, [side]: !prev[side as "left" | "right"] }))
          }
          disabled={autoRunning}
          className="h-7 px-1.5 text-[10px]"
          title={t.playground.systemPrompt}
        >
          {state.systemPrompt ? "📝" : "📝"}
        </Button>
        {state.sessionId && (
          <Badge variant="secondary" className="text-[10px] h-5 shrink-0">
            {state.sessionId.slice(0, 6)}…
          </Badge>
        )}
      </div>
      {sysPromptOpen[side as "left" | "right"] && (
        <div className="mb-2">
          <textarea
            value={state.systemPrompt}
            onChange={(e) =>
              updateSide(side, (prev) => ({ ...prev, systemPrompt: e.target.value }))
            }
            placeholder={t.playground.systemPromptPlaceholder}
            rows={2}
            disabled={autoRunning}
            className="w-full bg-black/30 border border-current/20 rounded-sm px-2 py-1 text-[10px] font-mono leading-relaxed resize-y focus:outline-none focus:border-midground/40"
          />
        </div>
      )}
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
              {t.compare.title}
            </CardTitle>
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-xs">
                {left.tenantId}
              </Badge>
              <span className="opacity-40 text-xs">{t.compare.vs}</span>
              <Badge variant="outline" className="text-xs">
                {right.tenantId}
              </Badge>
              <Button
                variant="ghost"
                size="sm"
                onClick={clearAll}
                disabled={autoRunning}
                className="h-6 px-1.5"
                title={t.compare.clearBoth}
              >
                <RotateCcw className="h-3 w-3" />
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent className="flex-1 flex gap-3 min-h-0 pb-2">
          <div className="flex-1 min-w-0">{renderPanel("left", left, leftScrollRef)}</div>
          <div className="w-px bg-current/10 shrink-0" />
          <div className="flex-1 min-w-0">{renderPanel("right", right, rightScrollRef)}</div>
        </CardContent>
      </Card>

      {/* Auto Chat control */}
      <div className="border border-current/20 rounded-sm overflow-hidden shrink-0">
        <button
          onClick={() => setAutoModeOpen(!autoModeOpen)}
          className="w-full flex items-center justify-between px-3 py-2 text-xs tracking-wider opacity-70 hover:opacity-100 transition-opacity bg-current/5"
        >
          <span className="flex items-center gap-1.5">
            {autoRunning ? (
              <>
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-midground opacity-75" />
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-midground" />
                </span>
                {t.compare.autoChatRunning}
              </>
            ) : (
              <>
                <Play className="h-3 w-3" />
                {t.compare.autoChatMode}
              </>
            )}
          </span>
          {autoModeOpen ? (
            <span className="text-[10px] opacity-50">{t.compare.collapse}</span>
          ) : (
            <span className="text-[10px] opacity-50">{t.compare.expand}</span>
          )}
        </button>
        {autoModeOpen && (
          <div className="p-3 space-y-3">
            <div className="flex gap-3">
              <div className="flex-1">
                <label className="text-[10px] opacity-60 block mb-1">
                  {t.compare.initialTopic}
                </label>
                <Input
                  value={autoTopic}
                  onChange={(e) => setAutoTopic(e.target.value)}
                  placeholder={t.compare.initialTopicPlaceholder}
                  disabled={autoRunning}
                  className="h-8 text-xs"
                />
              </div>
              <div className="w-24">
                <label className="text-[10px] opacity-60 block mb-1">
                  {t.compare.rounds}
                </label>
                <Input
                  type="number"
                  min={1}
                  max={20}
                  value={autoRounds}
                  onChange={(e) => setAutoRounds(Number(e.target.value))}
                  disabled={autoRunning}
                  className="h-8 text-xs"
                />
              </div>
            </div>
            <div className="flex items-center gap-2 text-[10px] opacity-50">
              <span>
                {t.compare.roundsHint
                  .replace("{leftTenant}", left.tenantId)
                  .replace("{rightTenant}", right.tenantId)
                  .replace("{rounds}", String(autoRounds))
                  .replace("{totalMessages}", String(autoRounds * 2))}
              </span>
            </div>
            <div className="flex justify-end gap-2">
              {autoRunning ? (
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={stopAutoChat}
                  className="h-7 text-xs px-3"
                >
                  <Square className="h-3 w-3 mr-1" />
                  {t.compare.stop}
                </Button>
              ) : (
                <Button
                  size="sm"
                  onClick={runAutoChat}
                  disabled={!autoTopic.trim()}
                  className="h-7 text-xs px-3"
                >
                  <Play className="h-3 w-3 mr-1" />
                  {t.compare.startAutoChat}
                </Button>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Manual shared input */}
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
          placeholder={t.compare.askBoth}
          disabled={sending || autoRunning}
          className="flex-1 h-10"
        />
        <Button
          onClick={sendMessage}
          disabled={!input.trim() || sending || autoRunning}
          className="h-10 px-4"
        >
          <Send className="h-4 w-4 mr-1.5" />
          {t.compare.send}
        </Button>
      </div>
    </div>
  );
}
