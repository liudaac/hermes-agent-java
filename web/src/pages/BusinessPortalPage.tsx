import { useEffect, useMemo, useState } from "react";
import {
  BriefcaseBusiness,
  ClipboardCheck,
  Lightbulb,
  RefreshCw,
  Route,
  Users,
} from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import type {
  BusinessApprovalRecord,
  BusinessHomeResponse,
  BusinessScenarioRecord,
  BusinessPromptAssetRecord,
  CreateBusinessApprovalPayload,
  CreateBusinessPromptAssetPayload,
  CreateBusinessScenarioPayload,
  CreateBusinessRunPayload,
  CreateBusinessTeamBlueprintPayload,
  CreateBusinessWorkspacePayload,
  BusinessInsightRecord,
  BusinessRunRecord,
  BusinessApprovalRecord as BusinessApprovalRecordType,
  BusinessTeamCard,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { useToast } from "@/hooks/useToast";
import { cn } from "@/lib/utils";
import TeamBlueprintEditor from "@/components/business/TeamBlueprintEditor";
import {
  DemoDataGuide,
  InsightsAndActionsSection,
  PromptAssetsSection,
  ScenariosSection,
  MetricCard,
  RunsAndApprovalsSection,
  TeamsSection,
  TodayAndAttentionSection,
} from "@/components/business/BusinessPortalSections";
import { BusinessCreationPanel, CreateApprovalCardForm, CreatePromptAssetForm, CreateRunStoryForm, CreateScenarioForm, CreateTeamBlueprintForm, CreateWorkspaceForm } from "@/components/business/BusinessPortalForms";

export default function BusinessPortalPage() {
  const { showToast } = useToast();
  const [workspaceId, setWorkspaceId] = useState("");
  const [home, setHome] = useState<BusinessHomeResponse | null>(null);
  const [teams, setTeams] = useState<BusinessTeamCard[]>([]);
  const [scenarios, setScenarios] = useState<BusinessScenarioRecord[]>([]);
  const [promptAssets, setPromptAssets] = useState<BusinessPromptAssetRecord[]>([]);
  const [scenarioId, setScenarioId] = useState("");
  const [runs, setRuns] = useState<BusinessRunRecord[]>([]);
  const [approvals, setApprovals] = useState<BusinessApprovalRecord[]>([]);
  const [insights, setInsights] = useState<BusinessInsightRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [executingId, setExecutingId] = useState<string | null>(null);
  const [editingTeam, setEditingTeam] = useState<BusinessTeamCard | null>(null);

  const selectedWorkspace = workspaceId || undefined;

  const createWorkspace = async (payload: CreateBusinessWorkspacePayload) => {
    const response = await api.createBusinessWorkspace(payload);
    setWorkspaceId(response.workspaceId);
    showToast(`Workspace created: ${response.workspaceId}`, "success");
    return response.workspace;
  };

  const createScenario = async (targetWorkspaceId: string, payload: CreateBusinessScenarioPayload) => {
    const response = await api.createBusinessScenario(targetWorkspaceId, payload);
    setScenarioId(response.scenarioId);
    showToast(`Scenario created: ${response.scenarioId}`, "success");
    await load();
    return response.scenario;
  };

  const createPromptAsset = async (targetWorkspaceId: string, payload: CreateBusinessPromptAssetPayload) => {
    const response = await api.createBusinessPromptAsset(targetWorkspaceId, payload);
    showToast(`Prompt asset created: ${response.assetId}`, "success");
    await load();
    return response.promptAsset;
  };

  const createTeamBlueprint = async (targetWorkspaceId: string, payload: CreateBusinessTeamBlueprintPayload) => {
    const response = await api.createBusinessTeamBlueprint(targetWorkspaceId, payload);
    showToast(`Team created: ${response.teamId}`, "success");
    await load();
  };

  const createRunStory = async (targetWorkspaceId: string, payload: CreateBusinessRunPayload) => {
    const response = await api.createBusinessRun(targetWorkspaceId, payload);
    showToast(`Run story created: ${response.runId}`, "success");
    await load();
  };

  const createApprovalCard = async (targetWorkspaceId: string, payload: CreateBusinessApprovalPayload) => {
    const response = await api.createBusinessApproval(targetWorkspaceId, payload);
    showToast(`Approval card created: ${response.approvalId}`, "success");
    await load();
  };

  const executeScenario = async (targetScenarioId: string, userInput: string) => {
    if (!workspaceId) return;
    setExecutingId(targetScenarioId);
    try {
      const response = await api.executeBusinessScenario(workspaceId, targetScenarioId, userInput);
      showToast(`Scenario executed: ${response.runId}`, "success");
      await load();
    } catch (err) {
      showToast(`Execution failed: ${String(err)}`, "error");
    } finally {
      setExecutingId(null);
    }
  };

  const approveApproval = async (approval: BusinessApprovalRecordType, reason: string) => {
    await api.approveBusinessApproval(approval.workspaceId, approval.approvalId, {
      actor: "business-portal-ui",
      reason,
    });
    showToast(`Approval approved: ${approval.approvalId}`, "success");
    await load();
  };

  const rejectApproval = async (approval: BusinessApprovalRecordType, reason: string) => {
    await api.rejectBusinessApproval(approval.workspaceId, approval.approvalId, {
      actor: "business-portal-ui",
      reason,
    });
    showToast(`Approval rejected: ${approval.approvalId}`, "success");
    await load();
  };

  const requestApprovalInfo = async (approval: BusinessApprovalRecordType, requestedInfo: string) => {
    await api.requestBusinessApprovalInfo(approval.workspaceId, approval.approvalId, {
      actor: "business-portal-ui",
      requestedInfo,
    });
    showToast(`Requested more info: ${approval.approvalId}`, "success");
    await load();
  };

  const resumeExecution = async (approval: BusinessApprovalRecordType) => {
    const evidence = approval.evidence as Record<string, string> | undefined;
    const scenarioId = evidence?.scenarioId;
    const userInput = evidence?.userInput;
    if (!scenarioId || !userInput) {
      showToast("Missing scenarioId or userInput in approval evidence", "error");
      return;
    }
    try {
      const response = await api.resumeExecution(approval.workspaceId, approval.approvalId, scenarioId, userInput);
      showToast(`Execution resumed: ${response.runId}`, "success");
      await load();
    } catch (err) {
      showToast(`Resume failed: ${String(err)}`, "error");
    }
  };

  const load = async () => {
    setLoading(true);
    try {
      const [homeRes, teamsRes, runsRes, approvalsRes, insightsRes] = await Promise.all([
        api.getBusinessHome(selectedWorkspace),
        api.getBusinessTeams(selectedWorkspace),
        api.getBusinessRuns(selectedWorkspace, 10, scenarioId || undefined),
        api.getBusinessApprovals(selectedWorkspace, "ALL"),
        api.getBusinessInsights(selectedWorkspace, scenarioId || undefined),
      ]);
      setHome(homeRes);
      setTeams(teamsRes.teams ?? []);
      if (selectedWorkspace) {
        const [scenariosRes, promptAssetsRes] = await Promise.all([
          api.getBusinessScenarios(selectedWorkspace),
          api.getBusinessPromptAssets(selectedWorkspace),
        ]);
        setScenarios(scenariosRes.scenarios ?? []);
        setPromptAssets(promptAssetsRes.promptAssets ?? []);
      } else {
        setScenarios([]);
        setPromptAssets([]);
        setScenarioId("");
      }
      setRuns(runsRes.runs ?? []);
      setApprovals(approvalsRes.approvals ?? []);
      setInsights(insightsRes.insights ?? []);
    } catch (error) {
      showToast(`Failed to load Business Portal: ${String(error)}`, "error");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId, scenarioId]);

  const workspaceOptions = useMemo(() => home?.workspaces ?? [], [home]);
  const summary = home?.summary;

  return (
    <div className="space-y-5">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <div className="flex items-center gap-2 text-xs tracking-[0.18em] opacity-60">
            <BriefcaseBusiness className="h-4 w-4" /> Business Portal
          </div>
          <H2 className="mt-1">Business Command Center</H2>
          <p className="mt-2 max-w-3xl text-sm normal-case tracking-normal text-muted-foreground">
            Business-facing cockpit for workspaces, digital employee teams, run stories, approvals and insights.
          </p>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
          <select
            value={workspaceId}
            onChange={(event) => {
              setWorkspaceId(event.target.value);
              setScenarioId("");
            }}
            className="h-9 rounded-sm border border-border bg-background px-3 text-xs uppercase tracking-[0.12em]"
          >
            <option value="">All workspaces</option>
            {workspaceOptions.map((workspace) => (
              <option key={workspace.workspaceId} value={workspace.workspaceId}>
                {workspace.name || workspace.workspaceId}
              </option>
            ))}
          </select>
          <select
            value={scenarioId}
            onChange={(event) => setScenarioId(event.target.value)}
            disabled={!workspaceId || scenarios.length === 0}
            className="h-9 rounded-sm border border-border bg-background px-3 text-xs uppercase tracking-[0.12em]"
          >
            <option value="">All scenarios</option>
            {scenarios.map((scenario) => (
              <option key={scenario.scenarioId} value={scenario.scenarioId}>
                {scenario.name || scenario.scenarioId}
              </option>
            ))}
          </select>
          <Button onClick={load} disabled={loading} variant="outline" size="sm">
            <RefreshCw className={cn("mr-2 h-4 w-4", loading && "animate-spin")} /> Refresh
          </Button>
        </div>
      </div>

      <BusinessCreationPanel
        workspaceCount={summary?.workspaceCount ?? 0}
        teamCount={summary?.teamCount ?? 0}
        workspaceForm={<CreateWorkspaceForm onCreate={createWorkspace} />}
        scenarioForm={<CreateScenarioForm workspaceId={workspaceId} teams={teams} onCreate={createScenario} />}
        promptAssetForm={<CreatePromptAssetForm workspaceId={workspaceId} onCreate={createPromptAsset} />}
        teamForm={<CreateTeamBlueprintForm workspaceId={workspaceId} promptAssets={promptAssets} onCreate={createTeamBlueprint} />}
        runForm={<CreateRunStoryForm workspaceId={workspaceId} teams={teams} onCreate={createRunStory} />}
        approvalForm={<CreateApprovalCardForm workspaceId={workspaceId} teams={teams} onCreate={createApprovalCard} />}
      />

      {home?.emptyState ? (
        <Card>
          <CardContent className="flex flex-col gap-3 p-5 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div className="font-expanded text-sm tracking-[0.1em]">No business workspace yet</div>
              <p className="mt-1 text-sm normal-case text-muted-foreground">{home.emptyState}</p>
            </div>
            <Badge variant="info">use API examples or smoke script</Badge>
          </CardContent>
        </Card>
      ) : null}

      <DemoDataGuide workspaceId={workspaceId} />

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <MetricCard title="Workspaces" value={summary?.workspaceCount} icon={BriefcaseBusiness} />
        <MetricCard title="Teams" value={summary?.teamCount} icon={Users} />
        <MetricCard title="Runs" value={summary?.runCount} icon={Route} />
        <MetricCard title="Pending approvals" value={summary?.pendingApprovals} icon={ClipboardCheck} />
        <MetricCard title="Open insights" value={summary?.openInsights} icon={Lightbulb} />
      </section>

      <TodayAndAttentionSection home={home} />
      <section className="grid gap-4 xl:grid-cols-2">
        <ScenariosSection scenarios={scenarios} workspaceId={workspaceId} onExecute={executeScenario} executingId={executingId} />
        <PromptAssetsSection promptAssets={promptAssets} />
      </section>
      <TeamsSection teams={teams} home={home} onEditTeam={setEditingTeam} />

      {/* Team Blueprint Editor Drawer */}
      {editingTeam && workspaceId && (
        <div className="fixed inset-0 z-50 bg-black/50" onClick={() => setEditingTeam(null)}>
          <div
            className="absolute right-0 top-0 h-full w-full max-w-2xl bg-background shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex h-full flex-col">
              <div className="flex items-center justify-between border-b border-border px-4 py-3">
                <h2 className="text-sm font-semibold">Team Blueprint Editor</h2>
                <Button variant="ghost" size="sm" onClick={() => setEditingTeam(null)}>
                  Close
                </Button>
              </div>
              <div className="flex-1 overflow-y-auto p-4">
                <TeamBlueprintEditor
                  workspaceId={workspaceId}
                  team={editingTeam}
                  onSaved={() => {
                    load();
                  }}
                />
              </div>
            </div>
          </div>
        </div>
      )}

      <RunsAndApprovalsSection
        runs={runs}
        approvals={approvals}
        onApproveApproval={approveApproval}
        onRejectApproval={rejectApproval}
        onRequestApprovalInfo={requestApprovalInfo}
        onResumeExecution={resumeExecution}
      />
      <InsightsAndActionsSection insights={insights} actions={home?.nextActions ?? []} />
    </div>
  );
}
