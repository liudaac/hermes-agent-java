import { useEffect, useState, useCallback } from "react";
import { RotateCcw, CheckCircle, AlertOctagon, RotateCw, Activity } from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/hooks/useToast";
import { useSse } from "@/hooks/useSse";
import { cn } from "@/lib/utils";

interface DLQItem {
  itemId: string;
  runId: string;
  taskTitle: string;
  reason: string;
  enqueuedAt: string;
  retryCount: number;
  status: "PENDING" | "RETRIED" | "RESOLVED";
}

export default function DLQPage() {
  const { showToast } = useToast();
  const [items, setItems] = useState<DLQItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState<string | null>(null);
  const [liveEvent, setLiveEvent] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      const res = await api.getDLQ();
      if (res.ok && Array.isArray(res.items)) {
        setItems(
          res.items.map((i: any) => ({
            itemId: i.itemId,
            runId: i.runId,
            taskTitle: i.taskTitle,
            reason: i.reason,
            enqueuedAt: i.enqueuedAt,
            retryCount: i.retryCount ?? 0,
            status: i.status,
          })),
        );
      } else {
        setItems([]);
      }
    } catch (e: any) {
      showToast(e?.message || "Failed to load DLQ", "error");
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    load();
  }, [load]);

  useSse({
    onEvent: (evt) => {
      if (["DLQ_ENQUEUE", "DLQ_STATUS_CHANGE", "RUN_STATUS"].includes(evt.type)) {
        setLiveEvent(`${evt.type} @ ${new Date().toLocaleTimeString()}`);
        load();
      }
    },
    onError: () => {},
  });

  const handleRetry = async (itemId: string) => {
    try {
      setActing(itemId);
      await api.retryDLQItem(itemId);
      showToast("Item marked for retry", "success");
      await load();
    } catch (e: any) {
      showToast(e?.message || "Retry failed", "error");
    } finally {
      setActing(null);
    }
  };

  const handleResolve = async (itemId: string) => {
    try {
      setActing(itemId);
      await api.resolveDLQItem(itemId);
      showToast("Item resolved", "success");
      await load();
    } catch (e: any) {
      showToast(e?.message || "Resolve failed", "error");
    } finally {
      setActing(null);
    }
  };

  const statusBadge = (status: string) => {
    const styles: Record<string, string> = {
      PENDING: "bg-amber-500/10 text-amber-500 border-amber-500/20",
      RETRIED: "bg-blue-500/10 text-blue-500 border-blue-500/20",
      RESOLVED: "bg-green-500/10 text-green-500 border-green-500/20",
    };
    return (
      <Badge variant="outline" className={cn("text-[0.6rem]", styles[status] || "")}>
        {status}
      </Badge>
    );
  };

  const pendingItems = items.filter((i) => i.status === "PENDING");
  const stats = {
    total: items.length,
    pending: pendingItems.length,
    retried: items.filter((i) => i.status === "RETRIED").length,
    resolved: items.filter((i) => i.status === "RESOLVED").length,
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <AlertOctagon className="h-5 w-5 opacity-70" />
          <H2 className="text-base tracking-[0.08em]">Dead Letter Queue</H2>
          {liveEvent && (
            <Badge variant="outline" className="text-[0.6rem] text-green-500 border-green-500/20 animate-pulse">
              <Activity className="h-3 w-3 mr-1" />
              {liveEvent}
            </Badge>
          )}
        </div>
        <div className="flex items-center gap-3">
          <div className="flex gap-2">
            <Badge variant="outline" className="text-[0.6rem]">
              Total {stats.total}
            </Badge>
            <Badge variant="outline" className="text-[0.6rem] text-amber-500 border-amber-500/20">
              Pending {stats.pending}
            </Badge>
            <Badge variant="outline" className="text-[0.6rem] text-green-500 border-green-500/20">
              Resolved {stats.resolved}
            </Badge>
          </div>
          <Button variant="outline" size="sm" onClick={load} disabled={loading} className="text-[0.65rem]">
            <RotateCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} />
            Refresh
          </Button>
        </div>
      </div>

      {loading ? (
        <div className="flex h-32 items-center justify-center text-sm opacity-60">
          <RotateCw className="mr-2 h-4 w-4 animate-spin" />
          Loading DLQ...
        </div>
      ) : pendingItems.length === 0 ? (
        <Card>
          <CardContent className="p-6 text-center space-y-2">
            <CheckCircle className="h-8 w-8 mx-auto text-green-500 opacity-60" />
            <div className="text-sm opacity-60">All clear! No pending dead letter items.</div>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          {pendingItems.map((item) => (
            <Card key={item.itemId} className="overflow-hidden">
              <CardContent className="p-4 space-y-3">
                <div className="flex items-start justify-between">
                  <div className="space-y-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <AlertOctagon className="h-4 w-4 text-red-500 shrink-0" />
                      <span className="font-medium text-sm">{item.taskTitle}</span>
                      {statusBadge(item.status)}
                    </div>
                    <div className="text-[0.65rem] opacity-50 font-mono">
                      {item.runId} / {item.itemId}
                    </div>
                  </div>
                </div>

                <div className="text-[0.7rem] opacity-70">
                  <span className="opacity-50">Reason:</span> {item.reason}
                </div>

                <div className="flex items-center justify-between text-[0.65rem] opacity-50">
                  <span>Retries: {item.retryCount}</span>
                  <span>{new Date(item.enqueuedAt).toLocaleString()}</span>
                </div>

                <div className="flex gap-2 pt-1">
                  <Button
                    size="sm"
                    variant="outline"
                    className="text-[0.65rem]"
                    disabled={acting === item.itemId}
                    onClick={() => handleRetry(item.itemId)}
                  >
                    <RotateCcw className="h-3 w-3 mr-1" />
                    {acting === item.itemId ? "Processing..." : "Retry"}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    className="text-[0.65rem]"
                    disabled={acting === item.itemId}
                    onClick={() => handleResolve(item.itemId)}
                  >
                    <CheckCircle className="h-3 w-3 mr-1" />
                    Resolve
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
