import { useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { Plus } from "lucide-react";
import type {
  BusinessPromptAssetRecord,
  BusinessScenarioRecord,
  BusinessTeamCard,
  CreateBusinessApprovalPayload,
  CreateBusinessPromptAssetPayload,
  CreateBusinessRunPayload,
  CreateBusinessScenarioPayload,
  CreateBusinessTeamBlueprintPayload,
  CreateBusinessWorkspacePayload,
  WorkspaceRecord,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

const BUSINESS_ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}$/;
const BUSINESS_ID_HELP = "Use 2-64 chars: letters, numbers, dot, underscore or dash. Start with a letter or number.";

function isValidBusinessId(value: string): boolean {
  return BUSINESS_ID_PATTERN.test(value.trim());
}

function friendlyBusinessError(error: unknown, kind: "workspace" | "scenario" | "promptAsset" | "team"): string {
  const raw = String(error);
  const lower = raw.toLowerCase();
  if (lower.includes("already exists")) {
    if (kind === "workspace") return "A workspace with this ID already exists. Pick another ID or select the existing workspace.";
    if (kind === "scenario") return "A scenario with this ID already exists in the selected workspace. Pick another scenario ID.";
    if (kind === "promptAsset") return "A prompt asset with this ID already exists in the selected workspace. Pick another asset ID.";
    return "A team blueprint with this ID already exists in the selected workspace. Pick another team ID.";
  }
  if (lower.includes("must be 2-64") || lower.includes("id is required") || lower.includes("invalid")) return BUSINESS_ID_HELP;
  if (lower.includes("workspace not found")) return "The selected workspace no longer exists. Refresh the page and choose another workspace.";
  return raw;
}

function TextAreaField({
  label,
  value,
  onChange,
  placeholder,
  disabled,
  rows = 3,
  className = "md:col-span-3",
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
      <label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">{label}</label>
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

export function BusinessCreationPanel({
  workspaceCount,
  teamCount,
  workspaceForm,
  scenarioForm,
  promptAssetForm,
  teamForm,
  runForm,
  approvalForm,
}: {
  workspaceCount: number;
  teamCount: number;
  workspaceForm: ReactNode;
  scenarioForm: ReactNode;
  promptAssetForm: ReactNode;
  teamForm: ReactNode;
  runForm: ReactNode;
  approvalForm: ReactNode;
}) {
  return (
    <section className="space-y-3 rounded-sm border border-border/70 p-3">
      <div>
        <div className="font-expanded text-sm tracking-normal sm:tracking-[0.1em]">Create business objects</div>
        <p className="mt-1 text-sm normal-case text-muted-foreground">Use these focused forms to build the Business Portal loop step by step.</p>
      </div>
      <CreationStep title="1. Workspace" description="Create the business space." defaultOpen={workspaceCount === 0}>{workspaceForm}</CreationStep>
      <CreationStep title="2. Scenario" description="Create a reusable business scenario." defaultOpen={false}>{scenarioForm}</CreationStep>
      <CreationStep title="3. Prompt Asset" description="Create a reusable prompt asset." defaultOpen={false}>{promptAssetForm}</CreationStep>
      <CreationStep title="4. Team Blueprint" description="Create the first digital employee team." defaultOpen={workspaceCount > 0 && teamCount === 0}>{teamForm}</CreationStep>
      <CreationStep title="5. Run Story" description="Record a business-readable run story." defaultOpen={false}>{runForm}</CreationStep>
      <CreationStep title="6. Approval Card" description="Create a mobile-first approval card." defaultOpen={false}>{approvalForm}</CreationStep>
    </section>
  );
}

function CreationStep({ title, description, defaultOpen, children }: { title: string; description: string; defaultOpen: boolean; children: ReactNode }) {
  return (
    <details className="rounded-sm border border-border/60 p-3" open={defaultOpen}>
      <summary className="cursor-pointer">
        <div className="inline-flex flex-col gap-1 align-middle">
          <span className="font-expanded text-xs uppercase tracking-normal sm:tracking-[0.1em]">{title}</span>
          <span className="text-sm normal-case text-muted-foreground">{description}</span>
        </div>
      </summary>
      <div className="mt-3">{children}</div>
    </details>
  );
}

export function CreateWorkspaceForm({ onCreate }: { onCreate: (payload: CreateBusinessWorkspacePayload) => Promise<WorkspaceRecord> }) {
  const [workspaceId, setWorkspaceId] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [owner, setOwner] = useState("ops");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const normalizedWorkspaceId = workspaceId.trim();
  const canSubmit = isValidBusinessId(normalizedWorkspaceId) && !saving;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) { setError(BUSINESS_ID_HELP); return; }
    setSaving(true); setError(null);
    try {
      await onCreate({ workspaceId: normalizedWorkspaceId, name: name.trim() || normalizedWorkspaceId, description: description.trim() || undefined, owner: owner.trim() || "ops", metadata: { source: "business-portal-ui" } });
      setWorkspaceId(""); setName(""); setDescription(""); setOwner("ops");
    } catch (err) { setError(friendlyBusinessError(err, "workspace")); }
    finally { setSaving(false); }
  };

  return (
    <Card><CardHeader><CardTitle>Create Workspace</CardTitle><CardDescription>Create the first business space without leaving the Business Portal.</CardDescription></CardHeader><CardContent>
      <form className="grid gap-3 md:grid-cols-[1fr_1fr_1fr_auto]" onSubmit={submit}>
        <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Workspace ID</label><Input value={workspaceId} onChange={(e) => setWorkspaceId(e.target.value)} placeholder="customer-service-demo" required /><div className="text-xs normal-case text-muted-foreground">{BUSINESS_ID_HELP}</div></div>
        <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Name</label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Customer Service Demo" /></div>
        <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Owner</label><Input value={owner} onChange={(e) => setOwner(e.target.value)} placeholder="ops" /></div>
        <div className="flex items-end"><Button type="submit" disabled={!canSubmit} className="w-full md:w-auto"><Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}</Button></div>
        <TextAreaField label="Description" value={description} onChange={setDescription} placeholder="Business workspace for after-sales scenarios" className="md:col-span-4" rows={2} />
        {error ? <div className="text-sm normal-case text-destructive md:col-span-4">{error}</div> : null}
      </form>
    </CardContent></Card>
  );
}

