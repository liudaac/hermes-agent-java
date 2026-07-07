import { useEffect, useState, useCallback } from "react";
import { Timer, AlertTriangle, Activity, RotateCw, ShieldCheck, ShieldAlert, ShieldX } from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import { Card, CardContent } from "@hermes/ui";
import { Badge } from "@hermes/ui";
import { Button } from "@hermes/ui";
import { useToast } from "@/hooks/useToast";
import { useSse } from "@/hooks/useSse";
import { cn } from "@hermes/ui";

interface SLAMetric {
  name: string;
  warnThreshold: number;
  breachThreshold: number;
  avgTime: number;
  breachCount: number;
  status: "healthy" | "warning" | "breached";
}

export default function SLAPage() {
  const { showToast } = useToast();
  const [metrics, setMetrics] = useState<SLAMetric[]>([]);
  const [loading, setLoading] = useState(true);
  const [liveEvent, setLiveEvent] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      const res = await api.getSLATemplates();
      if (res.ok && res.templates) {
        const list = Object.entries(res.templates).map(([key, t]: [string, any]) => ({
          name: t.name || key,
          warnThreshold: Math.round((t.warnThresholdMs ?? 0) / 1000),
          breachThreshold: Math.round((t.breachThresholdMs ?? 0) / 1000),
          avgTime: 0,
          breachCount: 0,
          status: "healthy" as const,
        }));
        setMetrics(list);
      } else {
        setMetrics([]);
      }
    } catch (e: any) {
      showToast(e?.message || "Failed to load SLA", "error");
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    load();
  }, [load]);

  useSse({
    onEvent: (evt) => {
      if (["SLA_WARN", "SLA_BREACH", "RUN_STATUS"].includes(evt.type)) {
        setLiveEvent(`${evt.type} @ ${new Date().toLocaleTimeString()}`);
        load();
      }
    },
    onError: () => {},
  });

  const healthy = metrics.filter((m) => m.status === "healthy").length;
  const warning = metrics.filter((m) => m.status === "warning").length;
  const breached = metrics.filter((m) => m.status === "breached").length;

  const statusConfig = {
    healthy: { icon: ShieldCheck, color: "text-green-500", bg: "bg-green-500/10", border: "border-green-500/20", bar: "bg-green-500" },
    warning: { icon: ShieldAlert, color: "text-amber-500", bg: "bg-amber-500/10", border: "border-amber-500/20", bar: "bg-amber-500" },
    breached: { icon: ShieldX, color: "text-red-500", bg: "bg-red-500/10", border: "border-red-500/20", bar: "bg-red-500" },
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Timer className="h-5 w-5 opacity-70" />
          <H2 className="text-base tracking-[0.08em]">SLA Monitor</H2>
          {liveEvent && (
            <Badge variant="outline" className="text-[0.6rem] text-green-500 border-green-500/20 animate-pulse">
              <Activity className="h-3 w-3 mr-1" />
              {liveEvent}
            </Badge>
          )}
        </div>
        <div className="flex items-center gap-3">
          <div className="flex gap-2">
            <Badge variant="outline" className="text-[0.6rem] border-green-500/20 text-green-500">
              Healthy {healthy}
            </Badge>
            <Badge variant="outline" className="text-[0.6rem] border-amber-500/20 text-amber-500">
              Warning {warning}
            </Badge>
            <Badge variant="outline" className="text-[0.6rem] border-red-500/20 text-red-500">
              Breached {breached}
            </Badge>
          </div>
          <Button variant="outline" size="sm" onClick={load} disabled={loading} className="text-[0.65rem]">
            <RotateCw className={cn("mr-1.5 h-3.5 w-3.5", loading && "animate-spin")} />
            Refresh
          </Button>
        </div>
      </div>

      {loading ? (
        <div className="flex h-32 items-center justify-center text-sm opacity-60">
          <RotateCw className="mr-2 h-4 w-4 animate-spin" />
          Loading SLA metrics...
        </div>
      ) : metrics.length === 0 ? (
        <Card>
          <CardContent className="p-6 text-center space-y-2">
            <Timer className="h-8 w-8 mx-auto opacity-40" />
            <div className="text-sm opacity-60">No SLA templates configured.</div>
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {metrics.map((m) => {
            const cfg = statusConfig[m.status];
            const Icon = cfg.icon;
            const pct = m.breachThreshold > 0 ? Math.min(100, Math.round((m.avgTime / m.breachThreshold) * 100)) : 0;
            return (
              <Card key={m.name} className={cn("overflow-hidden border", cfg.border)}>
                <CardContent className="p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Icon className={cn("h-4 w-4", cfg.color)} />
                      <span className="font-medium text-sm">{m.name}</span>
                    </div>
                    <Badge variant="outline" className={cn("text-[0.6rem] capitalize", cfg.bg, cfg.color, cfg.border)}>
                      {m.status}
                    </Badge>
                  </div>

                  <div className="grid grid-cols-3 gap-2 text-center">
                    <div className="space-y-1">
                      <div className="text-[0.6rem] opacity-50">Avg Time</div>
                      <div className="text-sm font-medium">{m.avgTime}s</div>
                    </div>
                    <div className="space-y-1">
                      <div className="text-[0.6rem] opacity-50">Warn At</div>
                      <div className="text-sm font-medium">{m.warnThreshold}s</div>
                    </div>
                    <div className="space-y-1">
                      <div className="text-[0.6rem] opacity-50">Breach At</div>
                      <div className="text-sm font-medium">{m.breachThreshold}s</div>
                    </div>
                  </div>

                  <div className="space-y-1">
                    <div className="flex justify-between text-[0.65rem] opacity-60">
                      <span>Usage</span>
                      <span>{pct}% of limit</span>
                    </div>
                    <div className="h-2 bg-muted rounded-full overflow-hidden">
                      <div className={cn("h-full transition-all", cfg.bar)} style={{ width: `${pct}%` }} />
                    </div>
                  </div>

                  {m.breachCount > 0 && (
                    <div className="flex items-center gap-1 text-[0.65rem] text-red-500">
                      <AlertTriangle className="h-3 w-3" />
                      <span>
                        {m.breachCount} breach{m.breachCount > 1 ? "es" : ""} in last 24h
                      </span>
                    </div>
                  )}
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
