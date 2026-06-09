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
  Plus,
  Trash2,
  RefreshCw,
  Clock,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Select, SelectOption } from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { api, type CompareRun } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import MarkdownRenderer from "@/components/MarkdownRenderer";
import { useI18n } from "@/i18n";

interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "error" | "tool";
  content: string;
  streaming?: boolean;
}

interface ParticipantState {
  id: string;
  tenantId: string;
  sessionId: string;
  messages: ChatMessage[];
  loading: boolean;
}

function createParticipant(tenantId: string): ParticipantState {
  return {
    id: crypto.randomUUID(),
    tenantId,
    sessionId: "",
    messages: [],
    loading: false,
  };
}

const COMPARE_STORAGE_KEY = "hermes:compare";

function loadCompareState(): Record<string, unknown> | null {
  try {
    const raw = localStorage.getItem(COMPARE_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function saveCompareState(state: Record<string, unknown>) {
  try {
    localStorage.setItem(COMPARE_STORAGE_KEY, JSON.stringify(state));
  } catch {
    // ignore quota errors
  }
}

function tenantIdsFromSaved(saved: Record<string, unknown> | null): string[] {
  const list = saved?.participantTenantIds;
  if (Array.isArray(list) && list.length > 0) {
    return list.map(String);
  }
  return [
    (saved?.leftTenantId as string) ?? "default",
    (saved?.rightTenantId as string) ?? "default",
  ];
}

export default function ComparePage() {
  const { showToast } = useToast();
  const { t } = useI18n();
  const saved = loadCompareState();

  const [participants, setParticipants] = useState<ParticipantState[]>(() =>
    tenantIdsFromSaved(saved).map(createParticipant),
  );
  const [tenants, setTenants] = useState<string[]>(["default"]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [conclusion, setConclusion] = useState("");
  const [conclusionLoading, setConclusionLoading] = useState(false);
  const [historyRuns, setHistoryRuns] = useState<CompareRun[]>([]);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);

  const [autoRunning, setAutoRunning] = useState(false);
  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [autoTopic, setAutoTopic] = useState<string>(saved?.autoTopic as string ?? "");
  const [autoRounds, setAutoRounds] = useState<number>(saved?.autoRounds as number ?? 3);
  const [autoModeOpen, setAutoModeOpen] = useState(false);
  const abortAutoRef = useRef(false);
  const scrollRefs = useRef<Record<string, HTMLDivElement | null>>({});

  useEffect(() => {
    participants.forEach((p) => {
      scrollRefs.current[p.id]?.scrollIntoView({ behavior: "smooth", block: "end" });
    });
  }, [participants]);

  useEffect(() => {
    api.getTenants()
      .then((res) => {
        const ids = res.tenants.map((tenant) => tenant.tenantId);
        if (ids.length === 0) ids.push("default");
        setTenants(ids);

        const savedTenantIds = tenantIdsFromSaved(saved);
        const validTenantIds = savedTenantIds
          .map((id) => (ids.includes(id) ? id : ids[0] ?? "default"))
          .slice(0, Math.max(2, savedTenantIds.length));

        if (validTenantIds.length < 2) {
          validTenantIds.push(ids[1] ?? ids[0] ?? "default");
        }
        setParticipants(validTenantIds.map(createParticipant));
      })
      .catch(() => {
        setTenants(["default"]);
        setParticipants([createParticipant("default"), createParticipant("default")]);
      });
  }, []);

  useEffect(() => {
    saveCompareState({
      participantTenantIds: participants.map((p) => p.tenantId),
      autoTopic,
      autoRounds,
    });
  }, [participants, autoTopic, autoRounds]);

  const loadHistoryRuns = useCallback(async () => {
    setHistoryLoading(true);
    try {
      const res = await api.listCompareRuns();
      setHistoryRuns(res.runs ?? []);
    } catch (err) {
      showToast(err instanceof Error ? err.message : String(err), "error");
    } finally {
      setHistoryLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    loadHistoryRuns();
  }, [loadHistoryRuns]);

  const updateParticipant = useCallback(
    (id: string, updater: (prev: ParticipantState) => ParticipantState) => {
      setParticipants((prev) => prev.map((p) => (p.id === id ? updater(p) : p)));
    },
    [],
  );

  const resetParticipantTenant = useCallback((id: string, tenantId: string) => {
    setParticipants((prev) => prev.map((p) => (p.id === id ? { ...createParticipant(tenantId), id } : p)));
    setConclusion("");
  }, []);

  const addParticipant = useCallback(() => {
    setParticipants((prev) => [...prev, createParticipant(tenants[0] ?? "default")]);
    setConclusion("");
  }, [tenants]);

  const removeParticipant = useCallback((id: string) => {
    setParticipants((prev) => {
      if (prev.length <= 2) return prev;
      return prev.filter((p) => p.id !== id);
    });
    setConclusion("");
  }, []);

  const clearAll = useCallback(() => {
    setParticipants((prev) => prev.map((p) => ({ ...createParticipant(p.tenantId), id: p.id })));
    setConclusion("");
  }, []);

  const sendToParticipantAuto = useCallback(
    (participantId: string, text: string): Promise<string> => {
      return new Promise((resolve, reject) => {
        const state = participants.find((p) => p.id === participantId);
        if (!state) {
          reject(new Error("Participant not found"));
          return;
        }

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

        updateParticipant(participantId, (prev) => ({
          ...prev,
          messages: [...prev.messages, userMsg, assistantMsg],
          loading: true,
        }));

        api.chatStream({
          message: text,
          tenant_id: state.tenantId,
          session_id: currentSid || undefined,
          onEvent: (event, data) => {
            const d = data as Record<string, unknown>;
            if (event === "session" && d.session_id) {
              currentSid = String(d.session_id);
              updateParticipant(participantId, (prev) => ({ ...prev, sessionId: currentSid }));
            }
            if (event === "message" || event === "delta") {
              const content = String(d.content ?? "");
              finalResponse += content;
              updateParticipant(participantId, (prev) => {
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
            if (event === "tool_chain") {
              const calls = Array.isArray(d.calls) ? d.calls : [];
              if (calls.length > 0) {
                const summary = calls.map((call) => {
                  const c = call as Record<string, unknown>;
                  return `${c.tool ?? c.name ?? "tool"}: ${c.ok ?? c.status ?? "done"}`;
                }).join("\n");
                updateParticipant(participantId, (prev) => ({
                  ...prev,
                  messages: [...prev.messages, { id: crypto.randomUUID(), role: "tool", content: summary }],
                }));
              }
            }
            if (event === "usage") {
              updateParticipant(participantId, (prev) => ({
                ...prev,
                messages: [
                  ...prev.messages,
                  { id: crypto.randomUUID(), role: "tool", content: `Usage: ${JSON.stringify(d)}` },
                ],
              }));
            }
            if (event === "done") {
              updateParticipant(participantId, (prev) => {
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
              updateParticipant(participantId, (prev) => ({
                ...prev,
                messages: [...prev.messages, { id: crypto.randomUUID(), role: "error", content: errMsg }],
                loading: false,
              }));
              reject(new Error(errMsg));
            }
          },
          onError: (err) => {
            showToast(`${state.tenantId}: ${err.message}`, "error");
            updateParticipant(participantId, (prev) => ({ ...prev, loading: false }));
            reject(err);
          },
        }).catch(reject);
      });
    },
    [participants, showToast, updateParticipant],
  );

  const sendMessage = useCallback(async () => {
    const text = input.trim();
    if (!text || sending || participants.length === 0) return;
    setSending(true);
    setInput("");
    try {
      await Promise.all(participants.map((p) => sendToParticipantAuto(p.id, text)));
    } finally {
      setSending(false);
    }
  }, [input, participants, sending, sendToParticipantAuto]);

  const buildTranscript = useCallback((state: ParticipantState, index: number) => {
    const body = state.messages
      .filter((m) => m.role === "user" || m.role === "assistant")
      .map((m) => `${m.role.toUpperCase()}: ${m.content}`)
      .join("\n\n");
    return `# Participant ${index + 1} (${state.tenantId})\n${body}`;
  }, []);

  const synthesizeConclusion = useCallback(async () => {
    if (conclusionLoading) return;
    if (!participants.some((p) => p.messages.length > 0)) return;

    setConclusion("");
    setConclusionLoading(true);
    const transcripts = participants.map(buildTranscript).join("\n\n---\n\n");
    const prompt = [
      "You are a neutral evaluator. Compare all tenant conversations below.",
      "Return a concise structured conclusion with: consensus, disagreements, each participant's strengths and weaknesses, final recommendation, and next actions.",
      transcripts,
    ].join("\n\n");

    try {
      await api.chatStream({
        message: prompt,
        tenant_id: "default",
        session_id: `compare-summary-${Date.now()}`,
        onEvent: (event, data) => {
          const d = data as Record<string, unknown>;
          if (event === "message" || event === "delta") {
            setConclusion((prev) => prev + String(d.content ?? ""));
          }
          if (event === "done") {
            setConclusionLoading(false);
          }
          if (event === "error") {
            setConclusionLoading(false);
            showToast(String(d.error ?? "Conclusion generation failed"), "error");
          }
        },
        onError: (err) => {
          setConclusionLoading(false);
          showToast(err.message, "error");
        },
      });
    } catch (err) {
      setConclusionLoading(false);
      showToast(err instanceof Error ? err.message : String(err), "error");
    }
  }, [buildTranscript, conclusionLoading, participants, showToast]);

  const applyCompareRun = useCallback((run: CompareRun) => {
    const byTenant = new Map<string, ChatMessage[]>();
    for (const event of run.events ?? []) {
      const role = event.role === "user" || event.role === "assistant" ? event.role : "tool";
      const messages = byTenant.get(event.tenant_id) ?? [];
      messages.push({
        id: `${run.id}-${event.timestamp}-${messages.length}`,
        role,
        content: event.content,
      });
      byTenant.set(event.tenant_id, messages);
    }

    setParticipants((prev) => prev.map((participant) => ({
      ...participant,
      sessionId: run.participants.find((p) => p.tenant_id === participant.tenantId)?.session_id ?? participant.sessionId,
      messages: byTenant.get(participant.tenantId) ?? participant.messages,
      loading: run.status === "RUNNING" || run.status === "PENDING",
    })));
    setConclusion(run.conclusion ?? "");
  }, []);

  const watchCompareRun = useCallback(async (runId: string) => {
    abortAutoRef.current = false;
    setAutoRunning(true);
    setActiveRunId(runId);
    try {
      await api.streamCompareRun(runId, {
        onEvent: (event, data) => {
          const payload = data as Record<string, unknown>;
          if (event === "run" || event === "done") {
            applyCompareRun(payload as unknown as CompareRun);
          }
          if (event === "error") {
            showToast(String(payload.error ?? "Comparison stream failed"), "error");
          }
        },
        onError: (err) => {
          if (!abortAutoRef.current) {
            showToast(err.message, "error");
          }
        },
      });
    } catch (err) {
      showToast(
        t.compare.autoChatStopped.replace("{error}", err instanceof Error ? err.message : String(err)),
        "error",
      );
    } finally {
      setAutoRunning(false);
      setActiveRunId(null);
      loadHistoryRuns();
    }
  }, [applyCompareRun, loadHistoryRuns, showToast, t]);

  const loadRunFromHistory = useCallback(async (runId: string) => {
    try {
      const res = await api.getCompareRun(runId);
      const run = res.run;
      const byTenant = new Map<string, ChatMessage[]>();
      for (const event of run.events ?? []) {
        const role = event.role === "user" || event.role === "assistant" ? event.role : "tool";
        const messages = byTenant.get(event.tenant_id) ?? [];
        messages.push({ id: `${run.id}-${event.timestamp}-${messages.length}`, role, content: event.content });
        byTenant.set(event.tenant_id, messages);
      }
      const inFlight = run.status === "RUNNING" || run.status === "PENDING";
      setParticipants(run.participants.map((p) => ({
        id: crypto.randomUUID(),
        tenantId: p.tenant_id,
        sessionId: p.session_id,
        messages: byTenant.get(p.tenant_id) ?? [],
        loading: inFlight,
      })));
      setConclusion(run.conclusion ?? "");
      setActiveRunId(inFlight ? run.id : null);
      setHistoryOpen(false);
      if (inFlight) {
        showToast(t.compare.runningRunRestoreNotice, "success");
        await watchCompareRun(run.id);
      }
    } catch (err) {
      showToast(err instanceof Error ? err.message : String(err), "error");
    }
  }, [showToast, t, watchCompareRun]);

  const runAutoChat = useCallback(async () => {
    const topic = autoTopic.trim();
    if (!topic || participants.length < 2) return;
    setConclusion("");

    try {
      const tenantIds = participants.map((p) => p.tenantId);
      const created = await api.createCompareRun({ topic, rounds: autoRounds, tenant_ids: tenantIds });
      applyCompareRun(created.run);
      await watchCompareRun(created.run.id);
    } catch (err) {
      showToast(
        t.compare.autoChatStopped.replace("{error}", err instanceof Error ? err.message : String(err)),
        "error",
      );
    }
  }, [autoTopic, autoRounds, applyCompareRun, participants, showToast, t, watchCompareRun]);

  const stopAutoChat = useCallback(async () => {
    abortAutoRef.current = true;
    if (activeRunId) {
      try {
        await api.stopCompareRun(activeRunId);
      } catch (err) {
        showToast(err instanceof Error ? err.message : String(err), "error");
      }
    }
    setAutoRunning(false);
  }, [activeRunId, showToast]);


  const formatRunTime = useCallback((value: string) => {
    try {
      return new Intl.DateTimeFormat(undefined, {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
      }).format(new Date(value));
    } catch {
      return value;
    }
  }, []);

  const renderPanel = (state: ParticipantState, index: number) => (
    <div className="flex flex-col h-full min-h-[22rem]">
      <div className="flex items-center gap-2 mb-2">
        <Badge variant="outline" className="text-[10px] h-5 shrink-0">
          #{index + 1}
        </Badge>
        <Select
          value={state.tenantId}
          onValueChange={(v) => resetParticipantTenant(state.id, v)}
          className="h-7 text-xs flex-1"
          disabled={autoRunning}
        >
          {tenants.map((id) => (
            <SelectOption key={id} value={id}>{id}</SelectOption>
          ))}
        </Select>
        {state.sessionId && (
          <Badge variant="secondary" className="text-[10px] h-5 shrink-0">
            {state.sessionId.slice(0, 6)}…
          </Badge>
        )}
        <Button
          variant="ghost"
          size="sm"
          onClick={() => removeParticipant(state.id)}
          disabled={autoRunning || participants.length <= 2}
          className="h-6 px-1.5"
          title={t.compare.removeParticipant}
        >
          <Trash2 className="h-3 w-3" />
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

  const activeLabels = participants.map((p) => p.tenantId).join(" → ");

  return (
    <div className="space-y-4 min-h-[calc(100vh-8rem)] flex flex-col">
      <Card className="flex-1 flex flex-col min-h-0">
        <CardHeader className="pb-2 shrink-0">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base tracking-wide flex items-center gap-2">
              <ArrowLeftRight className="h-4 w-4" />
              {t.compare.title}
            </CardTitle>
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-xs">
                {participants.length} {t.compare.participants}
              </Badge>
              <Button
                variant="outline"
                size="sm"
                onClick={addParticipant}
                disabled={autoRunning}
                className="h-7 text-xs px-2"
              >
                <Plus className="h-3 w-3 mr-1" />
                {t.compare.addParticipant}
              </Button>
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

      <div className="border border-current/20 rounded-sm overflow-hidden shrink-0">
        <button
          onClick={() => setHistoryOpen(!historyOpen)}
          className="w-full flex items-center justify-between px-3 py-2 text-xs tracking-wider opacity-70 hover:opacity-100 transition-opacity bg-current/5"
        >
          <span>{t.compare.history}</span>
          <span className="text-[10px] opacity-50">{historyOpen ? t.compare.collapse : t.compare.expand}</span>
        </button>
        {historyOpen && (
          <div className="p-3 space-y-2 max-h-64 overflow-y-auto">
            <div className="flex items-center justify-between gap-2">
              <div className="text-[10px] opacity-50">{historyRuns.length} runs</div>
              <Button
                variant="ghost"
                size="sm"
                onClick={loadHistoryRuns}
                disabled={historyLoading}
                className="h-6 px-2 text-[10px]"
                title={t.compare.refreshHistory}
              >
                <RefreshCw className={cn("h-3 w-3 mr-1", historyLoading && "animate-spin")} />
                {t.compare.refreshHistory}
              </Button>
            </div>
            {historyRuns.length === 0 && (
              <div className="text-xs opacity-50">{t.compare.noHistory}</div>
            )}
            {historyRuns.slice(0, 20).map((run) => (
              <button
                key={run.id}
                onClick={() => loadRunFromHistory(run.id)}
                className="w-full text-left border border-current/10 hover:border-current/30 rounded-sm px-2 py-1.5 text-xs transition-colors"
                title={t.compare.openRun}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate font-medium">{run.topic}</span>
                  <Badge variant="outline" className="text-[10px] h-5 shrink-0">{run.status}</Badge>
                </div>
                <div className="opacity-50 mt-1 truncate">
                  {run.participants.map((p) => p.tenant_id).join(" → ")} · {run.event_count} {t.compare.runEvents}
                </div>
                <div className="opacity-45 mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="inline-flex items-center gap-1">
                    <Clock className="h-3 w-3" />
                    {t.compare.runCreated}: {formatRunTime(run.created_at)}
                  </span>
                  <span>{t.compare.runUpdated}: {formatRunTime(run.updated_at)}</span>
                </div>
                {run.error && (
                  <div className="mt-1 text-red-300 truncate">
                    {t.compare.runError}: {run.error}
                  </div>
                )}
              </button>
            ))}
          </div>
        )}
      </div>

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
                  .replace("{participants}", activeLabels)
                  .replace("{rounds}", String(autoRounds))
                  .replace("{totalMessages}", String(autoRounds * participants.length))}
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
                  disabled={!autoTopic.trim() || participants.length < 2 || new Set(participants.map((p) => p.tenantId)).size < 2}
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

      {(conclusion || conclusionLoading || participants.some((p) => p.messages.length > 0)) && (
        <Card className="shrink-0">
          <CardHeader className="py-2">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm">{t.compare.conclusion}</CardTitle>
              <Button
                variant="outline"
                size="sm"
                onClick={synthesizeConclusion}
                disabled={conclusionLoading || autoRunning || !participants.some((p) => p.messages.length > 0)}
                className="h-7 text-xs px-3"
              >
                {conclusionLoading ? t.compare.conclusionLoading : t.compare.generateConclusion}
              </Button>
            </div>
          </CardHeader>
          {(conclusion || conclusionLoading) && (
            <CardContent className="pt-0 text-xs max-h-48 overflow-y-auto">
              <MarkdownRenderer content={conclusion || t.compare.conclusionLoading} />
            </CardContent>
          )}
        </Card>
      )}

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
          disabled={!input.trim() || sending || autoRunning || participants.length === 0}
          className="h-10 px-4"
        >
          <Send className="h-4 w-4 mr-1.5" />
          {t.compare.send}
        </Button>
      </div>
    </div>
  );
}