export function CreateScenarioForm({ workspaceId, teams, onCreate }: { workspaceId?: string; teams: BusinessTeamCard[]; onCreate: (workspaceId: string, payload: CreateBusinessScenarioPayload) => Promise<BusinessScenarioRecord> }) {
  const [scenarioId, setScenarioId] = useState("after-sales-ticket");
  const [name, setName] = useState("After-sales Ticket Handling");
  const [description, setDescription] = useState("Handle refund and after-sales tickets with explainable business steps.");
  const [entryTeamId, setEntryTeamId] = useState("");
  const [successCriteria, setSuccessCriteria] = useState("Correctly classify the ticket\nGenerate an actionable recommendation");
  const [approvalRules, setApprovalRules] = useState("High-risk or high-value actions require human approval");
  const [collaborationPattern, setCollaborationPattern] = useState("SEQUENTIAL");
  const [slaName, setSlaName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canSubmit = Boolean(workspaceId) && isValidBusinessId(scenarioId.trim()) && name.trim().length > 0 && !saving;

  const COLLABORATION_PATTERNS = [
    { value: "SEQUENTIAL", label: "Sequential", desc: "A → B → C in order" },
    { value: "PARALLEL", label: "Parallel", desc: "All agents run simultaneously" },
    { value: "REVIEW", label: "Review", desc: "Generate → Review → Approve" },
    { value: "COMPETITIVE", label: "Competitive", desc: "Multiple agents, best wins" },
    { value: "MASTER_WORKER", label: "Master-Worker", desc: "Lead plans, workers execute" },
    { value: "PIPELINE", label: "Pipeline", desc: "Data flows through chain" },
  ];

  const SLA_OPTIONS = [
    { value: "", label: "None" },
    { value: "customer_service", label: "Customer Service (5min warn / 10min breach)" },
    { value: "order_processing", label: "Order Processing (30s warn / 60s breach)" },
    { value: "inventory_alert", label: "Inventory Alert (5min warn / 10min breach)" },
    { value: "payment_processing", label: "Payment Processing (10s warn / 30s breach)" },
    { value: "general", label: "General (5min warn / 10min breach)" },
  ];

  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!workspaceId) return; if (!canSubmit) { setError(BUSINESS_ID_HELP); return; }
    setSaving(true); setError(null);
    try {
      await onCreate(workspaceId, {
        scenarioId: scenarioId.trim(),
        name: name.trim(),
        description: description.trim() || undefined,
        entryTeamId: entryTeamId || undefined,
        successCriteria: successCriteria.split("\n").map((x) => x.trim()).filter(Boolean),
        approvalRules: approvalRules.split("\n").map((x) => x.trim()).filter(Boolean),
        collaborationPattern: collaborationPattern || undefined,
        slaName: slaName || undefined,
        metadata: { source: "business-portal-ui" }
      });
    }
    catch (err) { setError(friendlyBusinessError(err, "scenario")); }
    finally { setSaving(false); }
  };

  return <Card><CardHeader><CardTitle>Create Scenario</CardTitle><CardDescription>Create a reusable business scenario with orchestration settings.</CardDescription></CardHeader><CardContent><form className="mt-3 grid gap-3 md:grid-cols-[1fr_1fr_auto]" onSubmit={submit}>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Scenario ID</label><Input value={scenarioId} onChange={(e) => setScenarioId(e.target.value)} disabled={!workspaceId} required /><div className="text-xs normal-case text-muted-foreground">{BUSINESS_ID_HELP}</div></div>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Name</label><Input value={name} onChange={(e) => setName(e.target.value)} disabled={!workspaceId} /></div>
    <div className="flex items-end"><Button type="submit" disabled={!canSubmit} className="w-full md:w-auto"><Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}</Button></div>
    <div className="space-y-1 md:col-span-3"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Entry Team</label><select value={entryTeamId} onChange={(e) => setEntryTeamId(e.target.value)} disabled={!workspaceId} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm"><option value="">No entry team yet</option>{teams.map((team) => <option key={team.teamId} value={team.teamId}>{team.name || team.teamId}</option>)}</select></div>

    {/* Orchestration settings */}
    <div className="space-y-1 md:col-span-2"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Collaboration Pattern</label><select value={collaborationPattern} onChange={(e) => setCollaborationPattern(e.target.value)} disabled={!workspaceId} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm">{COLLABORATION_PATTERNS.map((p) => <option key={p.value} value={p.value}>{p.label} — {p.desc}</option>)}</select><div className="text-xs normal-case text-muted-foreground">How agents coordinate when executing this scenario.</div></div>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">SLA Policy</label><select value={slaName} onChange={(e) => setSlaName(e.target.value)} disabled={!workspaceId} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm">{SLA_OPTIONS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}</select></div>

    <TextAreaField label="Description" value={description} onChange={setDescription} disabled={!workspaceId} />
    <TextAreaField label="Success criteria" value={successCriteria} onChange={setSuccessCriteria} disabled={!workspaceId} />
    <TextAreaField label="Approval rules" value={approvalRules} onChange={setApprovalRules} disabled={!workspaceId} />
    {error ? <div className="text-sm normal-case text-destructive md:col-span-3">{error}</div> : null}
  </form></CardContent></Card>;
}

