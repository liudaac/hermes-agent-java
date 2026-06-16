import { useState } from "react";
import type { ReactNode } from "react";
import { Plus } from "lucide-react";
import type { BusinessTeamCard, CreateBusinessApprovalPayload, CreateBusinessRunPayload, CreateBusinessTeamBlueprintPayload, CreateBusinessWorkspacePayload, WorkspaceRecord } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";



const BUSINESS_ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}$/;
const BUSINESS_ID_HELP = "Use 2-64 chars: letters, numbers, dot, underscore or dash. Start with a letter or number.";

function isValidBusinessId(value: string): boolean {
  return BUSINESS_ID_PATTERN.test(value.trim());
}

function friendlyBusinessError(error: unknown, kind: "workspace" | "team"): string {
  const raw = String(error);
  const lower = raw.toLowerCase();
  if (lower.includes("already exists")) {
    return kind === "workspace"
      ? "A workspace with this ID already exists. Pick another ID or select the existing workspace."
      : "A team blueprint with this ID already exists in the selected workspace. Pick another team ID.";
  }
  if (lower.includes("must be 2-64") || lower.includes("id is required") || lower.includes("invalid")) {
    return BUSINESS_ID_HELP;
  }
  if (lower.includes("workspace not found")) {
    return "The selected workspace no longer exists. Refresh the page and choose another workspace.";
  }
  return raw;
}

export function BusinessCreationPanel({
  workspaceCount,
  teamCount,
  workspaceForm,
  teamForm,
  runForm,
  approvalForm,
}: {
  workspaceCount: number;
  teamCount: number;
  workspaceForm: ReactNode;
  teamForm: ReactNode;
  runForm: ReactNode;
  approvalForm: ReactNode;
}) {
  const shouldOpenWorkspace = workspaceCount === 0;
  const shouldOpenTeam = workspaceCount > 0 && teamCount === 0;

  return (
    <section className="space-y-3 rounded-sm border border-border/70 p-3">
      <div>
        <div className="font-expanded text-sm tracking-[0.1em]">Create business objects</div>
        <p className="mt-1 text-sm normal-case text-muted-foreground">
          Use these focused forms to build the Business Portal loop step by step.
        </p>
      </div>
      <CreationStep title="1. Workspace" description="Create the business space." defaultOpen={shouldOpenWorkspace}>
        {workspaceForm}
      </CreationStep>
      <CreationStep title="2. Team Blueprint" description="Create the first digital employee team." defaultOpen={shouldOpenTeam}>
        {teamForm}
      </CreationStep>
      <CreationStep title="3. Run Story" description="Record a business-readable run story." defaultOpen={false}>
        {runForm}
      </CreationStep>
      <CreationStep title="4. Approval Card" description="Create a mobile-first approval card." defaultOpen={false}>
        {approvalForm}
      </CreationStep>
    </section>
  );
}


function TextAreaField({
  label,
  value,
  onChange,
  placeholder,
  disabled,
  rows = 3,
  className = "md:col-span-4",
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  rows?: number;
  className?: string;
}) {
  return (
    <div className={`space-y-1 ${className}`}>
      <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">{label}</label>
      <textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        disabled={disabled}
        rows={rows}
        className="min-h-20 w-full rounded-sm border border-border bg-background px-3 py-2 text-sm normal-case tracking-normal outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
      />
    </div>
  );
}

function CreationStep({
  title,
  description,
  defaultOpen,
  children,
}: {
  title: string;
  description: string;
  defaultOpen: boolean;
  children: ReactNode;
}) {
  return (
    <details className="rounded-sm border border-border/60 p-3" open={defaultOpen}>
      <summary className="cursor-pointer">
        <div className="inline-flex flex-col gap-1 align-middle">
          <span className="font-expanded text-xs uppercase tracking-[0.1em]">{title}</span>
          <span className="text-sm normal-case text-muted-foreground">{description}</span>
        </div>
      </summary>
      <div className="mt-3">{children}</div>
    </details>
  );
}

