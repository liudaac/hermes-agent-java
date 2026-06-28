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
 *
 * The timeline reads like a story — who did what, in what order, with
 * what result — instead of dumping raw JSON.
 *
 * Visual upgrades in Step E:
 *  - Vertical connector is a gradient (emerald→sky→amber→rose by tone)
 *    rather than a flat border line
 *  - Dot wrappers use glass-card-style ring + tone-specific glow
 *  - Running step pulses (status-pulse halo)
 *  - Hover-to-emphasize a single step (subtle bg tint)
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

  // Build a tonal gradient stop list from the visible steps so the
  // connector visually summarises run health at a glance.
  const stops = visible.map((s, i) => {
    const tone = toneColor(s.status);
    const pct = visible.length === 1 ? 100 : (i / (visible.length - 1)) * 100;
    return `${tone} ${pct}%`;
  });
  const gradient = `linear-gradient(180deg, ${stops.join(", ")})`;

  return (
    <div className={cn("space-y-0", className)}>
      <div className="mb-2 flex items-center gap-2 text-xs uppercase tracking-wider text-muted-foreground">
        <Users className="h-3 w-3" />
        <span>协作时间线 · {steps.length} 步</span>
      </div>

      <ol className="relative space-y-1">
        {/* tonal gradient connector — sits behind the dots */}
        <div
          className="pointer-events-none absolute left-[15px] top-2 bottom-2 w-px"
          style={{ background: gradient }}
          aria-hidden
        />
        {visible.map((step, i) => (
          <TimelineStep
            key={step.stepId ?? `${i}`}
            step={step}
            index={i}
            isLast={i === visible.length - 1}
          />
        ))}
      </ol>

      {hidden > 0 && !expanded && (
        <button
          onClick={() => setExpanded(true)}
          className="mt-2 inline-flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          <ChevronDown className="h-3.5 w-3.5" />
          展开 {hidden} 个步骤
        </button>
      )}
      {expanded && steps.length > collapsedCount && (
        <button
          onClick={() => setExpanded(false)}
          className="mt-2 inline-flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
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
  glow: string;
  badge: "success" | "warning" | "destructive" | "outline" | "info";
  running: boolean;
}

function statusVisual(status?: string): StatusVisual {
  const s = (status ?? "").toUpperCase();
  if (["COMPLETED", "SUCCESS", "DONE", "OK"].includes(s)) {
    return {
      icon: CheckCircle2,
      className: "text-emerald-500",
      glow: "shadow-[0_0_0_4px_color-mix(in_srgb,oklch(0.74_0.14_142)_18%,transparent)]",
      badge: "success",
      running: false,
    };
  }
  if (["RUNNING", "PROCESSING", "IN_PROGRESS"].includes(s)) {
    return {
      icon: PlayCircle,
      className: "text-sky-500",
      glow: "shadow-[0_0_0_4px_color-mix(in_srgb,oklch(0.7_0.14_240)_22%,transparent)]",
      badge: "info",
      running: true,
    };
  }
  if (["FAILED", "ERROR"].includes(s)) {
    return {
      icon: AlertCircle,
      className: "text-rose-500",
      glow: "shadow-[0_0_0_4px_color-mix(in_srgb,oklch(0.65_0.2_27)_22%,transparent)]",
      badge: "destructive",
      running: false,
    };
  }
  if (["RETRY", "RETRIED", "PENDING"].includes(s)) {
    return {
      icon: Clock,
      className: "text-amber-500",
      glow: "shadow-[0_0_0_4px_color-mix(in_srgb,oklch(0.79_0.16_60)_22%,transparent)]",
      badge: "warning",
      running: false,
    };
  }
  return {
    icon: Clock,
    className: "text-muted-foreground",
    glow: "",
    badge: "outline",
    running: false,
  };
}

/** OKLCH tone color used for the connector gradient (matches statusVisual). */
function toneColor(status?: string): string {
  const s = (status ?? "").toUpperCase();
  if (["COMPLETED", "SUCCESS", "DONE", "OK"].includes(s)) return "oklch(0.74 0.14 142)"; // emerald
  if (["RUNNING", "PROCESSING", "IN_PROGRESS"].includes(s)) return "oklch(0.70 0.14 240)"; // sky
  if (["FAILED", "ERROR"].includes(s)) return "oklch(0.65 0.20 27)"; // rose
  if (["RETRY", "RETRIED", "PENDING"].includes(s)) return "oklch(0.79 0.16 60)"; // amber
  return "color-mix(in srgb, currentColor 20%, transparent)";
}

function TimelineStep({
  step,
  index,
  isLast,
}: {
  step: BusinessRunStep;
  index: number;
  isLast: boolean;
}) {
  const visual = statusVisual(step.status);
  const Icon = visual.icon;
  const actor = step.actor ?? step.agentId ?? `Step ${index + 1}`;
  const action = step.title ?? step.summary ?? "—";
  return (
    <li
      className={cn(
        "group relative flex gap-3 rounded-md pl-0 pr-1 transition-colors",
        "hover:bg-foreground/[0.025]",
        isLast && "pb-0.5",
      )}
    >
      {/* Dot — glass-card style ring + tone glow */}
      <div className="relative shrink-0">
        <div
          className={cn(
            "relative z-10 flex h-8 w-8 items-center justify-center rounded-full bg-background ring-1 ring-border/80 transition-all duration-200",
            "group-hover:ring-foreground/40",
            visual.glow,
          )}
        >
          <Icon className={cn("h-3.5 w-3.5", visual.className)} />
        </div>
        {/* status-pulse for running steps */}
        {visual.running && (
          <span
            aria-hidden
            className={cn(
              "status-pulse absolute inset-0 z-0 rounded-full",
              visual.className,
            )}
          />
        )}
      </div>

      <div className="min-w-0 flex-1 pb-3 pt-1">
        <div className="flex flex-wrap items-center gap-2">
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
            <span className="metric-number text-xs text-muted-foreground">
              score {step.score.toFixed(2)}
            </span>
          )}
          {step.timestamp && (
            <span className="metric-number text-xs text-muted-foreground/80">
              {formatTime(step.timestamp)}
            </span>
          )}
        </div>
        <p className="mt-0.5 text-sm leading-relaxed text-muted-foreground">{action}</p>
        {step.summary && step.title && step.summary !== step.title && (
          <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground/80">{step.summary}</p>
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
                  className="rounded-full bg-foreground/5 px-1.5 py-0.5 text-xs text-muted-foreground"
                >
                  {skill}
                </span>
              ))}
          </div>
        )}
        {step.evidence && (
          <details className="mt-1.5 text-xs">
            <summary className="cursor-pointer text-muted-foreground transition-colors hover:text-foreground">
              证据
            </summary>
            <pre className="mt-1 overflow-x-auto rounded-md border border-border/60 bg-background/50 p-2 font-mono text-xs leading-relaxed backdrop-blur-sm">
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
