/**
 * useHarnessStream - subscribe to /api/harness/{sessionId}/stream
 *
 * Returns aggregated HarnessState derived from the AgentEvent stream.
 * Components use this to render real-time agent execution panels.
 *
 * Usage:
 *   const { state, delta } = useHarnessStream(sessionId);
 *   // state.status -> "running" | "paused_approval" | ...
 *   // state.toolCalls -> [{ name, status, durationMs }, ...]
 *   // delta -> latest LLM_DELTA content (for streaming text)
 */
import { useEffect, useRef, useState, useCallback } from "react";
import type {
  AgentEvent,
  HarnessState,
  ToolCallState,
} from "../types/agent-event";
import { initialHarnessState } from "../types/agent-event";
import { getSessionToken } from "../lib/api";

interface UseHarnessStreamResult {
  state: HarnessState;
  /** Latest delta text (for streaming display). Reset on new turn. */
  delta: string;
  /** All events received (bounded). */
  events: AgentEvent[];
  /** Close the stream. */
  close: () => void;
}

const GATEWAY_URL =
  import.meta.env.VITE_HERMES_GATEWAY_URL ?? "http://127.0.0.1:8080";

export function useHarnessStream(
  sessionId: string | null,
  options?: { tenantId?: string; onEvent?: (e: AgentEvent) => void },
): UseHarnessStreamResult {
  const [state, setState] = useState<HarnessState>(initialHarnessState);
  const [delta, setDelta] = useState("");
  const [events, setEvents] = useState<AgentEvent[]>([]);
  const esRef = useRef<EventSource | null>(null);
  const onEventRef = useRef(options?.onEvent);
  onEventRef.current = options?.onEvent;

  const close = useCallback(() => {
    esRef.current?.close();
    esRef.current = null;
  }, []);

  useEffect(() => {
    if (!sessionId) return;

    // Reset state for new session
    setState(initialHarnessState);
    setDelta("");
    setEvents([]);

    const qs = new URLSearchParams();
    if (options?.tenantId) qs.set("tenant_id", options.tenantId);
    const token = getSessionToken();
    if (token) qs.set("token", token);
    const url = `${GATEWAY_URL}/api/harness/${sessionId}/stream?${qs}`;

    const es = new EventSource(url);
    esRef.current = es;

    const handleEvent = (type: string, rawData: unknown) => {
      const data = (rawData as Record<string, unknown>) ?? {};
      const event: AgentEvent = {
        type: type as AgentEvent["type"],
        tenantId: (data.tenantId as string) ?? "",
        sessionId: (data.sessionId as string) ?? sessionId,
        agentId: (data.agentId as string) ?? "",
        timestamp: (data.timestamp as number) ?? Date.now(),
        data,
      };

      // Buffer events (bounded to 100)
      setEvents((prev) => [...prev.slice(-99), event]);
      onEventRef.current?.(event);

      // Update aggregated state
      setState((prev) => {
        switch (event.type) {
          case "LOOP_START":
            return {
              ...prev,
              status: "running",
              startedAt: event.timestamp,
              maxIterations: (data.budget as number) ?? 0,
              iteration: 0,
              toolCalls: [],
              pendingApproval: null,
              error: null,
            };

          case "PRE_LLM":
            return {
              ...prev,
              status: "running",
              phase: "thinking",
              iteration: (data.iteration as number) ?? prev.iteration,
            };

          case "LLM_DELTA":
            setDelta((d) => d + ((data.content as string) ?? ""));
            return { ...prev, phase: "thinking" };

          case "POST_LLM":
            return {
              ...prev,
              phase: "acting",
              tokensUsed:
                prev.tokensUsed +
                ((data.totalTokens as number) ?? 0),
            };

          case "PRE_TOOL": {
            const tc: ToolCallState = {
              callId: (data.callId as string) ?? "",
              name: (data.tool as string) ?? "",
              args: (data.args as Record<string, unknown>) ?? {},
              status: "running",
              startedAt: event.timestamp,
            };
            return {
              ...prev,
              phase: "acting",
              toolCalls: [...prev.toolCalls, tc],
            };
          }

          case "POST_TOOL":
            return {
              ...prev,
              phase: "observing",
              toolCalls: prev.toolCalls.map((tc) =>
                tc.callId === (data.callId as string)
                  ? {
                      ...tc,
                      status: (data.ok as boolean) ? "done" : "error",
                      ok: data.ok as boolean,
                      durationMs: data.durationMs as number,
                    }
                  : tc,
              ),
            };

          case "APPROVAL_NEEDED": {
            const tc: ToolCallState = {
              callId: (data.callId as string) ?? "",
              name: (data.tool as string) ?? "",
              args: {},
              status: "approval_needed",
              startedAt: event.timestamp,
            };
            return {
              ...prev,
              status: "paused_approval",
              phase: "approval_pending",
              pendingApproval: tc,
            };
          }

          case "CONTEXT_COMPRESSED":
            return prev; // just informational

          case "LOOP_END":
            return {
              ...prev,
              status: "idle",
              phase: "idle",
              iteration: (data.iterations as number) ?? prev.iteration,
            };

          case "ERROR":
            return {
              ...prev,
              status: "error",
              error: (data.message as string) ?? "Unknown error",
            };

          default:
            return prev;
        }
      });
    };

    // Wire named events
    const eventTypes = [
      "LOOP_START", "PRE_LLM", "LLM_DELTA", "POST_LLM",
      "PRE_TOOL", "POST_TOOL", "APPROVAL_NEEDED",
      "CONTEXT_COMPRESSED", "LOOP_END", "ERROR",
      "connected", "heartbeat",
    ];
    for (const type of eventTypes) {
      es.addEventListener(type, (e: MessageEvent) => {
        try {
          handleEvent(type, JSON.parse(e.data));
        } catch {
          handleEvent(type, e.data);
        }
      });
    }

    es.onerror = () => {
      setState((prev) => ({
        ...prev,
        status: "error",
        error: "Connection lost",
      }));
    };

    return () => {
      es.close();
      esRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  return { state, delta, events, close };
}