export function CreateWorkspaceForm({
  onCreate,
}: {
  onCreate: (payload: CreateBusinessWorkspacePayload) => Promise<WorkspaceRecord>;
}) {
  const [workspaceId, setWorkspaceId] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [owner, setOwner] = useState("ops");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const normalizedWorkspaceId = workspaceId.trim();
  const workspaceIdValid = isValidBusinessId(normalizedWorkspaceId);
  const canSubmit = workspaceIdValid && !saving;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSubmit) {
      setError(BUSINESS_ID_HELP);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onCreate({
        workspaceId: normalizedWorkspaceId,
        name: name.trim() || normalizedWorkspaceId,
        description: description.trim() || undefined,
        owner: owner.trim() || "ops",
        metadata: { source: "business-portal-ui" },
      });
      setWorkspaceId("");
      setName("");
      setDescription("");
      setOwner("ops");
    } catch (err) {
      setError(friendlyBusinessError(err, "workspace"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Create Workspace</CardTitle>
        <CardDescription>Create the first business space without leaving the Business Portal.</CardDescription>
      </CardHeader>
      <CardContent>
        <form className="grid gap-3 md:grid-cols-[1fr_1fr_1fr_auto]" onSubmit={submit}>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Workspace ID</label>
            <Input
              value={workspaceId}
              onChange={(event) => setWorkspaceId(event.target.value)}
              placeholder="customer-service-demo"
              required
            />
            <div className="text-[0.65rem] normal-case text-muted-foreground">{BUSINESS_ID_HELP}</div>
          </div>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Name</label>
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Customer Service Demo" />
          </div>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Owner</label>
            <Input value={owner} onChange={(event) => setOwner(event.target.value)} placeholder="ops" />
          </div>
          <div className="flex items-end">
            <Button type="submit" disabled={!canSubmit} className="w-full md:w-auto">
              <Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}
            </Button>
          </div>
          <TextAreaField
            label="Description"
            value={description}
            onChange={setDescription}
            placeholder="Business workspace for after-sales scenarios"
            rows={2}
          />
          {error ? <div className="text-sm normal-case text-destructive md:col-span-4">{error}</div> : null}
        </form>
      </CardContent>
    </Card>
  );
}


