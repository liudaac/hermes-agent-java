import { type AgentTemplateRecord } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { ChevronRight, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";
import { categoryLabel, colorsFor, iconFor } from "@/components/business/templateVisuals";

interface AgentRoleCardProps {
  template: AgentTemplateRecord;
  onClick?: (template: AgentTemplateRecord) => void;
  compact?: boolean;
}

export default function AgentRoleCard({ template, onClick, compact }: AgentRoleCardProps) {
  const Icon = iconFor(template.icon);
  const c = colorsFor(template.color);
  const skillsToShow = template.skills.slice(0, compact ? 2 : 3);
  const moreSkills = Math.max(0, template.skills.length - skillsToShow.length);

  return (
    <Card
      className={cn(
        "group cursor-pointer transition-all duration-200 hover:shadow-md hover:-translate-y-0.5",
        "border-border/70 hover:border-border ring-0",
      )}
      onClick={() => onClick?.(template)}
    >
      <CardContent className={cn("p-4", compact && "p-3")}>
        <div className="flex items-start gap-3">
          <div
            className={cn(
              "flex items-center justify-center rounded-xl shrink-0 ring-1",
              compact ? "h-10 w-10" : "h-12 w-12",
              c.bg,
              c.ring,
            )}
          >
            <Icon className={cn(compact ? "h-5 w-5" : "h-6 w-6", c.text)} />
          </div>

          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <h3 className={cn("font-semibold tracking-tight truncate", compact ? "text-sm" : "text-base")}>
                {template.name || template.templateId}
              </h3>
              {template.status === "BETA" && (
                <Badge variant="outline" className="text-xs uppercase tracking-wider">
                  Beta
                </Badge>
              )}
              {template.status === "EXPERIMENTAL" && (
                <Badge variant="warning" className="text-xs uppercase tracking-wider">
                  Exp
                </Badge>
              )}
            </div>

            <p className="mt-0.5 text-xs text-muted-foreground">
              <span className="font-mono">{template.role || template.templateId}</span>
              <span className="mx-1.5 opacity-30">·</span>
              <span>{categoryLabel(template.category)}</span>
            </p>

            {!compact && template.mission && (
              <p className="mt-2 text-sm text-muted-foreground line-clamp-2 leading-relaxed">
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
                <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                  +{moreSkills}
                </span>
              )}
            </div>

            {!compact && template.metrics.length > 0 && (
              <div className="mt-3 grid grid-cols-3 gap-2">
                {template.metrics.slice(0, 3).map((metric) => (
                  <div key={metric.label} className="rounded-sm border border-border/60 p-1.5 text-center">
                    <div className={cn("font-mono text-xs font-semibold", c.text)}>
                      {metric.value}
                      {metric.unit && <span className="text-xs opacity-60 ml-0.5">{metric.unit}</span>}
                    </div>
                    <div className="text-xs uppercase tracking-wider text-muted-foreground mt-0.5">
                      {metric.label}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <ChevronRight className="h-4 w-4 shrink-0 opacity-30 group-hover:opacity-60 transition-opacity" />
        </div>
      </CardContent>
    </Card>
  );
}

export function AgentRoleCardSkeleton() {
  return (
    <Card>
      <CardContent className="p-4">
        <div className="flex items-start gap-3">
          <div className="h-12 w-12 rounded-xl bg-muted animate-pulse" />
          <div className="flex-1 space-y-2">
            <div className="h-4 w-32 rounded bg-muted animate-pulse" />
            <div className="h-3 w-24 rounded bg-muted animate-pulse" />
            <div className="h-12 w-full rounded bg-muted animate-pulse" />
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

export function EmptyAgentRoleList() {
  return (
    <Card>
      <CardContent className="flex flex-col items-center justify-center gap-3 py-10 text-center">
        <Sparkles className="h-8 w-8 text-muted-foreground/40" />
        <div>
          <p className="text-sm font-medium">还没有可用的数字员工</p>
          <p className="mt-1 text-xs text-muted-foreground">
            模板正在加载，请稍后刷新
          </p>
        </div>
      </CardContent>
    </Card>
  );
}
