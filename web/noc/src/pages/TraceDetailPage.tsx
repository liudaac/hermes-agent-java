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
  ShieldCheck,
  RotateCw,
  RefreshCw,
  ArrowLeft,
  ArrowRight,
} from "lucide-react";
import { useParams, useNavigate } from "react-router-dom";
import { Badge } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { Card, CardContent, CardHeader, CardTitle } from "@hermes/ui";
import { cn } from "@hermes/ui";

// Span-based trace (matches F2 ExecutionTrace.toApi())
interface TraceSpan {
  spanId: string;
  name: string;
  type: string; // model_call, tool_call, agent_message, error
  startTime: string;
  endTime?: string;
  durationMs: number;
  attributes: Record<string, unknown>;
}

interface SpanTraceDetail {
  traceId: string;
  tenantId?: string;
  agentId?: string;
  sessionId?: string;
  status: string; // RUNNING, COMPLETED, FAILED
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  spans: TraceSpan[];
}

// Legacy trace format (old AgentTrace)
interface LegacyTraceStep {
  type: string;
  content: string;
  tokens: number;
  durationMs: number;
  toolUsed: string;
  confidence: number;
}

interface LegacyTraceDetail {
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
  steps: LegacyTraceStep[];
}

// Phase grouping for chain traces
interface PhaseGroup {
  phase: string;
  phaseLabel: string;
  icon: typeof Brain;
  color: string;
  span: TraceSpan;
  childSpans: TraceSpan[];
}

function groupSpans(spans: TraceSpan[]): PhaseGroup[] | null {
  // Detect chain mode: has planner/executor/reviewer spans
  const hasChainSpans = spans.some(s =>
    s.name === "planner" || s.name === "executor" || s.name === "reviewer"
  );
  if (!hasChainSpans) return null;

  const PHASE_META: Record<string, { label: string; icon: typeof Brain; color: string }> = {
    planner: { label: "规划", icon: Brain, color: "text-violet-400" },
    executor: { label: "执行", icon: Wrench, color: "text-sky-400" },
    reviewer: { label: "评审", icon: ShieldCheck, color: "text-emerald-400" },
  };

  const groups: PhaseGroup[] = [];
  let current: PhaseGroup | null = null;

  for (const span of spans) {
    const name = span.name;
    if (name === "planner" || name === "executor" || name === "reviewer") {
      if (current) groups.push(current);
      const meta = PHASE_META[name] || { label: name, icon: Zap, color: "text-muted-foreground" };
      current = { phase: name, phaseLabel: meta.label, icon: meta.icon, color: meta.color, span, childSpans: [] };
    } else if (current) {
      current.childSpans.push(span);
    } else {
      // Span before any phase - create a misc group
      current = { phase: "other", phaseLabel: name, icon: Zap, color: "text-muted-foreground", span, childSpans: [] };
    }
  }
  if (current) groups.push(current);
  return groups;
}

const SPAN_TYPE_ICONS: Record<string, typeof Brain> = {
  model_call: Brain,
  tool_call: Wrench,
  agent_message: Zap,
  error: AlertCircle,
};

const SPAN_TYPE_COLORS: Record<string, string> = {
  model_call: "text-violet-400",
  tool_call: "text-sky-400",
  agent_message: "text-amber-400",
  error: "text-rose-400",
};

const STATUS_COLORS: Record<string, string> = {
  COMPLETED: "bg-emerald-500/10 text-emerald-600 border-emerald-500/30",
  RUNNING: "bg-sky-500/10 text-sky-600 border-sky-500/30",
  FAILED: "bg-rose-500/10 text-rose-600 border-rose-500/30",
  SUCCESS: "bg-emerald-500/10 text-emerald-600 border-emerald-500/30",
};

