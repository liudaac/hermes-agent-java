/**
 * Business Portal API — workspace, team, run, approval, scenario, template.
 *
 * All endpoints under /api/v1/business/* and /api/v1/workspaces/{wsId}/*.
 * Aligned with the three-space refactor: business users land here.
 */
import { fetchJSON } from "./_base";
import type {
  BusinessHomeResponse,
  BusinessTeamsResponse,
  BusinessRunsResponse,
  BusinessRunRecord,
  BusinessApprovalsResponse,
  BusinessInsightsResponse,
  BusinessScenariosResponse,
  BusinessPromptAssetsResponse,
  CreateBusinessScenarioPayload,
  CreateBusinessScenarioResponse,
  CreateBusinessRunResponse,
  CreateBusinessPromptAssetPayload,
  CreateBusinessPromptAssetResponse,
  CreateBusinessWorkspacePayload,
  CreateBusinessWorkspaceResponse,
  AgentBlueprintPayload,
  CreateBusinessTeamBlueprintPayload,
  CreateBusinessTeamBlueprintResponse,
  BusinessTeamCard,
  CreateBusinessRunPayload,
  CreateBusinessApprovalPayload,
  CreateBusinessApprovalResponse,
  ResolveBusinessApprovalPayload,
  RequestBusinessApprovalInfoPayload,
  ResolveBusinessApprovalResponse,
  QuickTeamDraft,
} from "./types/portal";
import type {
  SLATemplatesResponse,
  DLQResponse,
  ApprovalAnalyticsResponse,
  TakeoverSession,
  WorkflowStatusResponse,
  ConnectorsResponse,
  VerticalSeedResponse,
} from "./types/orchestration";
import type {
  AgentTemplateRecord,
  ScenarioTemplateRecord,
  BusinessRiskPolicySummary,
  EvolutionProposalRecord,
  UserTemplateListItem,
  IndustryDashboardResponse,
  IndustryDrillDownRun,
} from "./types/templates";