export function CreatePromptAssetForm({ workspaceId, onCreate }: { workspaceId?: string; onCreate: (workspaceId: string, payload: CreateBusinessPromptAssetPayload) => Promise<BusinessPromptAssetRecord> }) {
  const [assetId, setAssetId] = useState("after-sales-base");
  const [name, setName] = useState("After-sales Base Prompt");
  const [purpose, setPurpose] = useState("Guide after-sales ticket handling with explainable policy checks.");
  const [content, setContent] = useState("You are an after-sales policy specialist. First identify the customer request, then check policy constraints, then explain the recommended action.");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canSubmit = Boolean(workspaceId) && isValidBusinessId(assetId.trim()) && name.trim().length > 0 && !saving;
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!workspaceId) return; if (!canSubmit) { setError(BUSINESS_ID_HELP); return; }
    setSaving(true); setError(null);
    try { await onCreate(workspaceId, { assetId: assetId.trim(), name: name.trim(), purpose: purpose.trim() || undefined, content: content.trim() || undefined, tags: ["business-portal"], metadata: { source: "business-portal-ui" } }); }
    catch (err) { setError(friendlyBusinessError(err, "promptAsset")); }
    finally { setSaving(false); }
  };
  return <Card><CardHeader><CardTitle>Create Prompt Asset</CardTitle><CardDescription>Create a reusable prompt asset.</CardDescription></CardHeader><CardContent><form className="mt-3 grid gap-3 md:grid-cols-[1fr_1fr_auto]" onSubmit={submit}>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Asset ID</label><Input value={assetId} onChange={(e) => setAssetId(e.target.value)} disabled={!workspaceId} required /><div className="text-xs normal-case text-muted-foreground">{BUSINESS_ID_HELP}</div></div>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Name</label><Input value={name} onChange={(e) => setName(e.target.value)} disabled={!workspaceId} /></div>
    <div className="flex items-end"><Button type="submit" disabled={!canSubmit} className="w-full md:w-auto"><Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}</Button></div>
    <TextAreaField label="Purpose" value={purpose} onChange={setPurpose} disabled={!workspaceId} />
    <TextAreaField label="Content" value={content} onChange={setContent} disabled={!workspaceId} rows={5} />
    {error ? <div className="text-sm normal-case text-destructive md:col-span-3">{error}</div> : null}
  </form></CardContent></Card>;
}

