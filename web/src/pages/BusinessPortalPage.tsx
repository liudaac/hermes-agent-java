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
  CreateBusinessApprovalPayload,
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
import {
  DemoDataGuide,
  InsightsAndActionsSection,
  MetricCard,
  RunsAndApprovalsSection,
  TeamsSection,
  TodayAndAttentionSection,
} from "@/components/business/BusinessPortalSections";
import { BusinessCreationPanel, CreateApprovalCardForm, CreateRunStoryForm, CreateTeamBlueprintForm, CreateWorkspaceForm } from "@/components/business/BusinessPortalForms";

export default function BusinessPortalPage() {
  const { showToast } = useToast();
  const [workspaceId, setWorkspaceId] = useState("");
  const [home, setHome] = useState<BusinessHomeResponse | null>(null);
  const [teams, setTeams] = useState<BusinessTeamCard[]>([]);
  const [runs, setRuns] = useState<BusinessRunRecord[]>([]);
  const [approvals, setApprovals] = useState<BusinessApprovalRecord[]>([]);
  const [insights, setInsights] = useState<BusinessInsightRecord[]>([]);
  const [loading, setLoading] = useState(true);

  const selectedWorkspace = workspaceId || undefined;

  const createWorkspace = async (payload: CreateBusinessWorkspacePayload) => {
    const response = await api.createBusinessWorkspace(payload);
    setWorkspaceId(response.workspaceId);
    showToast(`Workspace created: ${response.workspaceId}`, "success");
    return response.workspace;
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

  const load = async () => {
    setLoading(true);
    try {
      const [homeRes, teamsRes, runsRes, approvalsRes, insightsRes] = await Promise.all([
        api.getBusinessHome(selectedWorkspace),
        api.getBusinessTeams(selectedWorkspace),
        api.getBusinessRuns(selectedWorkspace, 10),
        api.getBusinessApprovals(selectedWorkspace, "ALL"),
        api.getBusinessInsights(selectedWorkspace),
      ]);
      setHome(homeRes);
      setTeams(teamsRes.teams ?? []);
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
  }, [workspaceId]);

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
            onChange={(event) => setWorkspaceId(event.target.value)}
            className="h-9 rounded-sm border border-border bg-background px-3 text-xs uppercase tracking-[0.12em]"
          >
            <option value="">All workspaces</option>
            {workspaceOptions.map((workspace) => (
              <option key={workspace.workspaceId} value={workspace.workspaceId}>
                {workspace.name || workspace.workspaceId}
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
        teamForm={<CreateTeamBlueprintForm workspaceId={workspaceId} onCreate={createTeamBlueprint} />}
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
      <TeamsSection teams={teams} home={home} />
      <RunsAndApprovalsSection
        runs={runs}
        approvals={approvals}
        onApproveApproval={approveApproval}
        onRejectApproval={rejectApproval}
        onRequestApprovalInfo={requestApprovalInfo}
      />
      <InsightsAndActionsSection insights={insights} actions={home?.nextActions ?? []} />
    </div>
  );
}