export const portalApi = {
  // ── Home / aggregation ──
  getBusinessHome: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<BusinessHomeResponse>(`/api/v1/business/home${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getBusinessTeams: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<BusinessTeamsResponse>(`/api/v1/business/teams${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getBusinessRuns: (workspaceId?: string, limit = 20, scenarioId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    if (scenarioId) qs.set("scenarioId", scenarioId);
    qs.set("limit", String(limit));
    return fetchJSON<BusinessRunsResponse>(`/api/v1/business/runs?${qs.toString()}`);
  },
  getBusinessRun: (workspaceId: string, runId: string) =>
    fetchJSON<{ ok: boolean; run: BusinessRunRecord }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/runs/${encodeURIComponent(runId)}`,
    ),
  getBusinessApprovals: (workspaceId?: string, status = "ALL") => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    qs.set("status", status);
    return fetchJSON<BusinessApprovalsResponse>(`/api/v1/business/approvals?${qs.toString()}`);
  },
  getBusinessInsights: (workspaceId?: string, scenarioId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    if (scenarioId) qs.set("scenarioId", scenarioId);
    return fetchJSON<BusinessInsightsResponse>(`/api/v1/business/insights${qs.toString() ? `?${qs.toString()}` : ""}`);
  },

  // ── Workspace lifecycle ──
  createBusinessWorkspace: (payload: CreateBusinessWorkspacePayload) =>
    fetchJSON<CreateBusinessWorkspaceResponse>("/api/v1/workspaces", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),

  // ── Scenarios ──
  getBusinessScenarios: (workspaceId: string) =>
    fetchJSON<BusinessScenariosResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios`),
  createBusinessScenario: (workspaceId: string, payload: CreateBusinessScenarioPayload) =>
    fetchJSON<CreateBusinessScenarioResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  executeBusinessScenario: (workspaceId: string, scenarioId: string, userInput: string) =>
    fetchJSON<CreateBusinessRunResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios/${encodeURIComponent(scenarioId)}/execute`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userInput }),
    }),

  // ── Prompt assets ──
  getBusinessPromptAssets: (workspaceId: string) =>
    fetchJSON<BusinessPromptAssetsResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/prompt-assets`),
  createBusinessPromptAsset: (workspaceId: string, payload: CreateBusinessPromptAssetPayload) =>
    fetchJSON<CreateBusinessPromptAssetResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/prompt-assets`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),

  // ── Teams (blueprint + versioned) ──
  createBusinessTeamBlueprint: (workspaceId: string, payload: CreateBusinessTeamBlueprintPayload) =>
    fetchJSON<CreateBusinessTeamBlueprintResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/team-blueprints`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  getBusinessTeamBlueprint: (workspaceId: string, teamId: string) =>
    fetchJSON<{ ok: boolean; team: BusinessTeamCard }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/team-blueprints/${encodeURIComponent(teamId)}`,
    ),
  createTeamBlueprintDraftVersion: (workspaceId: string, teamId: string, payload: { changeSummary: string; agents?: AgentBlueprintPayload[] }) =>
    fetchJSON<{ ok: boolean; version: { version: number; status: string } }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/team-blueprints/${encodeURIComponent(teamId)}/versions`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  activateTeamBlueprintVersion: (workspaceId: string, teamId: string, version: number) =>
    fetchJSON<{ ok: boolean; activeVersion: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/team-blueprints/${encodeURIComponent(teamId)}/versions/${version}/activate`,
      { method: "POST" },
    ),

  // ── Runs ──
  createBusinessRun: (workspaceId: string, payload: CreateBusinessRunPayload) =>
    fetchJSON<CreateBusinessRunResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/runs`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  resumeExecution: (workspaceId: string, approvalId: string, scenarioId: string, userInput: string) =>
    fetchJSON<CreateBusinessRunResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals/${encodeURIComponent(approvalId)}/resume-execution`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ scenarioId, userInput }) },
    ),

  // ── Approvals ──
  createBusinessApproval: (workspaceId: string, payload: CreateBusinessApprovalPayload) =>
    fetchJSON<CreateBusinessApprovalResponse>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
  approveBusinessApproval: (workspaceId: string, approvalId: string, payload: ResolveBusinessApprovalPayload) =>
    fetchJSON<ResolveBusinessApprovalResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals/${encodeURIComponent(approvalId)}/approve`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  rejectBusinessApproval: (workspaceId: string, approvalId: string, payload: ResolveBusinessApprovalPayload) =>
    fetchJSON<ResolveBusinessApprovalResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals/${encodeURIComponent(approvalId)}/reject`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  requestBusinessApprovalInfo: (workspaceId: string, approvalId: string, payload: RequestBusinessApprovalInfoPayload) =>
    fetchJSON<ResolveBusinessApprovalResponse>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/approvals/${encodeURIComponent(approvalId)}/request-info`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),

  // ── Quick Team Builder ──
  quickTeamDraft: (workspaceId: string, description: string) =>
    fetchJSON<{ ok: boolean; draft: QuickTeamDraft }>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/quick-draft`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ description }),
    }),
  quickTeamRefine: (workspaceId: string, description: string, previousDraft: string, answers: string[]) =>
    fetchJSON<{ ok: boolean; draft: QuickTeamDraft }>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/quick-refine`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ description, previousDraft, answers }),
    }),
  quickTeamPublish: (workspaceId: string, draft: QuickTeamDraft) =>
    fetchJSON<{ ok: boolean; teamId: string; team: BusinessTeamCard }>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/quick-publish`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ draft }),
    }),

  // ── Eval sets & canary ──
  listEvalSets: (workspaceId: string, scenarioId: string) =>
    fetchJSON<{ ok: boolean; evalSets: unknown[]; total: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios/${encodeURIComponent(scenarioId)}/evalsets`,
    ),
  createEvalSet: (workspaceId: string, scenarioId: string, payload: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; evalSetId: string; evalSet: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/scenarios/${encodeURIComponent(scenarioId)}/evalsets`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  listCanaries: (workspaceId: string, teamId: string) =>
    fetchJSON<{ ok: boolean; canaries: unknown[] }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries`,
    ),
  startCanary: (workspaceId: string, teamId: string, payload: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; canary: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  getActiveCanary: (workspaceId: string, teamId: string) =>
    fetchJSON<{ ok: boolean; canary: unknown | null }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries/active`,
    ),
  updateCanaryTraffic: (workspaceId: string, teamId: string, releaseId: string, trafficPercent: number) =>
    fetchJSON<{ ok: boolean; canary: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries/${encodeURIComponent(releaseId)}/traffic`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ trafficPercent }) },
    ),
  promoteCanary: (workspaceId: string, teamId: string, releaseId: string) =>
    fetchJSON<{ ok: boolean; canary: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries/${encodeURIComponent(releaseId)}/promote`,
      { method: "POST" },
    ),
  rollbackCanary: (workspaceId: string, teamId: string, releaseId: string) =>
    fetchJSON<{ ok: boolean; canary: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/teams/${encodeURIComponent(teamId)}/canaries/${encodeURIComponent(releaseId)}/rollback`,
      { method: "POST" },
    ),

  // ── Active memory ──
  listMemories: (workspaceId: string) =>
    fetchJSON<{ ok: boolean; memories: unknown[]; total: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/memories`,
    ),
  createMemory: (workspaceId: string, payload: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; memoryId: string; memory: unknown }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/memories`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  recallMemories: (workspaceId: string, payload: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; memories: unknown[]; count: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/memories/recall`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),

  // ── Template marketplace (Business) ──
  listAgentTemplates: (category?: string) =>
    fetchJSON<{ ok: boolean; count: number; items: AgentTemplateRecord[] }>(
      `/api/v1/business/agent-templates${category ? `?category=${encodeURIComponent(category)}` : ""}`,
    ),
  getAgentTemplate: (templateId: string) =>
    fetchJSON<{ ok: boolean; item: AgentTemplateRecord }>(
      `/api/v1/business/agent-templates/${encodeURIComponent(templateId)}`,
    ),
  listScenarioTemplates: (category?: string) =>
    fetchJSON<{ ok: boolean; count: number; items: ScenarioTemplateRecord[] }>(
      `/api/v1/business/scenario-templates${category ? `?category=${encodeURIComponent(category)}` : ""}`,
    ),
  getScenarioTemplate: (templateId: string) =>
    fetchJSON<{ ok: boolean; item: ScenarioTemplateRecord }>(
      `/api/v1/business/scenario-templates/${encodeURIComponent(templateId)}`,
    ),
  cloneScenarioTemplate: (
    templateId: string,
    payload: { workspaceId?: string; workspaceName?: string; owner?: string },
  ) =>
    fetchJSON<{
      ok: boolean;
      workspaceId: string;
      teamId: string;
      scenarioId: string;
      promptAssetIds: string[];
      workspaceCreated: boolean;
    }>(`/api/v1/business/scenario-templates/${encodeURIComponent(templateId)}/clone`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload ?? {}),
    }),

  // ── Risk policy ──
  getBusinessRiskPolicySummary: (category?: string) =>
    fetchJSON<BusinessRiskPolicySummary>(
      `/api/v1/business/risk-policy/summary${category ? `?category=${encodeURIComponent(category)}` : ""}`,
    ),

  // ── Evolution proposals (self-evolution) ──
  listEvolutionProposals: (workspaceId: string, status?: string) =>
    fetchJSON<{ ok: boolean; proposals: EvolutionProposalRecord[]; total: number }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/evolution-proposals${status ? `?status=${encodeURIComponent(status)}` : ""}`,
    ),
  getEvolutionProposal: (workspaceId: string, proposalId: string) =>
    fetchJSON<{ ok: boolean; proposal: EvolutionProposalRecord }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/evolution-proposals/${encodeURIComponent(proposalId)}`,
    ),
  approveEvolutionProposal: (workspaceId: string, proposalId: string, payload: { actor?: string; reason?: string }) =>
    fetchJSON<{ ok: boolean; proposal: EvolutionProposalRecord }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/evolution-proposals/${encodeURIComponent(proposalId)}/approve`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  rejectEvolutionProposal: (workspaceId: string, proposalId: string, payload: { actor?: string; reason?: string }) =>
    fetchJSON<{ ok: boolean; proposal: EvolutionProposalRecord }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/evolution-proposals/${encodeURIComponent(proposalId)}/reject`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  applyEvolutionProposal: (workspaceId: string, proposalId: string, payload: { actor?: string }) =>
    fetchJSON<{ ok: boolean; proposal: EvolutionProposalRecord }>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/evolution-proposals/${encodeURIComponent(proposalId)}/apply`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),

  // ── User templates (M4) ──
  listUserTemplates: () =>
    fetchJSON<{ ok: boolean; root: string; items: UserTemplateListItem[] }>(
      `/api/v1/business/user-templates`,
    ),
  uploadUserAgentTemplate: (yamlBody: string, author?: string) =>
    fetchJSON<{ ok: boolean; templateId: string; template: AgentTemplateRecord }>(
      `/api/v1/business/user-templates/agents`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ yaml: yamlBody, author }) },
    ),
  uploadUserScenarioTemplate: (yamlBody: string, author?: string) =>
    fetchJSON<{ ok: boolean; templateId: string; template: ScenarioTemplateRecord }>(
      `/api/v1/business/user-templates/scenarios`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ yaml: yamlBody, author }) },
    ),
  deleteUserTemplate: (templateId: string) =>
    fetchJSON<{ ok: boolean }>(
      `/api/v1/business/user-templates/${encodeURIComponent(templateId)}`,
      { method: "DELETE" },
    ),

  // ── Industry dashboard (M4) ──
  getIndustryDashboard: (category?: string, limit?: number) => {
    const params = new URLSearchParams();
    if (category) params.set("category", category);
    if (limit) params.set("limit", String(limit));
    const qs = params.toString();
    return fetchJSON<IndustryDashboardResponse>(`/api/v1/business/industry-dashboard${qs ? `?${qs}` : ""}`);
  },
  getIndustryDashboardRuns: (filter: { category?: string; status?: string; limit?: number }) => {
    const params = new URLSearchParams();
    if (filter.category) params.set("category", filter.category);
    if (filter.status) params.set("status", filter.status);
    if (filter.limit) params.set("limit", String(filter.limit));
    const qs = params.toString();
    return fetchJSON<{
      ok: boolean;
      filter: { category: string; status: string; limit: number };
      count: number;
      items: IndustryDrillDownRun[];
    }>(`/api/v1/business/industry-dashboard/runs${qs ? `?${qs}` : ""}`);
  },

  // ── Orchestration (Portal view) ──
  getSLATemplates: () => fetchJSON<SLATemplatesResponse>("/api/v1/business/sla/templates"),
  getDLQ: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<DLQResponse>(`/api/v1/business/dlq${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  retryDLQItem: (itemId: string) =>
    fetchJSON<{ ok: boolean; itemId: string; status: string }>(
      `/api/v1/business/dlq/${encodeURIComponent(itemId)}/retry`, { method: "POST" },
    ),
  resolveDLQItem: (itemId: string) =>
    fetchJSON<{ ok: boolean; itemId: string; status: string }>(
      `/api/v1/business/dlq/${encodeURIComponent(itemId)}/resolve`, { method: "POST" },
    ),
  getApprovalAnalytics: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<ApprovalAnalyticsResponse>(`/api/v1/business/approval-analytics${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getTakeovers: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<{ ok: boolean; takeovers: TakeoverSession[] }>(`/api/v1/business/takeovers${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  requestTakeover: (workspaceId: string, runId: string, operatorId: string) =>
    fetchJSON<{ ok: boolean; takeover: TakeoverSession }>("/api/v1/business/takeovers", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ workspaceId, runId, operatorId }),
    }),
  confirmTakeover: (takeoverId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/v1/business/takeovers/${encodeURIComponent(takeoverId)}/confirm`, { method: "POST" }),
  releaseTakeover: (takeoverId: string) =>
    fetchJSON<{ ok: boolean }>(`/api/v1/business/takeovers/${encodeURIComponent(takeoverId)}/release`, { method: "POST" }),

  // ── Workflows & connectors (Portal) ──
  getWorkflows: (workspaceId?: string) => {
    const qs = new URLSearchParams();
    if (workspaceId) qs.set("workspaceId", workspaceId);
    return fetchJSON<{ ok: boolean; workflows: unknown[] }>(`/api/v1/business/workflows${qs.toString() ? `?${qs.toString()}` : ""}`);
  },
  getWorkflowStatus: (workflowId: string) =>
    fetchJSON<WorkflowStatusResponse>(`/api/v1/business/workflows/${encodeURIComponent(workflowId)}`),
  approveWorkflowCheckpoint: (workflowId: string, decision: string) =>
    fetchJSON<{ ok: boolean }>(`/api/v1/business/workflows/${encodeURIComponent(workflowId)}/checkpoint`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decision }),
    }),
  getConnectors: () => fetchJSON<ConnectorsResponse>("/api/v1/business/connectors"),
  executeConnector: (connectorName: string, operation: string, params: Record<string, unknown>) =>
    fetchJSON<{ ok: boolean; result: Record<string, unknown> }>(
      `/api/v1/business/connectors/${encodeURIComponent(connectorName)}/execute`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ operation, params }) },
    ),
  seedEcommerceVertical: (workspaceId: string) =>
    fetchJSON<VerticalSeedResponse>("/api/v1/business/verticals/ecommerce/seed", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ workspaceId }),
    }),
};
