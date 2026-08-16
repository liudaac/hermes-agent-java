import { useEffect, useState, useCallback } from "react";
import { Workflow, CheckCircle2, XCircle, Clock } from "lucide-react";
import { opsNocApi } from "@/lib/api/ops";
import { Card, CardContent } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { useI18n } from "@/i18n";
import { useToast } from "@/hooks/useToast";

interface WorkflowItem {
  workflowId?: string;
  title?: string;
  status?: string;
  pendingCheckpoint?: string;
  createdAt?: string;
  updatedAt?: string;
}

export default function WorkflowPage() {
  const [workflows, setWorkflows] = useState<WorkflowItem[]>([]);
  const [loading, setLoading] = useState(true);
  const { t } = useI18n();
  const { showToast } = useToast();

  const reload = useCallback(() => {
    opsNocApi
      .getWorkflows()
      .then((r) => setWorkflows((r.workflows ?? []) as WorkflowItem[]))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    reload();
    const interval = setInterval(reload, 30000);
    return () => clearInterval(interval);
  }, [reload]);

  const approve = (id: string) => {
    opsNocApi
      .approveWorkflowCheckpoint(id, "approve")
      .then(() => {
        showToast("Approved ✓", "success");
        reload();
      })
      .catch(() => showToast("Approve failed", "error"));
  };

  const reject = (id: string) => {
    opsNocApi
      .approveWorkflowCheckpoint(id, "reject")
      .then(() => {
        showToast("Rejected ✓", "success");
        reload();
      })
      .catch(() => showToast("Reject failed", "error"));
  };

  const STATUS_VARIANT: Record<string, "success" | "warning" | "destructive" | "outline"> = {
    running: "success",
    pending: "warning",
    completed: "outline",
    failed: "destructive",
    approved: "success",
    rejected: "destructive",
  };

  return (
    <div className="flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Workflow className="h-5 w-5 text-muted-foreground" />
        <div>
          <h2 className="text-lg font-semibold tracking-tight">
            {t.workflows?.title ?? "Workflows"}
          </h2>
          <p className="text-xs text-muted-foreground">
            {t.workflows?.subtitle ?? "Business workflow monitoring"}
          </p>
        </div>
      </div>

      {/* Loading skeleton */}
      {loading && (
        <div className="flex flex-col gap-2">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-20 rounded-lg border border-border animate-pulse bg-muted/20" />
          ))}
        </div>
      )}

      {/* Empty state */}
      {!loading && workflows.length === 0 && (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-16">
            <CheckCircle2 className="h-10 w-10 text-success mb-3" />
            <p className="text-sm font-medium text-muted-foreground">
              {t.workflows?.empty ?? "No active workflows"}
            </p>
          </CardContent>
        </Card>
      )}

      {/* Items */}
      {!loading && workflows.length > 0 && (
        <div className="flex flex-col gap-2">
          {workflows.map((wf, i) => (
            <Card key={wf.workflowId ?? i}>
              <CardContent className="py-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-medium text-sm truncate">
                        {wf.title ?? wf.workflowId ?? "Unknown"}
                      </span>
                      <Badge
                        variant={STATUS_VARIANT[wf.status ?? ""] ?? "outline"}
                        className="text-[10px]"
                      >
                        {wf.status ?? "unknown"}
                      </Badge>
                    </div>
                    {wf.pendingCheckpoint && (
                      <div className="flex items-center gap-1.5 mt-1 text-xs text-warning">
                        <Clock className="h-3 w-3" />
                        <span>{t.workflows?.pending ?? "Pending Checkpoint"}: {wf.pendingCheckpoint}</span>
                      </div>
                    )}
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-muted-foreground mt-1">
                      {wf.workflowId && (
                        <span className="font-mono-ui">ID: {wf.workflowId.slice(0, 12)}</span>
                      )}
                      {wf.createdAt && <span>Created: {new Date(wf.createdAt).toLocaleString()}</span>}
                      {wf.updatedAt && <span>Updated: {new Date(wf.updatedAt).toLocaleString()}</span>}
                    </div>
                  </div>
                  {wf.pendingCheckpoint && (
                    <div className="flex items-center gap-1.5 shrink-0">
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 text-xs text-success border-success/30 hover:bg-success/10"
                        onClick={() => approve(wf.workflowId!)}
                      >
                        <CheckCircle2 className="h-3 w-3 mr-1" />
                        {t.workflows?.approve ?? "Approve"}
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 text-xs text-destructive border-destructive/30 hover:bg-destructive/10"
                        onClick={() => reject(wf.workflowId!)}
                      >
                        <XCircle className="h-3 w-3 mr-1" />
                        {t.workflows?.reject ?? "Reject"}
                      </Button>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
