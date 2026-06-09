import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  AlertTriangle,
  Brain,
  GitBranch,
  Network,
  RefreshCw,
  Users,
  Zap,
} from "lucide-react";
import { fetchJSON } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

interface Overview {
  tenants: number;
  teams: number;
  members: number;
  intent_runs: number;
  traces: number;
  anomalies: number;
  evolution_failures: number;
  tenant_rows: Array<Record<string, any>>;
}

export default function OrgControlCenterPage() {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [teams, setTeams] = useState<any[]>([]);
  const [runs, setRuns] = useState<any[]>([]);
  const [traces, setTraces] = useState<any[]>([]);
  const [evolution, setEvolution] = useState<any[]>([]);
  const [anomalies, setAnomalies] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [o, t, i, tr, e, a] = await Promise.all([
        fetchJSON<Overview>("/api/org/control/overview"),
        fetchJSON<any>("/api/org/control/teams"),
        fetchJSON<any>("/api/org/control/intents"),
        fetchJSON<any>("/api/org/control/traces?n=30"),
        fetchJSON<any>("/api/org/control/evolution"),
        fetchJSON<any>("/api/org/control/anomalies?n=30"),
      ]);
      setOverview(o);
      setTeams(t.teams || []);
      setRuns(i.runs || []);
      setTraces(tr.traces || []);
      setEvolution(e.evolution || []);
      setAnomalies(a.anomalies || []);
    } catch (err: any) {
      setError(err?.message || "Failed to load Org Control Center");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const recentTraces = useMemo(() => traces.slice(0, 8), [traces]);
  const recentRuns = useMemo(() => runs.slice(0, 8), [runs]);

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <RefreshCw className="h-8 w-8 animate-spin opacity-70" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-4 p-6">
        <h1 className="text-2xl font-bold">Org Control Center</h1>
        <Card>
          <CardContent className="p-6 text-sm text-red-400">{error}</CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold">Org Control Center</h1>
          <p className="text-sm text-muted-foreground">
            Five-knife AI-native organization loop: teams, intent runs, traces, evolution, anomalies.
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={load}>
          <RefreshCw className="mr-2 h-4 w-4" /> Refresh
        </Button>
      </div>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-7">
        <Metric icon={Network} label="Tenants" value={overview?.tenants ?? 0} />
        <Metric icon={Users} label="Teams" value={overview?.teams ?? 0} />
        <Metric icon={Users} label="Members" value={overview?.members ?? 0} />
        <Metric icon={Zap} label="Intent Runs" value={overview?.intent_runs ?? 0} />
        <Metric icon={Activity} label="Traces" value={overview?.traces ?? 0} />
        <Metric icon={Brain} label="Failures" value={overview?.evolution_failures ?? 0} />
        <Metric icon={AlertTriangle} label="Anomalies" value={overview?.anomalies ?? 0} />
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Users className="h-4 w-4" /> Teams
            </CardTitle>
          </CardHeader>
          <CardContent>
            {teams.length === 0 ? (
              <Empty label="No teams loaded yet" />
            ) : (
              <div className="space-y-3">
                {teams.slice(0, 8).map((team) => (
                  <div key={`${team.tenant_id}:${team.team_id}`} className="rounded-lg border border-current/15 p-3">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <div className="font-medium">{team.name}</div>
                        <div className="text-xs text-muted-foreground">{team.mission || "No mission"}</div>
                      </div>
                      <Badge variant="outline">{team.size ?? 0} members</Badge>
                    </div>
                    <div className="mt-2 flex flex-wrap gap-1">
                      {(team.members || []).slice(0, 6).map((m: string) => (
                        <Badge key={m} variant="secondary">{m}</Badge>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Zap className="h-4 w-4" /> Intent Runs
            </CardTitle>
          </CardHeader>
          <CardContent>
            {recentRuns.length === 0 ? (
              <Empty label="No intent runs yet" />
            ) : (
              <div className="space-y-3">
                {recentRuns.map((run) => (
                  <div key={`${run.tenant_id}:${run.run_id}`} className="rounded-lg border border-current/15 p-3">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="truncate font-medium">{run.intent}</div>
                        <div className="text-xs text-muted-foreground">{run.run_id} · {run.tenant_id}</div>
                      </div>
                      <StatusBadge status={run.status} />
                    </div>
                    <div className="mt-2 text-xs text-muted-foreground">
                      {run.succeeded ?? 0} succeeded / {run.failed ?? 0} failed / {run.subtasks_total ?? 0} subtasks
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Activity className="h-4 w-4" /> Recent Traces
            </CardTitle>
          </CardHeader>
          <CardContent>
            {recentTraces.length === 0 ? (
              <Empty label="No traces captured yet" />
            ) : (
              <div className="space-y-2">
                {recentTraces.map((trace) => (
                  <div key={`${trace.tenant_id}:${trace.trace_id}`} className="grid gap-2 rounded-lg border border-current/15 p-3 md:grid-cols-[1fr_auto]">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{trace.task}</div>
                      <div className="text-xs text-muted-foreground">
                        {trace.agent} · {trace.session} · {trace.steps} steps · {trace.duration_ms}ms
                      </div>
                    </div>
                    <StatusBadge status={trace.status} />
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <AlertTriangle className="h-4 w-4" /> Anomalies
            </CardTitle>
          </CardHeader>
          <CardContent>
            {anomalies.length === 0 ? (
              <Empty label="No anomalies" />
            ) : (
              <div className="space-y-2">
                {anomalies.slice(0, 8).map((a, idx) => (
                  <div key={`${a.tenant_id}:${a.time}:${idx}`} className="rounded-lg border border-current/15 p-3">
                    <div className="flex items-center justify-between gap-2">
                      <Badge variant="destructive">{a.type}</Badge>
                      <span className="text-xs text-muted-foreground">{a.agent}</span>
                    </div>
                    <div className="mt-2 text-sm">{a.message}</div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Brain className="h-4 w-4" /> Evolution
            </CardTitle>
          </CardHeader>
          <CardContent>
            {evolution.length === 0 ? (
              <Empty label="No evolution data" />
            ) : (
              <div className="space-y-2">
                {evolution.map((e) => (
                  <div key={e.tenant_id} className="rounded-lg border border-current/15 p-3">
                    <div className="font-medium">{e.tenant_id}</div>
                    <div className="mt-1 text-xs text-muted-foreground">
                      failures: {e.total_failures ?? 0} · resolved: {e.resolved ?? 0} · rate: {String(e.resolution_rate ?? 0)}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <GitBranch className="h-4 w-4" /> Tenant Rows
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {(overview?.tenant_rows || []).slice(0, 10).map((tenant) => (
                <div key={tenant.tenant_id} className="grid grid-cols-[1fr_auto] gap-3 rounded-lg border border-current/15 p-3">
                  <div>
                    <div className="font-medium">{tenant.tenant_id}</div>
                    <div className="text-xs text-muted-foreground">{tenant.state}</div>
                  </div>
                  <div className="text-right text-xs text-muted-foreground">
                    {tenant.teams} teams · {tenant.traces} traces
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function Metric({ icon: Icon, label, value }: { icon: any; label: string; value: number }) {
  return (
    <Card>
      <CardContent className="flex items-center gap-3 p-4">
        <div className="rounded-lg border border-current/15 p-2">
          <Icon className="h-4 w-4" />
        </div>
        <div>
          <div className="text-xl font-semibold">{value}</div>
          <div className="text-xs text-muted-foreground">{label}</div>
        </div>
      </CardContent>
    </Card>
  );
}

function StatusBadge({ status }: { status?: string }) {
  const s = status || "UNKNOWN";
  const variant = s === "SUCCESS" || s === "COMPLETED" ? "default" : s === "FAILED" ? "destructive" : "outline";
  return <Badge variant={variant as any}>{s}</Badge>;
}

function Empty({ label }: { label: string }) {
  return <div className="rounded-lg border border-dashed border-current/20 p-6 text-center text-sm text-muted-foreground">{label}</div>;
}
