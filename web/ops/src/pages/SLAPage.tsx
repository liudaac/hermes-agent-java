import { useEffect, useState, useCallback } from "react";
import { ShieldCheck, AlertTriangle, CheckCircle2, Activity } from "lucide-react";
import { opsNocApi } from "@/lib/api/ops";
import { Card, CardContent } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { useI18n } from "@/i18n";

interface SLATemplate {
  id?: string;
  name?: string;
  description?: string;
  targetMetric?: string;
  targetValue?: string;
  status?: string;
  compliance?: string;
}

const STATUS_VARIANT: Record<string, "success" | "warning" | "destructive" | "outline"> = {
  compliant: "success",
  "at-risk": "warning",
  atRisk: "warning",
  violated: "destructive",
  active: "success",
  inactive: "outline",
};

export default function SLAPage() {
  const [templates, setTemplates] = useState<SLATemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const { t } = useI18n();

  const reload = useCallback(() => {
    opsNocApi
      .getSLATemplates()
      .then((r) => setTemplates((r.templates ?? []) as SLATemplate[]))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    reload();
    const interval = setInterval(reload, 30000);
    return () => clearInterval(interval);
  }, [reload]);

  const total = templates.length;
  const compliant = templates.filter((t) => t.status === "compliant" || t.compliance === "compliant").length;
  const violated = templates.filter((t) => t.status === "violated" || t.compliance === "violated").length;
  const atRisk = templates.filter((t) => t.status === "at-risk" || t.status === "atRisk" || t.compliance === "at-risk").length;

  return (
    <div className="flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <ShieldCheck className="h-5 w-5 text-muted-foreground" />
        <div>
          <h2 className="text-lg font-semibold tracking-tight">
            {t.sla?.title ?? "SLA Monitor"}
          </h2>
          <p className="text-xs text-muted-foreground">
            {t.sla?.subtitle ?? "Service Level Agreement monitoring"}
          </p>
        </div>
      </div>

      {/* Dashboard cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <Card>
          <CardContent className="py-3 flex items-center gap-3">
            <Activity className="h-5 w-5 text-muted-foreground" />
            <div>
              <div className="text-2xl font-bold">{total}</div>
              <div className="text-[10px] text-muted-foreground uppercase tracking-wider">Total</div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="py-3 flex items-center gap-3">
            <CheckCircle2 className="h-5 w-5 text-success" />
            <div>
              <div className="text-2xl font-bold text-success">{compliant}</div>
              <div className="text-[10px] text-muted-foreground uppercase tracking-wider">
                {t.sla?.compliant ?? "Compliant"}
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="py-3 flex items-center gap-3">
            <AlertTriangle className="h-5 w-5 text-warning" />
            <div>
              <div className="text-2xl font-bold text-warning">{atRisk}</div>
              <div className="text-[10px] text-muted-foreground uppercase tracking-wider">
                {t.sla?.atRisk ?? "At Risk"}
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="py-3 flex items-center gap-3">
            <AlertTriangle className="h-5 w-5 text-destructive" />
            <div>
              <div className="text-2xl font-bold text-destructive">{violated}</div>
              <div className="text-[10px] text-muted-foreground uppercase tracking-wider">
                {t.sla?.violated ?? "Violated"}
              </div>
            </div>
          </CardContent>
        </Card>
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
      {!loading && templates.length === 0 && (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-16">
            <ShieldCheck className="h-10 w-10 text-muted-foreground mb-3" />
            <p className="text-sm font-medium text-muted-foreground">
              {t.sla?.empty ?? "No SLA templates"}
            </p>
          </CardContent>
        </Card>
      )}

      {/* SLA templates */}
      {!loading && templates.length > 0 && (
        <div className="flex flex-col gap-2">
          {templates.map((tmpl, i) => (
            <Card key={tmpl.id ?? i}>
              <CardContent className="py-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-medium text-sm truncate">
                        {tmpl.name ?? tmpl.id ?? "Unknown"}
                      </span>
                      <Badge
                        variant={STATUS_VARIANT[tmpl.status ?? tmpl.compliance ?? ""] ?? "outline"}
                        className="text-[10px]"
                      >
                        {tmpl.status ?? tmpl.compliance ?? "unknown"}
                      </Badge>
                    </div>
                    {tmpl.description && (
                      <p className="text-xs text-muted-foreground mb-1">{tmpl.description}</p>
                    )}
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-muted-foreground">
                      {tmpl.targetMetric && (
                        <span>Metric: {tmpl.targetMetric}</span>
                      )}
                      {tmpl.targetValue && (
                        <span>Target: {tmpl.targetValue}</span>
                      )}
                    </div>
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
