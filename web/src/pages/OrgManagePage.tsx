import { useCallback, useEffect, useMemo, useState } from "react";
import { Network, Plus, RefreshCw, Trash2 } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { api, fetchJSON } from "@/lib/api";
import { useI18n } from "@/i18n";

type RoleRow = {
  tenant_id: string;
  agent_id: string;
  name: string;
  description?: string;
  level: string;
  skills?: string[];
  responsibilities?: string[];
  reports_to?: string;
  allowed_tools?: string[];
};

type Summary = {
  tenants: number;
  agent_roles: number;
  relationship?: Record<string, string>;
};

const LEVELS = ["JUNIOR", "MID", "SENIOR", "LEAD"];

export default function OrgManagePage() {
  const { t } = useI18n();
  const om = t.orgManage;
  const [summary, setSummary] = useState<Summary | null>(null);
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [tenantOptions, setTenantOptions] = useState<string[]>(["default"]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({
    tenant_id: "default",
    agent_id: "",
    role_name: "",
    description: "",
    level: "MID",
    skills: "",
    responsibilities: "",
    reports_to: "",
    allowed_tools: "",
  });

  const tenants = useMemo(() => Array.from(new Set(["default", ...tenantOptions, ...roles.map((r) => r.tenant_id)])).sort(), [tenantOptions, roles]);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [s, r, tenantsResponse] = await Promise.all([
        fetchJSON<Summary>("/api/org/manage/summary"),
        fetchJSON<{ roles: RoleRow[] }>("/api/org/manage/roles"),
        api.getTenants(),
      ]);
      setSummary(s);
      setRoles(r.roles || []);
      setTenantOptions((tenantsResponse.tenants || []).map((tenant) => tenant.tenantId));
    } catch (err: any) {
      setError(err?.message || om.failedToLoad);
    } finally {
      setLoading(false);
    }
  }, [om.failedToLoad]);

  useEffect(() => { loadData(); }, [loadData]);

  const submit = async () => {
    setError(null);
    try {
      await fetchJSON("/api/org/manage/roles", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...form,
          skills: split(form.skills),
          responsibilities: split(form.responsibilities),
          allowed_tools: split(form.allowed_tools),
        }),
      });
      setForm((f) => ({ ...f, agent_id: "", role_name: "", description: "", skills: "", responsibilities: "", reports_to: "", allowed_tools: "" }));
      await loadData();
    } catch (err: any) {
      setError(err?.message || om.saveFailed);
    }
  };

  const edit = (role: RoleRow) => {
    setForm({
      tenant_id: role.tenant_id,
      agent_id: role.agent_id,
      role_name: role.name,
      description: role.description || "",
      level: role.level || "MID",
      skills: (role.skills || []).join(", "),
      responsibilities: (role.responsibilities || []).join(", "),
      reports_to: role.reports_to || "",
      allowed_tools: (role.allowed_tools || []).join(", "),
    });
  };

  const remove = async (role: RoleRow) => {
    if (!window.confirm(om.deleteConfirm.replace("{agent}", role.agent_id))) return;
    setError(null);
    try {
      await fetchJSON(`/api/org/manage/roles/${encodeURIComponent(role.tenant_id)}/${encodeURIComponent(role.agent_id)}`, { method: "DELETE" });
      await loadData();
    } catch (err: any) {
      setError(err?.message || om.deleteFailed);
    }
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{om.title}</h1>
          <p className="text-sm text-muted-foreground">{om.subtitle}</p>
        </div>
        <Button variant="outline" onClick={loadData} disabled={loading}>
          <RefreshCw className={`mr-2 h-4 w-4 ${loading ? "animate-spin" : ""}`} /> {t.common.refresh}
        </Button>
      </div>

      {error && <div className="rounded-md border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-200">{error}</div>}

      <div className="grid gap-4 md:grid-cols-3">
        <InfoCard title={om.relationship.tenants} value={summary?.tenants ?? 0} desc={om.relationship.tenantsDesc} />
        <InfoCard title={om.relationship.orgManagement} value={summary?.agent_roles ?? 0} desc={om.relationship.orgManagementDesc} />
        <InfoCard title={om.relationship.orgControl} value={roles.length} desc={om.relationship.orgControlDesc} />
      </div>

      <div className="grid gap-4 lg:grid-cols-[420px,1fr]">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base"><Plus className="h-4 w-4" /> {om.form.title}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <Field label={om.fields.tenant}>
              <select className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={form.tenant_id} onChange={(e) => setForm({ ...form, tenant_id: e.target.value })}>
                {tenants.map((tenant) => <option key={tenant} value={tenant}>{tenant}</option>)}
              </select>
            </Field>
            <Field label={om.fields.agentId}><input className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={form.agent_id} onChange={(e) => setForm({ ...form, agent_id: e.target.value })} /></Field>
            <Field label={om.fields.roleName}><input className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={form.role_name} onChange={(e) => setForm({ ...form, role_name: e.target.value })} /></Field>
            <Field label={om.fields.description}><textarea className="w-full rounded-md border bg-background px-3 py-2 text-sm" rows={2} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
            <Field label={om.fields.level}>
              <select className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={form.level} onChange={(e) => setForm({ ...form, level: e.target.value })}>
                {LEVELS.map((level) => <option key={level} value={level}>{level}</option>)}
              </select>
            </Field>
            <Field label={om.fields.skills}><input className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={form.skills} placeholder={om.form.csvPlaceholder} onChange={(e) => setForm({ ...form, skills: e.target.value })} /></Field>
            <Field label={om.fields.responsibilities}><input className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={form.responsibilities} placeholder={om.form.csvPlaceholder} onChange={(e) => setForm({ ...form, responsibilities: e.target.value })} /></Field>
            <Field label={om.fields.reportsTo}><input className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={form.reports_to} onChange={(e) => setForm({ ...form, reports_to: e.target.value })} /></Field>
            <Field label={om.fields.allowedTools}><input className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={form.allowed_tools} placeholder={om.form.csvPlaceholder} onChange={(e) => setForm({ ...form, allowed_tools: e.target.value })} /></Field>
            <Button className="w-full" onClick={submit} disabled={!form.tenant_id || !form.agent_id || !form.role_name}>{om.form.saveRole}</Button>
            <p className="text-xs text-muted-foreground">{om.form.note}</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2 text-base"><Network className="h-4 w-4" /> {om.roles.title}</CardTitle></CardHeader>
          <CardContent>
            {roles.length === 0 ? <div className="text-sm text-muted-foreground">{om.roles.empty}</div> : (
              <div className="space-y-3">
                {roles.map((role) => (
                  <div key={`${role.tenant_id}:${role.agent_id}`} className="rounded-lg border border-current/15 p-3">
                    <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge variant="outline">{role.tenant_id}</Badge>
                          <span className="font-medium">{role.agent_id}</span>
                          <Badge>{role.level}</Badge>
                        </div>
                        <div className="mt-1 text-sm text-muted-foreground">{role.name} · {role.description || t.common.none}</div>
                        <ChipRow label={om.fields.skills} values={role.skills || []} />
                        <ChipRow label={om.fields.responsibilities} values={role.responsibilities || []} />
                      </div>
                      <div className="flex gap-2">
                        <Button variant="outline" size="sm" onClick={() => edit(role)}>{om.roles.edit}</Button>
                        <Button variant="ghost" size="sm" onClick={() => remove(role)}><Trash2 className="h-3 w-3" /></Button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function InfoCard({ title, value, desc }: { title: string; value: number; desc: string }) {
  return <Card><CardContent className="p-4"><div className="text-sm text-muted-foreground">{title}</div><div className="mt-1 text-2xl font-semibold">{value}</div><div className="mt-1 text-xs text-muted-foreground">{desc}</div></CardContent></Card>;
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="block space-y-1"><span className="text-xs font-medium text-muted-foreground">{label}</span>{children}</label>;
}

function ChipRow({ label, values }: { label: string; values: string[] }) {
  if (!values.length) return null;
  return <div className="mt-2 flex flex-wrap gap-1 text-xs"><span className="mr-1 text-muted-foreground">{label}</span>{values.map((v) => <Badge key={v} variant="secondary">{v}</Badge>)}</div>;
}

function split(value: string) {
  return value.split(",").map((v) => v.trim()).filter(Boolean);
}
