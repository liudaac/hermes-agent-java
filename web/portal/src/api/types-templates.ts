/**
 * Template marketplace types — agent/scenario catalogs, evolution proposals.
 */
// ── Business Templates ────────────────────────────────────────────────

export interface AgentTemplateMetric {
  label: string;
  value: string;
  unit?: string;
}

export interface AgentTemplateWorkflowStep {
  step: number;
  actor: string;
  action: string;
  duration?: string;
}

export interface AgentTemplateRiskPolicy {
  high: string[];
  medium: string[];
  low: string[];
}

export interface AgentTemplateHandoffTrigger {
  condition: string;
  target: string;
}

export interface AgentTemplateHandoffPolicy {
  defaultTarget?: string;
  triggers: AgentTemplateHandoffTrigger[];
}

export interface AgentTemplateRecord {
  templateId: string;
  name: string;
  role: string;
  category: string;
  status: "STABLE" | "BETA" | "EXPERIMENTAL";
  icon?: string;
  color?: string;
  mission?: string;
  description?: string;
  skills: string[];
  metrics: AgentTemplateMetric[];
  allowedTools: string[];
  allowedSkills: string[];
  instructions?: string;
  handoffPolicy?: AgentTemplateHandoffPolicy;
  riskPolicy: AgentTemplateRiskPolicy;
  demoWorkflow: AgentTemplateWorkflowStep[];
}

export interface ScenarioTemplateInvolvedAgent {
  templateId: string;
  roleInScenario?: string;
}

export interface ScenarioTemplateTimelineEntry {
  t: string;
  actor: string;
  action: string;
}

export interface ScenarioTemplatePromptAssetSpec {
  assetId: string;
  name: string;
  purpose?: string;
  content?: string;
}

export interface ScenarioTemplateTeamSpec {
  name: string;
  description?: string;
}

export interface ScenarioTemplateScenarioSpec {
  name: string;
  description?: string;
  collaborationPattern?: string;
  successCriteria: string[];
}

export interface ScenarioTemplateCloneBlueprint {
  team?: ScenarioTemplateTeamSpec;
  promptAssets: ScenarioTemplatePromptAssetSpec[];
  scenario?: ScenarioTemplateScenarioSpec;
}

export interface ScenarioTemplateRecord {
  templateId: string;
  name: string;
  category: string;
  status: "STABLE" | "BETA" | "EXPERIMENTAL";
  industryTag?: string;
  icon?: string;
  color?: string;
  summary?: string;
  description?: string;
  metrics: AgentTemplateMetric[];
  involvedAgents: ScenarioTemplateInvolvedAgent[];
  cloneBlueprint: ScenarioTemplateCloneBlueprint;
  workflowTimeline: ScenarioTemplateTimelineEntry[];
}

// ── Business Risk Policy ──────────────────────────────────────────────

export interface BusinessRiskCategoryBucket {
  category: string;
  agents: number;
  high: number;
  medium: number;
  low: number;
}

export interface BusinessRiskAction {
  templateId: string;
  agentName: string;
  category: string;
  action: string;
}

export interface BusinessRiskPolicySummary {
  ok: boolean;
  totals: {
    agents: number;
    high: number;
    medium: number;
    low: number;
    coverage: string;
  };
  byCategory: BusinessRiskCategoryBucket[];
  highRiskActions: BusinessRiskAction[];
}

// ── Evolution Proposal ───────────────────────────────────────────────

export interface EvolutionProposalRecord {
  workspaceId: string;
  proposalId: string;
  scenarioId?: string;
  teamId?: string;
  sourceInsightId?: string;
  title?: string;
  finding?: string;
  proposedChange?: string;
  expectedBenefit?: string;
  status?: string;            // DRAFT | EVALUATED | AWAITING_APPROVAL | APPROVED | REJECTED | APPLIED
  targetTeamId?: string;
  targetDraftVersion?: number;
  approvalId?: string;
  evidence?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
  appliedAt?: string;
}

// ── User Templates (M4 external ecosystem) ────────────────────────────

export interface UserTemplateListItem {
  type: "agent" | "scenario";
  templateId: string;
  name: string;
  category?: string;
  path?: string;
  meta?: { source?: string; author?: string; uploadedAt?: string };
}

// ── Industry Dashboard (M4) ───────────────────────────────────────────

export interface IndustryCategoryRollup {
  category: string;
  total: number;
  completed: number;
  failed: number;
  successRate: number;
  avgLatencyMs: number;
}

export interface IndustryTrendPoint {
  date: string;
  tasks: number;
  failures: number;
}

export interface IndustryDashboardResponse {
  ok: boolean;
  filter: { category: string; limit: number };
  byCategory: IndustryCategoryRollup[];
  trend: IndustryTrendPoint[];
  topAgents: { agent: string; tasks: number }[];
}

export interface IndustryDrillDownRun {
  runId: string;
  workspaceId?: string;
  teamId?: string;
  status?: string;
  category?: string;
  scenario?: string;
  scenarioId?: string;
  taskTitle?: string;
  resultSummary?: string;
  createdAt?: string;
  updatedAt?: string;
}
