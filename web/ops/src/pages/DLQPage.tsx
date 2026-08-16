import { useEffect, useState, useCallback } from "react";
import { AlertOctagon, CheckCircle2, RefreshCw, Filter } from "lucide-react";
import { opsNocApi } from "@/lib/api/ops";
import { Card, CardContent } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { Input } from "@hermes/ui";
import { useI18n } from "@/i18n";
import { useToast } from "@/hooks/useToast";

interface DLQItem {
  itemId?: string;
  runId?: string;
  taskTitle?: string;
  error?: string;
  createdAt?: string;
  workspaceId?: string;
}

export default function DLQPage() {
  const [items, setItems] = useState<DLQItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("");
  const { t } = useI18n();
  const { showToast } = useToast();

  const reload = useCallback(() => {
    opsNocApi
      .getDLQ(filter || undefined)
      .then((r) => setItems((r.items ?? []) as DLQItem[]))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [filter]);

  useEffect(() => {
    reload();
    const interval = setInterval(reload, 30000);
    return () => clearInterval(interval);
  }, [reload]);

  const retry = (id: string) => {
    opsNocApi
      .retryDLQItem(id)
      .then(() => {
        showToast("Retried ✓", "success");
        reload();
      })
      .catch(() => showToast("Retry failed", "error"));
  };

  const resolve = (id: string) => {
    opsNocApi
      .resolveDLQItem(id)
      .then(() => {
        showToast("Resolved ✓", "success");
        reload();
      })
      .catch(() => showToast("Resolve failed", "error"));
  };

  return (
    <div className="flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <AlertOctagon className="h-5 w-5 text-muted-foreground" />
          <div>
            <h2 className="text-lg font-semibold tracking-tight">
              {t.dlq?.title ?? "Dead Letter Queue"}
            </h2>
            <p className="text-xs text-muted-foreground">
              {t.dlq?.subtitle ?? "Failed messages"}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="relative w-56">
            <Filter className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
            <Input
              className="pl-8 h-8 text-xs"
              placeholder={t.dlq?.filterWorkspace ?? "Filter by workspace..."}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
          </div>
          <Button variant="outline" size="sm" onClick={reload} className="h-8 text-xs">
            <RefreshCw className="h-3.5 w-3.5 mr-1" />
            {t.common.refresh}
          </Button>
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
      {!loading && items.length === 0 && (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-16">
            <CheckCircle2 className="h-10 w-10 text-success mb-3" />
            <p className="text-sm font-medium text-muted-foreground">
              {t.dlq?.empty ?? "Queue is empty ✓"}
            </p>
          </CardContent>
        </Card>
      )}

      {/* Items */}
      {!loading && items.length > 0 && (
        <div className="flex flex-col gap-2">
          {items.map((item, i) => (
            <Card key={item.itemId ?? i}>
              <CardContent className="py-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-medium text-sm truncate">
                        {item.taskTitle ?? item.itemId ?? "Unknown"}
                      </span>
                      <Badge variant="destructive" className="text-[10px]">failed</Badge>
                    </div>
                    {item.error && (
                      <p className="text-xs text-destructive/80 mb-1">{item.error}</p>
                    )}
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-muted-foreground">
                      {item.itemId && (
                        <span className="font-mono-ui">ID: {item.itemId.slice(0, 12)}</span>
                      )}
                      {item.runId && (
                        <span className="font-mono-ui">Run: {item.runId.slice(0, 12)}</span>
                      )}
                      {item.workspaceId && <span>WS: {item.workspaceId}</span>}
                      {item.createdAt && <span>{new Date(item.createdAt).toLocaleString()}</span>}
                    </div>
                  </div>
                  <div className="flex items-center gap-1.5 shrink-0">
                    <Button
                      variant="outline"
                      size="sm"
                      className="h-7 text-xs"
                      onClick={() => retry(item.itemId!)}
                    >
                      {t.dlq?.retry ?? "Retry"}
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 text-xs"
                      onClick={() => resolve(item.itemId!)}
                    >
                      {t.dlq?.resolve ?? "Resolve"}
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
