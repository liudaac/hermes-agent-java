import { useState, useCallback } from "react";
import { GitBranch, Search, Clock, Cpu, Wrench, ChevronRight } from "lucide-react";
import { api } from "@/lib/api";
import type { TraceDetail, TraceStep } from "@/lib/api/types/ops";
import { Card, CardContent, CardHeader, CardTitle } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { Input } from "@hermes/ui";
import { cn } from "@hermes/ui";
import { useI18n } from "@/i18n";

const STATUS_VARIANT: Record<string, "success" | "warning" | "destructive" | "outline"> = {
  completed: "success",
  success: "success",
  running: "warning",
  pending: "outline",
  failed: "destructive",
  error: "destructive",
};

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}m`;
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleString();
}

function TraceStepView({ step, depth = 0 }: { step: TraceStep; depth?: number }) {
  const [expanded, setExpanded] = useState(depth < 2);

  const hasChildren = step.children && step.children.length > 0;

  return (
    <div className="flex flex-col">
      <div
        className={cn(
          "flex items-center gap-2 py-1.5 px-2 rounded-sm hover:bg-accent/20 cursor-pointer",
          depth > 0 && "ml-4 border-l border-border/40 pl-2",
        )}
        onClick={() => hasChildren && setExpanded(!expanded)}
      >
        {hasChildren ? (
          <ChevronRight className={cn("h-3 w-3 shrink-0 transition-transform", expanded && "rotate-90")} />
        ) : (
          <span className="w-3 shrink-0" />
        )}
        <span className="text-xs font-mono-ui shrink-0">
          {formatTime(step.startTime)}
        </span>
        <Cpu className="h-3 w-3 text-muted-foreground shrink-0" />
        <span className="text-xs truncate">{step.agentId}</span>
        {step.tool && (
          <>
            <Wrench className="h-3 w-3 text-muted-foreground shrink-0" />
            <span className="text-xs font-mono-ui text-muted-foreground truncate">{step.tool}</span>
          </>
        )}
        <span className="text-[10px] text-muted-foreground shrink-0 flex items-center gap-0.5">
          <Clock className="h-2.5 w-2.5" />
          {formatDuration(step.duration)}
        </span>
        <Badge
          variant={STATUS_VARIANT[step.status] ?? "outline"}
          className="text-[9px] shrink-0"
        >
          {step.status}
        </Badge>
      </div>
      {expanded && hasChildren && (
        <div className="flex flex-col">
          {step.children!.map((child, i) => (
            <TraceStepView key={i} step={child} depth={depth + 1} />
          ))}
        </div>
      )}
    </div>
  );
}

export default function TracesPage() {
  const [traceId, setTraceId] = useState("");
  const [trace, setTrace] = useState<TraceDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const { t } = useI18n();

  const search = useCallback(async () => {
    const id = traceId.trim();
    if (!id) return;
    setLoading(true);
    setNotFound(false);
    setTrace(null);
    try {
      const result = await api.getTrace(id);
      setTrace(result);
    } catch {
      setNotFound(true);
    } finally {
      setLoading(false);
    }
  }, [traceId]);

  return (
    <div className="flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <GitBranch className="h-5 w-5 text-muted-foreground" />
        <div>
          <h2 className="text-lg font-semibold tracking-tight">
            {t.traces?.title ?? "Traces"}
          </h2>
          <p className="text-xs text-muted-foreground">
            {t.traces?.subtitle ?? "Agent execution trace"}
          </p>
        </div>
      </div>

      {/* Search */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
          <Input
            className="pl-8 h-9 text-sm"
            placeholder={t.traces?.placeholder ?? "Enter Trace ID..."}
            value={traceId}
            onChange={(e) => setTraceId(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") search();
            }}
          />
        </div>
        <Button onClick={search} disabled={loading || !traceId.trim()} size="sm" className="h-9">
          <Search className="h-3.5 w-3.5 mr-1" />
          {t.traces?.search ?? "Search"}
        </Button>
      </div>

      {/* Loading */}
      {loading && (
        <Card>
          <CardContent className="py-12">
            <div className="flex items-center justify-center">
              <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            </div>
          </CardContent>
        </Card>
      )}

      {/* Not found */}
      {notFound && !loading && (
        <Card>
          <CardContent className="py-12 text-center">
            <GitBranch className="h-10 w-10 text-muted-foreground mx-auto mb-3" />
            <p className="text-sm text-muted-foreground">
              {t.traces?.notFound ?? "Trace not found"}
            </p>
          </CardContent>
        </Card>
      )}

      {/* Empty state */}
      {!loading && !notFound && !trace && (
        <Card>
          <CardContent className="py-12 text-center">
            <GitBranch className="h-10 w-10 text-muted-foreground mx-auto mb-3" />
            <p className="text-sm text-muted-foreground">
              {t.traces?.empty ?? "Enter a Trace ID to start"}
            </p>
          </CardContent>
        </Card>
      )}

      {/* Trace result */}
      {trace && !loading && (
        <>
          {/* Summary */}
          <Card>
            <CardHeader className="py-3 px-4">
              <CardTitle className="text-sm flex items-center gap-2">
                <GitBranch className="h-4 w-4" />
                <span className="font-mono-ui">{trace.traceId}</span>
                <Badge variant="outline" className="text-[10px] ml-auto">
                  {t.traces?.duration ?? "Duration"}: {formatDuration(trace.totalDuration)}
                </Badge>
                <Badge variant="outline" className="text-[10px]">
                  {t.traces?.steps ?? "Steps"}: {trace.steps.length}
                </Badge>
              </CardTitle>
            </CardHeader>
            <CardContent className="px-4 pb-3 text-[11px] text-muted-foreground">
              <div className="flex flex-wrap gap-x-4 gap-y-1">
                <span>Tenant: {trace.tenantId}</span>
                <span>Start: {formatTime(trace.startTime)}</span>
                <span>End: {formatTime(trace.endTime)}</span>
              </div>
            </CardContent>
          </Card>

          {/* Timeline */}
          <Card>
            <CardHeader className="py-3 px-4">
              <CardTitle className="text-sm">Timeline</CardTitle>
            </CardHeader>
            <CardContent className="px-2 pb-3">
              <div className="flex flex-col">
                {trace.steps.map((step, i) => (
                  <TraceStepView key={i} step={step} />
                ))}
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