export function CreateTeamBlueprintForm({
  workspaceId,
  onCreate,
}: {
  workspaceId?: string;
  onCreate: (workspaceId: string, payload: CreateBusinessTeamBlueprintPayload) => Promise<void>;
}) {
  const [teamId, setTeamId] = useState("");
  const [name, setName] = useState("");
  const [scenario, setScenario] = useState("after-sales ticket handling");
  const [scenarioId, setScenarioId] = useState("");
  const [description, setDescription] = useState("");
  const [operatingManual, setOperatingManual] = useState("Classify the task, check policy, decide whether approval is needed, then explain the result.");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const normalizedTeamId = teamId.trim();
  const teamIdValid = isValidBusinessId(normalizedTeamId);
  const canSubmit = Boolean(workspaceId) && teamIdValid && !saving;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!workspaceId) return;
    if (!canSubmit) {
      setError(BUSINESS_ID_HELP);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onCreate(workspaceId, {
        teamId: normalizedTeamId,
        name: name.trim() || normalizedTeamId,
        description: description.trim() || undefined,
        scenario: scenario.trim() || undefined,
        scenarioId: scenarioId.trim() || undefined,
        operatingManual: operatingManual.trim() || undefined,
        promptAssetRefs: ["prompt://business-portal/default-team"],
        agents: [
          {
            agentId: "business-analyst",
            displayName: "Business Analyst",
            responsibility: "Understand the business task, check available policy context, and explain the recommended action.",
            knowledgeRefs: ["knowledge://business/default-policy"],
            allowedTools: ["policy.search"],
            approvalRules: ["High-risk or high-value actions require human approval"],
            metadata: { source: "business-portal-ui" },
          },
        ],
        metadata: { source: "business-portal-ui" },
      });
      setTeamId("");
      setName("");
      setScenario("after-sales ticket handling");
      setScenarioId("");
      setDescription("");
      setOperatingManual("Classify the task, check policy, decide whether approval is needed, then explain the result.");
    } catch (err) {
      setError(friendlyBusinessError(err, "team"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Create Team Blueprint</CardTitle>
        <CardDescription>
          Create a first versioned digital employee team for the selected workspace.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {!workspaceId ? (
          <div className="rounded-sm border border-dashed border-border/70 p-3 text-sm normal-case text-muted-foreground">
            Select or create a workspace before creating a team blueprint.
          </div>
        ) : null}
        <form className="mt-3 grid gap-3 md:grid-cols-[1fr_1fr_1fr_auto]" onSubmit={submit}>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Team ID</label>
            <Input value={teamId} onChange={(event) => setTeamId(event.target.value)} placeholder="after-sales-team" disabled={!workspaceId} required />
            <div className="text-[0.65rem] normal-case text-muted-foreground">{BUSINESS_ID_HELP}</div>
          </div>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Name</label>
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="After-sales Team" disabled={!workspaceId} />
          </div>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Scenario</label>
            <Input value={scenario} onChange={(event) => setScenario(event.target.value)} placeholder="after-sales ticket handling" disabled={!workspaceId} />
          </div>
          <div className="space-y-1 md:col-span-3">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Scenario ID</label>
            <Input value={scenarioId} onChange={(event) => setScenarioId(event.target.value)} placeholder="after-sales-ticket" disabled={!workspaceId} />
            <div className="text-[0.65rem] normal-case text-muted-foreground">Optional: bind this team to a Scenario object.</div>
          </div>
          <div className="flex items-end">
            <Button type="submit" disabled={!canSubmit} className="w-full md:w-auto">
              <Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}
            </Button>
          </div>
          <TextAreaField
            label="Description"
            value={description}
            onChange={setDescription}
            placeholder="Handles refund and after-sales cases"
            disabled={!workspaceId}
            rows={2}
          />
          <TextAreaField
            label="Operating Manual"
            value={operatingManual}
            onChange={setOperatingManual}
            disabled={!workspaceId}
            rows={3}
          />
          {error ? <div className="text-sm normal-case text-destructive md:col-span-4">{error}</div> : null}
        </form>
      </CardContent>
    </Card>
  );
}


