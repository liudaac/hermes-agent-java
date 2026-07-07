import { useState, useEffect, useCallback } from "react";
import {
  Activity,
  Clock,
  CheckCircle2,
  AlertCircle,
  ChevronDown,
  ChevronRight,
  Brain,
  Wrench,
  Zap,
  User,
  RefreshCw,
  ArrowLeft,
} from "lucide-react";
import { useParams, useNavigate } from "react-router-dom";
import { Badge } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Card, CardContent, CardHeader, CardTitle } from "@hermes/ui";
import { cn } from "@hermes/ui";

// S3-3: Trace Step 类型（对齐后端 AgentTrace.Step）
interface TraceStep {
  type: string;
  content: string;
  tokens: number;
  durationMs: number;
  toolUsed: string;
  confidence: number;
}

interface TraceDetail {
  ok: boolean;
  traceId: string;
  agentId: string;
  sessionId: string;
  task: string;
  status: string;
  startTime: string;
  endTime: string;
  totalTokens: number;
  estimatedCost: number;
  errorCount: number;
  timeline: string;
  steps: TraceStep[];
}

const STEP_ICONS: Record<string, typeof Brain> = {
  THINKING: Brain,
  TOOL_CALL: Wrench,
  TOOL_RESULT: CheckCircle2,
  DECISION: Zap,
  ERROR: AlertCircle,
  HUMAN_HANDOFF: User,
};

const STEP_COLORS: Record<string, string> = {
  THINKING: "text-violet-500",
  TOOL_CALL: "text-sky-500",
  TOOL_RESULT: "text-emerald-500",
  DECISION: "text-amber-500",
  ERROR: "text-rose-500",
  HUMAN_HANDOFF: "text-cyan-500",
};

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: "bg-emerald-500/10 text-emerald-600 border-emerald-500/30",
  FAILED: "bg-rose-500/10 text-rose-600 border-rose-500/30",
  CANCELLED: "bg-slate-500/10 text-slate-600 border-slate-500/30",
  TIMED_OUT: "bg-amber-500/10 text-amber-600 border-amber-500/30",
  PARTIAL: "bg-amber-500/10 text-amber-600 border-amber-500/30",
};