export function CreateTeamBlueprintForm({ workspaceId, promptAssets, onCreate }: { workspaceId?: string; promptAssets: BusinessPromptAssetRecord[]; onCreate: (workspaceId: string, payload: CreateBusinessTeamBlueprintPayload) => Promise<void> }) {
  const [teamId, setTeamId] = useState("");
  const [name, setName] = useState("");
  const [scenario, setScenario] = useState("after-sales ticket handling");
  const [scenarioId, setScenarioId] = useState("");
  const [selectedPromptAssetIds, setSelectedPromptAssetIds] = useState<string[]>([]);
  const [description, setDescription] = useState("");
  const [operatingManual, setOperatingManual] = useState("Classify the task, check policy, decide whether approval is needed, then explain the result.");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const normalizedTeamId = teamId.trim();
  const canSubmit = Boolean(workspaceId) && isValidBusinessId(normalizedTeamId) && !saving;
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!workspaceId) return; if (!canSubmit) { setError(BUSINESS_ID_HELP); return; }
    setSaving(true); setError(null);
    try {
      await onCreate(workspaceId, { teamId: normalizedTeamId, name: name.trim() || normalizedTeamId, description: description.trim() || undefined, scenario: scenario.trim() || undefined, scenarioId: scenarioId.trim() || undefined, operatingManual: operatingManual.trim() || undefined, promptAssetRefs: selectedPromptAssetIds.map((assetId) => `prompt://${assetId}`), agents: [{ agentId: "business-analyst", displayName: "Business Analyst", responsibility: "Understand the business task, check available policy context, and explain the recommended action.", knowledgeRefs: ["knowledge://business/default-policy"], allowedTools: ["policy.search"], approvalRules: ["High-risk or high-value actions require human approval"], metadata: { source: "business-portal-ui" } }], metadata: { source: "business-portal-ui" } });
      setTeamId(""); setName(""); setScenario("after-sales ticket handling"); setScenarioId(""); setSelectedPromptAssetIds([]); setDescription("");
    } catch (err) { setError(friendlyBusinessError(err, "team")); }
    finally { setSaving(false); }
  };
  return <Card><CardHeader><CardTitle>Create Team Blueprint</CardTitle><CardDescription>Create a first versioned digital employee team for the selected workspace.</CardDescription></CardHeader><CardContent><form className="mt-3 grid gap-3 md:grid-cols-[1fr_1fr_1fr_auto]" onSubmit={submit}>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Team ID</label><Input value={teamId} onChange={(e) => setTeamId(e.target.value)} disabled={!workspaceId} required /><div className="text-xs normal-case text-muted-foreground">{BUSINESS_ID_HELP}</div></div>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Name</label><Input value={name} onChange={(e) => setName(e.target.value)} disabled={!workspaceId} /></div>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Scenario</label><Input value={scenario} onChange={(e) => setScenario(e.target.value)} disabled={!workspaceId} /></div>
    <div className="flex items-end"><Button type="submit" disabled={!canSubmit} className="w-full md:w-auto"><Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}</Button></div>
    <div className="space-y-1 md:col-span-4"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Scenario ID</label><Input value={scenarioId} onChange={(e) => setScenarioId(e.target.value)} disabled={!workspaceId} placeholder="after-sales-ticket" /></div>
    <div className="space-y-1 md:col-span-4"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Prompt Assets</label><select multiple value={selectedPromptAssetIds} onChange={(e) => setSelectedPromptAssetIds(Array.from(e.target.selectedOptions).map((o) => o.value))} disabled={!workspaceId || promptAssets.length === 0} className="min-h-24 w-full rounded-sm border border-border bg-background px-3 py-2 text-sm">{promptAssets.map((asset) => <option key={asset.assetId} value={asset.assetId}>{asset.name || asset.assetId}</option>)}</select><div className="text-xs normal-case text-muted-foreground">Optional: selected assets become prompt://assetId refs.</div></div>
    <TextAreaField label="Description" value={description} onChange={setDescription} disabled={!workspaceId} className="md:col-span-4" />
    <TextAreaField label="Operating Manual" value={operatingManual} onChange={setOperatingManual} disabled={!workspaceId} className="md:col-span-4" />
    {error ? <div className="text-sm normal-case text-destructive md:col-span-4">{error}</div> : null}
  </form></CardContent></Card>;
}