export function CreateRunStoryForm({
  workspaceId,
  teams,
  onCreate,
}: {
  workspaceId?: string;
  teams: BusinessTeamCard[];
  onCreate: (workspaceId: string, payload: CreateBusinessRunPayload) => Promise<void>;
}) {
  const [teamId, setTeamId] = useState("");
  const [taskTitle, setTaskTitle] = useState("Customer requested a refund");
  const [taskInput, setTaskInput] = useState("The customer signed for the order 3 days ago and wants to return it.");
  const [resultSummary, setResultSummary] = useState("Suggest allowing the customer to initiate a return request.");
  const [conclusionReason, setConclusionReason] = useState("The order is within the return window and does not match a special restricted category.");
  const [status, setStatus] = useState("COMPLETED");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedTeamId = teamId || teams[0]?.teamId || "";
  const canSubmit = Boolean(workspaceId) && Boolean(selectedTeamId) && taskTitle.trim().length > 0 && resultSummary.trim().length > 0 && !saving;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!workspaceId || !canSubmit) return;
    setSaving(true);
    setError(null);
    try {
      await onCreate(workspaceId, {
        teamId: selectedTeamId,
        scenario: teams.find((team) => team.teamId === selectedTeamId)?.scenario || "business task",
        scenarioId: teams.find((team) => team.teamId === selectedTeamId)?.scenarioId,
        taskTitle: taskTitle.trim(),
        taskInput: taskInput.trim() || undefined,
        resultSummary: resultSummary.trim(),
        conclusionReason: conclusionReason.trim() || undefined,
        systemAction: "Recorded the business run story from the Business Portal UI.",
        riskJudgement: status === "NEEDS_APPROVAL" ? "Manual approval may be required." : "No immediate manual approval required.",
        nextSuggestion: "Review the run story details and use failed or approval-needed runs to improve the team blueprint.",
        status,
        technicalTraceRef: "trace://business-portal-ui/manual-run",
        steps: [
          {
            stepId: "step-1",
            title: "Business review",
            summary: conclusionReason.trim() || resultSummary.trim(),
            actor: "business-portal-ui",
            evidence: taskInput.trim() || "Manual run story input",
            status: "COMPLETED",
            metadata: { source: "business-portal-ui" },
          },
        ],
        metrics: { source: "business-portal-ui" },
        metadata: { source: "business-portal-ui" },
      });
      setTaskTitle("Customer requested a refund");
      setTaskInput("The customer signed for the order 3 days ago and wants to return it.");
      setResultSummary("Suggest allowing the customer to initiate a return request.");
      setConclusionReason("The order is within the return window and does not match a special restricted category.");
      setStatus("COMPLETED");
    } catch (err) {
      setError(String(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Create Run Story</CardTitle>
        <CardDescription>Create a business-readable run record for the selected workspace and team.</CardDescription>
      </CardHeader>
      <CardContent>
        {!workspaceId || teams.length === 0 ? (
          <div className="rounded-sm border border-dashed border-border/70 p-3 text-sm normal-case text-muted-foreground">
            Select a workspace with at least one team before creating a run story.
          </div>
        ) : null}
        <form className="mt-3 grid gap-3 md:grid-cols-[1fr_1fr_auto]" onSubmit={submit}>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Team</label>
            <select
              value={selectedTeamId}
              onChange={(event) => setTeamId(event.target.value)}
              disabled={!workspaceId || teams.length === 0}
              className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm"
            >
              {teams.length === 0 ? <option value="">No teams</option> : null}
              {teams.map((team) => (
                <option key={team.teamId} value={team.teamId}>{team.name || team.teamId}</option>
              ))}
            </select>
          </div>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Status</label>
            <select value={status} onChange={(event) => setStatus(event.target.value)} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm" disabled={!workspaceId || teams.length === 0}>
              <option value="COMPLETED">COMPLETED</option>
              <option value="NEEDS_APPROVAL">NEEDS_APPROVAL</option>
              <option value="FAILED">FAILED</option>
              <option value="RUNNING">RUNNING</option>
            </select>
          </div>
          <div className="flex items-end">
            <Button type="submit" disabled={!canSubmit} className="w-full md:w-auto">
              <Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}
            </Button>
          </div>
          <div className="space-y-1 md:col-span-3">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Task title</label>
            <Input value={taskTitle} onChange={(event) => setTaskTitle(event.target.value)} disabled={!workspaceId || teams.length === 0} />
          </div>
          <TextAreaField
            label="Task input"
            value={taskInput}
            onChange={setTaskInput}
            disabled={!workspaceId || teams.length === 0}
            className="md:col-span-3"
          />
          <TextAreaField
            label="Result summary"
            value={resultSummary}
            onChange={setResultSummary}
            disabled={!workspaceId || teams.length === 0}
            className="md:col-span-3"
          />
          <TextAreaField
            label="Conclusion reason"
            value={conclusionReason}
            onChange={setConclusionReason}
            disabled={!workspaceId || teams.length === 0}
            className="md:col-span-3"
          />
          {error ? <div className="text-sm normal-case text-destructive md:col-span-3">{error}</div> : null}
        </form>
      </CardContent>
    </Card>
  );
}


