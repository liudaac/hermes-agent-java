import { type AgentTemplateRecord } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { ChevronRight, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";
import { categoryLabel, colorsFor, iconFor } from "@/components/business/templateVisuals";

interface AgentRoleCardProps {
  template: AgentTemplateRecord;
  onClick?: (template: AgentTemplateRecord) => void;
  compact?: boolean;
}

/**
 * AgentRoleCard — single digital employee role.
 *
 * Visual: glass-card with subtle category-tinted gradient backdrop,
 * tonal ring around the icon, pulsing online dot, and hover lift.
 * Mobile-first paddings; truncates skills tail into a +N pill.
 */
export default function AgentRoleCard({ template, onClick, compact }: AgentRoleCardProps) {
  const Icon = iconFor(template.icon);
  const c = colorsFor(template.color);
  const skillsToShow = template.skills.slice(0, compact ? 2 : 3);
  const moreSkills = Math.max(0, template.skills.length - skillsToShow.length);
  const isBeta = template.status === "BETA";
  const isExperimental = template.status === "EXPERIMENTAL";

  return (
    <article
      className={cn(
        "glass-card glass-card-interactive group relative cursor-pointer overflow-hidden",
        "transition-all duration-200",
      )}
      onClick={() => onClick?.(template)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onClick?.(template);
        }
      }}
    >
      {/* Category-tinted gradient wash, top-right corner */}
      <div
        aria-hidden
        className={cn(
          "pointer-events-none absolute -right-12 -top-12 h-32 w-32 rounded-full blur-3xl opacity-50 transition-opacity duration-300 group-hover:opacity-80",
          c.bg,
        )}
      />

      <div className={cn("relative p-4", compact && "p-3")}>
        <div className="flex items-start gap-3">
          {/* Icon tile with online dot */}
          <div className="relative shrink-0">
            <div
              className={cn(
                "flex items-center justify-center rounded-xl ring-1 transition-transform duration-200 group-hover:scale-105",
                compact ? "h-10 w-10" : "h-12 w-12",
                c.bg,
                c.ring,
              )}
            >
              <Icon className={cn(compact ? "h-5 w-5" : "h-6 w-6", c.text)} />
            </div>
            {/* online dot */}
            <span
              aria-hidden
              className="absolute -right-0.5 -top-0.5 inline-flex h-2.5 w-2.5 items-center justify-center"
            >
              <span className="absolute inset-0 rounded-full bg-emerald-500 opacity-90" />
              <span className="status-pulse absolute inset-0 rounded-full text-emerald-500" />
            </span>
          </div>

          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <h3
                className={cn(
                  "truncate font-semibold tracking-tight",
                  compact ? "text-sm" : "text-base",
                )}
              >
                {template.name || template.templateId}
              </h3>
              {isBeta && (
                <Badge variant="outline" className="text-xs uppercase tracking-wider">
                  Beta
                </Badge>
              )}
              {isExperimental && (
                <Badge variant="warning" className="text-xs uppercase tracking-wider">
                  Exp
                </Badge>
              )}
            </div>

            <p className="mt-0.5 truncate text-xs text-muted-foreground">
              <span className="font-mono">{template.role || template.templateId}</span>
              <span className="mx-1.5 opacity-30">·</span>
              <span>{categoryLabel(template.category)}</span>
            </p>

            {!compact && template.mission && (
              <p className="mt-2 line-clamp-2 text-sm leading-relaxed text-muted-foreground">
                {template.mission}
              </p>
            )}

            <div className="mt-3 flex flex-wrap gap-1.5">
              {skillsToShow.map((skill) => (
                <span
                  key={skill}
                  className={cn(
                    "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium",
                    c.chip,
                  )}
                >
                  {skill}
                </span>
              ))}
              {moreSkills > 0 && (
                <span className="inline-flex items-center rounded-full bg-foreground/5 px-2 py-0.5 text-xs text-muted-foreground">
                  +{moreSkills}
                </span>
              )}
            </div>

            {!compact && template.metrics.length > 0 && (
              <div className="mt-3 grid grid-cols-3 gap-1.5 sm:gap-2">
                {template.metrics.slice(0, 3).map((metric) => (
                  <div
                    key={metric.label}
                    className="rounded-md border border-border/50 bg-background/40 px-1.5 py-1.5 text-center backdrop-blur-sm"
                  >
                    <div className={cn("metric-number text-xs font-semibold", c.text)}>
                      {metric.value}
                      {metric.unit && (
                        <span className="ml-0.5 text-[0.65rem] opacity-60">{metric.unit}</span>
                      )}
                    </div>
                    <div className="mt-0.5 truncate text-[0.65rem] uppercase tracking-wider text-muted-foreground">
                      {metric.label}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <ChevronRight className="h-4 w-4 shrink-0 self-center opacity-30 transition-all duration-200 group-hover:translate-x-0.5 group-hover:opacity-70" />
        </div>
      </div>
    </article>
  );
}

export function AgentRoleCardSkeleton() {
  return (
    <div className="glass-card relative overflow-hidden p-4">
      <div className="flex items-start gap-3">
        <div className="shimmer-bg h-12 w-12 rounded-xl" />
        <div className="flex-1 space-y-2">
          <div className="shimmer-bg h-4 w-32 rounded" />
          <div className="shimmer-bg h-3 w-24 rounded" />
          <div className="shimmer-bg h-12 w-full rounded" />
        </div>
      </div>
    </div>
  );
}

export function EmptyAgentRoleList() {
  return (
    <div className="glass-card flex flex-col items-center justify-center gap-3 py-10 text-center">
      <Sparkles className="h-8 w-8 text-muted-foreground/40" />
      <div>
        <p className="text-sm font-medium">还没有可用的数字员工</p>
        <p className="mt-1 text-xs text-muted-foreground">模板正在加载，请稍后刷新</p>
      </div>
    </div>
  );
}
