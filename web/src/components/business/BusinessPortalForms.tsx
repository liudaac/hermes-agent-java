import { useState } from "react";
import { Plus } from "lucide-react";
import type { CreateBusinessTeamBlueprintPayload, CreateBusinessWorkspacePayload, WorkspaceRecord } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

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
  const canSubmit = normalizedWorkspaceId.length >= 2 && !saving;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
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
      setError(String(err));
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
          <div className="space-y-1 md:col-span-4">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Description</label>
            <Input
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder="Business workspace for after-sales scenarios"
            />
          </div>
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
  const [description, setDescription] = useState("");
  const [operatingManual, setOperatingManual] = useState("Classify the task, check policy, decide whether approval is needed, then explain the result.");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const normalizedTeamId = teamId.trim();
  const canSubmit = Boolean(workspaceId) && normalizedTeamId.length >= 2 && !saving;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!workspaceId || !canSubmit) return;
    setSaving(true);
    setError(null);
    try {
      await onCreate(workspaceId, {
        teamId: normalizedTeamId,
        name: name.trim() || normalizedTeamId,
        description: description.trim() || undefined,
        scenario: scenario.trim() || undefined,
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
      setDescription("");
      setOperatingManual("Classify the task, check policy, decide whether approval is needed, then explain the result.");
    } catch (err) {
      setError(String(err));
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
          </div>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Name</label>
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="After-sales Team" disabled={!workspaceId} />
          </div>
          <div className="space-y-1">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Scenario</label>
            <Input value={scenario} onChange={(event) => setScenario(event.target.value)} placeholder="after-sales ticket handling" disabled={!workspaceId} />
          </div>
          <div className="flex items-end">
            <Button type="submit" disabled={!canSubmit} className="w-full md:w-auto">
              <Plus className="mr-2 h-4 w-4" /> {saving ? "Creating..." : "Create"}
            </Button>
          </div>
          <div className="space-y-1 md:col-span-4">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Description</label>
            <Input value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Handles refund and after-sales cases" disabled={!workspaceId} />
          </div>
          <div className="space-y-1 md:col-span-4">
            <label className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Operating Manual</label>
            <Input value={operatingManual} onChange={(event) => setOperatingManual(event.target.value)} disabled={!workspaceId} />
          </div>
          {error ? <div className="text-sm normal-case text-destructive md:col-span-4">{error}</div> : null}
        </form>
      </CardContent>
    </Card>
  );
}
