import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  X,
  ShieldAlert,
  Workflow,
  ListChecks,
  Wrench,
  GitBranch,
  Sparkles,
  ArrowRight,
  type LucideIcon,
} from "lucide-react";
import { api, type AgentTemplateRecord } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import {
  categoryLabel,
  colorsFor,
  iconFor,
} from "@/components/business/templateVisuals";

interface AgentDetailSheetProps {
  template: AgentTemplateRecord | null;
  open: boolean;
  onClose: () => void;
}

export default function AgentDetailSheet({ template, open, onClose }: AgentDetailSheetProps) {
  const navigate = useNavigate();
  const [detail, setDetail] = useState<AgentTemplateRecord | null>(template);

  useEffect(() => {
    if (!template) {
      setDetail(null);
      return;
    }
    setDetail(template);
    api
      .getAgentTemplate(template.templateId)
      .then((res) => setDetail(res.item))
      .catch(() => undefined);
  }, [template]);

  if (!open || !template) return null;
  const t = detail ?? template;
  const Icon = iconFor(t.icon);
  const c = colorsFor(t.color);

  return (
    <div className="fixed inset-0 z-50 flex">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} aria-hidden />
      <aside className="relative ml-auto h-full w-full max-w-xl overflow-y-auto bg-background shadow-2xl">
        <header className="sticky top-0 z-10 border-b border-border bg-background/95 px-5 py-4 backdrop-blur">
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-start gap-3">
              <div className={cn("flex h-12 w-12 items-center justify-center rounded-xl ring-1 shrink-0", c.bg, c.ring)}>
                <Icon className={cn("h-6 w-6", c.text)} />
              </div>
              <div className="min-w-0">
                <h2 className="text-lg font-semibold tracking-tight truncate">{t.name || t.templateId}</h2>
                <p className="text-xs text-muted-foreground">
                  <span className="font-mono">{t.role}</span>
                  <span className="mx-1.5 opacity-30">·</span>
                  <span>{categoryLabel(t.category)}</span>
                </p>
              </div>
            </div>
            <Button variant="ghost" size="icon" onClick={onClose}>
              <X className="h-4 w-4" />
            </Button>
          </div>
        </header>

        <div className="space-y-6 px-5 py-5">
          {t.mission && <p className="text-sm leading-relaxed text-foreground">{t.mission}</p>}
          {t.description && (
            <p className="text-sm leading-relaxed text-muted-foreground whitespace-pre-line">{t.description}</p>
          )}

          {t.metrics.length > 0 && (
            <Section icon={Sparkles} title="关键指标">
              <div className="grid grid-cols-3 gap-2">
                {t.metrics.map((m) => (
                  <div key={m.label} className="rounded-md border border-border/70 p-3 text-center">
                    <div className={cn("font-mono text-base font-semibold", c.text)}>
                      {m.value}
                      {m.unit && <span className="text-xs opacity-60 ml-0.5">{m.unit}</span>}
                    </div>
                    <div className="mt-1 text-xs uppercase tracking-wider text-muted-foreground">{m.label}</div>
                  </div>
                ))}
              </div>
            </Section>
          )}

          {t.skills.length > 0 && (
            <Section icon={ListChecks} title={`核心技能 · ${t.skills.length}`}>
              <div className="flex flex-wrap gap-1.5">
                {t.skills.map((s) => (
                  <span key={s} className={cn("inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium", c.chip)}>
                    {s}
                  </span>
                ))}
              </div>
            </Section>
          )}

          {t.demoWorkflow.length > 0 && (
            <Section icon={Workflow} title="工作流演示">
              <ol className="space-y-2">
                {t.demoWorkflow.map((step) => (
                  <li key={step.step} className="flex items-start gap-3 rounded-md border border-border/60 p-3">
                    <div className={cn("flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold font-mono", c.bg, c.text)}>
                      {step.step}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-medium text-sm">{step.actor}</span>
                        {step.duration && (
                          <Badge variant="outline" className="text-xs font-mono">{step.duration}</Badge>
                        )}
                      </div>
                      <p className="mt-0.5 text-sm text-muted-foreground">{step.action}</p>
                    </div>
                  </li>
                ))}
              </ol>
            </Section>
          )}

          {(t.riskPolicy.high.length + t.riskPolicy.medium.length + t.riskPolicy.low.length) > 0 && (
            <Section icon={ShieldAlert} title="风险与审批边界">
              <div className="space-y-2">
                {t.riskPolicy.high.length > 0 && <RiskBucket level="HIGH" items={t.riskPolicy.high} />}
                {t.riskPolicy.medium.length > 0 && <RiskBucket level="MEDIUM" items={t.riskPolicy.medium} />}
                {t.riskPolicy.low.length > 0 && <RiskBucket level="LOW" items={t.riskPolicy.low} />}
              </div>
            </Section>
          )}

          {(t.allowedTools.length > 0 || t.allowedSkills.length > 0) && (
            <Section icon={Wrench} title="允许调用">
              <div className="grid gap-3 sm:grid-cols-2">
                {t.allowedTools.length > 0 && (
                  <div>
                    <p className="text-xs uppercase tracking-wider text-muted-foreground mb-1.5">Tools</p>
                    <div className="flex flex-wrap gap-1.5">
                      {t.allowedTools.map((x) => (
                        <code key={x} className="rounded bg-muted px-1.5 py-0.5 text-xs font-mono">{x}</code>
                      ))}
                    </div>
                  </div>
                )}
                {t.allowedSkills.length > 0 && (
                  <div>
                    <p className="text-xs uppercase tracking-wider text-muted-foreground mb-1.5">Skills</p>
                    <div className="flex flex-wrap gap-1.5">
                      {t.allowedSkills.map((x) => (
                        <code key={x} className="rounded bg-muted px-1.5 py-0.5 text-xs font-mono">{x}</code>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </Section>
          )}

          {t.handoffPolicy && t.handoffPolicy.defaultTarget && (
            <Section icon={GitBranch} title="交接策略">
              <div className="rounded-md border border-border/60 p-3 text-sm">
                <span className="text-muted-foreground">默认交接给：</span>
                <code className="ml-1.5 rounded bg-muted px-1.5 py-0.5 text-xs font-mono">
                  {t.handoffPolicy.defaultTarget}
                </code>
              </div>
              {t.handoffPolicy.triggers.length > 0 && (
                <ul className="mt-2 space-y-1.5">
                  {t.handoffPolicy.triggers.map((tr, i) => (
                    <li key={i} className="flex items-center gap-2 rounded-md border border-border/60 p-2 text-xs">
                      <code className="rounded bg-muted px-1.5 py-0.5 font-mono">{tr.condition}</code>
                      <ArrowRight className="h-3.5 w-3.5 opacity-50" />
                      <code className="rounded bg-muted px-1.5 py-0.5 font-mono">{tr.target}</code>
                    </li>
                  ))}
                </ul>
              )}
            </Section>
          )}
        </div>

        <footer className="sticky bottom-0 border-t border-border bg-background/95 px-5 py-3 backdrop-blur">
          <div className="flex gap-2">
            <Button variant="outline" size="sm" className="flex-1" onClick={onClose}>关闭</Button>
            <Button size="sm" className="flex-1" onClick={() => { onClose(); navigate("/portal/templates"); }}>
              查看相关场景
              <ArrowRight className="ml-1 h-3.5 w-3.5" />
            </Button>
          </div>
        </footer>
      </aside>
    </div>
  );
}

function Section({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children: React.ReactNode }) {
  return (
    <section>
      <h3 className="mb-2.5 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        <Icon className="h-3.5 w-3.5" />
        {title}
      </h3>
      {children}
    </section>
  );
}

function RiskBucket({ level, items }: { level: "HIGH" | "MEDIUM" | "LOW"; items: string[] }) {
  const styles: Record<string, string> = {
    HIGH: "border-rose-500/40 bg-rose-500/5 text-rose-700 dark:text-rose-300",
    MEDIUM: "border-amber-500/40 bg-amber-500/5 text-amber-700 dark:text-amber-300",
    LOW: "border-emerald-500/40 bg-emerald-500/5 text-emerald-700 dark:text-emerald-300",
  };
  const label: Record<string, string> = { HIGH: "🔴 高风险 · 必须人审", MEDIUM: "🟡 中风险 · 可配置审批", LOW: "🟢 低风险 · 自动通过" };
  return (
    <div className={cn("rounded-md border p-3", styles[level])}>
      <p className="mb-2 text-xs font-semibold">{label[level]}</p>
      <ul className="space-y-1 text-xs">
        {items.map((item) => <li key={item}>· {item}</li>)}
      </ul>
    </div>
  );
}
