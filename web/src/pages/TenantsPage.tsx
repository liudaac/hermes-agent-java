import { useEffect, useMemo, useState } from "react";
import {
  CheckCircle2,
  CircleAlert,
  Layers,
  Pause,
  Play,
  RefreshCw,
  Search,
  Shield,
  Trash2,
  Users,
} from "lucide-react";
import { H2 } from "@nous-research/ui";
import { api } from "@/lib/api";
import type { TenantSkillInfo, TenantSummary } from "@/lib/api";
import { useToast } from "@/hooks/useToast";
import { Toast } from "@/components/Toast";
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

export default function TenantsPage() {
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [selectedTenantId, setSelectedTenantId] = useState<string | null>(null);
  const [selectedTenant, setSelectedTenant] = useState<TenantSummary | null>(null);
  const [tenantSkills, setTenantSkills] = useState<TenantSkillInfo[]>([]);
  const [newTenantId, setNewTenantId] = useState("");
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [busyTenantId, setBusyTenantId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const { toast, showToast } = useToast();

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
      const [tenant, skills] = await Promise.all([
        api.getTenant(tenantId),
        api.getTenantSkills(tenantId),
      ]);
      setSelectedTenant(tenant);
      setTenantSkills(skills.skills ?? []);
    } catch (e) {
      showToast(`Failed to load tenant details: ${e}`, "error");
      setSelectedTenant(null);
      setTenantSkills([]);
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

  return (
    <div className="flex flex-col gap-4">
      <Toast toast={toast} />

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
                      {selectedTenant.state === "ACTIVE" ? (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => runTenantAction(selectedTenant.tenantId, "suspend")}
                          disabled={busyTenantId === selectedTenant.tenantId}
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
                        disabled={busyTenantId === selectedTenant.tenantId}
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
