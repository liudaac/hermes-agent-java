import { useCallback, useEffect, useRef, useState } from "react";
import { api, type CompareRun } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { useI18n } from "@/i18n";
import { CompareChat, type ParticipantState, type ChatMessage } from "@/components/compare/CompareChat";
import { CompareParticipants, ParticipantActions } from "@/components/compare/CompareParticipants";
import { CompareControls } from "@/components/compare/CompareControls";

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

  const activeLabels = participants.map((p) => p.tenantId).join(" -> ");
  const hasMessages = participants.some((p) => p.messages.length > 0);

  return (
    <div className="space-y-4 min-h-[calc(100vh-8rem)] flex flex-col">
      <div className="flex items-center justify-between shrink-0">
        <ParticipantActions
          participantsCount={participants.length}
          autoRunning={autoRunning}
          onAdd={addParticipant}
          onClearAll={clearAll}
        />
      </div>

      <CompareChat
        participants={participants}
        tenants={tenants}
        input={input}
        sending={sending}
        autoRunning={autoRunning}
        onInputChange={setInput}
        onSend={sendMessage}
        onResetParticipant={resetParticipantTenant}
        onRemoveParticipant={removeParticipant}
      />

      <CompareParticipants
        historyOpen={historyOpen}
        onToggleHistory={() => setHistoryOpen(!historyOpen)}
        historyRuns={historyRuns}
        historyLoading={historyLoading}
        onRefreshHistory={loadHistoryRuns}
        onLoadRun={loadRunFromHistory}
        formatRunTime={formatRunTime}
      />

      <CompareControls
        autoRunning={autoRunning}
        autoModeOpen={autoModeOpen}
        autoTopic={autoTopic}
        autoRounds={autoRounds}
        conclusion={conclusion}
        conclusionLoading={conclusionLoading}
        hasMessages={hasMessages}
        onToggleMode={() => setAutoModeOpen(!autoModeOpen)}
        onTopicChange={setAutoTopic}
        onRoundsChange={setAutoRounds}
        onStart={runAutoChat}
        onStop={stopAutoChat}
        onGenerateConclusion={synthesizeConclusion}
        activeLabels={activeLabels}
      />
    </div>
  );
}
