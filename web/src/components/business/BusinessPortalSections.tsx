import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  type LucideIcon,
} from "lucide-react";
import type {
  BusinessAction,
  BusinessApprovalRecord,
  BusinessHomeResponse,
  BusinessInsightRecord,
  BusinessRunRecord,
  BusinessTeamCard,
} from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

export function fmt(value: unknown): string {
  if (typeof value === "number") return new Intl.NumberFormat().format(value);
  if (typeof value === "string" && value) return value;
  return "-";
}

export function riskVariant(
  level?: string,
): "success" | "warning" | "destructive" | "outline" {
  const normalized = (level ?? "").toUpperCase();
  if (normalized === "HIGH" || normalized === "CRITICAL") return "destructive";
  if (normalized === "MEDIUM") return "warning";
  if (normalized === "LOW" || normalized === "INFO") return "success";
  return "outline";
}

export function statusVariant(
  status?: string,
): "success" | "warning" | "destructive" | "outline" | "info" {
  const normalized = (status ?? "").toUpperCase();
  if (["ACTIVE", "COMPLETED", "APPROVED"].includes(normalized)) return "success";
  if (["PENDING", "NEEDS_APPROVAL", "INFO_REQUESTED", "RUNNING"].includes(normalized)) return "warning";
  if (["FAILED", "REJECTED", "HIGH", "CRITICAL"].includes(normalized)) return "destructive";
  if (["DRAFT", "INACTIVE", "INFO"].includes(normalized)) return "info";
  return "outline";
}

export function timeLabel(value?: string): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

