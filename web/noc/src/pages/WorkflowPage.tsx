import { useEffect, useState, useCallback } from "react";
import {
  Activity,
  CheckCircle,
  Clock,
  Pause,
  AlertTriangle,
  Play,
  RotateCw,
  GitBranch,
} from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/hooks/useToast";
import { useSse } from "@/hooks/useSse";
import { cn } from "@/lib/utils";

interface WorkflowStatus {
  found: boolean;
  workflowId: string;
  name: string;
  status: string;
  progress: number;
  currentStep: string | null;
  stepsTotal: number;
  stepsCompleted: number;
  waitingForHuman: boolean;
  createdAt: string;
}

export default function WorkflowPage() {
  const { showToast } = useToast();
  const [workflows, setWorkflows] = useState<WorkflowStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState<string | null>(null);
  const [liveEvent, setLiveEvent] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      const res = await api.getWorkflows();
      if (res.ok && Array.isArray(res.workflows)) {
        setWorkflows(
          res.workflows.map((w: any) => ({
            found: w.found ?? true,
            workflowId: w.workflowId,
            name: w.name,
            status: w.status,
            progress: w.progress ?? 0,
            currentStep: w.currentStep ?? null,
            stepsTotal: w.stepsTotal ?? 0,
            stepsCompleted: w.stepsCompleted ?? 0,
            waitingForHuman: w.waitingForHuman ?? false,
            createdAt: w.createdAt,
          })),
        );
      } else {
        setWorkflows([]);
      }
    } catch (e: any) {
      showToast(e?.message || "Failed to load workflows", "error");
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    load();
  }, [load]);

  const handleCheckpoint = useCallback(
    async (workflowId: string, decision: "approve" | "reject") => {
      try {
        setActing(workflowId);
        await api.approveWorkflowCheckpoint(workflowId, decision);
        showToast(
          decision === "approve" ? "Workflow approved" : "Workflow rejected",
          "success",
        );
        await load();
      } catch (e: any) {
        showToast(e?.message || `Failed to ${decision} workflow`, "error");
      } finally {
        setActing(null);
      }
    },
    [load, showToast],
  );

  // SSE real-time refresh
  useSse({
    onEvent: (evt) => {
      if (["WORKFLOW_STATUS", "WORKFLOW_CHECKPOINT", "RUN_STATUS"].includes(evt.type)) {
        setLiveEvent(`${evt.type} @ ${new Date().toLocaleTimeString()}`);
        load();
      }
    },
    onError: () => {},
  });

  const statusIcon = (status: string) => {
    switch (status) {
      case "COMPLETED":
        return <CheckCircle className="h-4 w-4 text-green-500" />;
      case "RUNNING":
        return <Activity className="h-4 w-4 text-blue-500 animate-pulse" />;
      case "WAITING_HUMAN":
        return <Pause className="h-4 w-4 text-amber-500" />;
      case "FAILED":
        return <AlertTriangle className="h-4 w-4 text-red-500" />;
      default:
        return <Clock className="h-4 w-4 text-gray-500" />;
    }
  };

  const statusBadge = (status: string) => {
    const variants: Record<string, string> = {
      COMPLETED: "bg-green-500/10 text-green-500 border-green-500/20",
      RUNNING: "bg-blue-500/10 text-blue-500 border-blue-500/20",
      WAITING_HUMAN: "bg-amber-500/10 text-amber-500 border-amber-500/20",
      FAILED: "bg-red-500/10 text-red-500 border-red-500/20",
    };
    return (
      <Badge variant="outline" className={cn("text-[0.65rem]", variants[status] || "")}>
        {status}
      </Badge>
    );
  };

  const total = workflows.length;
  const running = workflows.filter((w) => w.status === "RUNNING").length;
  const waiting = workflows.filter((w) => w.waitingForHuman).length;
  const completed = workflows.filter((w) => w.status === "COMPLETED").length;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <GitBranch className="h-5 w-5 opacity-70" />
          <H2 className="text-base tracking-[0.08em]">Workflow Monitor</H2>
          {liveEvent && (
            <Badge variant="outline" className="text-[0.6rem] text-green-500 border-green-500/20 animate-pulse">
              <Activity className="h-3 w-3 mr-1" />
              {liveEvent}
            </Badge>
          )}
        </div>
        <Button variant="outline" size="sm" onClick={load} disabled={loading} className="text-[0.65rem]">
          <RotateCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} />
          Refresh
        </Button>
      </div>

      {/* Summary strip */}
      <div className="grid grid-cols-4 gap-3">
        <Card>
          <CardContent className="p-3 text-center">
            <div className="text-[0.65rem] opacity-50">Total</div>
            <div className="mt-1 text-xl font-expanded">{total}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-3 text-center">
            <div className="text-[0.65rem] opacity-50">Running</div>
            <div className="mt-1 text-xl font-expanded text-blue-500">{running}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-3 text-center">
            <div className="text-[0.65rem] opacity-50">Waiting</div>
            <div className="mt-1 text-xl font-expanded text-amber-500">{waiting}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-3 text-center">
            <div className="text-[0.65rem] opacity-50">Completed</div>
            <div className="mt-1 text-xl font-expanded text-green-500">{completed}</div>
          </CardContent>
        </Card>
      </div>

      {loading ? (
        <div className="flex h-32 items-center justify-center text-sm opacity-60">
          <RotateCw className="mr-2 h-4 w-4 animate-spin" />
          Loading workflows...
        </div>
      ) : workflows.length === 0 ? (
        <Card>
          <CardContent className="p-6 text-center space-y-2">
            <GitBranch className="h-8 w-8 mx-auto opacity-40" />
            <div className="text-sm opacity-60">No active workflows. Start a scenario to see workflows here.</div>
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4">
          {workflows.map((wf) => (
            <Card key={wf.workflowId} className={cn("overflow-hidden", wf.waitingForHuman && "border-amber-500/30")}>
              <CardContent className="p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    {statusIcon(wf.status)}
                    <span className="font-medium text-sm">{wf.name}</span>
                    <span className="text-[0.65rem] opacity-50 font-mono">{wf.workflowId}</span>
                  </div>
                  {statusBadge(wf.status)}
                </div>

                <div className="space-y-1">
                  <div className="flex justify-between text-[0.65rem] opacity-60">
                    <span>Progress</span>
                    <span>{Math.round(wf.progress * 100)}%</span>
                  </div>
                  <div className="h-2 bg-muted rounded-full overflow-hidden">
                    <div
                      className={cn(
                        "h-full transition-all duration-500",
                        wf.status === "COMPLETED"
                          ? "bg-green-500"
                          : wf.status === "FAILED"
                            ? "bg-red-500"
                            : wf.waitingForHuman
                              ? "bg-amber-500"
                              : "bg-blue-500",
                      )}
                      style={{ width: `${wf.progress * 100}%` }}
                    />
                  </div>
                </div>

                <div className="flex items-center justify-between text-[0.7rem] opacity-70">
                  <span>
                    Step {wf.stepsCompleted} / {wf.stepsTotal}
                  </span>
                  <span>Current: {wf.currentStep || "—"}</span>
                </div>

                {wf.waitingForHuman && (
                  <div className="flex gap-2 pt-1">
                    <Button
                      size="sm"
                      className="text-[0.65rem]"
                      disabled={acting === wf.workflowId}
                      onClick={() => handleCheckpoint(wf.workflowId, "approve")}
                    >
                      <Play className="h-3 w-3 mr-1" />
                      {acting === wf.workflowId ? "Processing..." : "Approve"}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      className="text-[0.65rem]"
                      disabled={acting === wf.workflowId}
                      onClick={() => handleCheckpoint(wf.workflowId, "reject")}
                    >
                      Reject
                    </Button>
                  </div>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
