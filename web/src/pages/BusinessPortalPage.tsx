import { useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  ArrowRight,
  BriefcaseBusiness,
  CheckCircle2,
  ClipboardCheck,
  Lightbulb,
  RefreshCw,
  Route,
  Users,
} from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import type {
  BusinessAction,
  BusinessApprovalRecord,
  BusinessHomeResponse,
  BusinessInsightRecord,
  BusinessRunRecord,
  BusinessTeamCard,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/hooks/useToast";
import { cn } from "@/lib/utils";

function fmt(value: unknown): string {
  if (typeof value === "number") return new Intl.NumberFormat().format(value);
  if (typeof value === "string" && value) return value;
  return "-";
}

function riskVariant(level?: string): "success" | "warning" | "destructive" | "outline" {
  const normalized = (level ?? "").toUpperCase();
  if (normalized === "HIGH" || normalized === "CRITICAL") return "destructive";
  if (normalized === "MEDIUM") return "warning";
  if (normalized === "LOW") return "success";
  return "outline";
}

function statusVariant(status?: string): "success" | "warning" | "destructive" | "outline" | "info" {
  const normalized = (status ?? "").toUpperCase();
  if (["ACTIVE", "COMPLETED", "APPROVED"].includes(normalized)) return "success";
  if (["PENDING", "NEEDS_APPROVAL", "INFO_REQUESTED", "RUNNING"].includes(normalized)) return "warning";
  if (["FAILED", "REJECTED", "HIGH", "CRITICAL"].includes(normalized)) return "destructive";
  if (["DRAFT", "INACTIVE"].includes(normalized)) return "info";
  return "outline";
}

function timeAgo(value?: string): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

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
  const today = home?.today ?? {};

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

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <MetricCard title="Workspaces" value={summary?.workspaceCount} icon={BriefcaseBusiness} />
        <MetricCard title="Teams" value={summary?.teamCount} icon={Users} />
        <MetricCard title="Runs" value={summary?.runCount} icon={Route} />
        <MetricCard title="Pending approvals" value={summary?.pendingApprovals} icon={ClipboardCheck} />
        <MetricCard title="Open insights" value={summary?.openInsights} icon={Lightbulb} />
      </section>

      <section className="grid gap-4 xl:grid-cols-[1.2fr_0.8fr]">
        <Card>
          <CardHeader className="flex-row items-center justify-between gap-3">
            <div>
              <CardTitle>Today</CardTitle>
              <CardDescription>Business health summary from run and approval data.</CardDescription>
            </div>
            <Badge variant={riskVariant(home?.risk?.level)}>{home?.risk?.level ?? "UNKNOWN"}</Badge>
          </CardHeader>
          <CardContent>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <MiniStat label="Processed" value={today.processedTasks} />
              <MiniStat label="Auto completion" value={`${fmt(today.autoCompletionRate)}%`} />
              <MiniStat label="Failed runs" value={today.failedRuns} />
              <MiniStat label="High risk approvals" value={today.highRiskApprovals} />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Needs attention</CardTitle>
            <CardDescription>Items that should be handled first.</CardDescription>
          </CardHeader>
          <CardContent>
            <ActionList actions={home?.needsAttention ?? []} empty="Nothing urgent right now." />
          </CardContent>
        </Card>
      </section>

      <section className="grid gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader>
            <CardTitle>Teams</CardTitle>
            <CardDescription>Digital employee team cards from Team Blueprint data.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {teams.length === 0 ? <EmptyLine text="No teams yet." /> : teams.slice(0, 6).map((team) => (
              <div key={`${team.workspaceId}:${team.teamId}`} className="rounded-sm border border-border/70 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="font-expanded text-sm tracking-[0.08em]">{team.name}</div>
                  <Badge variant={statusVariant(team.status)}>{team.status}</Badge>
                </div>
                <div className="mt-2 text-xs normal-case text-muted-foreground">
                  {team.scenario || "No scenario"} · v{team.activeVersion} · {team.workspaceId}
                </div>
              </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Team status</CardTitle>
            <CardDescription>Operational state derived from runs and approvals.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <MiniStat label="Total" value={home?.teamStatus?.total} />
            <MiniStat label="Normal" value={home?.teamStatus?.normal} />
            <MiniStat label="Needs attention" value={home?.teamStatus?.needsAttention} />
            {home?.teamStatus?.emptyState ? <EmptyLine text={home.teamStatus.emptyState} /> : null}
          </CardContent>
        </Card>
      </section>

      <section className="grid gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Recent run stories</CardTitle>
            <CardDescription>Business-readable traces.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {runs.length === 0 ? <EmptyLine text="No run stories yet." /> : runs.slice(0, 5).map((run) => (
              <div key={run.runId} className="rounded-sm border border-border/70 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="font-expanded text-sm tracking-[0.08em]">{run.taskTitle}</div>
                  <Badge variant={statusVariant(run.status)}>{run.status}</Badge>
                </div>
                <p className="mt-2 text-sm normal-case text-muted-foreground">{run.resultSummary}</p>
                <div className="mt-2 text-[0.7rem] tracking-[0.12em] opacity-60">{run.teamId ?? "-"} · {timeAgo(run.createdAt)}</div>
              </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Approvals</CardTitle>
            <CardDescription>Mobile-first approval cards.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {approvals.length === 0 ? <EmptyLine text="No approvals yet." /> : approvals.slice(0, 5).map((approval) => (
              <div key={approval.approvalId} className="rounded-sm border border-border/70 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="font-expanded text-sm tracking-[0.08em]">{approval.title}</div>
                  <div className="flex gap-2">
                    <Badge variant={riskVariant(approval.riskLevel)}>{approval.riskLevel}</Badge>
                    <Badge variant={statusVariant(approval.status)}>{approval.status}</Badge>
                  </div>
                </div>
                <p className="mt-2 text-sm normal-case text-muted-foreground">{approval.summary}</p>
                <div className="mt-2 text-[0.7rem] tracking-[0.12em] opacity-60">{approval.teamId ?? "-"} · {timeAgo(approval.createdAt)}</div>
              </div>
            ))}
          </CardContent>
        </Card>
      </section>

      <section className="grid gap-4 xl:grid-cols-[1fr_0.8fr]">
        <Card>
          <CardHeader>
            <CardTitle>Insights</CardTitle>
            <CardDescription>Findings, causes and recommendations.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {insights.length === 0 ? <EmptyLine text="No insights yet." /> : insights.slice(0, 6).map((insight) => (
              <div key={insight.insightId} className="rounded-sm border border-border/70 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="font-expanded text-sm tracking-[0.08em]">{insight.title}</div>
                  <Badge variant={riskVariant(insight.severity)}>{insight.severity ?? "INFO"}</Badge>
                </div>
                <p className="mt-2 text-sm normal-case text-muted-foreground">{insight.finding}</p>
                {insight.recommendation ? (
                  <p className="mt-2 text-sm normal-case">{insight.recommendation}</p>
                ) : null}
              </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Next actions</CardTitle>
            <CardDescription>Recommended business actions.</CardDescription>
          </CardHeader>
          <CardContent>
            <ActionList actions={home?.nextActions ?? []} empty="No recommendations yet." />
          </CardContent>
        </Card>
      </section>
    </div>
  );
}

function MetricCard({ title, value, icon: Icon }: { title: string; value: unknown; icon: React.ComponentType<{ className?: string }> }) {
  return (
    <Card>
      <CardContent className="flex items-center justify-between gap-3 p-4">
        <div>
          <div className="text-[0.7rem] tracking-[0.15em] opacity-60">{title}</div>
          <div className="mt-2 font-expanded text-2xl tracking-[0.05em]">{fmt(value)}</div>
        </div>
        <Icon className="h-7 w-7 opacity-50" />
      </CardContent>
    </Card>
  );
}

function MiniStat({ label, value }: { label: string; value: unknown }) {
  return (
    <div className="rounded-sm border border-border/70 p-3">
      <div className="text-[0.65rem] tracking-[0.15em] opacity-60">{label}</div>
      <div className="mt-1 font-expanded text-lg tracking-[0.06em]">{fmt(value)}</div>
    </div>
  );
}

function ActionList({ actions, empty }: { actions: BusinessAction[]; empty: string }) {
  if (!actions.length) return <EmptyLine text={empty} icon={CheckCircle2} />;
  return (
    <div className="space-y-2">
      {actions.map((action) => (
        <div key={action.id} className="flex items-start gap-3 rounded-sm border border-border/70 p-3">
          <ArrowRight className="mt-0.5 h-4 w-4 shrink-0 opacity-60" />
          <div>
            <div className="font-expanded text-xs tracking-[0.1em]">{action.title}</div>
            {action.description ? <div className="mt-1 text-sm normal-case text-muted-foreground">{action.description}</div> : null}
          </div>
        </div>
      ))}
    </div>
  );
}

function EmptyLine({ text, icon: Icon = AlertTriangle }: { text: string; icon?: React.ComponentType<{ className?: string }> }) {
  return (
    <div className="flex items-center gap-2 rounded-sm border border-dashed border-border/70 p-3 text-sm normal-case text-muted-foreground">
      <Icon className="h-4 w-4" />
      {text}
    </div>
  );
}