export function CreateRunStoryForm({ workspaceId, teams, onCreate }: { workspaceId?: string; teams: BusinessTeamCard[]; onCreate: (workspaceId: string, payload: CreateBusinessRunPayload) => Promise<void> }) {
  const [teamId, setTeamId] = useState("");
  const [taskTitle, setTaskTitle] = useState("Customer requested a refund");
  const [taskInput, setTaskInput] = useState("The customer signed for the order 3 days ago and wants to return it.");
  const [resultSummary, setResultSummary] = useState("Suggest allowing the customer to initiate a return request.");
  const [conclusionReason, setConclusionReason] = useState("The order is within the return window and does not match a special restricted category.");
  const [status, setStatus] = useState("COMPLETED");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const selectedTeamId = teamId || teams[0]?.teamId || "";
  const selectedTeam = teams.find((team) => team.teamId === selectedTeamId);
  const canSubmit = Boolean(workspaceId) && Boolean(selectedTeamId) && taskTitle.trim().length > 0 && resultSummary.trim().length > 0 && !saving;
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!workspaceId || !canSubmit) return; setSaving(true); setError(null);
    try { await onCreate(workspaceId, { teamId: selectedTeamId, scenario: selectedTeam?.scenario || "business task", scenarioId: selectedTeam?.scenarioId, taskTitle: taskTitle.trim(), taskInput: taskInput.trim() || undefined, resultSummary: resultSummary.trim(), conclusionReason: conclusionReason.trim() || undefined, systemAction: "Recorded the business run story from the Business Portal UI.", riskJudgement: status === "NEEDS_APPROVAL" ? "Manual approval may be required." : "No immediate manual approval required.", nextSuggestion: "Review the run story details and use failed or approval-needed runs to improve the team blueprint.", status, technicalTraceRef: "trace://business-portal-ui/manual-run", steps: [{ stepId: "step-1", title: "Business review", summary: conclusionReason.trim() || resultSummary.trim(), actor: "business-portal-ui", evidence: taskInput.trim() || "Manual run story input", status: "COMPLETED", metadata: { source: "business-portal-ui" } }], metrics: { source: "business-portal-ui" }, metadata: { source: "business-portal-ui" } }); }
    catch (err) { setError(String(err)); } finally { setSaving(false); }
  };
  return <Card><CardHeader><CardTitle>Create Run Story</CardTitle><CardDescription>Create a business-readable run record for the selected workspace and team.</CardDescription></CardHeader><CardContent><form className="mt-3 grid gap-3 md:grid-cols-[1fr_1fr_auto]" onSubmit={submit}>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Team</label><select value={selectedTeamId} onChange={(e) => setTeamId(e.target.value)} disabled={!workspaceId || teams.length === 0} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm">{teams.length === 0 ? <option value="">No teams</option> : null}{teams.map((team) => <option key={team.teamId} value={team.teamId}>{team.name || team.teamId}</option>)}</select></div>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Status</label><select value={status} onChange={(e) => setStatus(e.target.value)} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm" disabled={!workspaceId || teams.length === 0}><option value="COMPLETED">COMPLETED</option><option value="NEEDS_APPROVAL">NEEDS_APPROVAL</option><option value="FAILED">FAILED</option><option value="RUNNING">RUNNING</option></select></div>
    <div className="flex items-end"><Button type="submit" disabled={!canSubmit} className="w-full md:w-auto"><Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}</Button></div>
    <TextAreaField label="Task title" value={taskTitle} onChange={setTaskTitle} disabled={!workspaceId || teams.length === 0} />
    <TextAreaField label="Task input" value={taskInput} onChange={setTaskInput} disabled={!workspaceId || teams.length === 0} />
    <TextAreaField label="Result summary" value={resultSummary} onChange={setResultSummary} disabled={!workspaceId || teams.length === 0} />
    <TextAreaField label="Conclusion reason" value={conclusionReason} onChange={setConclusionReason} disabled={!workspaceId || teams.length === 0} />
    {error ? <div className="text-sm normal-case text-destructive md:col-span-3">{error}</div> : null}
  </form></CardContent></Card>;
}

