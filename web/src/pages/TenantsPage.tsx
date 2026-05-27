import { useEffect, useMemo, useState } from "react";
import {
  CheckCircle2,
  CircleAlert,
  FileClock,
  Gauge,
  HardDrive,
  Layers,
  Pause,
  Play,
  RefreshCw,
  Search,
  Shield,
  SlidersHorizontal,
  Trash2,
  Users,
} from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import type {
  TenantAuditEvent,
  TenantQuota,
  TenantSecurity,
  TenantSkillInfo,
  TenantSummary,
  TenantUsage,
} from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

function formatTime(value?: string): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

function stateVariant(state: string): "default" | "secondary" | "destructive" | "outline" {
  const normalized = state.toUpperCase();
  if (normalized === "ACTIVE") return "default";
  if (normalized === "SUSPENDED") return "secondary";
  if (normalized === "DESTROYED") return "destructive";
  return "outline";
}

function stateIcon(state: string) {
  return state.toUpperCase() === "ACTIVE" ? CheckCircle2 : CircleAlert;
}

function formatNumber(value: number | undefined): string {
  if (value === undefined || Number.isNaN(value)) return "—";
  return new Intl.NumberFormat().format(value);
}

function formatBytes(value: number | undefined): string {
  if (value === undefined || Number.isNaN(value)) return "—";
  if (value < 1024) return `${value} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let current = value / 1024;
  let unitIndex = 0;
  while (current >= 1024 && unitIndex < units.length - 1) {
    current /= 1024;
    unitIndex += 1;
  }
  return `${current.toFixed(current >= 10 ? 1 : 2)} ${units[unitIndex]}`;
}

function csvToList(value: string): string[] {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function listToCsv(value: string[] | undefined): string {
  return (value ?? []).join(", ");
}

export default function TenantsPage() {
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [selectedTenantId, setSelectedTenantId] = useState<string | null>(null);
  const [selectedTenant, setSelectedTenant] = useState<TenantSummary | null>(null);
  const [tenantSkills, setTenantSkills] = useState<TenantSkillInfo[]>([]);
  const [tenantQuota, setTenantQuota] = useState<TenantQuota | null>(null);
  const [tenantUsage, setTenantUsage] = useState<TenantUsage | null>(null);
  const [tenantSecurity, setTenantSecurity] = useState<TenantSecurity | null>(null);
  const [tenantAudit, setTenantAudit] = useState<TenantAuditEvent[]>([]);
  const [newTenantId, setNewTenantId] = useState("");
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [busyTenantId, setBusyTenantId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [savingQuota, setSavingQuota] = useState(false);
  const [savingSecurity, setSavingSecurity] = useState(false);
  const [quotaForm, setQuotaForm] = useState({
    maxDailyRequests: "",
    maxDailyTokens: "",
    maxConcurrentAgents: "",
    maxConcurrentSessions: "",
    maxStorageBytes: "",
    maxMemoryBytes: "",
  });
  const [securityForm, setSecurityForm] = useState({
    allowCodeExecution: false,
    requireSandbox: true,
    allowNetworkAccess: false,
    allowFileRead: true,
    allowFileWrite: true,
    allowedLanguages: "",
    allowedHosts: "",
    allowedTools: "",
    deniedTools: "",
    deniedPaths: "",
  });
  const { showToast } = useToast();

  const loadTenants = async () => {
    setLoading(true);
    try {
      const response = await api.getTenants();
      setTenants(response.tenants ?? []);
      if (!selectedTenantId && response.tenants?.length) {
        setSelectedTenantId(response.tenants[0].tenantId);
      }
    } catch (e) {
      showToast(`Failed to load tenants: ${e}`, "error");
    } finally {
      setLoading(false);
    }
  };

  const loadTenantDetails = async (tenantId: string) => {
    setDetailLoading(true);
    try {
      const [tenant, skills, quota, usage, security, audit] = await Promise.all([
        api.getTenant(tenantId),
        api.getTenantSkills(tenantId),
        api.getTenantQuota(tenantId),
        api.getTenantUsage(tenantId),
        api.getTenantSecurity(tenantId),
        api.getTenantAudit(tenantId, 20),
      ]);
      setSelectedTenant(tenant);
      setTenantSkills(skills.skills ?? []);
      setTenantQuota(quota);
      setTenantUsage(usage);
      setTenantSecurity(security);
      setTenantAudit(audit.events ?? audit.logs ?? []);
      setQuotaForm({
        maxDailyRequests: String(quota.maxDailyRequests ?? ""),
        maxDailyTokens: String(quota.maxDailyTokens ?? ""),
        maxConcurrentAgents: String(quota.maxConcurrentAgents ?? ""),
        maxConcurrentSessions: String(quota.maxConcurrentSessions ?? ""),
        maxStorageBytes: String(quota.maxStorageBytes ?? ""),
        maxMemoryBytes: String(quota.maxMemoryBytes ?? ""),
      });
      setSecurityForm({
        allowCodeExecution: Boolean(security.allowCodeExecution),
        requireSandbox: Boolean(security.requireSandbox),
        allowNetworkAccess: Boolean(security.allowNetworkAccess),
        allowFileRead: Boolean(security.allowFileRead),
        allowFileWrite: Boolean(security.allowFileWrite),
        allowedLanguages: listToCsv(security.allowedLanguages),
        allowedHosts: listToCsv(security.allowedHosts),
        allowedTools: listToCsv(security.allowedTools),
        deniedTools: listToCsv(security.deniedTools),
        deniedPaths: listToCsv(security.deniedPaths),
      });
    } catch (e) {
      showToast(`Failed to load tenant details: ${e}`, "error");
      setSelectedTenant(null);
      setTenantSkills([]);
      setTenantQuota(null);
      setTenantUsage(null);
      setTenantSecurity(null);
      setTenantAudit([]);
    } finally {
      setDetailLoading(false);
    }
  };

  useEffect(() => {
    loadTenants();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selectedTenantId) {
      setSelectedTenant(null);
      setTenantSkills([]);
      setTenantQuota(null);
      setTenantUsage(null);
      setTenantSecurity(null);
      setTenantAudit([]);
      return;
    }
    loadTenantDetails(selectedTenantId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedTenantId]);

  const filteredTenants = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return tenants;
    return tenants.filter(
      (tenant) =>
        tenant.tenantId.toLowerCase().includes(q) ||
        tenant.state.toLowerCase().includes(q),
    );
  }, [tenants, search]);

  const activeCount = tenants.filter((tenant) => tenant.state === "ACTIVE").length;
  const suspendedCount = tenants.filter((tenant) => tenant.state === "SUSPENDED").length;

  const createTenant = async () => {
    const tenantId = newTenantId.trim();
    if (!tenantId) return;
    setCreating(true);
    try {
      await api.createTenant(tenantId);
      setNewTenantId("");
      setSelectedTenantId(tenantId);
      showToast(`Tenant ${tenantId} created`, "success");
      await loadTenants();
    } catch (e) {
      showToast(`Failed to create tenant: ${e}`, "error");
    } finally {
      setCreating(false);
    }
  };

  const runTenantAction = async (
    tenantId: string,
    action: "suspend" | "resume" | "delete",
  ) => {
    if (action === "delete" && !confirm(`Delete tenant "${tenantId}"?`)) {
      return;
    }

    setBusyTenantId(tenantId);
    try {
      if (action === "suspend") {
        await api.suspendTenant(tenantId);
      } else if (action === "resume") {
        await api.resumeTenant(tenantId);
      } else {
        await api.deleteTenant(tenantId);
        if (selectedTenantId === tenantId) {
          setSelectedTenantId(null);
          setSelectedTenant(null);
          setTenantSkills([]);
          setTenantQuota(null);
          setTenantUsage(null);
          setTenantSecurity(null);
          setTenantAudit([]);
        }
      }
      const actionLabel = action === "delete" ? "deleted" : action === "suspend" ? "suspended" : "resumed";
      showToast(`Tenant ${tenantId} ${actionLabel}`, "success");
      await loadTenants();
      if (selectedTenantId === tenantId && action !== "delete") {
        await loadTenantDetails(tenantId);
      }
    } catch (e) {
      showToast(`Failed to ${action} tenant: ${e}`, "error");
    } finally {
      setBusyTenantId(null);
    }
  };


  const saveQuota = async () => {
    if (!selectedTenantId) return;
    setSavingQuota(true);
    try {
      await api.updateTenantQuota(selectedTenantId, {
        maxDailyRequests: Number(quotaForm.maxDailyRequests),
        maxDailyTokens: Number(quotaForm.maxDailyTokens),
        maxConcurrentAgents: Number(quotaForm.maxConcurrentAgents),
        maxConcurrentSessions: Number(quotaForm.maxConcurrentSessions),
        maxStorageBytes: Number(quotaForm.maxStorageBytes),
        maxMemoryBytes: Number(quotaForm.maxMemoryBytes),
      });
      showToast(`Quota updated for ${selectedTenantId}`, "success");
      await loadTenantDetails(selectedTenantId);
    } catch (e) {
      showToast(`Failed to update quota: ${e}`, "error");
    } finally {
      setSavingQuota(false);
    }
  };

  const saveSecurity = async () => {
    if (!selectedTenantId) return;
    if (securityForm.allowCodeExecution && !confirm("Allowing code execution can increase tenant risk. Continue?")) {
      return;
    }
    if (securityForm.allowNetworkAccess && !confirm("Allowing network access can expose external resources. Continue?")) {
      return;
    }

    setSavingSecurity(true);
    try {
      await api.updateTenantSecurity(selectedTenantId, {
        allowCodeExecution: securityForm.allowCodeExecution,
        requireSandbox: securityForm.requireSandbox,
        allowNetworkAccess: securityForm.allowNetworkAccess,
        allowFileRead: securityForm.allowFileRead,
        allowFileWrite: securityForm.allowFileWrite,
        allowedLanguages: csvToList(securityForm.allowedLanguages),
        allowedHosts: csvToList(securityForm.allowedHosts),
        allowedTools: csvToList(securityForm.allowedTools),
        deniedTools: csvToList(securityForm.deniedTools),
        deniedPaths: csvToList(securityForm.deniedPaths),
      });
      showToast(`Security policy updated for ${selectedTenantId}`, "success");
      await loadTenantDetails(selectedTenantId);
    } catch (e) {
      showToast(`Failed to update security policy: ${e}`, "error");
    } finally {
      setSavingSecurity(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <Users className="h-5 w-5 text-muted-foreground" />
          <H2 variant="sm">Tenants</H2>
          <span className="text-xs text-muted-foreground">
            {tenants.length} total · {activeCount} active · {suspendedCount} suspended
          </span>
        </div>
        <Button variant="outline" size="sm" onClick={loadTenants} disabled={loading}>
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
          Refresh
        </Button>
      </div>

      <div className="grid gap-4 lg:grid-cols-[360px_1fr]">
        <div className="flex flex-col gap-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Create Tenant</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <Input
                value={newTenantId}
                onChange={(e) => setNewTenantId(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && createTenant()}
                placeholder="tenant-id"
                disabled={creating}
              />
              <Button onClick={createTenant} disabled={!newTenantId.trim() || creating}>
                {creating ? "Creating…" : "Create"}
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Tenant List</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
                <Input
                  className="pl-8"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search tenants..."
                />
              </div>

              <div className="flex max-h-[560px] flex-col gap-2 overflow-y-auto pr-1">
                {loading && (
                  <div className="py-8 text-center text-xs text-muted-foreground">Loading tenants…</div>
                )}
                {!loading && filteredTenants.length === 0 && (
                  <div className="py-8 text-center text-xs text-muted-foreground">No tenants found</div>
                )}
                {filteredTenants.map((tenant) => {
                  const StateIcon = stateIcon(tenant.state);
                  const selected = selectedTenantId === tenant.tenantId;
                  return (
                    <button
                      key={tenant.tenantId}
                      type="button"
                      onClick={() => setSelectedTenantId(tenant.tenantId)}
                      className={`border p-3 text-left transition-colors ${
                        selected
                          ? "border-primary bg-primary/10"
                          : "border-border hover:bg-foreground/5"
                      }`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <div className="truncate text-sm font-medium text-foreground">
                            {tenant.tenantId}
                          </div>
                          <div className="mt-1 text-[11px] text-muted-foreground">
                            {tenant.activeAgents} agents · {tenant.activeSessions} sessions
                          </div>
                        </div>
                        <Badge variant={stateVariant(tenant.state)} className="shrink-0">
                          <StateIcon className="mr-1 h-3 w-3" />
                          {tenant.state}
                        </Badge>
                      </div>
                    </button>
                  );
                })}
              </div>
            </CardContent>
          </Card>
        </div>

        <div className="flex flex-col gap-4">
          {!selectedTenantId && (
            <Card>
              <CardContent className="py-16 text-center text-sm text-muted-foreground">
                Select a tenant to inspect details.
              </CardContent>
            </Card>
          )}

          {selectedTenantId && (
            <Card>
              <CardHeader>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div>
                    <CardTitle className="flex items-center gap-2 text-base">
                      <Shield className="h-4 w-4 text-muted-foreground" />
                      {selectedTenant?.tenantId ?? selectedTenantId}
                    </CardTitle>
                    <p className="mt-1 text-xs text-muted-foreground">
                      Canonical tenantId view backed by /api/tenants/{"{tenantId}"}
                    </p>
                  </div>
                  {selectedTenant && (
                    <div className="flex flex-wrap gap-2">
                      {selectedTenant.tenantId === "default" && (
                        <Badge variant="outline" className="text-[10px]">
                          Protected
                        </Badge>
                      )}
                      {selectedTenant.state === "ACTIVE" ? (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => runTenantAction(selectedTenant.tenantId, "suspend")}
                          disabled={
                            busyTenantId === selectedTenant.tenantId ||
                            selectedTenant.tenantId === "default"
                          }
                          title={
                            selectedTenant.tenantId === "default"
                              ? "Cannot suspend the system default tenant"
                              : undefined
                          }
                        >
                          <Pause className="h-3.5 w-3.5" />
                          Suspend
                        </Button>
                      ) : (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => runTenantAction(selectedTenant.tenantId, "resume")}
                          disabled={busyTenantId === selectedTenant.tenantId}
                        >
                          <Play className="h-3.5 w-3.5" />
                          Resume
                        </Button>
                      )}
                      <Button
                        variant="destructive"
                        size="sm"
                        onClick={() => runTenantAction(selectedTenant.tenantId, "delete")}
                        disabled={
                          busyTenantId === selectedTenant.tenantId ||
                          selectedTenant.tenantId === "default"
                        }
                        title={
                          selectedTenant.tenantId === "default"
                            ? "Cannot delete the system default tenant"
                            : undefined
                        }
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                        Delete
                      </Button>
                    </div>
                  )}
                </div>
              </CardHeader>
              <CardContent className="flex flex-col gap-4">
                {detailLoading && (
                  <div className="py-10 text-center text-xs text-muted-foreground">Loading details…</div>
                )}

                {!detailLoading && selectedTenant && (
                  <>
                    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                      <MetricCard label="State" value={selectedTenant.state} />
                      <MetricCard label="Active Agents" value={String(selectedTenant.activeAgents)} />
                      <MetricCard label="Active Sessions" value={String(selectedTenant.activeSessions)} />
                      <MetricCard label="Tenant Skills" value={String(tenantSkills.length)} />
                    </div>

                    <div className="grid gap-3 sm:grid-cols-2">
                      <InfoRow label="Created" value={formatTime(selectedTenant.createdAt)} />
                      <InfoRow label="Last Activity" value={formatTime(selectedTenant.lastActivity)} />
                    </div>

                    <div className="grid gap-4 xl:grid-cols-3">
                      <Card>
                        <CardHeader>
                          <CardTitle className="flex items-center gap-2 text-sm">
                            <Gauge className="h-4 w-4 text-muted-foreground" />
                            Usage
                          </CardTitle>
                        </CardHeader>
                        <CardContent className="grid gap-3 text-xs">
                          {tenantUsage && (
                            <>
                              <QuotaBar
                                label="Daily Requests"
                                used={tenantUsage.dailyRequests}
                                max={tenantUsage.maxDailyRequests}
                              />
                              <QuotaBar
                                label="Daily Tokens"
                                used={tenantUsage.dailyTokens}
                                max={tenantUsage.maxDailyTokens}
                              />
                              <QuotaBar
                                label="Storage"
                                used={tenantUsage.storage ?? tenantUsage.storageUsage ?? 0}
                                max={tenantQuota?.maxStorageBytes ?? 0}
                                unit="bytes"
                              />
                              <QuotaBar
                                label="Memory"
                                used={tenantUsage.memory ?? 0}
                                max={tenantQuota?.maxMemoryBytes ?? 0}
                                unit="bytes"
                              />
                              <div className="flex items-center justify-between text-[11px] pt-1 border-t border-border/40">
                                <span className="text-muted-foreground uppercase tracking-wider">
                                  Active Agents
                                </span>
                                <span className="font-mono">
                                  {formatNumber(tenantUsage.activeAgents)}
                                </span>
                              </div>
                            </>
                          )}
                          {!tenantUsage && (
                            <div className="text-muted-foreground text-center py-4">
                              No usage data
                            </div>
                          )}
                        </CardContent>
                      </Card>

                      <Card>
                        <CardHeader>
                          <CardTitle className="flex items-center gap-2 text-sm">
                            <HardDrive className="h-4 w-4 text-muted-foreground" />
                            Quota
                          </CardTitle>
                        </CardHeader>
                        <CardContent className="grid gap-3 text-xs">
                          <NumberField label="Max Daily Requests" value={quotaForm.maxDailyRequests} onChange={(value) => setQuotaForm((form) => ({ ...form, maxDailyRequests: value }))} />
                          <NumberField label="Max Daily Tokens" value={quotaForm.maxDailyTokens} onChange={(value) => setQuotaForm((form) => ({ ...form, maxDailyTokens: value }))} />
                          <NumberField label="Concurrent Agents" value={quotaForm.maxConcurrentAgents} onChange={(value) => setQuotaForm((form) => ({ ...form, maxConcurrentAgents: value }))} />
                          <NumberField label="Concurrent Sessions" value={quotaForm.maxConcurrentSessions} onChange={(value) => setQuotaForm((form) => ({ ...form, maxConcurrentSessions: value }))} />
                          <NumberField label="Max Storage Bytes" value={quotaForm.maxStorageBytes} onChange={(value) => setQuotaForm((form) => ({ ...form, maxStorageBytes: value }))} hint={formatBytes(Number(quotaForm.maxStorageBytes))} />
                          <NumberField label="Max Memory Bytes" value={quotaForm.maxMemoryBytes} onChange={(value) => setQuotaForm((form) => ({ ...form, maxMemoryBytes: value }))} hint={formatBytes(Number(quotaForm.maxMemoryBytes))} />
                          <Button size="sm" onClick={saveQuota} disabled={savingQuota || !tenantQuota}>
                            {savingQuota ? "Saving quota…" : "Save Quota"}
                          </Button>
                        </CardContent>
                      </Card>

                      <Card>
                        <CardHeader>
                          <CardTitle className="flex items-center gap-2 text-sm">
                            <SlidersHorizontal className="h-4 w-4 text-muted-foreground" />
                            Security
                          </CardTitle>
                        </CardHeader>
                        <CardContent className="grid gap-3 text-xs">
                          <CheckField label="Code Execution" checked={securityForm.allowCodeExecution} onChange={(value) => setSecurityForm((form) => ({ ...form, allowCodeExecution: value }))} danger />
                          <CheckField label="Sandbox Required" checked={securityForm.requireSandbox} onChange={(value) => setSecurityForm((form) => ({ ...form, requireSandbox: value }))} />
                          <CheckField label="Network Access" checked={securityForm.allowNetworkAccess} onChange={(value) => setSecurityForm((form) => ({ ...form, allowNetworkAccess: value }))} danger />
                          <CheckField label="File Read" checked={securityForm.allowFileRead} onChange={(value) => setSecurityForm((form) => ({ ...form, allowFileRead: value }))} />
                          <CheckField label="File Write" checked={securityForm.allowFileWrite} onChange={(value) => setSecurityForm((form) => ({ ...form, allowFileWrite: value }))} />
                          <TextField label="Allowed Languages" value={securityForm.allowedLanguages} onChange={(value) => setSecurityForm((form) => ({ ...form, allowedLanguages: value }))} placeholder="python, javascript" />
                          <TextField label="Allowed Hosts" value={securityForm.allowedHosts} onChange={(value) => setSecurityForm((form) => ({ ...form, allowedHosts: value }))} placeholder="example.com" />
                          <TextField label="Allowed Tools" value={securityForm.allowedTools} onChange={(value) => setSecurityForm((form) => ({ ...form, allowedTools: value }))} placeholder="empty = all except denied" />
                          <TextField label="Denied Tools" value={securityForm.deniedTools} onChange={(value) => setSecurityForm((form) => ({ ...form, deniedTools: value }))} />
                          <TextField label="Denied Paths" value={securityForm.deniedPaths} onChange={(value) => setSecurityForm((form) => ({ ...form, deniedPaths: value }))} placeholder="/etc, /root/.ssh" />
                          <Button size="sm" onClick={saveSecurity} disabled={savingSecurity || !tenantSecurity}>
                            {savingSecurity ? "Saving security…" : "Save Security"}
                          </Button>
                        </CardContent>
                      </Card>
                    </div>

                    <Card>
                      <CardHeader>
                        <CardTitle className="flex items-center gap-2 text-sm">
                          <Layers className="h-4 w-4 text-muted-foreground" />
                          Tenant Skills
                        </CardTitle>
                      </CardHeader>
                      <CardContent>
                        {tenantSkills.length === 0 ? (
                          <div className="py-6 text-center text-xs text-muted-foreground">
                            No tenant-scoped skills installed.
                          </div>
                        ) : (
                          <div className="grid gap-2 md:grid-cols-2">
                            {tenantSkills.map((skill) => (
                              <div key={skill.name} className="border border-border p-3">
                                <div className="flex items-start justify-between gap-2">
                                  <div className="min-w-0">
                                    <div className="truncate text-sm font-medium text-foreground">
                                      {skill.name}
                                    </div>
                                    <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                                      {skill.description || "No description available."}
                                    </p>
                                  </div>
                                  {skill.readOnly && <Badge variant="outline">Read-only</Badge>}
                                </div>
                                <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-muted-foreground">
                                  {skill.version && <span>v{skill.version}</span>}
                                  {skill.source && <span>{skill.source}</span>}
                                  <span>{skill.scope}</span>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </CardContent>
                    </Card>

                    <Card>
                      <CardHeader>
                        <CardTitle className="flex items-center gap-2 text-sm">
                          <FileClock className="h-4 w-4 text-muted-foreground" />
                          Recent Audit Events
                        </CardTitle>
                      </CardHeader>
                      <CardContent>
                        {tenantAudit.length === 0 ? (
                          <div className="py-6 text-center text-xs text-muted-foreground">
                            No audit events found.
                          </div>
                        ) : (
                          <div className="flex flex-col divide-y divide-border border border-border">
                            {tenantAudit.map((event, index) => (
                              <div key={`${event.timestamp}-${event.type}-${index}`} className="p-3">
                                <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
                                  <div className="text-sm font-medium text-foreground">{event.type || event.event}</div>
                                  <div className="text-[11px] text-muted-foreground">{formatTime(event.timestamp)}</div>
                                </div>
                                <pre className="mt-2 max-h-24 overflow-auto whitespace-pre-wrap text-[11px] normal-case text-muted-foreground">
                                  {JSON.stringify(event.details ?? {}, null, 2)}
                                </pre>
                              </div>
                            ))}
                          </div>
                        )}
                      </CardContent>
                    </Card>
                  </>
                )}
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}


function NumberField({ label, value, onChange, hint }: { label: string; value: string; onChange: (value: string) => void; hint?: string }) {
  return (
    <label className="grid gap-1">
      <span className="text-[11px] tracking-[0.12em] text-muted-foreground uppercase">{label}</span>
      <Input type="number" min="0" value={value} onChange={(e) => onChange(e.target.value)} />
      {hint && hint !== "—" && <span className="text-[10px] text-muted-foreground normal-case">{hint}</span>}
    </label>
  );
}

function TextField({ label, value, onChange, placeholder }: { label: string; value: string; onChange: (value: string) => void; placeholder?: string }) {
  return (
    <label className="grid gap-1">
      <span className="text-[11px] tracking-[0.12em] text-muted-foreground uppercase">{label}</span>
      <Input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  );
}

function CheckField({ label, checked, onChange, danger }: { label: string; checked: boolean; onChange: (value: boolean) => void; danger?: boolean }) {
  return (
    <label className={`flex items-center justify-between gap-3 border border-border p-2 ${danger && checked ? "border-destructive/60 bg-destructive/10" : ""}`}>
      <span className="text-[11px] tracking-[0.12em] text-muted-foreground uppercase">{label}</span>
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 accent-foreground"
      />
    </label>
  );
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="border border-border p-3">
      <div className="text-[11px] tracking-[0.12em] text-muted-foreground uppercase">{label}</div>
      <div className="mt-2 text-xl text-foreground">{value}</div>
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="border border-border p-3">
      <div className="text-[11px] tracking-[0.12em] text-muted-foreground uppercase">{label}</div>
      <div className="mt-2 text-sm text-foreground normal-case">{value}</div>
    </div>
  );
}

function QuotaBar({
  label,
  used,
  max,
  unit,
}: {
  label: string;
  used: number;
  max: number;
  unit?: "bytes" | "number";
}) {
  const pct = max > 0 ? Math.min(100, Math.round((used / max) * 100)) : 0;
  const color =
    pct >= 90 ? "bg-destructive" : pct >= 70 ? "bg-warning" : "bg-success";
  const fmt = (n: number) => {
    if (unit === "bytes") return formatBytes(n);
    return formatNumber(n);
  };
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between text-[11px]">
        <span className="text-muted-foreground uppercase tracking-wider">
          {label}
        </span>
        <span className="font-mono">
          {fmt(used)} / {fmt(max)} ({pct}%)
        </span>
      </div>
      <div className="h-1.5 w-full bg-muted overflow-hidden rounded-full">
        <div
          className={`h-full ${color} transition-all`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}