export function CreateApprovalCardForm({
  workspaceId,
  teams,
  onCreate,
}: {
  workspaceId?: string;
  teams: BusinessTeamCard[];
  onCreate: (workspaceId: string, payload: CreateBusinessApprovalPayload) => Promise<void>;
}) {
  const [teamId, setTeamId] = useState("");
  const [title, setTitle] = useState("High value refund approval");
  const [summary, setSummary] = useState("Customer requested a 1200 CNY refund; manual approval is required.");
  const [riskLevel, setRiskLevel] = useState("HIGH");
  const [reasonRequired, setReasonRequired] = useState("Refund amount is above the automatic approval threshold.");
  const [recommendation, setRecommendation] = useState("Approve after checking the product return condition.");
  const [approveEffect, setApproveEffect] = useState("The system can continue the refund flow and draft the customer reply.");
  const [rejectEffect, setRejectEffect] = useState("The case stays in manual handling and no automatic refund response is sent.");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedTeamId = teamId || teams[0]?.teamId || "";
  const canSubmit = Boolean(workspaceId) && Boolean(selectedTeamId) && title.trim().length > 0 && summary.trim().length > 0 && !saving;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!workspaceId || !canSubmit) return;
    setSaving(true);
    setError(null);
    try {
      await onCreate(workspaceId, {
        teamId: selectedTeamId,
        title: title.trim(),
        summary: summary.trim(),
        riskLevel,
        reasonRequired: reasonRequired.trim() || undefined,
        recommendation: recommendation.trim() || undefined,
        approveEffect: approveEffect.trim() || undefined,
        rejectEffect: rejectEffect.trim() || undefined,
        evidence: {
          source: "business-portal-ui",
          teamId: selectedTeamId,
          riskLevel,
        },
        metadata: { source: "business-portal-ui" },
      });
      setTitle("High value refund approval");
      setSummary("Customer requested a 1200 CNY refund; manual approval is required.");
      setRiskLevel("HIGH");
      setReasonRequired("Refund amount is above the automatic approval threshold.");
      setRecommendation("Approve after checking the product return condition.");
      setApproveEffect("The system can continue the refund flow and draft the customer reply.");
      setRejectEffect("The case stays in manual handling and no automatic refund response is sent.");
    } catch (err) {
      setError(String(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Create Approval Card</CardTitle>
        <CardDescription>Create a mobile-first approval card for the selected workspace and team.</CardDescription>
      </CardHeader>
      <CardContent>
        {!workspaceId || teams.length === 0 ? (
          <div className="rounded-sm border border-dashed border-border/70 p-3 text-sm normal-case text-muted-foreground">
            Select a workspace with at least one team before creating an approval card.
          </div>
        ) : null}
        <form className="mt-3 grid gap-3 md:grid-cols-[1fr_1fr_auto]" onSubmit={submit}>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Team</label>
            <select
              value={selectedTeamId}
              onChange={(event) => setTeamId(event.target.value)}
              disabled={!workspaceId || teams.length === 0}
              className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm"
            >
              {teams.length === 0 ? <option value="">No teams</option> : null}
              {teams.map((team) => (
                <option key={team.teamId} value={team.teamId}>{team.name || team.teamId}</option>
              ))}
            </select>
          </div>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Risk level</label>
            <select value={riskLevel} onChange={(event) => setRiskLevel(event.target.value)} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm" disabled={!workspaceId || teams.length === 0}>
              <option value="LOW">LOW</option>
              <option value="MEDIUM">MEDIUM</option>
              <option value="HIGH">HIGH</option>
              <option value="CRITICAL">CRITICAL</option>
            </select>
          </div>
          <div className="flex items-end">
            <Button type="submit" disabled={!canSubmit} className="w-full md:w-auto">
              <Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}
            </Button>
          </div>
          <div className="space-y-1 md:col-span-3">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Title</label>
            <Input value={title} onChange={(event) => setTitle(event.target.value)} disabled={!workspaceId || teams.length === 0} />
          </div>
          <TextAreaField
            label="Summary"
            value={summary}
            onChange={setSummary}
            disabled={!workspaceId || teams.length === 0}
            className="md:col-span-3"
          />
          <TextAreaField
            label="Reason required"
            value={reasonRequired}
            onChange={setReasonRequired}
            disabled={!workspaceId || teams.length === 0}
            className="md:col-span-3"
          />
          <TextAreaField
            label="Recommendation"
            value={recommendation}
            onChange={setRecommendation}
            disabled={!workspaceId || teams.length === 0}
            className="md:col-span-3"
          />
          <TextAreaField
            label="Approve effect"
            value={approveEffect}
            onChange={setApproveEffect}
            disabled={!workspaceId || teams.length === 0}
            className="md:col-span-3"
          />
          <TextAreaField
            label="Reject effect"
            value={rejectEffect}
            onChange={setRejectEffect}
            disabled={!workspaceId || teams.length === 0}
            className="md:col-span-3"
          />
          {error ? <div className="text-sm normal-case text-destructive md:col-span-3">{error}</div> : null}
        </form>
      </CardContent>
    </Card>
  );
}
