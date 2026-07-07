/**
 * Business Portal types — customer-facing workspace, team, run, approval.
 */
// ── Business Portal types ──────────────────────────────────────────────

export interface BusinessAction {
  id: string;
  title: string;
  description?: string;
}

export interface BusinessInsightRecord {
  insightId: string;
  workspaceId?: string;
  title: string;
  finding: string;
  possibleCause?: string;
  recommendation?: string;
  expectedBenefit?: string;
  suggestedAction?: string;
  severity?: string;
  metrics?: Record<string, unknown>;
  generatedAt?: string;
}

export interface BusinessHomeTrendPoint {
  date: string;
  value: number;
}

export interface BusinessHomeTrends {
  tasks: BusinessHomeTrendPoint[];
  failures: BusinessHomeTrendPoint[];
  completions: BusinessHomeTrendPoint[];
}

export interface BusinessHomeResponseBucket {
  index: number;
  fromMs: number;
  toMs: number;
  count: number;
}

export interface BusinessHomeResponseDistribution {
  sampleSize: number;
  p50Ms: number;
  p95Ms: number;
  buckets: BusinessHomeResponseBucket[];
}

export interface BusinessHomeRecentRun {
  runId: string;
  workspaceId?: string;
  teamId?: string;
  status?: string;
  scenario?: string;
  scenarioId?: string;
  taskTitle?: string;
  resultSummary?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface BusinessHomeResponse {
  ok: boolean;
  entry: string;
  workspaceId?: string;
  summary: {
    workspaceCount: number;
    teamCount: number;
    runCount: number;
    pendingApprovals: number;
    openInsights: number;
  };
  today: Record<string, number>;
  needsAttention: BusinessAction[];
  risk: { level: string; [key: string]: unknown };
  teamStatus: { total: number; normal: number; needsAttention: number; emptyState?: string };
  insights: BusinessInsightRecord[];
  nextActions: BusinessAction[];
  workspaces: WorkspaceRecord[];
  emptyState?: string;
  trends?: BusinessHomeTrends;
  responseDistribution?: BusinessHomeResponseDistribution;
  recentRuns?: BusinessHomeRecentRun[];
}

export interface WorkspaceRecord {
  workspaceId: string;
  tenantId: string;
  name: string;
  description?: string;
  owner?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  metadata?: Record<string, unknown>;
}

export interface BusinessTeamCard {
  workspaceId: string;
  teamId: string;
  name: string;
  scenario?: string;
  scenarioId?: string;
  activeVersion: number;
  versionCount: number;
  status: string;
}

export interface BusinessTeamsResponse {
  ok: boolean;
  entry: string;
  workspaceId?: string;
  teams: BusinessTeamCard[];
  total: number;
  emptyState?: string;
}

export interface BusinessRunStep {
  stepId?: string;
  title?: string;
  summary?: string;
  actor?: string;
  agentId?: string;
  score?: number;
  matchedSkills?: string;
  retry?: boolean;
  retryFrom?: string;
  evidence?: string;
  status?: string;
  timestamp?: string;
  metadata?: Record<string, unknown>;
}

export interface BusinessRunRecord {
  runId: string;
  workspaceId: string;
  teamId?: string;
  scenario?: string;
  scenarioId?: string;
  collaborationPattern?: string;
  slaName?: string;
  slaStatus?: string;
  taskTitle: string;
  taskInput?: string;
  resultSummary: string;
  conclusionReason?: string;
  systemAction?: string;
  riskJudgement?: string;
  nextSuggestion?: string;
  technicalTraceRef?: string;
  steps?: BusinessRunStep[];
  metrics?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  status: string;
  tokensUsed?: number;
  estimatedCost?: number;
  createdAt?: string;
}

export interface BusinessRunsResponse {
  ok: boolean;
  entry: string;
  workspaceId?: string;
  teamId?: string;
  runs: BusinessRunRecord[];
  total: number;
  emptyState?: string;
  nextActions?: BusinessAction[];
}

export interface BusinessApprovalRecord {
  approvalId: string;
  workspaceId: string;
  teamId?: string;
  title: string;
  summary: string;
  reasonRequired?: string;
  approveEffect?: string;
  rejectEffect?: string;
  recommendation?: string;
  riskLevel: string;
  status: string;
  evidence?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  createdAt?: string;
  resolvedAt?: string;
  resolvedBy?: string;
  resolutionReason?: string;
  requestedInfo?: string;
}

export interface BusinessApprovalsResponse {
  ok: boolean;
  entry: string;
  workspaceId?: string;
  approvals: BusinessApprovalRecord[];
  total: number;
  emptyState?: string;
}

export interface BusinessInsightsResponse {
  ok: boolean;
  entry: string;
  metrics: Record<string, number>;
  insights: BusinessInsightRecord[];
  total: number;
  nextActions: BusinessAction[];
  emptyState?: string;
}

export interface CreateBusinessWorkspacePayload {
  workspaceId: string;
  name: string;
  description?: string;
  owner?: string;
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessWorkspaceResponse {
  ok: boolean;
  workspaceId: string;
  tenantId: string;
  workspace: WorkspaceRecord;
  message?: string;
}

export interface AgentBlueprintPayload {
  agentId: string;
  displayName: string;
  responsibility?: string;
  knowledgeRefs?: string[];
  allowedTools?: string[];
  allowedSkills?: string[];
  approvalRules?: string[];
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessTeamBlueprintPayload {
  teamId: string;
  name: string;
  description?: string;
  scenario?: string;
  scenarioId?: string;
  operatingManual?: string;
  promptAssetRefs?: string[];
  agents?: AgentBlueprintPayload[];
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessTeamBlueprintResponse {
  ok: boolean;
  workspaceId: string;
  teamId: string;
  team: unknown;
  message?: string;
}

export interface CreateBusinessRunPayload {
  teamId?: string;
  scenario?: string;
  scenarioId?: string;
  taskTitle: string;
  taskInput?: string;
  resultSummary: string;
  conclusionReason?: string;
  systemAction?: string;
  riskJudgement?: string;
  nextSuggestion?: string;
  status?: string;
  technicalTraceRef?: string;
  steps?: BusinessRunStep[];
  metrics?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessRunResponse {
  ok: boolean;
  workspaceId: string;
  runId: string;
  run: BusinessRunRecord;
  message?: string;
}

export interface CreateBusinessApprovalPayload {
  teamId?: string;
  title: string;
  summary: string;
  reasonRequired?: string;
  approveEffect?: string;
  rejectEffect?: string;
  recommendation?: string;
  riskLevel?: string;
  evidence?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessApprovalResponse {
  ok: boolean;
  workspaceId: string;
  approvalId: string;
  approval: BusinessApprovalRecord;
  message?: string;
}

export interface ResolveBusinessApprovalPayload {
  actor?: string;
  reason?: string;
}

export interface RequestBusinessApprovalInfoPayload {
  actor?: string;
  requestedInfo?: string;
}

export interface ResolveBusinessApprovalResponse {
  ok: boolean;
  workspaceId: string;
  approvalId: string;
  status: string;
  approval: BusinessApprovalRecord;
  message?: string;
}

export interface BusinessScenarioRecord {
  workspaceId: string;
  scenarioId: string;
  name: string;
  description?: string;
  entryTeamId?: string;
  status?: string;
  successCriteria?: string[];
  approvalRules?: string[];
  collaborationPattern?: string;
  slaName?: string;
  metadata?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

export interface BusinessScenariosResponse {
  ok: boolean;
  workspaceId: string;
  scenarios: BusinessScenarioRecord[];
  total: number;
}

export interface CreateBusinessScenarioPayload {
  scenarioId: string;
  name: string;
  description?: string;
  entryTeamId?: string;
  successCriteria?: string[];
  approvalRules?: string[];
  collaborationPattern?: string;
  slaName?: string;
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessScenarioResponse {
  ok: boolean;
  workspaceId: string;
  scenarioId: string;
  scenario: BusinessScenarioRecord;
  message?: string;
}

export interface BusinessPromptAssetRecord {
  workspaceId: string;
  assetId: string;
  name: string;
  purpose?: string;
  content?: string;
  version: number;
  status: string;
  tags?: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface BusinessPromptAssetsResponse {
  ok: boolean;
  workspaceId: string;
  promptAssets: BusinessPromptAssetRecord[];
  total: number;
}

export interface CreateBusinessPromptAssetPayload {
  assetId: string;
  name: string;
  purpose?: string;
  content?: string;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface CreateBusinessPromptAssetResponse {
  ok: boolean;
  workspaceId: string;
  assetId: string;
  promptAsset: BusinessPromptAssetRecord;
  message?: string;
}


export interface QuickTeamDraft {
  teamId: string;
  teamName: string;
  description: string;
  scenario: string;
  scenarioId: string;
  operatingManual: string;
  approvalThreshold: string;
  tone: string;
  agents: {
    agentId: string;
    displayName: string;
    responsibility: string;
    allowedTools: string[];
    approvalRules: string[];
    knowledgeRefs: string[];
  }[];
  suggestedConnectors: string[];
  questions: string[];
  rawJson: string;
}

// ── 7-step workspace progress (Portal progress bar) ─────────────

export type StepStatus = "missing" | "partial" | "done" | "active";

export interface BusinessProgressStep {
  step: number;
  key: "template" | "workspace" | "team" | "scenario" | "run" | "approval" | "knowledge";
  label: string;
  status: StepStatus;
  id?: string | null;
  name?: string | null;
  members?: number;
  count?: number;
  total?: number;
  running?: number;
  failed?: number;
  pending?: number;
  highRisk?: number;
  insights?: number;
  skillsAdded?: number;
}

export interface BusinessProgressResponse {
  ok: boolean;
  workspaceId: string | null;
  activeStep: number;
  steps: BusinessProgressStep[];
  pendingApprovals: number;
  highRiskApprovals: number;
  generatedAt: string;
}

