import { useState } from "react";
import {
  ChevronDown,
  ChevronUp,
  Clock,
  CheckCircle2,
  AlertCircle,
  PlayCircle,
  Users,
  type LucideIcon,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { BusinessRunStep } from "@/lib/api";

interface RunStoryTimelineProps {
  steps?: BusinessRunStep[];
  className?: string;
  collapsedCount?: number;
}

/**
 * Business-facing visualization of a run's execution steps.
 * Replaces the raw-JSON dump with a colored timeline that reads like
 * a story: who did what, in what order, and with what result.
 */
export default function RunStoryTimeline({
  steps,
  className,
  collapsedCount = 4,
}: RunStoryTimelineProps) {
  const [expanded, setExpanded] = useState(false);
  if (!steps || steps.length === 0) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-dashed border-border/70 p-3 text-xs text-muted-foreground">
        <Clock className="h-3.5 w-3.5" />
        暂无执行步骤
      </div>
    );
  }

  const visible = expanded ? steps : steps.slice(0, collapsedCount);
  const hidden = steps.length - visible.length;

  return (
    <div className={cn("space-y-0", className)}>
      <div className="mb-2 flex items-center gap-2 text-xs uppercase tracking-wider text-muted-foreground">
        <Users className="h-3 w-3" />
        <span>协作时间线 · {steps.length} 步</span>
      </div>

      <ol className="relative space-y-1">
        {/* connector line */}
        <div className="absolute left-[15px] top-2 bottom-2 w-px bg-border" aria-hidden />
        {visible.map((step, i) => (
          <TimelineStep key={step.stepId ?? `${i}`} step={step} index={i} />
        ))}
      </ol>

      {hidden > 0 && !expanded && (
        <button
          onClick={() => setExpanded(true)}
          className="mt-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <ChevronDown className="h-3.5 w-3.5" />
          展开 {hidden} 个步骤
        </button>
      )}
      {expanded && steps.length > collapsedCount && (
        <button
          onClick={() => setExpanded(false)}
          className="mt-2 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <ChevronUp className="h-3.5 w-3.5" />
          收起
        </button>
      )}
    </div>
  );
}

interface StatusVisual {
  icon: LucideIcon;
  className: string;
  badge: "success" | "warning" | "destructive" | "outline" | "info";
}

function statusVisual(status?: string): StatusVisual {
  const s = (status ?? "").toUpperCase();
  if (["COMPLETED", "SUCCESS", "DONE", "OK"].includes(s)) {
    return { icon: CheckCircle2, className: "text-emerald-500", badge: "success" };
  }
  if (["RUNNING", "PROCESSING", "IN_PROGRESS"].includes(s)) {
    return { icon: PlayCircle, className: "text-sky-500 animate-pulse", badge: "info" };
  }
  if (["FAILED", "ERROR"].includes(s)) {
    return { icon: AlertCircle, className: "text-rose-500", badge: "destructive" };
  }
  if (["RETRY", "RETRIED", "PENDING"].includes(s)) {
    return { icon: Clock, className: "text-amber-500", badge: "warning" };
  }
  return { icon: Clock, className: "text-muted-foreground", badge: "outline" };
}

function TimelineStep({ step, index }: { step: BusinessRunStep; index: number }) {
  const visual = statusVisual(step.status);
  const Icon = visual.icon;
  const actor = step.actor ?? step.agentId ?? `Step ${index + 1}`;
  const action = step.title ?? step.summary ?? "—";
  return (
    <li className="relative flex gap-3 pl-0">
      <div className="relative z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-background ring-1 ring-border">
        <Icon className={cn("h-3.5 w-3.5", visual.className)} />
      </div>
      <div className="min-w-0 flex-1 pb-3 pt-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium tracking-tight">{actor}</span>
          {step.status && (
            <Badge variant={visual.badge} className="text-xs uppercase tracking-wider">
              {step.status}
            </Badge>
          )}
          {step.retry && (
            <Badge variant="warning" className="text-xs uppercase tracking-wider">
              Retry
            </Badge>
          )}
          {typeof step.score === "number" && (
            <span className="font-mono text-xs text-muted-foreground">
              score {step.score.toFixed(2)}
            </span>
          )}
          {step.timestamp && (
            <span className="font-mono text-xs text-muted-foreground">
              {formatTime(step.timestamp)}
            </span>
          )}
        </div>
        <p className="mt-0.5 text-sm text-muted-foreground leading-relaxed">{action}</p>
        {step.summary && step.title && step.summary !== step.title && (
          <p className="mt-0.5 text-xs text-muted-foreground/80 leading-relaxed">{step.summary}</p>
        )}
        {step.matchedSkills && (
          <div className="mt-1 flex flex-wrap gap-1">
            {step.matchedSkills
              .split(/[,，;；\s]+/)
              .filter(Boolean)
              .slice(0, 4)
              .map((skill) => (
                <span
                  key={skill}
                  className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground"
                >
                  {skill}
                </span>
              ))}
          </div>
        )}
        {step.evidence && (
          <details className="mt-1.5 text-xs">
            <summary className="cursor-pointer text-muted-foreground hover:text-foreground">
              证据
            </summary>
            <pre className="mt-1 overflow-x-auto rounded border border-border/60 bg-muted/30 p-2 font-mono text-xs leading-relaxed">
              {step.evidence}
            </pre>
          </details>
        )}
      </div>
    </li>
  );
}

function formatTime(raw: string): string {
  try {
    const d = new Date(raw);
    if (Number.isNaN(d.getTime())) return raw;
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  } catch {
    return raw;
  }
}