export function CreateApprovalCardForm({ workspaceId, teams, onCreate }: { workspaceId?: string; teams: BusinessTeamCard[]; onCreate: (workspaceId: string, payload: CreateBusinessApprovalPayload) => Promise<void> }) {
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
  const submit = async (event: FormEvent) => { event.preventDefault(); if (!workspaceId || !canSubmit) return; setSaving(true); setError(null); try { await onCreate(workspaceId, { teamId: selectedTeamId, title: title.trim(), summary: summary.trim(), riskLevel, reasonRequired: reasonRequired.trim() || undefined, recommendation: recommendation.trim() || undefined, approveEffect: approveEffect.trim() || undefined, rejectEffect: rejectEffect.trim() || undefined, evidence: { source: "business-portal-ui", teamId: selectedTeamId, riskLevel }, metadata: { source: "business-portal-ui" } }); } catch (err) { setError(String(err)); } finally { setSaving(false); } };
  return <Card><CardHeader><CardTitle>Create Approval Card</CardTitle><CardDescription>Create a mobile-first approval card for the selected workspace and team.</CardDescription></CardHeader><CardContent><form className="mt-3 grid gap-3 md:grid-cols-[1fr_1fr_auto]" onSubmit={submit}>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Team</label><select value={selectedTeamId} onChange={(e) => setTeamId(e.target.value)} disabled={!workspaceId || teams.length === 0} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm">{teams.length === 0 ? <option value="">No teams</option> : null}{teams.map((team) => <option key={team.teamId} value={team.teamId}>{team.name || team.teamId}</option>)}</select></div>
    <div className="space-y-1"><label className="text-xs uppercase tracking-normal sm:tracking-[0.14em] opacity-60">Risk level</label><select value={riskLevel} onChange={(e) => setRiskLevel(e.target.value)} className="h-10 w-full rounded-sm border border-border bg-background px-3 text-sm" disabled={!workspaceId || teams.length === 0}><option value="LOW">LOW</option><option value="MEDIUM">MEDIUM</option><option value="HIGH">HIGH</option><option value="CRITICAL">CRITICAL</option></select></div>
    <div className="flex items-end"><Button type="submit" disabled={!canSubmit} className="w-full md:w-auto"><Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}</Button></div>
    <TextAreaField label="Title" value={title} onChange={setTitle} disabled={!workspaceId || teams.length === 0} />
    <TextAreaField label="Summary" value={summary} onChange={setSummary} disabled={!workspaceId || teams.length === 0} />
    <TextAreaField label="Reason required" value={reasonRequired} onChange={setReasonRequired} disabled={!workspaceId || teams.length === 0} />
    <TextAreaField label="Recommendation" value={recommendation} onChange={setRecommendation} disabled={!workspaceId || teams.length === 0} />
    <TextAreaField label="Approve effect" value={approveEffect} onChange={setApproveEffect} disabled={!workspaceId || teams.length === 0} />
    <TextAreaField label="Reject effect" value={rejectEffect} onChange={setRejectEffect} disabled={!workspaceId || teams.length === 0} />
    {error ? <div className="text-sm normal-case text-destructive md:col-span-3">{error}</div> : null}
  </form></CardContent></Card>;
}
