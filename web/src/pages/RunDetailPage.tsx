import { useEffect, useRef, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  CheckCircle2,
  Circle,
  Clock,
  XCircle,
  AlertCircle,
  Route,
  Zap,
  Loader2,
  Coins,
  GitBranch,
  ShieldCheck,
  ShieldAlert,
  ShieldX,
  RotateCcw,
  Award,
  Wrench,
} from "lucide-react";
import { api } from "@/lib/api";
import { openBusinessRunStream } from "@/lib/api/sse";
import type { BusinessRunRecord, BusinessRunStep } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";

const PATTERN_LABELS: Record<string, { label: string; icon: typeof GitBranch; color: string }> = {
  SEQUENTIAL: { label: "Sequential", icon: GitBranch, color: "text-blue-400" },
  PARALLEL: { label: "Parallel", icon: Zap, color: "text-purple-400" },
  REVIEW: { label: "Review", icon: ShieldCheck, color: "text-amber-400" },
  COMPETITIVE: { label: "Competitive", icon: Award, color: "text-pink-400" },
  MASTER_WORKER: { label: "Master-Worker", icon: Wrench, color: "text-cyan-400" },
  PIPELINE: { label: "Pipeline", icon: Route, color: "text-indigo-400" },
};

export default function RunDetailPage() {
  const { workspaceId, runId } = useParams<{ workspaceId: string; runId: string }>();
  const navigate = useNavigate();
  const [run, setRun] = useState<BusinessRunRecord | null>(null);
  const [steps, setSteps] = useState<BusinessRunStep[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [connected, setConnected] = useState(false);
  const [terminal, setTerminal] = useState(false);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!workspaceId || !runId) return;
    setLoading(true);
    api.getBusinessRun(workspaceId, runId)
      .then((res) => {
        setRun(res.run);
        setSteps(res.run.steps ?? []);
        setLoading(false);
      })
      .catch((err) => {
        setError(err?.message || "Failed to load run");
        setLoading(false);
      });
  }, [workspaceId, runId]);

  useEffect(() => {
    if (!workspaceId || !runId) return;
    if (terminal) return;

    const es = openBusinessRunStream(
      workspaceId,
      runId,
      (event, data) => {
        if (event === "run.state") {
          const payload = data as unknown as BusinessRunRecord;
          setRun((prev) => (prev ? { ...prev, ...payload } : payload));
          if (payload.steps) setSteps(payload.steps);
        } else if (event === "run.started") {
          setConnected(true);
        } else if (event === "step.started" || event === "step.completed" || event === "step.failed") {
          const stepData = (data as Record<string, unknown>)?.step as BusinessRunStep;
          if (stepData) {
            setSteps((prev) => {
              const existing = prev.findIndex((s) => s.stepId === stepData.stepId);
              if (existing >= 0) {
                const next = [...prev];
                next[existing] = stepData;
                return next;
              }
              return [...prev, stepData];
            });
          }
        } else if (event === "run.completed" || event === "run.failed") {
          setTerminal(true);
          const payload = data as unknown as Partial<BusinessRunRecord>;
          setRun((prev) => (prev ? { ...prev, ...payload } : prev));
        }
      },
      () => setConnected(false)
    );

    esRef.current = es;
    setConnected(true);

    return () => {
      es.close();
      esRef.current = null;
    };
  }, [workspaceId, runId, terminal]);

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (error || !run) {
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-4">
        <AlertCircle className="h-10 w-10 text-red-400" />
        <p className="text-muted-foreground">{error || "Run not found"}</p>
        <Button variant="outline" onClick={() => navigate(-1)}>
          <ArrowLeft className="mr-2 h-4 w-4" /> Back
        </Button>
      </div>
    );
  }

  const isRunning = run.status === "RUNNING";
  const isCompleted = run.status === "COMPLETED";
  const isFailed = run.status === "FAILED" || run.status === "PARTIAL";
  const isPendingApproval = run.status === "NEEDS_APPROVAL";

  const pattern = run.collaborationPattern ?? "SEQUENTIAL";
  const patternInfo = PATTERN_LABELS[pattern] || PATTERN_LABELS.SEQUENTIAL;
  const PatternIcon = patternInfo.icon;

  return (
    <div className="min-h-screen bg-background p-6">
      {/* Header */}
      <div className="mb-6 flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
            <ArrowLeft className="h-5 w-5" />
          </Button>
          <div>
            <h1 className="text-xl font-semibold">{run.taskTitle || run.runId}</h1>
            <p className="text-sm text-muted-foreground">{run.scenario || "-"}</p>
          </div>
        </div>
        <div className="flex items-center gap-3 flex-wrap">
          {connected && isRunning && (
            <span className="flex items-center gap-1.5 text-xs text-emerald-400">
              <span className="relative flex h-2 w-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
              </span>
              Live
            </span>
          )}

          {/* Collaboration pattern badge */}
          <Badge variant="outline" className={cn("text-[0.65rem] gap-1", patternInfo.color)}>
            <PatternIcon className="h-3 w-3" />
            {patternInfo.label}
          </Badge>

          {/* SLA badge */}
          {run.slaName && (
            <SLABadge slaName={run.slaName} slaStatus={run.slaStatus} />
          )}

          <Badge
            variant={
              isCompleted
                ? "default"
                : isFailed
                  ? "destructive"
                  : isPendingApproval
                    ? "outline"
                    : "secondary"
            }
          >
            {run.status}
          </Badge>
        </div>
      </div>

      {/* Summary cards */}
      <div className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <MetricCard label="Team" value={run.teamId ?? "-"} icon={Zap} />
        <MetricCard label="Steps" value={String(steps.length)} icon={Route} />
        <MetricCard label="Tokens" value={String(run.tokensUsed ?? 0)} icon={Coins} />
        <MetricCard
          label="Cost"
          value={run.estimatedCost ? `$${run.estimatedCost.toFixed(4)}` : "-"}
          icon={Coins}
        />
      </div>

      {/* Pattern execution flow visualization */}
      {steps.length > 0 && (
        <PatternFlowBar pattern={pattern} steps={steps} isRunning={isRunning} />
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        {/* Timeline */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">Execution Timeline</CardTitle>
          </CardHeader>
          <CardContent className="space-y-0">
            {steps.length === 0 ? (
              <div className="py-8 text-center text-sm text-muted-foreground">
                {isRunning ? (
                  <span className="flex items-center justify-center gap-2">
                    <Loader2 className="h-4 w-4 animate-spin" /> Waiting for steps...
                  </span>
                ) : (
                  "No steps recorded."
                )}
              </div>
            ) : (
              <div className="relative ml-3 border-l border-border/60 pl-6">
                {steps.map((step, i) => (
                  <StepNode
                    key={step.stepId ?? i}
                    step={step}
                    isLast={i === steps.length - 1}
                    isActive={isRunning && i === steps.length - 1 && step.status === "RUNNING"}
                  />
                ))}
                {isRunning && (
                  <div className="relative pb-2 pt-4">
                    <div className="absolute -left-[25px] top-5 flex h-4 w-4 items-center justify-center rounded-full bg-muted">
                      <Loader2 className="h-3 w-3 animate-spin text-muted-foreground" />
                    </div>
                    <p className="text-xs text-muted-foreground">Running...</p>
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>

        {/* Story panel */}
        <div className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Task</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">{run.taskInput || "No input provided."}</p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">Result</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm">{run.resultSummary || "Pending..."}</p>
              {run.conclusionReason && (
                <div className="rounded-md bg-muted/50 p-3 text-xs text-muted-foreground">
                  {run.conclusionReason}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">System Action</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">{run.systemAction || "-"}</p>
            </CardContent>
          </Card>

          {run.riskJudgement && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Risk Assessment</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground">{run.riskJudgement}</p>
              </CardContent>
            </Card>
          )}

          {run.nextSuggestion && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Next Step</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground">{run.nextSuggestion}</p>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

/** SLA status badge */
function SLABadge({ slaName, slaStatus }: { slaName: string; slaStatus?: string }) {
  const configs: Record<string, { icon: typeof ShieldCheck; color: string; bg: string }> = {
    healthy: { icon: ShieldCheck, color: "text-green-400", bg: "bg-green-400/10" },
    warning: { icon: ShieldAlert, color: "text-amber-400", bg: "bg-amber-400/10" },
    breached: { icon: ShieldX, color: "text-red-400", bg: "bg-red-400/10" },
  };
  const cfg = configs[slaStatus ?? "healthy"] || configs.healthy;
  const Icon = cfg.icon;
  return (
    <Badge variant="outline" className={cn("text-[0.6rem] gap-1", cfg.color)}>
      <Icon className="h-3 w-3" />
      SLA: {slaName}
    </Badge>
  );
}

/** Pattern flow bar — visual indicator of execution pattern */
function PatternFlowBar({ pattern, steps, isRunning }: { pattern: string; steps: BusinessRunStep[]; isRunning: boolean }) {
  const total = steps.length;
  const completed = steps.filter((s) => s.status === "COMPLETED").length;
  const failed = steps.filter((s) => s.status === "FAILED").length;

  const getStepColor = (step: BusinessRunStep) => {
    if (step.status === "COMPLETED") return "bg-emerald-400";
    if (step.status === "FAILED") return "bg-red-400";
    if (step.retry) return "bg-amber-400";
    return "bg-muted-foreground/30";
  };

  return (
    <Card className="mb-6">
      <CardContent className="p-4">
        <div className="flex items-center gap-3 mb-3">
          <span className="text-[0.65rem] uppercase tracking-[0.14em] opacity-60">Execution Pattern</span>
          <span className="text-xs font-medium">{pattern}</span>
          <span className="text-[0.65rem] opacity-50">
            {completed}/{total} completed{failed > 0 ? ` · ${failed} failed` : ""}
          </span>
        </div>
        <div className="flex items-center gap-1">
          {steps.map((step, i) => (
            <div key={step.stepId ?? i} className="flex items-center gap-1 flex-1">
              <div
                className={cn(
                  "h-2 rounded-full flex-1 transition-all duration-500",
                  getStepColor(step),
                  isRunning && step.status === "RUNNING" && "animate-pulse"
                )}
                title={`${step.title || "Step"} — ${step.agentId || step.actor || "system"}`}
              />
              {i < steps.length - 1 && pattern !== "PARALLEL" && (
                <div className="w-3 h-px bg-border/60" />
              )}
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

function StepNode({
  step,
  isLast,
  isActive,
}: {
  step: BusinessRunStep;
  isLast: boolean;
  isActive: boolean;
}) {
  const status = step.status ?? "PENDING";
  const isCompleted = status === "COMPLETED";
  const isFailed = status === "FAILED";
  const isRetry = step.retry;

  const Icon = isCompleted ? CheckCircle2 : isFailed ? XCircle : isActive ? Clock : Circle;
  const color = isCompleted
    ? "text-emerald-400"
    : isFailed
      ? "text-red-400"
      : isActive
        ? "text-amber-400"
        : "text-muted-foreground";

  return (
    <div className={cn("relative pb-6", isLast && "pb-0")}>
      {/* Node dot */}
      <div
        className={cn(
          "absolute -left-[25px] top-0 flex h-5 w-5 items-center justify-center rounded-full border-2 bg-background",
          isCompleted
            ? "border-emerald-400"
            : isFailed
              ? "border-red-400"
              : isActive
                ? "border-amber-400"
                : "border-muted-foreground/40"
        )}
      >
        <Icon className={cn("h-3 w-3", color)} />
      </div>

      {/* Content */}
      <div className="space-y-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium">{step.title || "Step"}</span>
          <Badge variant="outline" className="text-[0.65rem]">
            {step.actor ?? "system"}
          </Badge>
          {step.agentId && step.agentId !== step.actor && (
            <Badge variant="outline" className="text-[0.6rem] text-blue-400 border-blue-400/20">
              agent: {step.agentId}
            </Badge>
          )}
          {isRetry && (
            <Badge variant="outline" className="text-[0.6rem] text-amber-400 border-amber-400/20 gap-1">
              <RotateCcw className="h-3 w-3" />
              Retry{step.retryFrom ? ` from ${step.retryFrom}` : ""}
            </Badge>
          )}
          {typeof step.score === "number" && (
            <span className="text-[0.6rem] opacity-50">
              score: {step.score.toFixed(2)}
            </span>
          )}
        </div>
        <p className="text-sm text-muted-foreground">{step.summary || "-"}</p>

        {/* Matched skills */}
        {step.matchedSkills && (
          <div className="flex flex-wrap gap-1">
            {step.matchedSkills.split(", ").map((skill) => (
              <span
                key={skill}
                className="text-[0.6rem] px-1.5 py-0.5 rounded-sm bg-muted/50 text-muted-foreground"
              >
                {skill}
              </span>
            ))}
          </div>
        )}

        {step.evidence && (
          <details className="mt-1">
            <summary className="cursor-pointer text-xs text-muted-foreground hover:text-foreground">
              Evidence
            </summary>
            <pre className="mt-1 max-h-40 overflow-auto rounded-md bg-muted p-2 text-xs">
              {step.evidence}
            </pre>
          </details>
        )}
        {step.metadata && "reflection" in step.metadata && (
          <div className={cn(
            "mt-2 rounded-md p-2 text-xs",
            step.metadata.aligned === false
              ? "bg-amber-500/10 text-amber-600"
              : "bg-emerald-500/10 text-emerald-600"
          )}>
            <div className="flex items-center gap-1.5">
              {step.metadata.aligned === false ? (
                <AlertCircle className="h-3 w-3" />
              ) : (
                <CheckCircle2 className="h-3 w-3" />
              )}
              <span className="font-medium">Reflection</span>
              {typeof step.metadata.confidence === "number" && (
                <span className="text-[0.6rem] opacity-60">
                  ({Math.round((step.metadata.confidence as number) * 100)}%)
                </span>
              )}
            </div>
            <p className="mt-0.5">{String((step.metadata as Record<string, unknown>).reflection ?? "")}</p>
            {Boolean(step.metadata.replan) && Boolean(step.metadata.suggestion) && (
              <p className="mt-1 text-[0.65rem] opacity-80">
                Suggestion: {String((step.metadata as Record<string, unknown>).suggestion ?? "")}
              </p>
            )}
          </div>
        )}
        {step.timestamp && (
          <p className="text-[0.65rem] text-muted-foreground/60">
            {new Date(step.timestamp).toLocaleTimeString()}
          </p>
        )}
      </div>
    </div>
  );
}

function MetricCard({
  label,
  value,
  icon: Icon,
}: {
  label: string;
  value: string;
  icon: React.ElementType;
}) {
  return (
    <Card>
      <CardContent className="flex items-center gap-3 p-4">
        <Icon className="h-5 w-5 text-muted-foreground" />
        <div>
          <p className="text-xs text-muted-foreground">{label}</p>
          <p className="text-sm font-semibold">{value}</p>
        </div>
      </CardContent>
    </Card>
  );
}
