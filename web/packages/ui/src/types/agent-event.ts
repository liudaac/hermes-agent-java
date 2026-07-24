/**
 * AgentEvent types - mirrors backend AgentEvent.java
 *
 * Structured events emitted by the agent loop, consumed by the frontend
 * via SSE (/api/harness/{sessionId}/stream).
 */

export type AgentEventType =
  | "LOOP_START"
  | "PRE_LLM"
  | "LLM_DELTA"
  | "POST_LLM"
  | "PRE_TOOL"
  | "POST_TOOL"
  | "APPROVAL_NEEDED"
  | "CONTEXT_COMPRESSED"
  | "LOOP_END"
  | "ERROR"
  | "connected"
  | "heartbeat";

export interface AgentEvent {
  type: AgentEventType;
  tenantId: string;
  sessionId: string;
  agentId: string;
  timestamp: number;
  data: Record<string, unknown>;
}

/** Tool call state tracked by the frontend. */
export interface ToolCallState {
  callId: string;
  name: string;
  args: Record<string, unknown>;
  status: "running" | "done" | "error" | "approval_needed";
  result?: string;
  ok?: boolean;
  durationMs?: number;
  startedAt: number;
}

/** Harness lifecycle status. */
export type HarnessStatus =
  | "idle"
  | "running"
  | "paused_approval"
  | "paused_governance"
  | "stopped"
  | "error";

/** Current phase within the loop. */
export type AgentPhase = "thinking" | "acting" | "observing" | "idle" | "approval_pending";

/** Aggregated harness state derived from the event stream. */
export interface HarnessState {
  status: HarnessStatus;
  phase: AgentPhase;
  iteration: number;
  maxIterations: number;
  toolCalls: ToolCallState[];
  pendingApproval: ToolCallState | null;
  tokensUsed: number;
  startedAt: number;
  lastEventAt: number;
  error: string | null;
}

/** Snapshot from GET /api/harness/active */
export interface HarnessSnapshot {
  sessionId: string;
  tenantId: string;
  status: string;
  debug: Record<string, unknown>;
}

export const initialHarnessState: HarnessState = {
  status: "idle",
  phase: "idle",
  iteration: 0,
  maxIterations: 0,
  toolCalls: [],
  pendingApproval: null,
  tokensUsed: 0,
  startedAt: 0,
  lastEventAt: 0,
  error: null,
};