export default function TraceDetailPage() {
  const { traceId } = useParams<{ traceId: string }>();
  const navigate = useNavigate();
  const [trace, setTrace] = useState<TraceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedSteps, setExpandedSteps] = useState<Set<number>>(new Set());

  const fetchTrace = useCallback(async () => {
    if (!traceId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/traces/${traceId}`);
      if (!res.ok) {
        const data = await res.json();
        throw new Error(data.error || "Failed to load trace");
      }
      const data = await res.json();
      setTrace(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, [traceId]);

  useEffect(() => {
    fetchTrace();
  }, [fetchTrace]);

  const toggleStep = (idx: number) => {
    setExpandedSteps((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      return next;
    });
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center p-12 text-muted-foreground">
        <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
        加载 Trace...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 p-12">
        <AlertCircle className="h-8 w-8 text-rose-500" />
        <p className="text-sm text-muted-foreground">{error}</p>
        <Button variant="outline" onClick={() => navigate(-1)}>
          <ArrowLeft className="mr-2 h-4 w-4" /> 返回
        </Button>
      </div>
    );
  }

  if (!trace) return null;

  const durationMs = trace.startTime && trace.endTime
    ? new Date(trace.endTime).getTime() - new Date(trace.startTime).getTime()
    : 0;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="flex items-center gap-2 text-xl font-semibold">
              <Activity className="h-5 w-5 text-primary" />
              Trace {trace.traceId}
            </h1>
            <p className="text-sm text-muted-foreground">{trace.task || "No description"}</p>
          </div>
        </div>
        <Button variant="outline" size="sm" onClick={fetchTrace}>
          <RefreshCw className="mr-2 h-3.5 w-3.5" /> 刷新
        </Button>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <Card>
          <CardContent className="flex flex-col items-center p-4">
            <Clock className="mb-1 h-5 w-5 text-muted-foreground" />
            <span className="text-2xl font-bold">{(durationMs / 1000).toFixed(1)}s</span>
            <span className="text-xs text-muted-foreground">耗时</span>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex flex-col items-center p-4">
            <Brain className="mb-1 h-5 w-5 text-muted-foreground" />
            <span className="text-2xl font-bold">{trace.totalTokens.toLocaleString()}</span>
            <span className="text-xs text-muted-foreground">Tokens</span>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex flex-col items-center p-4">
            <span className="mb-1 text-2xl font-bold text-amber-600">
              ${trace.estimatedCost.toFixed(4)}
            </span>
            <span className="text-xs text-muted-foreground">成本</span>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex flex-col items-center p-4">
            <span
              className={cn(
                "mb-1 inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium",
                STATUS_COLORS[trace.status] || STATUS_COLORS.SUCCESS
              )}
            >
              {trace.status}
            </span>
            <span className="text-xs text-muted-foreground">
              {trace.errorCount > 0 ? `${trace.errorCount} 错误` : "无错误"}
            </span>
          </CardContent>
        </Card>
      </div>

      {/* Meta Info */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">元信息</CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-2 text-xs md:grid-cols-4">
          <div>
            <span className="text-muted-foreground">Agent:</span>{" "}
            <span className="font-mono">{trace.agentId}</span>
          </div>
          <div>
            <span className="text-muted-foreground">Session:</span>{" "}
            <span className="font-mono">{trace.sessionId}</span>
          </div>
          <div>
            <span className="text-muted-foreground">开始:</span>{" "}
            <span>{trace.startTime ? new Date(trace.startTime).toLocaleString() : "-"}</span>
          </div>
          <div>
            <span className="text-muted-foreground">结束:</span>{" "}
            <span>{trace.endTime ? new Date(trace.endTime).toLocaleString() : "-"}</span>
          </div>
        </CardContent>
      </Card>

      {/* Steps Timeline */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-sm">
            <Activity className="h-4 w-4" />
            执行步骤 ({trace.steps.length})
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-1">
          {trace.steps.length === 0 && (
            <div className="py-6 text-center text-sm text-muted-foreground">
              暂无步骤数据
            </div>
          )}
          {trace.steps.map((step, idx) => {
            const Icon = STEP_ICONS[step.type] || Zap;
            const color = STEP_COLORS[step.type] || "text-muted-foreground";
            const expanded = expandedSteps.has(idx);
            return (
              <div key={idx} className="relative">
                {/* 连接线 */}
                {idx < trace.steps.length - 1 && (
                  <div className="absolute left-[15px] top-7 h-full w-px bg-gradient-to-b from-border to-transparent" />
                )}
                <div
                  className="group flex cursor-pointer items-start gap-3 rounded-md p-2 transition-colors hover:bg-muted/40"
                  onClick={() => toggleStep(idx)}
                >
                  <div className={cn("mt-0.5 flex-shrink-0", color)}>
                    <Icon className="h-4 w-4" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-medium text-foreground">
                        {step.type}
                      </span>
                      {step.toolUsed && (
                        <Badge variant="secondary" className="px-1.5 py-0 text-[10px]">
                          {step.toolUsed}
                        </Badge>
                      )}
                      {step.tokens > 0 && (
                        <span className="text-[10px] text-muted-foreground">
                          {step.tokens} tokens
                        </span>
                      )}
                      {step.durationMs > 0 && (
                        <span className="text-[10px] text-muted-foreground">
                          {step.durationMs}ms
                        </span>
                      )}
                      {step.confidence > 0 && (
                        <span className="text-[10px] text-muted-foreground">
                          {Math.round(step.confidence * 100)}%
                        </span>
                      )}
                      <span className="ml-auto">
                        {expanded ? (
                          <ChevronDown className="h-3 w-3 text-muted-foreground" />
                        ) : (
                          <ChevronRight className="h-3 w-3 text-muted-foreground" />
                        )}
                      </span>
                    </div>
                    <p
                      className={cn(
                        "mt-0.5 text-xs text-muted-foreground",
                        !expanded && "line-clamp-2"
                      )}
                    >
                      {step.content}
                    </p>
                  </div>
                </div>
              </div>
            );
          })}
        </CardContent>
      </Card>

      {/* Raw Timeline (collapsible) */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">原始 Timeline</CardTitle>
        </CardHeader>
        <CardContent>
          <pre className="max-h-96 overflow-auto rounded-md bg-muted/50 p-3 text-xs">
            {trace.timeline}
          </pre>
        </CardContent>
      </Card>
    </div>
  );
}