export function MetricCard({
  title,
  value,
  icon: Icon,
}: {
  title: string;
  value: unknown;
  icon: LucideIcon;
}) {
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

export function MiniStat({ label, value }: { label: string; value: unknown }) {
  return (
    <div className="rounded-sm border border-border/70 p-3">
      <div className="text-[0.65rem] tracking-[0.15em] opacity-60">{label}</div>
      <div className="mt-1 font-expanded text-lg tracking-[0.06em]">{fmt(value)}</div>
    </div>
  );
}

export function ActionList({ actions, empty }: { actions: BusinessAction[]; empty: string }) {
  if (!actions.length) return <EmptyLine text={empty} icon={CheckCircle2} />;
  return (
    <div className="space-y-2">
      {actions.map((action) => (
        <div key={action.id} className="flex items-start gap-3 rounded-sm border border-border/70 p-3">
          <ArrowRight className="mt-0.5 h-4 w-4 shrink-0 opacity-60" />
          <div>
            <div className="font-expanded text-xs tracking-[0.1em]">{action.title}</div>
            {action.description ? (
              <div className="mt-1 text-sm normal-case text-muted-foreground">{action.description}</div>
            ) : null}
          </div>
        </div>
      ))}
    </div>
  );
}

export function EmptyLine({
  text,
  icon: Icon = AlertTriangle,
}: {
  text: string;
  icon?: LucideIcon;
}) {
  return (
    <div className="flex items-center gap-2 rounded-sm border border-dashed border-border/70 p-3 text-sm normal-case text-muted-foreground">
      <Icon className="h-4 w-4" />
      {text}
    </div>
  );
}

export function TodayAndAttentionSection({ home }: { home: BusinessHomeResponse | null }) {
  const today = home?.today ?? {};
  return (
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
  );
}

export function TeamsSection({ teams, home }: { teams: BusinessTeamCard[]; home: BusinessHomeResponse | null }) {
  return (
    <section className="grid gap-4 xl:grid-cols-3">
      <Card className="xl:col-span-2">
        <CardHeader>
          <CardTitle>Teams</CardTitle>
          <CardDescription>Digital employee team cards from Team Blueprint data.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {teams.length === 0 ? (
            <EmptyLine text="No teams yet." />
          ) : (
            teams.slice(0, 6).map((team) => <TeamRow key={`${team.workspaceId}:${team.teamId}`} team={team} />)
          )}
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
  );
}

function TeamRow({ team }: { team: BusinessTeamCard }) {
  return (
    <div className="rounded-sm border border-border/70 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="font-expanded text-sm tracking-[0.08em]">{team.name || team.teamId}</div>
        <Badge variant={statusVariant(team.status)}>{team.status || "UNKNOWN"}</Badge>
      </div>
      <div className="mt-2 text-xs normal-case text-muted-foreground">
        {team.scenario || "No scenario"} · v{fmt(team.activeVersion)} · {team.workspaceId}
      </div>
    </div>
  );
}

export function RunsAndApprovalsSection({
  runs,
  approvals,
}: {
  runs: BusinessRunRecord[];
  approvals: BusinessApprovalRecord[];
}) {
  return (
    <section className="grid gap-4 xl:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle>Recent run stories</CardTitle>
          <CardDescription>Business-readable traces.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {runs.length === 0 ? (
            <EmptyLine text="No run stories yet." />
          ) : (
            runs.slice(0, 5).map((run) => <RunRow key={run.runId} run={run} />)
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Approvals</CardTitle>
          <CardDescription>Mobile-first approval cards.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {approvals.length === 0 ? (
            <EmptyLine text="No approvals yet." />
          ) : (
            approvals.slice(0, 5).map((approval) => <ApprovalRow key={approval.approvalId} approval={approval} />)
          )}
        </CardContent>
      </Card>
    </section>
  );
}

function RunRow({ run }: { run: BusinessRunRecord }) {
  return (
    <div className="rounded-sm border border-border/70 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="font-expanded text-sm tracking-[0.08em]">{run.taskTitle || run.runId}</div>
        <Badge variant={statusVariant(run.status)}>{run.status || "UNKNOWN"}</Badge>
      </div>
      <p className="mt-2 text-sm normal-case text-muted-foreground">{run.resultSummary || "No result summary."}</p>
      <div className="mt-2 text-[0.7rem] tracking-[0.12em] opacity-60">
        {run.teamId ?? "-"} · {timeLabel(run.createdAt)}
      </div>
    </div>
  );
}

function ApprovalRow({ approval }: { approval: BusinessApprovalRecord }) {
  return (
    <div className="rounded-sm border border-border/70 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="font-expanded text-sm tracking-[0.08em]">{approval.title || approval.approvalId}</div>
        <div className="flex gap-2">
          <Badge variant={riskVariant(approval.riskLevel)}>{approval.riskLevel || "UNKNOWN"}</Badge>
          <Badge variant={statusVariant(approval.status)}>{approval.status || "UNKNOWN"}</Badge>
        </div>
      </div>
      <p className="mt-2 text-sm normal-case text-muted-foreground">{approval.summary || "No summary."}</p>
      <div className="mt-2 text-[0.7rem] tracking-[0.12em] opacity-60">
        {approval.teamId ?? "-"} · {timeLabel(approval.createdAt)}
      </div>
    </div>
  );
}

export function InsightsAndActionsSection({
  insights,
  actions,
}: {
  insights: BusinessInsightRecord[];
  actions: BusinessAction[];
}) {
  return (
    <section className="grid gap-4 xl:grid-cols-[1fr_0.8fr]">
      <Card>
        <CardHeader>
          <CardTitle>Insights</CardTitle>
          <CardDescription>Findings, causes and recommendations.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {insights.length === 0 ? (
            <EmptyLine text="No insights yet." />
          ) : (
            insights.slice(0, 6).map((insight) => <InsightRow key={insight.insightId} insight={insight} />)
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Next actions</CardTitle>
          <CardDescription>Recommended business actions.</CardDescription>
        </CardHeader>
        <CardContent>
          <ActionList actions={actions} empty="No recommendations yet." />
        </CardContent>
      </Card>
    </section>
  );
}

function InsightRow({ insight }: { insight: BusinessInsightRecord }) {
  return (
    <div className="rounded-sm border border-border/70 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="font-expanded text-sm tracking-[0.08em]">{insight.title || insight.insightId}</div>
        <Badge variant={riskVariant(insight.severity)}>{insight.severity ?? "INFO"}</Badge>
      </div>
      <p className="mt-2 text-sm normal-case text-muted-foreground">{insight.finding || "No finding."}</p>
      {insight.recommendation ? <p className="mt-2 text-sm normal-case">{insight.recommendation}</p> : null}
    </div>
  );
}

export function DemoDataGuide({ workspaceId }: { workspaceId?: string }) {
  const resolvedWorkspaceId = workspaceId || "customer-service-demo";
  const command = `HERMES_BASE_URL=http://127.0.0.1:9119 \\\nWORKSPACE_ID=${resolvedWorkspaceId} \\\nTEAM_ID=after-sales-team \\\nAPPROVAL_ACTION=approve \\\nscripts/smoke-business-portal.sh`;

  return (
    <Card>
      <CardHeader>
        <CardTitle>How to populate demo data</CardTitle>
        <CardDescription>
          Run the smoke script from a terminal to create a workspace, team, run story, approval card and insights.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <pre className="overflow-x-auto rounded-sm border border-border/70 bg-background/80 p-3 text-xs normal-case tracking-normal text-muted-foreground">
          <code>{command}</code>
        </pre>
        <div className="grid gap-2 text-sm normal-case text-muted-foreground sm:grid-cols-2">
          <div className="rounded-sm border border-border/70 p-3">
            <div className="font-expanded text-xs uppercase tracking-[0.1em] text-foreground">What it creates</div>
            <div className="mt-1">Workspace → Team Blueprint → Run Story → Approval → Insights</div>
          </div>
          <div className="rounded-sm border border-border/70 p-3">
            <div className="font-expanded text-xs uppercase tracking-[0.1em] text-foreground">Why not a button?</div>
            <div className="mt-1">The dashboard shows the command instead of executing local scripts from the browser.</div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
