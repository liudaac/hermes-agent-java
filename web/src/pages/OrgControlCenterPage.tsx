import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  AlertTriangle,
  Brain,
  ClipboardCheck,
  GitBranch,
  Globe,
  Network,
  RefreshCw,
  Users,
  Zap,
} from "lucide-react";
import { fetchJSON } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { useI18n } from "@/i18n";

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
  const { t } = useI18n();
  const oc = t.orgControl;
  const [overview, setOverview] = useState<Overview | null>(null);
  const [teams, setTeams] = useState<any[]>([]);
  const [runs, setRuns] = useState<any[]>([]);
  const [traces, setTraces] = useState<any[]>([]);
  const [evolution, setEvolution] = useState<any[]>([]);
  const [anomalies, setAnomalies] = useState<any[]>([]);
  const [audit, setAudit] = useState<any[]>([]);
  const [browserTimeline, setBrowserTimeline] = useState<any[]>([]);
  const [browserBridges, setBrowserBridges] = useState<any[]>([]);
  const [browserApprovals, setBrowserApprovals] = useState<any[]>([]);
  const [delegatedTasks, setDelegatedTasks] = useState<any[]>([]);
  const [browserApprovalStatus, setBrowserApprovalStatus] = useState<string>("PENDING");
  const [delegatedTaskStatus, setDelegatedTaskStatus] = useState<string>("ALL");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busyAction, setBusyAction] = useState("");
  const [rerouteTargets, setRerouteTargets] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [o, t, i, tr, e, a, au, b, bs, ba, dt] = await Promise.all([
        fetchJSON<Overview>("/api/org/control/overview"),
        fetchJSON<any>("/api/org/control/teams"),
        fetchJSON<any>("/api/org/control/intents?limit=50&offset=0"),
        fetchJSON<any>("/api/org/control/traces?n=30"),
        fetchJSON<any>("/api/org/control/evolution"),
        fetchJSON<any>("/api/org/control/anomalies?n=30"),
        fetchJSON<any>("/api/org/control/audit?n=30"),
        fetchJSON<any>("/api/org/control/browser?n=30"),
        fetchJSON<any>("/api/org/control/browser/status"),
        fetchJSON<any>(`/api/org/control/browser/approvals?n=30&status=${encodeURIComponent(browserApprovalStatus)}`),
        fetchJSON<any>("/api/org/control/delegated-tasks?n=50"),
      ]);
      setOverview(o);
      setTeams(t.teams || []);
      setRuns(i.runs || []);
      setTraces(tr.traces || []);
      setEvolution(e.evolution || []);
      setAnomalies(a.anomalies || []);
      setAudit(au.audit || []);
      setBrowserTimeline(b.browser_timeline || []);
      setBrowserBridges(bs.browser_bridges || []);
      setBrowserApprovals(ba.approvals || []);
      setDelegatedTasks(dt.delegated_tasks || []);
    } catch (err: any) {
      setError(err?.message || oc.failedToLoad);
    } finally {
      setLoading(false);
    }
  }, [browserApprovalStatus]);

  useEffect(() => {
    load();
  }, [load]);

  const recentTraces = useMemo(() => traces.slice(0, 8), [traces]);
  const recentRuns = useMemo(() => runs.slice(0, 8), [runs]);
  const visibleDelegatedTasks = useMemo(() => {
    if (delegatedTaskStatus === "ALL") return delegatedTasks;
    return delegatedTasks.filter((task) => String(task.status || "").toUpperCase() === delegatedTaskStatus);
  }, [delegatedTaskStatus, delegatedTasks]);
  const agentsByTenant = useMemo(() => {
    const map: Record<string, string[]> = {};
    for (const team of teams) {
      const tenantId = team.tenant_id;
      if (!tenantId) continue;
      const set = new Set(map[tenantId] || []);
      for (const member of team.members || []) set.add(String(member));
      map[tenantId] = Array.from(set).sort();
    }
    for (const run of runs) {
      const tenantId = run.tenant_id;
      if (!tenantId) continue;
      const set = new Set(map[tenantId] || []);
      for (const attempt of run.attempts || []) if (attempt.agent) set.add(String(attempt.agent));
      map[tenantId] = Array.from(set).sort();
    }
    return map;
  }, [runs, teams]);

  const runControl = useCallback(async (key: string, action: () => Promise<unknown>) => {
    setBusyAction(key);
    setError("");
    try {
      await action();
      await load();
    } catch (err: any) {
      setError(err?.message || oc.controlActionFailed);
    } finally {
      setBusyAction("");
    }
  }, [load]);

  const askReason = useCallback((defaultReason: string) => {
    const reason = window.prompt(oc.reasonPrompt, defaultReason);
    if (reason === null) return null;
    return reason.trim() || defaultReason;
  }, []);

  const replayRun = useCallback((run: any) => {
    const reason = askReason(oc.reasons.replayFailed);
    if (reason === null) return;
    const key = `${run.tenant_id}:${run.run_id}:replay`;
    return runControl(key, () => fetchJSON(`/api/org/control/intents/${encodeURIComponent(run.tenant_id)}/${encodeURIComponent(run.run_id)}/replay`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", reason }),
    }));
  }, [askReason, runControl]);

  const rerouteRun = useCallback((run: any, subtask: string) => {
    const reason = askReason(oc.reasons.rerouteFailed);
    if (reason === null) return;
    const targetKey = `${run.tenant_id}:${run.run_id}:${subtask}`;
    const target = rerouteTargets[targetKey];
    if (!target) {
      setError(oc.chooseTargetAgentError);
      return;
    }
    const actionKey = `${targetKey}:reroute`;
    return runControl(actionKey, () => fetchJSON(`/api/org/control/intents/${encodeURIComponent(run.tenant_id)}/${encodeURIComponent(run.run_id)}/reroute`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ subtask, target_agent: target, actor: "dashboard", reason }),
    }));
  }, [askReason, rerouteTargets, runControl]);

  const overrideAgent = useCallback((tenantId: string, agentId: string, mode: "normal" | "disabled" | "deprioritized") => {
    const reason = askReason(oc.reasons.overrideAgent.replace("{mode}", mode));
    if (reason === null) return;
    const key = `${tenantId}:${agentId}:override:${mode}`;
    return runControl(key, () => fetchJSON(`/api/org/control/agents/${encodeURIComponent(tenantId)}/${encodeURIComponent(agentId)}/override`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ mode, penalty: mode === "deprioritized" ? 1.5 : undefined, ttl_ms: mode === "normal" ? undefined : 60 * 60 * 1000, actor: "dashboard", reason }),
    }));
  }, [askReason, runControl]);


  const checkBrowserHealth = useCallback((tenantId: string) => {
    const key = `${tenantId}:browser:health`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/${encodeURIComponent(tenantId)}/health`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", reason: oc.reasons.health }),
    }));
  }, [runControl]);


  const checkBrowserCapabilities = useCallback((tenantId: string) => {
    const key = `${tenantId}:browser:capabilities`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/${encodeURIComponent(tenantId)}/capabilities`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", reason: oc.reasons.capabilities }),
    }));
  }, [runControl]);


  const runBrowserContract = useCallback((tenantId: string) => {
    const key = `${tenantId}:browser:contract`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/${encodeURIComponent(tenantId)}/contract`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", reason: oc.reasons.contract }),
    }));
  }, [runControl]);


  const probeBrowserProvider = useCallback((bridge: any) => {
    const tenantId = bridge.tenant_id;
    const endpoint = window.prompt(oc.endpointUrlToProbe, bridge.endpoint || "http://127.0.0.1:17362");
    if (endpoint === null) return;
    const key = `${tenantId}:browser:probe`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/${encodeURIComponent(tenantId)}/probe`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", endpoint, timeout_ms: 3000, reason: oc.reasons.probe }),
    }));
  }, [runControl]);


  const applyBrowserProbe = useCallback((tenantId: string) => {
    const key = `${tenantId}:browser:probe:apply`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/${encodeURIComponent(tenantId)}/probe/apply`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", reason: oc.reasons.applyProbe }),
    }));
  }, [runControl]);


  const resetBrowserBridge = useCallback((tenantId: string) => {
    const reason = askReason(oc.reasons.resetMock);
    if (reason === null) return;
    const key = `${tenantId}:browser:reset`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/${encodeURIComponent(tenantId)}/reset`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", reason }),
    }));
  }, [askReason, runControl]);

  const clearBrowserConfig = useCallback((tenantId: string) => {
    const reason = askReason(oc.reasons.clearConfig);
    if (reason === null) return;
    const key = `${tenantId}:browser:clear-config`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/${encodeURIComponent(tenantId)}/clear-config`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", reason }),
    }));
  }, [askReason, runControl]);

  const exportBrowserConfig = useCallback((bridge: any) => {
    const config = bridge.config || {};
    const payload = JSON.stringify({ tenant_id: bridge.tenant_id, config }, null, 2);
    window.prompt(oc.configJson, payload);
  }, []);

  const setBrowserProvider = useCallback((tenantId: string, provider: string) => {
    const reason = askReason(oc.reasons.setProvider.replace("{provider}", provider));
    if (reason === null) return;
    const defaultEndpoint = provider === "webbridge" ? "http://127.0.0.1:10086" : provider === "webbridge-contract" ? "http://127.0.0.1:17362" : provider === "kimi" ? "http://127.0.0.1:17361" : "http://127.0.0.1:14511";
    const endpoint = provider === "mock" ? "" : window.prompt(oc.endpointUrl, defaultEndpoint);
    if (endpoint === null) return;
    const key = `${tenantId}:browser:provider:${provider}`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/${encodeURIComponent(tenantId)}/provider`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        actor: "dashboard",
        provider,
        endpoint,
        timeout_ms: 10000,
        reason,
        action_path: provider === "webbridge" ? "/command" : undefined,
        health_path: provider === "webbridge" ? "/status" : undefined,
        capabilities_path: provider === "webbridge" ? "/status" : undefined,
      }),
    }));
  }, [askReason, runControl]);


  const decideBrowserApproval = useCallback((approval: any, decision: "approve" | "reject") => {
    const reason = askReason(oc.reasons.approvalDecision.replace("{decision}", decision).replace("{action}", approval.action));
    if (reason === null) return;
    const key = `${approval.tenant_id}:browser:approval:${approval.id}:${decision}`;
    return runControl(key, () => fetchJSON(`/api/org/control/browser/approvals/${encodeURIComponent(approval.tenant_id)}/${encodeURIComponent(approval.id)}/${decision}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actor: "dashboard", reason }),
    }));
  }, [askReason, runControl]);

  const submitDelegatedTask = useCallback((task: any, payload: { summary: string; changed_files: string[]; tests_run: any[]; risks: string[] }) => {
    const key = `${task.tenant_id}:${task.task_id}:delegated:submit`;
    return runControl(key, () => fetchJSON(`/api/org/control/delegated-tasks/${encodeURIComponent(task.tenant_id)}/${encodeURIComponent(task.task_id)}/submit`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        actor: "dashboard",
        summary: payload.summary.trim() || "Simulated specialist result",
        changed_files: payload.changed_files,
        tests_run: payload.tests_run,
        risks: payload.risks,
      }),
    }));
  }, [runControl]);

  const verifyDelegatedTask = useCallback((task: any, payload: { require_tests_reported: boolean; require_all_tests_passed: boolean; allowed_paths: string[] }) => {
    const key = `${task.tenant_id}:${task.task_id}:delegated:verify`;
    return runControl(key, () => fetchJSON(`/api/org/control/delegated-tasks/${encodeURIComponent(task.tenant_id)}/${encodeURIComponent(task.task_id)}/verify`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        actor: "dashboard",
        require_tests_reported: payload.require_tests_reported,
        require_tests: payload.require_tests_reported,
        require_all_tests_passed: payload.require_all_tests_passed,
        allowed_changed_file_prefixes: payload.allowed_paths,
      }),
    }));
  }, [runControl]);

  const executeDelegatedTask = useCallback((task: any, payload: DelegatedExecutePayload) => {
    const key = `${task.tenant_id}:${task.task_id}:delegated:execute:${payload.executor}`;
    return runControl(key, () => fetchJSON(`/api/org/control/delegated-tasks/${encodeURIComponent(task.tenant_id)}/${encodeURIComponent(task.task_id)}/execute`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        actor: "dashboard",
        executor: payload.executor,
        require_tests: true,
        require_all_tests_passed: true,
        allowed_changed_paths: payload.allowed_paths,
        allowed_changed_file_prefixes: payload.allowed_paths,
        denied_changed_paths: payload.denied_paths,
        requested_capabilities: payload.capabilities,
        capabilities: payload.capabilities,
        allow_command: payload.allow_command,
        allow_network: payload.allow_network,
        allow_browser: payload.allow_browser,
        require_patch_sandbox: payload.require_patch_sandbox,
        require_parent_verification: payload.require_parent_verification,
        allow_auto_merge: payload.allow_auto_merge,
        allow_file_changes: payload.allow_file_changes ?? false,
        patch: payload.patch,
        metadata: payload.metadata,
      }),
    }));
  }, [runControl]);

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
        <h1 className="text-2xl font-bold">{oc.title}</h1>
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
          <h1 className="text-2xl font-bold">{oc.title}</h1>
          <p className="text-sm text-muted-foreground">
            {oc.subtitle}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={load}>
          <RefreshCw className="mr-2 h-4 w-4" /> {t.common.refresh}
        </Button>
      </div>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-7">
        <Metric icon={Network} label={oc.metrics.tenants} value={overview?.tenants ?? 0} />
        <Metric icon={Users} label={oc.metrics.teams} value={overview?.teams ?? 0} />
        <Metric icon={Users} label={oc.metrics.members} value={overview?.members ?? 0} />
        <Metric icon={Zap} label={oc.metrics.intentRuns} value={overview?.intent_runs ?? 0} />
        <Metric icon={Activity} label={oc.metrics.traces} value={overview?.traces ?? 0} />
        <Metric icon={Brain} label={oc.metrics.failures} value={overview?.evolution_failures ?? 0} />
        <Metric icon={AlertTriangle} label={oc.metrics.anomalies} value={overview?.anomalies ?? 0} />
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Users className="h-4 w-4" /> {oc.sections.teams}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {teams.length === 0 ? (
              <Empty label={oc.empty.teams} />
            ) : (
              <div className="space-y-3">
                {teams.slice(0, 8).map((team) => (
                  <TeamCard
                    key={`${team.tenant_id}:${team.team_id}`}
                    team={team}
                    busyAction={busyAction}
                    onOverride={overrideAgent}
                  />
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Zap className="h-4 w-4" /> {oc.sections.intentRuns}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {recentRuns.length === 0 ? (
              <Empty label={oc.empty.intentRuns} />
            ) : (
              <div className="space-y-3">
                {recentRuns.map((run) => (
                  <IntentRunCard
                    key={`${run.tenant_id}:${run.run_id}`}
                    run={run}
                    agents={agentsByTenant[run.tenant_id] || []}
                    busyAction={busyAction}
                    rerouteTargets={rerouteTargets}
                    onReplay={replayRun}
                    onReroute={rerouteRun}
                    onTargetChange={(targetKey, value) => setRerouteTargets((prev) => ({ ...prev, [targetKey]: value }))}
                  />
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
              <Activity className="h-4 w-4" /> {oc.sections.recentTraces}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {recentTraces.length === 0 ? (
              <Empty label={oc.empty.traces} />
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
              <AlertTriangle className="h-4 w-4" /> {oc.sections.anomalies}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {anomalies.length === 0 ? (
              <Empty label={oc.empty.anomalies} />
            ) : (
              <div className="space-y-2">
                {anomalies.slice(0, 8).map((a, idx) => (
                  <div key={`${a.tenant_id}:${a.time}:${idx}`} className="rounded-lg border border-current/15 p-3">
                    <div className="flex items-center justify-between gap-2">
                      <Badge variant="destructive">{a.type}</Badge>
                      <span className="text-xs text-muted-foreground">{a.agent}</span>
                    </div>
                    <div className="mt-2 text-sm">{a.message}</div>
                    {a.suggestion && (
                      <div className="mt-3 flex items-center justify-between gap-2 rounded-md border border-current/10 p-2">
                        <div className="text-xs text-muted-foreground">
                          {oc.labels.suggested}: <span className="font-medium text-foreground">{a.suggestion.label}</span>
                        </div>
                        {a.suggestion.kind === "agent_override" ? (
                          <Button
                            variant="secondary"
                            size="sm"
                            disabled={busyAction === `${a.tenant_id}:${a.suggestion.target_agent}:override:${a.suggestion.mode}`}
                            onClick={() => overrideAgent(a.tenant_id, a.suggestion.target_agent, a.suggestion.mode)}
                          >
                            Apply
                          </Button>
                        ) : (
                          <Badge variant="outline">{oc.buttons.monitor}</Badge>
                        )}
                      </div>
                    )}
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
              <GitBranch className="h-4 w-4" /> {oc.sections.controlTimeline}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {audit.length === 0 ? (
              <Empty label={oc.empty.audit} />
            ) : (
              <div className="space-y-2">
                {audit.slice(0, 8).map((entry, idx) => (
                  <AuditEntryCard key={`${entry.tenant_id}:${entry.time}:${idx}`} entry={entry} />
                ))}
              </div>
            )}
          </CardContent>
        </Card>


        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <CardTitle className="flex items-center gap-2 text-base">
                <ClipboardCheck className="h-4 w-4" /> {oc.sections.delegatedTasks}
              </CardTitle>
              <select
                className="rounded-md border bg-background px-2 py-1 text-xs"
                value={delegatedTaskStatus}
                aria-label={oc.delegated.statusFilter}
                onChange={(event) => setDelegatedTaskStatus(event.target.value)}
              >
                {(["ALL", "PENDING", "SUBMITTED", "ACCEPTED", "REJECTED"] as const).map((status) => (
                  <option key={status} value={status}>{status}</option>
                ))}
              </select>
            </div>
          </CardHeader>
          <CardContent>
            {visibleDelegatedTasks.length === 0 ? (
              <Empty label={oc.empty.delegatedTasks} />
            ) : (
              <div className="space-y-2">
                {visibleDelegatedTasks.slice(0, 8).map((task) => (
                  <DelegatedTaskCard
                    key={`${task.tenant_id}:${task.task_id}`}
                    task={task}
                    busyAction={busyAction}
                    onSubmit={submitDelegatedTask}
                    onVerify={verifyDelegatedTask}
                    onExecute={executeDelegatedTask}
                  />
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Globe className="h-4 w-4" /> {oc.sections.browserControls}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {browserBridges.length === 0 ? (
              <Empty label={oc.empty.browserStatus} />
            ) : (
              <div className="space-y-2">
                {browserBridges.slice(0, 8).map((bridge) => (
                  <BrowserBridgeControlCard
                    key={bridge.tenant_id}
                    bridge={bridge}
                    busyAction={busyAction}
                    onHealth={checkBrowserHealth}
                    onCapabilities={checkBrowserCapabilities}
                    onContract={runBrowserContract}
                    onProbe={probeBrowserProvider}
                    onApplyProbe={applyBrowserProbe}
                    onReset={resetBrowserBridge}
                    onClearConfig={clearBrowserConfig}
                    onExportConfig={exportBrowserConfig}
                    onProvider={setBrowserProvider}
                  />
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <CardTitle className="flex items-center gap-2 text-base">
                <AlertTriangle className="h-4 w-4" /> {oc.sections.browserApprovals}
              </CardTitle>
              <select
                className="rounded-md border bg-background px-2 py-1 text-xs"
                value={browserApprovalStatus}
                onChange={(event) => setBrowserApprovalStatus(event.target.value)}
              >
                {(["PENDING", "EXECUTED", "REJECTED", "FAILED", "EXPIRED", "ALL"] as const).map((status) => (
                  <option key={status} value={status}>{status}</option>
                ))}
              </select>
            </div>
          </CardHeader>
          <CardContent>
            {browserApprovals.length === 0 ? (
              <Empty label={oc.empty.browserApprovals} />
            ) : (
              <div className="space-y-2">
                {browserApprovals.slice(0, 8).map((approval) => (
                  <BrowserApprovalCard
                    key={approval.id}
                    approval={approval}
                    busyAction={busyAction}
                    onDecide={decideBrowserApproval}
                  />
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Globe className="h-4 w-4" /> {oc.sections.browserTimeline}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {browserTimeline.length === 0 ? (
              <Empty label={oc.empty.browserActions} />
            ) : (
              <div className="space-y-2">
                {browserTimeline.slice(0, 8).map((b, idx) => (
                  <BrowserEntryCard key={`${b.tenant_id}:${b.time}:${idx}`} entry={b} />
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Brain className="h-4 w-4" /> {oc.sections.evolution}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {evolution.length === 0 ? (
              <Empty label={oc.empty.evolution} />
            ) : (
              <div className="space-y-2">
                {evolution.map((e) => (
                  <div key={e.tenant_id} className="rounded-lg border border-current/15 p-3">
                    <div className="font-medium">{e.tenant_id}</div>
                    <div className="mt-1 text-xs text-muted-foreground">
                      {oc.labels.failed}: {e.total_failures ?? 0} · {oc.labels.resolved}: {e.resolved ?? 0} · {oc.labels.rate}: {String(e.resolution_rate ?? 0)}
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
              <GitBranch className="h-4 w-4" /> {oc.sections.tenantRows}
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
                    {tenant.teams} {oc.labels.teams} · {tenant.traces} {oc.labels.traces}
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





function DelegatedTaskCard({
  task,
  busyAction,
  onSubmit,
  onVerify,
  onExecute,
}: {
  task: any;
  busyAction: string;
  onSubmit: (task: any, payload: { summary: string; changed_files: string[]; tests_run: any[]; risks: string[] }) => void;
  onVerify: (task: any, payload: { require_tests_reported: boolean; require_all_tests_passed: boolean; allowed_paths: string[] }) => void;
  onExecute: (task: any, payload: DelegatedExecutePayload) => void;
}) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const submitKey = `${task.tenant_id}:${task.task_id}:delegated:submit`;
  const verifyKey = `${task.tenant_id}:${task.task_id}:delegated:verify`;
  const noopExecuteKey = `${task.tenant_id}:${task.task_id}:delegated:execute:noop`;
  const mockExecuteKey = `${task.tenant_id}:${task.task_id}:delegated:execute:mock`;
  const localPatchExecuteKey = `${task.tenant_id}:${task.task_id}:delegated:execute:local_patch`;
  const envelope = task.envelope || {};
  const result = task.result || {};
  const verification = task.latest_verification || task.verification || {};
  const history = Array.isArray(task.verification_history) ? task.verification_history : [];
  const [historyOpen, setHistoryOpen] = useState(false);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [verifyOpen, setVerifyOpen] = useState(false);
  const [executeOpen, setExecuteOpen] = useState(false);
  const closeSubmit = () => setSubmitOpen(false);
  const closeVerify = () => setVerifyOpen(false);
  const closeExecute = () => setExecuteOpen(false);
  return (
    <div className="rounded-lg border border-current/15 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-medium">{envelope.intent || task.task_id}</div>
          <div className="text-xs text-muted-foreground">{task.tenant_id} · {task.task_id}</div>
          <div className="mt-1 flex flex-wrap gap-1">
            {envelope.suggested_team_id && <Badge variant="outline">{envelope.suggested_team_id}</Badge>}
            {envelope.suggested_profile && <Badge variant="secondary">{envelope.suggested_profile}</Badge>}
          </div>
        </div>
        <StatusBadge status={task.status} />
      </div>
      {result.summary && <div className="mt-2 line-clamp-2 text-xs text-muted-foreground">{result.summary}</div>}
      <div className="mt-2 text-xs text-muted-foreground">
        {(result.changed_files || []).length} {oc.delegated.files} · {(result.tests_run || []).length} {oc.delegated.tests}
      </div>
      {task.last_execution && (
        <div className="mt-2 rounded-md border border-current/10 p-2 text-xs text-muted-foreground">
          <div className="font-medium text-foreground/80">{oc.delegated.lastExecution}: {task.last_execution.status}</div>
          <div>{oc.delegated.executor}: {task.last_execution.executor_name || "noop"} · {oc.delegated.executed}: {task.last_execution.executed ? oc.labels.ok : oc.labels.fail} · {oc.delegated.submitted}: {task.last_execution.submitted ? oc.labels.ok : oc.labels.fail}</div>
          {task.last_execution.message && <div className="line-clamp-2">{task.last_execution.message}</div>}
          <SafetySummary safety={(task.last_execution.policy?.metadata || {}).safety_policy} />
        </div>
      )}
      {verification.reasons && (
        <div className="mt-2 line-clamp-2 text-xs text-muted-foreground">{verification.reasons.join("; ")}</div>
      )}
      {history.length > 0 && (
        <div className="mt-2 rounded-md border border-current/10 p-2 text-xs text-muted-foreground">
          <button
            type="button"
            className="flex w-full items-center justify-between gap-2 text-left"
            onClick={() => setHistoryOpen((open) => !open)}
          >
            <span>{oc.delegated.verificationHistory}: {history.length} · {oc.delegated.latest} {verification.status || task.status}</span>
            <span>{historyOpen ? oc.delegated.hideHistory : oc.delegated.showHistory}</span>
          </button>
          {historyOpen && (
            <div className="mt-2 space-y-2">
              {history.map((entry: any, idx: number) => (
                <VerificationHistoryEntry key={entry.sequence_id || idx} entry={entry} />
              ))}
            </div>
          )}
        </div>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" disabled={busyAction === submitKey} onClick={() => setSubmitOpen((open) => !open)}>
          {busyAction === submitKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          {submitOpen ? oc.buttons.cancel : oc.buttons.submitResult}
        </Button>
        <Button variant="outline" size="sm" disabled={busyAction === noopExecuteKey || busyAction === mockExecuteKey || busyAction === localPatchExecuteKey} onClick={() => setExecuteOpen((open) => !open)}>
          {busyAction === noopExecuteKey || busyAction === mockExecuteKey || busyAction === localPatchExecuteKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          {executeOpen ? oc.buttons.cancel : oc.buttons.execute}
        </Button>
        <Button variant="outline" size="sm" disabled={busyAction === verifyKey || !task.result} onClick={() => setVerifyOpen((open) => !open)}>
          {busyAction === verifyKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          {verifyOpen ? oc.buttons.cancel : oc.buttons.verify}
        </Button>
      </div>
      {submitOpen && (
        <DelegatedSubmitForm
          task={task}
          busy={busyAction === submitKey}
          onCancel={closeSubmit}
          onSubmit={(payload) => { onSubmit(task, payload); closeSubmit(); }}
        />
      )}
      {executeOpen && (
        <DelegatedExecuteForm
          task={task}
          busyNoop={busyAction === noopExecuteKey}
          busyMock={busyAction === mockExecuteKey}
          busyLocalPatch={busyAction === localPatchExecuteKey}
          onCancel={closeExecute}
          onSubmit={(payload) => { onExecute(task, payload); closeExecute(); }}
        />
      )}
      {verifyOpen && (
        <DelegatedVerifyForm
          task={task}
          busy={busyAction === verifyKey}
          onCancel={closeVerify}
          onSubmit={(payload) => { onVerify(task, payload); closeVerify(); }}
        />
      )}
    </div>
  );
}


type DelegatedExecutePayload = {
  executor: "noop" | "mock" | "local_patch";
  allowed_paths: string[];
  denied_paths: string[];
  capabilities: string[];
  allow_command: boolean;
  allow_network: boolean;
  allow_browser: boolean;
  require_patch_sandbox: boolean;
  require_parent_verification: boolean;
  allow_auto_merge: boolean;
  allow_file_changes?: boolean;
  patch?: string;
  metadata?: Record<string, any>;
};

function DelegatedExecuteForm({
  task,
  busyNoop,
  busyMock,
  busyLocalPatch,
  onCancel,
  onSubmit,
}: {
  task: any;
  busyNoop: boolean;
  busyMock: boolean;
  busyLocalPatch: boolean;
  onCancel: () => void;
  onSubmit: (payload: DelegatedExecutePayload) => void;
}) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const policy = task.verification_policy || {};
  const [allowedPaths, setAllowedPaths] = useState((policy.allowed_changed_file_prefixes || ["src/main/java", "src/test/java", "docs"]).join("\n"));
  const [deniedPaths, setDeniedPaths] = useState([".git", ".github", "target", "build", "out", "pom.xml", "gradle.properties", "settings.gradle"].join("\n"));
  const [capabilities, setCapabilities] = useState("FILE_READ\nPATCH_WRITE");
  const [allowCommand, setAllowCommand] = useState(false);
  const [allowNetwork, setAllowNetwork] = useState(false);
  const [allowBrowser, setAllowBrowser] = useState(false);
  const [requirePatchSandbox, setRequirePatchSandbox] = useState(true);
  const [requireParentVerification, setRequireParentVerification] = useState(true);
  const [allowAutoMerge, setAllowAutoMerge] = useState(false);
  const [patch, setPatch] = useState("");
  const payload = (executor: "noop" | "mock" | "local_patch") => ({
    executor,
    allowed_paths: splitLinesOrComma(allowedPaths),
    denied_paths: splitLinesOrComma(deniedPaths),
    capabilities: splitLinesOrComma(capabilities),
    allow_command: allowCommand,
    allow_network: allowNetwork,
    allow_browser: allowBrowser,
    require_patch_sandbox: requirePatchSandbox,
    require_parent_verification: requireParentVerification,
    allow_auto_merge: allowAutoMerge,
    allow_file_changes: executor === "local_patch",
    patch: executor === "local_patch" ? patch : undefined,
    metadata: executor === "local_patch" ? {
      patch,
      repository_root: ".",
      local_patch_ui: true,
    } : undefined,
  });
  const safety = {
    allowed_changed_path_prefixes: splitLinesOrComma(allowedPaths),
    denied_changed_path_prefixes: splitLinesOrComma(deniedPaths),
    default_capabilities: splitLinesOrComma(capabilities),
    allow_commands: allowCommand,
    allow_network: allowNetwork,
    allow_browser: allowBrowser,
    require_patch_sandbox: requirePatchSandbox,
    require_parent_verification: requireParentVerification,
    allow_auto_merge: allowAutoMerge,
  };
  return (
    <div className="mt-3 rounded-md border border-current/10 bg-background/40 p-3">
      <div className="mb-3 text-xs font-medium">{oc.delegated.executeFormTitle}</div>
      <div className="space-y-3">
        <TextAreaField label={oc.delegated.allowedPathsLabel} value={allowedPaths} onChange={setAllowedPaths} rows={3} placeholder={oc.delegated.allowedPathsPlaceholder} />
        <TextAreaField label={oc.delegated.deniedPathsLabel} value={deniedPaths} onChange={setDeniedPaths} rows={3} placeholder={oc.delegated.deniedPathsPlaceholder} />
        <TextAreaField label={oc.delegated.capabilitiesLabel} value={capabilities} onChange={setCapabilities} rows={3} placeholder={oc.delegated.capabilitiesPlaceholder} />
        <TextAreaField label={oc.delegated.patchLabel} value={patch} onChange={setPatch} rows={8} placeholder={oc.delegated.patchPlaceholder} />
        <div className="text-xs text-muted-foreground">{oc.delegated.localPatchHint}</div>
        <div className="grid gap-2 sm:grid-cols-2">
          <SwitchField label={oc.delegated.allowCommandLabel} checked={allowCommand} onCheckedChange={setAllowCommand} />
          <SwitchField label={oc.delegated.allowNetworkLabel} checked={allowNetwork} onCheckedChange={setAllowNetwork} />
          <SwitchField label={oc.delegated.allowBrowserLabel} checked={allowBrowser} onCheckedChange={setAllowBrowser} />
          <SwitchField label={oc.delegated.requirePatchSandboxLabel} checked={requirePatchSandbox} onCheckedChange={setRequirePatchSandbox} />
          <SwitchField label={oc.delegated.requireParentVerificationLabel} checked={requireParentVerification} onCheckedChange={setRequireParentVerification} />
          <SwitchField label={oc.delegated.allowAutoMergeLabel} checked={allowAutoMerge} onCheckedChange={setAllowAutoMerge} />
        </div>
        <SafetySummary safety={safety} title={oc.delegated.safetySummary} />
      </div>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        <Button variant="outline" size="sm" disabled={busyNoop || busyMock || busyLocalPatch} onClick={onCancel}>{oc.buttons.cancel}</Button>
        <Button variant="outline" size="sm" disabled={busyNoop || busyMock || busyLocalPatch} onClick={() => onSubmit(payload("noop"))}>
          {busyNoop ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          {oc.buttons.executeNoop}
        </Button>
        <Button variant="secondary" size="sm" disabled={busyNoop || busyMock || busyLocalPatch} onClick={() => onSubmit(payload("mock"))}>
          {busyMock ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          {oc.buttons.executeMock}
        </Button>
        <Button variant="secondary" size="sm" disabled={busyNoop || busyMock || busyLocalPatch || !patch.trim()} onClick={() => onSubmit(payload("local_patch"))}>
          {busyLocalPatch ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          {oc.buttons.executeLocalPatch}
        </Button>
      </div>
    </div>
  );
}

function SafetySummary({ safety, title }: { safety: any; title?: string }) {
  const { t } = useI18n();
  const oc = t.orgControl;
  if (!safety) return null;
  const allowed = safety.allowed_paths || safety.allowed_changed_path_prefixes || [];
  const denied = safety.denied_paths || safety.denied_changed_path_prefixes || [];
  const capabilities = safety.requested_capabilities || safety.default_capabilities || [];
  return (
    <div className="mt-2 rounded-sm border border-current/10 p-2 text-xs text-muted-foreground">
      <div className="font-medium text-foreground/80">{title || oc.delegated.safetyResult}</div>
      <div>{oc.delegated.allowedPathsShort}: {allowed.length ? allowed.join(", ") : "—"}</div>
      <div>{oc.delegated.deniedPathsShort}: {denied.length ? denied.join(", ") : "—"}</div>
      <div>{oc.delegated.capabilitiesShort}: {capabilities.length ? capabilities.join(", ") : "—"}</div>
      <div>{oc.delegated.sandboxRequired}: {(safety.require_patch_sandbox ?? true) ? oc.labels.ok : oc.labels.fail} · {oc.delegated.parentVerificationRequired}: {(safety.require_parent_verification ?? true) ? oc.labels.ok : oc.labels.fail} · {oc.delegated.autoMerge}: {(safety.allow_auto_merge ?? false) ? oc.labels.ok : oc.labels.fail}</div>
    </div>
  );
}

function DelegatedSubmitForm({
  task,
  busy,
  onCancel,
  onSubmit,
}: {
  task: any;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (payload: { summary: string; changed_files: string[]; tests_run: any[]; risks: string[] }) => void;
}) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const result = task.result || {};
  const [summary, setSummary] = useState(result.summary || "");
  const [changedFiles, setChangedFiles] = useState((result.changed_files || []).join("\n"));
  const [tests, setTests] = useState(formatTestsForForm(result.tests_run || []));
  const [risks, setRisks] = useState((result.risks || []).join("\n"));
  return (
    <div className="mt-3 rounded-md border border-current/10 bg-background/40 p-3">
      <div className="mb-3 text-xs font-medium">{oc.delegated.submitFormTitle}</div>
      <div className="space-y-3">
        <TextAreaField label={oc.delegated.summaryLabel} value={summary} onChange={setSummary} rows={2} placeholder={oc.delegated.summaryPlaceholder} />
        <TextAreaField label={oc.delegated.changedFilesLabel} value={changedFiles} onChange={setChangedFiles} rows={3} placeholder={oc.delegated.changedFilesPlaceholder} />
        <TextAreaField label={oc.delegated.testsRunLabel} value={tests} onChange={setTests} rows={3} placeholder={oc.delegated.testsRunPlaceholder} />
        <TextAreaField label={oc.delegated.risksLabel} value={risks} onChange={setRisks} rows={2} placeholder={oc.delegated.risksPlaceholder} />
      </div>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        <Button variant="outline" size="sm" disabled={busy} onClick={onCancel}>{oc.buttons.cancel}</Button>
        <Button
          variant="secondary"
          size="sm"
          disabled={busy}
          onClick={() => onSubmit({
            summary,
            changed_files: splitLinesOrComma(changedFiles),
            tests_run: parseTestsRun(tests),
            risks: splitLinesOrComma(risks),
          })}
        >
          {busy ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          {oc.buttons.submitResult}
        </Button>
      </div>
    </div>
  );
}

function DelegatedVerifyForm({
  task,
  busy,
  onCancel,
  onSubmit,
}: {
  task: any;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (payload: { require_tests_reported: boolean; require_all_tests_passed: boolean; allowed_paths: string[] }) => void;
}) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const policy = task.verification_policy || {};
  const [requireTests, setRequireTests] = useState(policy.require_tests !== false);
  const [requireAllTestsPassed, setRequireAllTestsPassed] = useState(policy.require_all_tests_passed !== false);
  const [allowedPaths, setAllowedPaths] = useState((policy.allowed_changed_file_prefixes || []).join("\n"));
  return (
    <div className="mt-3 rounded-md border border-current/10 bg-background/40 p-3">
      <div className="mb-3 text-xs font-medium">{oc.delegated.verifyFormTitle}</div>
      <div className="space-y-3">
        <SwitchField label={oc.delegated.requireTestsReportedLabel} checked={requireTests} onCheckedChange={setRequireTests} />
        <SwitchField label={oc.delegated.requireAllTestsPassedLabel} checked={requireAllTestsPassed} onCheckedChange={setRequireAllTestsPassed} />
        <TextAreaField label={oc.delegated.allowedPathsLabel} value={allowedPaths} onChange={setAllowedPaths} rows={3} placeholder={oc.delegated.allowedPathsPlaceholder} />
      </div>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        <Button variant="outline" size="sm" disabled={busy} onClick={onCancel}>{oc.buttons.cancel}</Button>
        <Button
          variant="secondary"
          size="sm"
          disabled={busy}
          onClick={() => onSubmit({
            require_tests_reported: requireTests,
            require_all_tests_passed: requireAllTestsPassed,
            allowed_paths: splitLinesOrComma(allowedPaths),
          })}
        >
          {busy ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          {oc.buttons.verify}
        </Button>
      </div>
    </div>
  );
}

function TextAreaField({ label, value, onChange, rows, placeholder }: { label: string; value: string; onChange: (value: string) => void; rows: number; placeholder?: string }) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      <textarea
        className="w-full rounded-sm border border-border bg-background/40 px-3 py-2 font-courier text-sm placeholder:text-muted-foreground/60 focus-visible:border-midground/40 focus-visible:bg-background/60 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-midground/40"
        rows={rows}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

function SwitchField({ label, checked, onCheckedChange }: { label: string; checked: boolean; onCheckedChange: (checked: boolean) => void }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-sm border border-current/10 px-3 py-2">
      <Label>{label}</Label>
      <Switch checked={checked} onCheckedChange={onCheckedChange} />
    </div>
  );
}

function splitLinesOrComma(value: string) {
  return value.split(/[\n,]+/).map((x) => x.trim()).filter(Boolean);
}

function parseTestsRun(value: string) {
  return splitLinesOrComma(value).map((item) => {
    const [name, status = "pass", ...rest] = item.split(":");
    const passed = !["fail", "failed", "false"].includes(status.trim().toLowerCase());
    return { name: name.trim(), passed, details: rest.join(":").trim() };
  }).filter((test) => test.name);
}

function formatTestsForForm(tests: any[]) {
  return tests.map((test) => {
    if (typeof test === "string") return test;
    const status = test.passed === false ? "fail" : "pass";
    return `${test.name || "test"}:${status}${test.details ? `:${test.details}` : ""}`;
  }).join("\n");
}

function VerificationHistoryEntry({ entry }: { entry: any }) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const result = entry.result || {};
  const policy = entry.policy || {};
  const reasons = Array.isArray(result.reasons) ? result.reasons : [];
  const timestamp = entry.recorded_at || result.verified_at;
  const accepted = result.accepted === true || String(result.accepted).toLowerCase() === "true";
  return (
    <div className="rounded border border-current/10 p-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={accepted ? "default" : "destructive"}>{result.status || "UNKNOWN"}</Badge>
          <span>{oc.delegated.accepted}: {accepted ? oc.labels.ok : oc.labels.fail}</span>
        </div>
        {timestamp && <span>{new Date(timestamp).toLocaleString()}</span>}
      </div>
      {reasons.length > 0 && (
        <div className="mt-1 line-clamp-2">{oc.delegated.reason}: {reasons.join("; ")}</div>
      )}
      {policy && Object.keys(policy).length > 0 && (
        <div className="mt-1 line-clamp-2">{oc.delegated.policy}: {formatVerificationPolicy(policy, oc)}</div>
      )}
    </div>
  );
}

function formatVerificationPolicy(policy: any, oc: any) {
  const prefixes = Array.isArray(policy.allowed_changed_file_prefixes) ? policy.allowed_changed_file_prefixes : [];
  const parts = [
    policy.require_tests === false ? oc.delegated.testsOptional : oc.delegated.testsRequired,
    policy.require_all_tests_passed === false ? oc.delegated.failedTestsAllowed : oc.delegated.allTestsPassed,
  ];
  if (prefixes.length > 0) parts.push(`${oc.delegated.paths}: ${prefixes.slice(0, 3).join(", ")}${prefixes.length > 3 ? "…" : ""}`);
  return parts.join(" · ");
}

function AuditEntryCard({ entry }: { entry: any }) {
  const raw = entry.details?.raw || "";
  const actor = extractRawValue(raw, "actor") || "unknown";
  const reason = extractRawValue(raw, "reason") || raw;
  return (
    <div className="rounded-lg border border-current/15 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-medium">{entry.event}</div>
          <div className="text-xs text-muted-foreground">{entry.tenant_id} · {actor}</div>
        </div>
        <Badge variant="outline">{new Date(entry.time).toLocaleTimeString()}</Badge>
      </div>
      <div className="mt-2 line-clamp-2 text-xs text-muted-foreground">{reason}</div>
    </div>
  );
}

function extractRawValue(raw: string, key: string) {
  const match = raw.match(new RegExp(`${key}=([^,}]+)`));
  return match ? match[1].trim() : "";
}

function TeamCard({
  team,
  busyAction,
  onOverride,
}: {
  team: any;
  busyAction: string;
  onOverride: (tenantId: string, agentId: string, mode: "normal" | "disabled" | "deprioritized") => void;
}) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const roles = team.agent_roles || [];
  return (
    <div className="rounded-lg border border-current/15 p-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="font-medium">{team.name}</div>
          <div className="text-xs text-muted-foreground">{team.mission || oc.labels.noMission}</div>
        </div>
        <Badge variant="outline">{team.size ?? 0} {oc.labels.members}</Badge>
      </div>
      <div className="mt-2 flex flex-wrap gap-1">
        {(team.members || []).slice(0, 6).map((m: string) => (
          <Badge key={m} variant="secondary">{m}</Badge>
        ))}
      </div>

      {roles.length > 0 && (
        <div className="mt-3 space-y-2 rounded-md border border-current/10 p-2">
          <div className="text-xs font-medium"> {oc.labels.agentRoutingControls}</div>
          {roles.slice(0, 6).map((role: any) => {
            const agent = role.agent;
            const metrics = role.metrics || {};
            const disabled = metrics.manual_disabled === true || metrics.manual_disabled === "true";
            const penalty = Number(metrics.manual_penalty || 0);
            const mode = disabled ? "disabled" : penalty > 0 ? "deprioritized" : "normal";
            const expiresAt = Number(metrics.manual_expires_at || 0);
            const expiresLabel = expiresAt > 0 ? ` · ${oc.labels.expires} ${new Date(expiresAt).toLocaleTimeString()}` : "";
            const baseKey = `${team.tenant_id}:${agent}:override`;
            return (
              <div key={agent} className="rounded border border-current/10 p-2">
                <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0">
                    <div className="truncate text-xs font-medium">{agent}</div>
                    <div className="text-xs text-muted-foreground">
                      {role.name} · {role.level} · {mode}{penalty > 0 && !disabled ? ` (-${penalty})` : ""}{expiresLabel}
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-1">
                    <Button
                      variant={mode === "disabled" ? "destructive" : "outline"}
                      size="sm"
                      disabled={busyAction === `${baseKey}:disabled`}
                      onClick={() => onOverride(team.tenant_id, agent, "disabled")}
                    >
                      {busyAction === `${baseKey}:disabled` ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
                      Disable
                    </Button>
                    <Button
                      variant={mode === "deprioritized" ? "secondary" : "outline"}
                      size="sm"
                      disabled={busyAction === `${baseKey}:deprioritized`}
                      onClick={() => onOverride(team.tenant_id, agent, "deprioritized")}
                    >
                      {busyAction === `${baseKey}:deprioritized` ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
                      Deprioritize
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={mode === "normal" || busyAction === `${baseKey}:normal`}
                      onClick={() => onOverride(team.tenant_id, agent, "normal")}
                    >
                      {busyAction === `${baseKey}:normal` ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
                      Restore
                    </Button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function IntentRunCard({
  run,
  agents,
  busyAction,
  rerouteTargets,
  onReplay,
  onReroute,
  onTargetChange,
}: {
  run: any;
  agents: string[];
  busyAction: string;
  rerouteTargets: Record<string, string>;
  onReplay: (run: any) => void;
  onReroute: (run: any, subtask: string) => void;
  onTargetChange: (targetKey: string, value: string) => void;
}) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const failedEntries = Object.entries(run.failures || {});
  const replayKey = `${run.tenant_id}:${run.run_id}:replay`;
  const isControlRun = Boolean(run.parent_run_id);

  return (
    <div className="rounded-lg border border-current/15 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate font-medium">{run.intent}</div>
          <div className="text-xs text-muted-foreground">{run.run_id} · {run.tenant_id}</div>
          <div className="mt-1 flex flex-wrap gap-1">
            <Badge variant="outline">{run.control_action || "execute"}</Badge>
            {isControlRun && <Badge variant="secondary">{oc.labels.parent} {run.parent_run_id}</Badge>}
          </div>
        </div>
        <StatusBadge status={run.status} />
      </div>
      <div className="mt-2 text-xs text-muted-foreground">
        {oc.labels.succeededFailedSubtasks.replace("{succeeded}", String(run.succeeded ?? 0)).replace("{failed}", String(run.failed ?? 0)).replace("{total}", String(run.subtasks_total ?? 0))}
      </div>

      {(run.assignments || []).length > 0 && (
        <div className="mt-3 space-y-2 rounded-md border border-current/10 p-2">
          <div className="text-xs font-medium"> {oc.labels.scoringExplanation}</div>
          {(run.assignments || []).slice(0, 4).map((assignment: any, idx: number) => (
            <ScoreBreakdown key={`${assignment.subtask}:${idx}`} assignment={assignment} />
          ))}
        </div>
      )}

      {failedEntries.length > 0 && (
        <div className="mt-3 space-y-2 rounded-md border border-red-500/20 bg-red-500/5 p-2">
          <div className="flex items-center justify-between gap-2">
            <div className="text-xs font-medium text-red-300"> {oc.labels.failedSubtasks}</div>
            <Button
              variant="outline"
              size="sm"
              disabled={busyAction === replayKey}
              onClick={() => onReplay(run)}
            >
              {busyAction === replayKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
              Replay failed
            </Button>
          </div>

          {failedEntries.slice(0, 4).map(([subtask, err]) => {
            const targetKey = `${run.tenant_id}:${run.run_id}:${subtask}`;
            const actionKey = `${targetKey}:reroute`;
            return (
              <div key={subtask} className="rounded border border-current/10 p-2">
                <div className="text-xs font-medium">{subtask}</div>
                <div className="mt-1 line-clamp-2 text-xs text-muted-foreground">{String(err)}</div>
                <div className="mt-2 flex flex-col gap-2 sm:flex-row">
                  <select
                    className="h-8 rounded-md border border-current/20 bg-background px-2 text-xs"
                    value={rerouteTargets[targetKey] || ""}
                    onChange={(e) => onTargetChange(targetKey, e.target.value)}
                  >
                    <option value="">{oc.labels.chooseTargetAgent}</option>
                    {agents.map((agent) => (
                      <option key={agent} value={agent}>{agent}</option>
                    ))}
                  </select>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={!rerouteTargets[targetKey] || busyAction === actionKey}
                    onClick={() => onReroute(run, subtask)}
                  >
                    {busyAction === actionKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
                    Reroute
                  </Button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}


function ScoreBreakdown({ assignment }: { assignment: any }) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const components = assignment.score_components || {};
  const entries = Object.entries(components);
  return (
    <div className="rounded border border-current/10 p-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate text-xs font-medium">{assignment.subtask}</div>
          <div className="text-xs text-muted-foreground">
            {assignment.agent} · {assignment.role || oc.labels.noRole} · {oc.labels.score} {Number(assignment.score || 0).toFixed(2)}
          </div>
        </div>
        <div className="flex flex-wrap gap-1">
          {(assignment.matched_skills || []).slice(0, 4).map((skill: string) => (
            <Badge key={skill} variant="secondary">{skill}</Badge>
          ))}
        </div>
      </div>
      {entries.length > 0 && (
        <div className="mt-2 grid gap-1 sm:grid-cols-2">
          {entries.map(([name, raw]) => {
            const value = Number(raw || 0);
            const positive = value >= 0;
            return (
              <div key={name} className="flex items-center justify-between rounded bg-current/5 px-2 py-1 text-xs">
                <span className="text-muted-foreground">{formatComponentName(name)}</span>
                <span className={positive ? "text-emerald-300" : "text-red-300"}>
                  {positive ? "+" : ""}{value.toFixed(2)}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function formatComponentName(name: string) {
  return name.replace(/_/g, " ");
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
  const variant = s === "SUCCESS" || s === "COMPLETED" ? "default" : s === "FAILED" || s === "INTERRUPTED" ? "destructive" : "outline";
  return <Badge variant={variant as any}>{s}</Badge>;
}

function BrowserApprovalCard({
  approval,
  busyAction,
  onDecide,
}: {
  approval: any;
  busyAction: string;
  onDecide: (approval: any, decision: "approve" | "reject") => void;
}) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const pending = approval.status === "PENDING";
  const approveKey = `${approval.tenant_id}:browser:approval:${approval.id}:approve`;
  const rejectKey = `${approval.tenant_id}:browser:approval:${approval.id}:reject`;
  return (
    <div className="rounded-lg border border-current/15 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Badge variant={pending ? "destructive" : "outline"}>{approval.status}</Badge>
            <span className="truncate text-sm font-medium">{approval.action}</span>
          </div>
          <div className="text-xs text-muted-foreground">{approval.tenant_id} · {approval.actor} · {approval.id}</div>
        </div>
        <Badge variant="outline">{new Date(approval.created_at).toLocaleTimeString()}</Badge>
      </div>
      <div className="mt-2 text-xs text-muted-foreground">
        {approval.url && <div className="truncate">{oc.labels.url}: {approval.url}</div>}
        {approval.target && <div>{oc.labels.target}: {approval.target}</div>}
        {approval.reason && <div className="italic">"{approval.reason}"</div>}
        {approval.expires_at && <div>{oc.labels.expiresLower}: {new Date(approval.expires_at).toLocaleTimeString()}</div>}
        {approval.deny_reason && <div className="text-red-400">{oc.labels.blocked}: {approval.deny_reason}</div>}
      </div>
      {pending && (
        <div className="mt-3 flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" disabled={busyAction === approveKey} onClick={() => onDecide(approval, "approve")}>
            {busyAction === approveKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
            Approve once
          </Button>
          <Button variant="outline" size="sm" disabled={busyAction === rejectKey} onClick={() => onDecide(approval, "reject")}>
            Reject
          </Button>
        </div>
      )}
    </div>
  );
}

function BrowserBridgeControlCard({
  bridge,
  busyAction,
  onHealth,
  onCapabilities,
  onContract,
  onProbe,
  onApplyProbe,
  onReset,
  onClearConfig,
  onExportConfig,
  onProvider,
}: {
  bridge: any;
  busyAction: string;
  onHealth: (tenantId: string) => void;
  onCapabilities: (tenantId: string) => void;
  onContract: (tenantId: string) => void;
  onProbe: (bridge: any) => void;
  onApplyProbe: (tenantId: string) => void;
  onReset: (tenantId: string) => void;
  onClearConfig: (tenantId: string) => void;
  onExportConfig: (bridge: any) => void;
  onProvider: (tenantId: string, provider: string) => void;
}) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const tenantId = bridge.tenant_id;
  const provider = bridge.provider || bridge.class || "unknown";
  const healthKey = `${tenantId}:browser:health`;
  const capabilitiesKey = `${tenantId}:browser:capabilities`;
  const contractKey = `${tenantId}:browser:contract`;
  const probeKey = `${tenantId}:browser:probe`;
  const applyProbeKey = `${tenantId}:browser:probe:apply`;
  const resetKey = `${tenantId}:browser:reset`;
  const clearConfigKey = `${tenantId}:browser:clear-config`;
  const capabilities = bridge.capabilities || bridge.last_capabilities || {};
  const contractReport = bridge.contract_report || {};
  const probeReport = bridge.probe_report || {};
  return (
    <div className="rounded-lg border border-current/15 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-medium">{tenantId}</div>
          <div className="text-xs text-muted-foreground">
            {oc.labels.provider}: {provider}{bridge.endpoint ? ` · ${bridge.endpoint}` : ""}
          </div>
          {bridge.config && <div className="text-[10px] text-muted-foreground/70">{oc.labels.persisted}: {bridge.config.action_path} · {bridge.config.health_path}</div>}
          {bridge.config?.file_path && <div className="text-[10px] text-muted-foreground/50 truncate">{bridge.config.file_path}</div>}
        </div>
        <Badge variant={bridge.healthy === false ? "destructive" : "default"}>{bridge.healthy === false ? oc.labels.unhealthy : oc.labels.ready}</Badge>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        <Button variant="outline" size="sm" disabled={busyAction === healthKey} onClick={() => onHealth(tenantId)}>
          {busyAction === healthKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          Health check
        </Button>
        <Button variant="outline" size="sm" disabled={busyAction === capabilitiesKey} onClick={() => onCapabilities(tenantId)}>
          {busyAction === capabilitiesKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          Capabilities
        </Button>
        <Button variant="outline" size="sm" disabled={busyAction === contractKey} onClick={() => onContract(tenantId)}>
          {busyAction === contractKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          Contract test
        </Button>
        <Button variant="outline" size="sm" disabled={busyAction === probeKey} onClick={() => onProbe(bridge)}>
          {busyAction === probeKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
          Probe
        </Button>
        <Button variant="ghost" size="sm" disabled={busyAction === resetKey} onClick={() => onReset(tenantId)}>
          Reset mock
        </Button>
        <Button variant="ghost" size="sm" disabled={busyAction === clearConfigKey} onClick={() => onClearConfig(tenantId)}>
          Clear config
        </Button>
        <Button variant="ghost" size="sm" onClick={() => onExportConfig(bridge)}>
          Export config
        </Button>
        {(["mock", "webbridge", "webbridge-contract", "kimi", "openclaw"] as const).map((p) => (
          <Button
            key={p}
            variant={String(provider).includes(p) ? "secondary" : "ghost"}
            size="sm"
            disabled={busyAction === `${tenantId}:browser:provider:${p}`}
            onClick={() => onProvider(tenantId, p)}
          >
            {p}
          </Button>
        ))}
      </div>
      {capabilities && Object.keys(capabilities).length > 0 && (
        <div className="mt-2 text-xs text-muted-foreground">
          {capabilities.protocol && <div>{oc.labels.protocol}: {capabilities.protocol}</div>}
          {capabilities.actions && <div className="truncate">{oc.labels.actions}: {String(capabilities.actions)}</div>}
          {capabilities.features && <div className="truncate">{oc.labels.features}: {String(capabilities.features)}</div>}
        </div>
      )}
      {probeReport && Object.keys(probeReport).length > 0 && (
        <div className="mt-2 rounded-md border border-current/10 p-2 text-xs text-muted-foreground">
          <div className="flex items-center justify-between gap-2">
            <span>{oc.labels.probeScore}: {probeReport.score ?? 0}</span>
            <Badge variant={probeReport.ok ? "default" : "secondary"}>{probeReport.ok ? oc.labels.compatible : oc.labels.partial}</Badge>
          </div>
          {probeReport.recommended_config && (
            <div className="mt-1 truncate">{oc.labels.recommended}: {probeReport.recommended_config.provider} {probeReport.recommended_config.action_path}</div>
          )}
          {Array.isArray(probeReport.candidates) && <div className="truncate">{oc.labels.candidates}: {probeReport.candidates.length}</div>}
          {probeReport.recommended_config && (
            <Button className="mt-2" variant="secondary" size="sm" disabled={busyAction === applyProbeKey} onClick={() => onApplyProbe(tenantId)}>
              {busyAction === applyProbeKey ? <RefreshCw className="mr-2 h-3 w-3 animate-spin" /> : null}
              Apply recommendation
            </Button>
          )}
        </div>
      )}
      {contractReport && Object.keys(contractReport).length > 0 && (
        <div className="mt-2 rounded-md border border-current/10 p-2 text-xs text-muted-foreground">
          <div className="flex items-center justify-between gap-2">
            <span>{oc.labels.contract}: {contractReport.ok ? oc.labels.compatible : oc.labels.failed}</span>
            <Badge variant={contractReport.ok ? "default" : "destructive"}>{contractReport.ok ? oc.labels.pass : oc.labels.fail}</Badge>
          </div>
          {contractReport.timestamp && <div>{new Date(contractReport.timestamp).toLocaleTimeString()}</div>}
          {Array.isArray(contractReport.checks) && (
            <div className="mt-1 space-y-1">
              {contractReport.checks.slice(0, 5).map((check: any) => (
                <div key={check.name} className="flex justify-between gap-2">
                  <span className="truncate">{check.name}</span>
                  <span className={check.ok ? "text-green-400" : "text-red-400"}>{check.ok ? oc.labels.ok : oc.labels.fail}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function BrowserEntryCard({ entry }: { entry: any }) {
  const { t } = useI18n();
  const oc = t.orgControl;
  const badgeVariant = entry.denied ? "destructive" : entry.ok === false ? "secondary" : "default";
  const badgeLabel = entry.denied ? "DENIED" : "OK";
  return (
    <div className="rounded-lg border border-current/15 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Badge variant={badgeVariant as any}>{badgeLabel}</Badge>
            <span className="truncate text-sm font-medium">{entry.action}</span>
          </div>
          <div className="text-xs text-muted-foreground">
            {entry.tenant_id} · {entry.actor}
          </div>
        </div>
        <Badge variant="outline">{new Date(entry.time).toLocaleTimeString()}</Badge>
      </div>
      <div className="mt-2 text-xs text-muted-foreground">
        {entry.url && <div className="truncate">{oc.labels.url}: {entry.url}</div>}
        {entry.target && <div>{oc.labels.target}: {entry.target}</div>}
        {entry.reason && <div className="italic">"{entry.reason}"</div>}
        {entry.deny_reason && <div className="text-red-400">{oc.labels.blocked}: {entry.deny_reason}</div>}
        {entry.session_id && <div className="text-[10px] text-muted-foreground/60">{oc.labels.session}: {entry.session_id}  {oc.labels.trace}: {entry.trace_id}</div>}
      </div>
    </div>
  );
}

function Empty({ label }: { label: string }) {
  return <div className="rounded-lg border border-dashed border-current/20 p-6 text-center text-sm text-muted-foreground">{label}</div>;
}
