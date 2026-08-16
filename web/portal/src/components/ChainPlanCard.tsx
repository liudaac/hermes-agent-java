import { useState } from "react";
import {
  Goal,
  ListChecks,
  CheckCircle2,
  Circle,
  Loader2,
  RotateCw,
  ChevronDown,
  ChevronRight,
  Wrench,
  Brain,
  MessageSquare,
  ShieldCheck,
  ArrowRight,
} from "lucide-react";
import { GlassCard } from "@/components/GlassCard";
import { cn } from "@hermes/ui";

export interface ChainPlanData {
  output: string;
  traceId: string;
  goal: string;
  stepCount: number;
  passthrough: boolean;
  steps?: ChainPlanStep[];
  successCriteria: string[];
  status: string;
  durationMs: number;
  spanCount: number;
}

export interface ChainPlanStep {
  id: string;
  action: string;
  tool: string;
  dependsOn: string[];
}

export interface TraceSpan {
  spanId: string;
  name: string;
  type: string;
  startTime: string;
  endTime?: string;
  durationMs: number;
  attributes: Record<string, unknown>;
}

export interface TraceData {
  traceId: string;
  status: string;
  startTime: string;
  endTime?: string;
  durationMs: number;
  spans: TraceSpan[];
}

type Phase = "planner" | "executor" | "reviewer" | "retry";
interface PhaseGroup {
  phase: Phase;
  span: TraceSpan;
  stepSpans: TraceSpan[];
  retrySpans: TraceSpan[];
}

function groupSpansByPhase(spans: TraceSpan[]): PhaseGroup[] {
  const groups: PhaseGroup[] = [];
  let current: PhaseGroup | null = null;

  for (const span of spans) {
    const name = span.name;

    if (name === "planner" || name === "executor" || name === "reviewer") {
      if (current) groups.push(current);
      current = { phase: name as Phase, span, stepSpans: [], retrySpans: [] };
    } else if (name.startsWith("step:") && current) {
      current.stepSpans.push(span);
    } else if (name.startsWith("retry:") && current) {
      current.retrySpans.push(span);
    } else if (current) {
      current.stepSpans.push(span);
    }
  }
  if (current) groups.push(current);
  return groups;
}

const PHASE_CONFIG = {
  planner: { label: "规划", icon: Brain, color: "text-violet-500" },
  executor: { label: "执行", icon: Wrench, color: "text-sky-500" },
  reviewer: { label: "评审", icon: ShieldCheck, color: "text-emerald-500" },
  retry: { label: "重试", icon: RotateCw, color: "text-amber-500" },
} as const;

interface ChainPlanCardProps {
  plan: ChainPlanData;
  trace?: TraceData | null;
}

