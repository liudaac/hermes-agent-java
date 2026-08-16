import { useEffect, useState, useCallback } from "react";
import { Hand, CheckCircle2, UserCheck, UserX } from "lucide-react";
import { opsNocApi } from "@/lib/api/ops";
import { Card, CardContent } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { useI18n } from "@/i18n";
import { useToast } from "@/hooks/useToast";

interface TakeoverItem {
  takeoverId?: string;
  runId?: string;
  operatorId?: string;
  status?: string;
  createdAt?: string;
  reason?: string;
}

export default function HumanLoopPage() {
  const [takeovers, setTakeovers] = useState<TakeoverItem[]>([]);
  const [loading, setLoading] = useState(true);
  const { t } = useI18n();
  const { showToast } = useToast();

  const reload = useCallback(() => {
    opsNocApi
      .getTakeovers()
      .then((r) => setTakeovers((r.takeovers ?? []) as TakeoverItem[]))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    reload();
    const interval = setInterval(reload, 30000);
    return () => clearInterval(interval);
  }, [reload]);

  const confirm = (id: string) => {
    opsNocApi
      .confirmTakeover(id)
      .then(() => {
        showToast("Confirmed ✓", "success");
        reload();
      })
      .catch(() => showToast("Confirm failed", "error"));
  };

  const release = (id: string) => {
    opsNocApi
      .releaseTakeover(id)
      .then(() => {
        showToast("Released ✓", "success");
        reload();
      })
      .catch(() => showToast("Release failed", "error"));
  };

  const STATUS_VARIANT: Record<string, "success" | "warning" | "destructive" | "outline"> = {
    active: "warning",
    pending: "warning",
    confirmed: "success",
    released: "outline",
    completed: "outline",
    failed: "destructive",
  };

  return (
    <div className="flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Hand className="h-5 w-5 text-muted-foreground" />
        <div>
          <h2 className="text-lg font-semibold tracking-tight">
            {t.hitl?.title ?? "Human-in-the-Loop"}
          </h2>
          <p className="text-xs text-muted-foreground">
            {t.hitl?.subtitle ?? "Human takeover control"}
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
      {!loading && takeovers.length === 0 && (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-16">
            <CheckCircle2 className="h-10 w-10 text-success mb-3" />
            <p className="text-sm font-medium text-muted-foreground">
              {t.hitl?.empty ?? "No active takeovers"}
            </p>
          </CardContent>
        </Card>
      )}

      {/* Items */}
      {!loading && takeovers.length > 0 && (
        <div className="flex flex-col gap-2">
          {takeovers.map((ts, i) => (
            <Card key={ts.takeoverId ?? i}>
              <CardContent className="py-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-medium text-sm">
                        Takeover {ts.takeoverId?.substring(0, 8) ?? "Unknown"}
                      </span>
                      <Badge
                        variant={STATUS_VARIANT[ts.status ?? ""] ?? "outline"}
                        className="text-[10px]"
                      >
                        {ts.status ?? "pending"}
                      </Badge>
                    </div>
                    {ts.operatorId && (
                      <p className="text-xs text-muted-foreground mb-0.5">
                        {t.hitl?.operator ?? "Operator"}: {ts.operatorId}
                      </p>
                    )}
                    {ts.reason && (
                      <p className="text-xs text-muted-foreground/70">{ts.reason}</p>
                    )}
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-muted-foreground mt-1">
                      {ts.runId && (
                        <span className="font-mono-ui">Run: {ts.runId.slice(0, 12)}</span>
                      )}
                      {ts.createdAt && <span>{new Date(ts.createdAt).toLocaleString()}</span>}
                    </div>
                  </div>
                  <div className="flex items-center gap-1.5 shrink-0">
                    <Button
                      variant="outline"
                      size="sm"
                      className="h-7 text-xs"
                      onClick={() => confirm(ts.takeoverId!)}
                    >
                      <UserCheck className="h-3 w-3 mr-1" />
                      {t.hitl?.confirm ?? "Confirm"}
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 text-xs"
                      onClick={() => release(ts.takeoverId!)}
                    >
                      <UserX className="h-3 w-3 mr-1" />
                      {t.hitl?.release ?? "Release"}
                    </Button>
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