export default function TraceDetailPage() {
  const { traceId } = useParams<{ traceId: string }>();
  const navigate = useNavigate();
  const [trace, setTrace] = useState<SpanTraceDetail | null>(null);
  const [legacy, setLegacy] = useState<LegacyTraceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedSpans, setExpandedSpans] = useState<Set<string>>(new Set());

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

      // Detect format: chain (spans-based) vs legacy (steps-based)
      if (data.spans && Array.isArray(data.spans)) {
        setTrace(data);
        setLegacy(null);
      } else if (data.steps && Array.isArray(data.steps)) {
        setLegacy(data);
        setTrace(null);
      } else {
        // Unknown format, try as span trace
        setTrace(data);
        setLegacy(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, [traceId]);

  useEffect(() => {
    fetchTrace();
  }, [fetchTrace]);

  const toggleSpan = (spanId: string) => {
    setExpandedSpans((prev) => {
      const next = new Set(prev);
      if (next.has(spanId)) next.delete(spanId);
      else next.add(spanId);
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

  // ============ Legacy trace rendering ============
  if (legacy) {
    const durationMs = legacy.startTime && legacy.endTime
      ? new Date(legacy.endTime).getTime() - new Date(legacy.startTime).getTime()
      : 0;

    const LEGACY_ICONS: Record<string, typeof Brain> = {
      THINKING: Brain, TOOL_CALL: Wrench, TOOL_RESULT: CheckCircle2,
      DECISION: Zap, ERROR: AlertCircle, HUMAN_HANDOFF: Activity,
    };
    const LEGACY_COLORS: Record<string, string> = {
      THINKING: "text-violet-500", TOOL_CALL: "text-sky-500",
      TOOL_RESULT: "text-emerald-500", DECISION: "text-amber-500",
      ERROR: "text-rose-500", HUMAN_HANDOFF: "text-cyan-500",
    };

    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
              <ArrowLeft className="h-4 w-4" />
            </Button>
            <div>
              <h1 className="flex items-center gap-2 text-xl font-semibold">
                <Activity className="h-5 w-5 text-primary" />
                Trace {legacy.traceId}
              </h1>
              <p className="text-sm text-muted-foreground">{legacy.task || "No description"}</p>
            </div>
          </div>
          <Button variant="outline" size="sm" onClick={fetchTrace}>
            <RefreshCw className="mr-2 h-3.5 w-3.5" /> 刷新
          </Button>
        </div>

        <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
          <Card><CardContent className="flex flex-col items-center p-4">
            <Clock className="mb-1 h-5 w-5 text-muted-foreground" />
            <span className="text-2xl font-bold">{(durationMs / 1000).toFixed(1)}s</span>
            <span className="text-xs text-muted-foreground">耗时</span>
          </CardContent></Card>
          <Card><CardContent className="flex flex-col items-center p-4">
            <Brain className="mb-1 h-5 w-5 text-muted-foreground" />
            <span className="text-2xl font-bold">{legacy.totalTokens.toLocaleString()}</span>
            <span className="text-xs text-muted-foreground">Tokens</span>
          </CardContent></Card>
          <Card><CardContent className="flex flex-col items-center p-4">
            <span className="mb-1 text-2xl font-bold text-amber-600">${legacy.estimatedCost.toFixed(4)}</span>
            <span className="text-xs text-muted-foreground">成本</span>
          </CardContent></Card>
          <Card><CardContent className="flex flex-col items-center p-4">
            <span className={cn("mb-1 inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium",
              STATUS_COLORS[legacy.status] || STATUS_COLORS.SUCCESS)}>
              {legacy.status}
            </span>
            <span className="text-xs text-muted-foreground">{legacy.errorCount > 0 ? `${legacy.errorCount} 错误` : "无错误"}</span>
          </CardContent></Card>
        </div>

        <Card>
          <CardHeader><CardTitle className="text-sm">执行步骤 ({legacy.steps.length})</CardTitle></CardHeader>
          <CardContent className="space-y-1">
            {legacy.steps.map((step, idx) => {
              const Icon = LEGACY_ICONS[step.type] || Zap;
              const color = LEGACY_COLORS[step.type] || "text-muted-foreground";
              const expanded = expandedSpans.has(String(idx));
              return (
                <div key={idx} className="relative">
                  {idx < legacy.steps.length - 1 && (
                    <div className="absolute left-[15px] top-7 h-full w-px bg-gradient-to-b from-border to-transparent" />
                  )}
                  <div className="group flex cursor-pointer items-start gap-3 rounded-md p-2 transition-colors hover:bg-muted/40"
                    onClick={() => toggleSpan(String(idx))}>
                    <div className={cn("mt-0.5 flex-shrink-0", color)}><Icon className="h-4 w-4" /></div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-medium">{step.type}</span>
                        {step.toolUsed && <Badge variant="secondary" className="px-1.5 py-0 text-[10px]">{step.toolUsed}</Badge>}
                        {step.tokens > 0 && <span className="text-[10px] text-muted-foreground">{step.tokens} tokens</span>}
                        {step.durationMs > 0 && <span className="text-[10px] text-muted-foreground">{step.durationMs}ms</span>}
                      </div>
                      <p className={cn("mt-0.5 text-xs text-muted-foreground", !expanded && "line-clamp-2")}>{step.content}</p>
                    </div>
                  </div>
                </div>
              );
            })}
          </CardContent>
        </Card>
      </div>
    );
  }

  // ============ Chain trace rendering (span-based) ============
  if (!trace) return null;

  const phaseGroups = groupSpans(trace.spans) || [];
  const isChain = phaseGroups.length > 0;
  const durationMs = trace.durationMs || 0;

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
            <p className="text-sm text-muted-foreground">
              {isChain ? "编排链追踪" : "执行追踪"}
              {trace.agentId && ` · Agent: ${trace.agentId}`}
            </p>
          </div>
        </div>
        <Button variant="outline" size="sm" onClick={fetchTrace}>
          <RefreshCw className="mr-2 h-3.5 w-3.5" /> 刷新
        </Button>
      </div>

      {/* Summary */}
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
            <Activity className="mb-1 h-5 w-5 text-muted-foreground" />
            <span className="text-2xl font-bold">{trace.spans.length}</span>
            <span className="text-xs text-muted-foreground">Spans</span>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex flex-col items-center p-4">
            <span className="mb-1 text-2xl font-bold text-violet-500">
              {phaseGroups.length}
            </span>
            <span className="text-xs text-muted-foreground">阶段</span>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex flex-col items-center p-4">
            <span className={cn("mb-1 inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium",
              STATUS_COLORS[trace.status] || STATUS_COLORS.RUNNING)}>
              {trace.status}
            </span>
            <span className="text-xs text-muted-foreground">
              {trace.status === "RUNNING" ? "进行中..." : "已结束"}
            </span>
          </CardContent>
        </Card>
      </div>

      {/* Meta info */}
      <Card>
        <CardHeader><CardTitle className="text-sm">元信息</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-2 gap-2 text-xs md:grid-cols-4">
          {trace.tenantId && (
            <div><span className="text-muted-foreground">Tenant:</span> <span className="font-mono">{trace.tenantId}</span></div>
          )}
          {trace.agentId && (
            <div><span className="text-muted-foreground">Agent:</span> <span className="font-mono">{trace.agentId}</span></div>
          )}
          {trace.sessionId && (
            <div><span className="text-muted-foreground">Session:</span> <span className="font-mono">{trace.sessionId}</span></div>
          )}
          {trace.startTime && (
            <div><span className="text-muted-foreground">开始:</span> {new Date(trace.startTime).toLocaleString()}</div>
          )}
        </CardContent>
      </Card>

      {/* Chain phase timeline */}
      {isChain && phaseGroups.map((group, gIdx) => {
        const Icon = group.icon;
        const isComplete = !!group.span.endTime;

        return (
          <Card key={gIdx}>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Icon className={cn("h-4 w-4", group.color)} />
                {group.phaseLabel}
                {isComplete ? (
                  <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
                ) : (
                  <RefreshCw className="h-3.5 w-3.5 animate-spin text-sky-500" />
                )}
                <span className="ml-auto text-[10px] font-normal text-muted-foreground">
                  {(group.span.durationMs / 1000).toFixed(1)}s
                </span>
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-1">
              {/* Phase attributes */}
              {group.span.attributes && Object.keys(group.span.attributes).length > 0 && (
                <div className="mb-2 flex flex-wrap gap-2 rounded-md bg-muted/30 p-2">
                  {Object.entries(group.span.attributes).map(([k, v]) => (
                    <span key={k} className="text-[10px] text-muted-foreground">
                      <span className="font-medium">{k}:</span> {String(v)}
                    </span>
                  ))}
                </div>
              )}

              {/* Child spans (steps, retries) */}
              {group.childSpans.map((child) => {
                const childColor = SPAN_TYPE_COLORS[child.type] || "text-muted-foreground";
                const expanded = expandedSpans.has(child.spanId);
                const isRetry = child.name.startsWith("retry:");
                const childComplete = !!child.endTime;

                return (
                  <div key={child.spanId} className="relative">
                    <div
                      className="group flex cursor-pointer items-start gap-3 rounded-md p-2 transition-colors hover:bg-muted/40"
                      onClick={() => toggleSpan(child.spanId)}
                    >
                      <div className={cn("mt-0.5 flex-shrink-0", childColor)}>
                        {isRetry ? <RotateCw className="h-3.5 w-3.5" /> : <childIcon className="h-3.5 w-3.5" />}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-medium">{child.name}</span>
                          <Badge variant="secondary" className="px-1.5 py-0 text-[10px]">{child.type}</Badge>
                          {childComplete ? (
                            <CheckCircle2 className="h-3 w-3 text-emerald-500" />
                          ) : (
                            <RefreshCw className="h-3 w-3 animate-spin text-sky-500" />
                          )}
                          <span className="text-[10px] text-muted-foreground">
                            {child.durationMs}ms
                          </span>
                          <span className="ml-auto">
                            {expanded ? <ChevronDown className="h-3 w-3 text-muted-foreground" />
                                      : <ChevronRight className="h-3 w-3 text-muted-foreground" />}
                          </span>
                        </div>
                        {/* Attributes */}
                        {expanded && child.attributes && Object.keys(child.attributes).length > 0 && (
                          <div className="mt-1 flex flex-wrap gap-2 pl-2">
                            {Object.entries(child.attributes).map(([k, v]) => (
                              <span key={k} className="text-[10px] text-muted-foreground">
                                <ArrowRight className="mr-0.5 inline h-2.5 w-2.5" />
                                {k}: {String(v).substring(0, 100)}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </CardContent>
          </Card>
        );
      })}

      {/* Flat span list (non-chain traces) */}
      {!isChain && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Spans ({trace.spans.length})</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1">
            {trace.spans.map((span) => {
              const Icon = SPAN_TYPE_ICONS[span.type] || Zap;
              const color = SPAN_TYPE_COLORS[span.type] || "text-muted-foreground";
              const expanded = expandedSpans.has(span.spanId);
              return (
                <div key={span.spanId} className="relative">
                  <div className="group flex cursor-pointer items-start gap-3 rounded-md p-2 transition-colors hover:bg-muted/40"
                    onClick={() => toggleSpan(span.spanId)}>
                    <div className={cn("mt-0.5 flex-shrink-0", color)}><Icon className="h-4 w-4" /></div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-medium">{span.name}</span>
                        <Badge variant="secondary" className="px-1.5 py-0 text-[10px]">{span.type}</Badge>
                        <span className="text-[10px] text-muted-foreground">{span.durationMs}ms</span>
                        <span className="ml-auto">
                          {expanded ? <ChevronDown className="h-3 w-3 text-muted-foreground" />
                                    : <ChevronRight className="h-3 w-3 text-muted-foreground" />}
                        </span>
                      </div>
                      {expanded && span.attributes && Object.keys(span.attributes).length > 0 && (
                        <div className="mt-1 flex flex-wrap gap-2 pl-2">
                          {Object.entries(span.attributes).map(([k, v]) => (
                            <span key={k} className="text-[10px] text-muted-foreground">
                              {k}: {String(v).substring(0, 100)}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