export function ChainPlanCard({ plan, trace }: ChainPlanCardProps) {
  const [expandedSteps, setExpandedSteps] = useState<Set<string>>(new Set());

  const toggleStep = (id: string) => {
    setExpandedSteps((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const stepStatus = (stepId: string): "pending" | "running" | "done" | "failed" | "retrying" => {
    if (!trace) return "pending";
    const stepSpan = trace.spans.find((s) => s.name === `step:${stepId}`);
    if (!stepSpan) return "pending";
    if (stepSpan.endTime) return "done";
    return "running";
  };

  const hasRetry = (stepId: string): boolean => {
    if (!trace) return false;
    return trace.spans.some((s) => s.name === `retry:${stepId}`);
  };

  const phaseGroups = trace ? groupSpansByPhase(trace.spans) : [];

  if (plan.passthrough) {
    return (
      <GlassCard className="mb-4 p-4">
        <div className="flex items-center gap-2 text-[13px] text-muted-foreground">
          <MessageSquare className="h-4 w-4 text-primary" />
          <span>直接执行（未拆分计划）</span>
        </div>
        <p className="mt-2 text-[12px] text-muted-foreground">
          {plan.goal}
        </p>
      </GlassCard>
    );
  }

  return (
    <div className="mb-4 space-y-3">
      {/* Goal card */}
      <GlassCard className="p-4">
        <div className="flex items-start gap-2">
          <Goal className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
          <div className="min-w-0">
            <h3 className="text-[11px] font-semibold tracking-[0.18em] uppercase text-muted-foreground">
              任务目标
            </h3>
            <p className="mt-1 text-[14px] font-medium text-foreground">
              {plan.goal}
            </p>
          </div>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-3 border-t border-border pt-2 text-[10px] text-muted-foreground">
          <span className="inline-flex items-center gap-1">
            <ListChecks className="h-3 w-3" />
            {plan.stepCount} 步
          </span>
          {plan.durationMs > 0 && (
            <span className="inline-flex items-center gap-1">
              <Circle className="h-2 w-2" />
              {(plan.durationMs / 1000).toFixed(1)}s
            </span>
          )}
          {plan.traceId && (
            <span className="inline-flex items-center gap-1 font-mono">
              trace: {plan.traceId.substring(0, 20)}...
            </span>
          )}
        </div>
      </GlassCard>

      {/* Plan steps */}
      {plan.steps && plan.steps.length > 0 && (
        <section>
          <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-muted-foreground">
            执行计划
          </h3>
          <GlassCard className="space-y-1">
            {plan.steps.map((step, idx) => {
              const status = stepStatus(step.id);
              const expanded = expandedSteps.has(step.id);
              const retried = hasRetry(step.id);
              return (
                <div key={step.id}>
                  <div
                    className="group flex cursor-pointer items-start gap-3 rounded-md p-2 transition-colors hover:bg-muted"
                    onClick={() => toggleStep(step.id)}
                  >
                    <div className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center">
                      {status === "done" ? (
                        <CheckCircle2 className="h-4 w-4 text-success" />
                      ) : status === "running" ? (
                        <Loader2 className="h-4 w-4 animate-spin text-info" />
                      ) : status === "retrying" ? (
                        <RotateCw className="h-4 w-4 text-warning" />
                      ) : (
                        <Circle className="h-4 w-4 text-muted-foreground" />
                      )}
                    </div>

                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="text-[10px] font-mono text-muted-foreground">
                          {step.id}
                        </span>
                        <span className="text-[13px] font-medium text-foreground">
                          {step.action}
                        </span>
                        {retried && (
                          <span className="inline-flex items-center gap-0.5 rounded bg-warning/10 px-1 py-0.5 text-[9px] text-warning">
                            <RotateCw className="h-2.5 w-2.5" />
                            重试
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

                      {step.tool && (
                        <span className="mt-1 inline-flex items-center gap-1 rounded bg-info/10 px-1.5 py-0.5 text-[10px] text-info">
                          <Wrench className="h-2.5 w-2.5" />
                          {step.tool}
                        </span>
                      )}

                      {expanded && step.dependsOn.length > 0 && (
                        <div className="mt-2 flex items-center gap-1 text-[11px] text-muted-foreground">
                          <span>依赖:</span>
                          {step.dependsOn.map((dep) => (
                            <span key={dep} className="inline-flex items-center gap-0.5">
                              <ArrowRight className="h-2.5 w-2.5" />
                              <span className="font-mono">{dep}</span>
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>

                  {idx < (plan.steps?.length ?? 0) - 1 && (
                    <div className="ml-[18px] h-3 w-px bg-border" />
                  )}
                </div>
              );
            })}
          </GlassCard>
        </section>
      )}

      {/* Success criteria */}
      {plan.successCriteria && plan.successCriteria.length > 0 && (
        <section>
          <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-muted-foreground">
            完成标准
          </h3>
          <GlassCard className="space-y-1">
            {plan.successCriteria.map((criteria, idx) => (
              <div key={idx} className="flex items-start gap-2 text-[13px]">
                <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success/60" />
                <span className="text-muted-foreground">{criteria}</span>
              </div>
            ))}
          </GlassCard>
        </section>
      )}

      {/* Phase timeline */}
      {phaseGroups.length > 0 && (
        <section>
          <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-muted-foreground">
            阶段追踪
          </h3>
          <GlassCard className="space-y-2">
            {phaseGroups.map((group, idx) => {
              const config = PHASE_CONFIG[group.phase];
              const Icon = config.icon;
              const isComplete = !!group.span.endTime;
              const hasRetries = group.retrySpans.length > 0;

              return (
                <div key={idx} className="flex items-start gap-3">
                  <Icon className={cn("mt-0.5 h-4 w-4 shrink-0", config.color)} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-[13px] font-medium text-foreground">
                        {config.label}
                      </span>
                      {isComplete ? (
                        <CheckCircle2 className="h-3 w-3 text-success" />
                      ) : (
                        <Loader2 className="h-3 w-3 animate-spin text-info" />
                      )}
                      <span className="text-[10px] text-muted-foreground">
                        {(group.span.durationMs / 1000).toFixed(1)}s
                      </span>
                      {hasRetries && (
                        <span className="inline-flex items-center gap-0.5 rounded bg-warning/10 px-1 py-0.5 text-[9px] text-warning">
                          <RotateCw className="h-2.5 w-2.5" />
                          {group.retrySpans.length} 次重试
                        </span>
                      )}
                    </div>

                    {group.span.attributes && Object.keys(group.span.attributes).length > 0 && (
                      <div className="mt-1 flex flex-wrap gap-2">
                        {Object.entries(group.span.attributes).map(([k, v]) => (
                          <span key={k} className="text-[10px] text-muted-foreground">
                            {k}: {String(v)}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </GlassCard>
        </section>
      )}

      {/* Final output */}
      {plan.output && (
        <section>
          <h3 className="mb-2 text-[12px] font-semibold tracking-[0.18em] uppercase text-muted-foreground">
            最终输出
          </h3>
          <GlassCard className="p-3">
            <pre className="whitespace-pre-wrap text-[13px] leading-relaxed text-foreground">
              {plan.output}
            </pre>
          </GlassCard>
        </section>
      )}
    </div>
  );
}
