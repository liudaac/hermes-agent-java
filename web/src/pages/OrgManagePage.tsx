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
  team_ids?: string[];
};

type TeamMemberRole = {
  agent_id: string;
  name?: string;
  description?: string;
  level?: string;
  skills?: string[];
  responsibilities?: string[];
  allowed_tools?: string[];
  missing_role?: boolean;
  is_lead?: boolean;
};

type TeamRow = {
  tenant_id: string;
  team_id: string;
  name: string;
  mission?: string;
  members?: string[];
  lead?: string;
  size?: number;
  member_roles?: TeamMemberRole[];
};

type Summary = {
  tenants: number;
  teams: number;
  agent_roles: number;
  relationship?: Record<string, string>;
};

const LEVELS = ["JUNIOR", "MID", "SENIOR", "LEAD"];

export default function OrgManagePage() {
  const { t } = useI18n();
  const om = t.orgManage;
  const [summary, setSummary] = useState<Summary | null>(null);
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [teams, setTeams] = useState<TeamRow[]>([]);
  const [audit, setAudit] = useState<any[]>([]);
  const [tenantOptions, setTenantOptions] = useState<string[]>(["default"]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [teamForm, setTeamForm] = useState({
    tenant_id: "default",
    team_id: "",
    name: "",
    mission: "",
    members: "",
    lead: "",
  });
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
    team_ids: "",
  });

  const tenants = useMemo(() => Array.from(new Set(["default", ...tenantOptions, ...roles.map((r) => r.tenant_id), ...teams.map((team) => team.tenant_id)])).sort(), [tenantOptions, roles, teams]);
  const roleOptionsForTeam = useMemo(() => roles.filter((role) => role.tenant_id === teamForm.tenant_id), [roles, teamForm.tenant_id]);
  const teamOptionsForRole = useMemo(() => teams.filter((team) => team.tenant_id === form.tenant_id), [teams, form.tenant_id]);
  const selectedRoleTeams = useMemo(() => split(form.team_ids), [form.team_ids]);
  const selectedTeamMembers = useMemo(() => split(teamForm.members), [teamForm.members]);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [s, r, tm, a, tenantsResponse] = await Promise.all([
        fetchJSON<Summary>("/api/org/manage/summary"),
        fetchJSON<{ roles: RoleRow[] }>("/api/org/manage/roles"),
        fetchJSON<{ teams: TeamRow[] }>("/api/org/manage/teams"),
        fetchJSON<{ audit: any[] }>("/api/org/manage/audit?n=30"),
        api.getTenants(),
      ]);
      setSummary(s);
      setRoles(r.roles || []);
      setTeams(tm.teams || []);
      setAudit(a.audit || []);
      setTenantOptions((tenantsResponse.tenants || []).map((tenant) => tenant.tenantId));
    } catch (err: any) {
      setError(err?.message || om.failedToLoad);
    } finally {
      setLoading(false);
    }
  }, [om.failedToLoad]);

  useEffect(() => { loadData(); }, [loadData]);

  const submitTeam = async () => {
    setError(null);
    try {
      await fetchJSON("/api/org/manage/teams", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...teamForm,
          members: split(teamForm.members),
        }),
      });
      setTeamForm((f) => ({ ...f, team_id: "", name: "", mission: "", members: "", lead: "" }));
      await loadData();
    } catch (err: any) {
      setError(err?.message || om.teams.saveFailed);
    }
  };

  const editTeam = (team: TeamRow) => {
    setTeamForm({
      tenant_id: team.tenant_id,
      team_id: team.team_id,
      name: team.name,
      mission: team.mission || "",
      members: (team.members || []).join(", "),
      lead: team.lead || "",
    });
  };

  const removeTeam = async (team: TeamRow) => {
    if (!window.confirm(om.teams.deleteConfirm.replace("{team}", team.team_id))) return;
    setError(null);
    try {
      await fetchJSON(`/api/org/manage/teams/${encodeURIComponent(team.tenant_id)}/${encodeURIComponent(team.team_id)}`, { method: "DELETE" });
      await loadData();
    } catch (err: any) {
      setError(err?.message || om.teams.deleteFailed);
    }
  };

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
          team_ids: split(form.team_ids),
        }),
      });
      setForm((f) => ({ ...f, agent_id: "", role_name: "", description: "", skills: "", responsibilities: "", reports_to: "", allowed_tools: "", team_ids: "" }));
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
      team_ids: (role.team_ids || []).join(", "),
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
        <InfoCard title={om.relationship.orgManagement} value={summary?.teams ?? 0} desc={om.relationship.orgManagementDesc} />
        <InfoCard title={om.relationship.orgControl} value={roles.length} desc={om.relationship.orgControlDesc} />
      </div>

      <div className="grid gap-4 lg:grid-cols-[420px,1fr]">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base"><Plus className="h-4 w-4" /> {om.teams.formTitle}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <Field label={om.fields.tenant}>
              <select className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={teamForm.tenant_id} onChange={(e) => setTeamForm({ ...teamForm, tenant_id: e.target.value })}>
                {tenants.map((tenant) => <option key={tenant} value={tenant}>{tenant}</option>)}
              </select>
            </Field>
            <Field label={om.teams.teamId}><input className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={teamForm.team_id} onChange={(e) => setTeamForm({ ...teamForm, team_id: e.target.value })} /></Field>
            <Field label={om.teams.name}><input className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={teamForm.name} onChange={(e) => setTeamForm({ ...teamForm, name: e.target.value })} /></Field>
            <Field label={om.teams.mission}><textarea className="w-full rounded-md border bg-background px-3 py-2 text-sm" rows={2} value={teamForm.mission} onChange={(e) => setTeamForm({ ...teamForm, mission: e.target.value })} /></Field>
            <Field label={om.teams.members}>
              <select
                multiple
                className="h-28 w-full rounded-md border bg-background px-3 py-2 text-sm"
                value={selectedTeamMembers}
                onChange={(e) => setTeamForm({ ...teamForm, members: Array.from(e.target.selectedOptions).map((option) => option.value).join(", ") })}
              >
                {roleOptionsForTeam.map((role) => <option key={role.agent_id} value={role.agent_id}>{role.agent_id} · {role.name} · {role.level}</option>)}
              </select>
              <input className="mt-2 w-full rounded-md border bg-background px-3 py-2 text-xs" value={teamForm.members} placeholder={om.form.csvPlaceholder} onChange={(e) => setTeamForm({ ...teamForm, members: e.target.value })} />
            </Field>
            <Field label={om.teams.lead}>
              <select className="w-full rounded-md border bg-background px-3 py-2 text-sm" value={teamForm.lead} onChange={(e) => setTeamForm({ ...teamForm, lead: e.target.value })}>
                <option value="">—</option>
                {roleOptionsForTeam.map((role) => <option key={role.agent_id} value={role.agent_id}>{role.agent_id} · {role.name}</option>)}
              </select>
            </Field>
            <Button className="w-full" onClick={submitTeam} disabled={!teamForm.tenant_id || !teamForm.team_id || !teamForm.name}>{om.teams.saveTeam}</Button>
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2 text-base"><Network className="h-4 w-4" /> {om.teams.title}</CardTitle></CardHeader>
          <CardContent>
            {teams.length === 0 ? <div className="text-sm text-muted-foreground">{om.teams.empty}</div> : (
              <div className="space-y-3">
                {teams.map((team) => (
                  <div key={`${team.tenant_id}:${team.team_id}`} className="rounded-lg border border-current/15 p-3">
                    <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge variant="outline">{team.tenant_id}</Badge>
                          <span className="font-medium">{team.name}</span>
                          <Badge variant="secondary">{team.team_id}</Badge>
                        </div>
                        <div className="mt-1 text-sm text-muted-foreground">{team.mission || t.common.none}</div>
                        {team.lead && <div className="mt-1 text-xs text-muted-foreground">{om.teams.lead}: {team.lead}</div>}
                        <TeamMemberRoles members={team.member_roles || []} fallback={team.members || []} />
                      </div>
                      <div className="flex gap-2">
                        <Button variant="outline" size="sm" onClick={() => editTeam(team)}>{om.roles.edit}</Button>
                        <Button variant="ghost" size="sm" onClick={() => removeTeam(team)}><Trash2 className="h-3 w-3" /></Button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
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
            <Field label={om.fields.teams}>
              <select
                multiple
                className="h-24 w-full rounded-md border bg-background px-3 py-2 text-sm"
                value={selectedRoleTeams}
                onChange={(e) => setForm({ ...form, team_ids: Array.from(e.target.selectedOptions).map((option) => option.value).join(", ") })}
              >
                {teamOptionsForRole.map((team) => <option key={team.team_id} value={team.team_id}>{team.team_id} · {team.name}</option>)}
              </select>
            </Field>
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
                        <ChipRow label={om.fields.teams} values={role.team_ids || []} />
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
      <Card>
        <CardHeader><CardTitle className="text-base">{om.audit.title}</CardTitle></CardHeader>
        <CardContent>
          {audit.length === 0 ? <div className="text-sm text-muted-foreground">{om.audit.empty}</div> : (
            <div className="space-y-2">
              {audit.slice(0, 12).map((entry, idx) => (
                <div key={`${entry.tenant_id}:${entry.time}:${idx}`} className="rounded-md border border-current/10 p-3 text-sm">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="font-medium">{formatOrgManageEvent(entry.event, om)}</div>
                    <Badge variant="outline">{new Date(entry.time).toLocaleTimeString()}</Badge>
                  </div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    {entry.tenant_id} · {entry.action || "—"}
                    {entry.team_id && ` · ${om.teams.teamId}: ${entry.team_id}`}
                    {entry.agent_id && ` · ${om.fields.agentId}: ${entry.agent_id}`}
                    {entry.role && ` · ${om.fields.roleName}: ${entry.role}`}
                    {entry.name && ` · ${om.teams.name}: ${entry.name}`}
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

    </div>
  );
}

function InfoCard({ title, value, desc }: { title: string; value: number; desc: string }) {
  return <Card><CardContent className="p-4"><div className="text-sm text-muted-foreground">{title}</div><div className="mt-1 text-2xl font-semibold">{value}</div><div className="mt-1 text-xs text-muted-foreground">{desc}</div></CardContent></Card>;
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="block space-y-1"><span className="text-xs font-medium text-muted-foreground">{label}</span>{children}</label>;
}


function TeamMemberRoles({ members, fallback }: { members: TeamMemberRole[]; fallback: string[] }) {
  const { t } = useI18n();
  const om = t.orgManage;
  const [expanded, setExpanded] = useState(false);
  if (!members.length) return <ChipRow label={om.teams.members} values={fallback} />;
  return (
    <div className="mt-2 space-y-2 text-xs">
      <div className="flex flex-wrap items-center gap-1">
        <span className="mr-1 text-muted-foreground">{om.teams.members}</span>
        {members.map((member) => (
          <Badge key={member.agent_id} variant={member.is_lead ? "default" : "secondary"}>
            {member.agent_id}{member.name ? ` · ${member.name}` : ""}{member.level ? ` · ${member.level}` : ""}{member.missing_role ? ` · ${om.teams.missingRole}` : ""}
          </Badge>
        ))}
        <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => setExpanded(!expanded)}>
          {expanded ? om.teams.hideDetails : om.teams.showDetails}
        </Button>
      </div>
      {expanded && (
        <div className="space-y-2 rounded-md border border-current/10 p-2">
          {members.map((member) => (
            <div key={member.agent_id} className="rounded-md bg-muted/30 p-2">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{member.agent_id}</span>
                {member.name && <Badge variant="outline">{member.name}</Badge>}
                {member.level && <Badge variant="secondary">{member.level}</Badge>}
                {member.is_lead && <Badge>{om.teams.lead}</Badge>}
                {member.missing_role && <Badge variant="destructive">{om.teams.missingRole}</Badge>}
              </div>
              {member.description && <div className="mt-1 text-muted-foreground">{member.description}</div>}
              <ChipRow label={om.fields.skills} values={member.skills || []} />
              <ChipRow label={om.fields.responsibilities} values={member.responsibilities || []} />
              <ChipRow label={om.fields.allowedTools} values={member.allowed_tools || []} />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function ChipRow({ label, values }: { label: string; values: string[] }) {
  if (!values.length) return null;
  return <div className="mt-2 flex flex-wrap gap-1 text-xs"><span className="mr-1 text-muted-foreground">{label}</span>{values.map((v) => <Badge key={v} variant="secondary">{v}</Badge>)}</div>;
}


function formatOrgManageEvent(event: string, om: ReturnType<typeof useI18n>["t"]["orgManage"]) {
  const events = om.audit.events;
  const map: Record<string, string> = {
    ORG_MANAGEMENT_ROLE_CREATED: events.roleCreated,
    ORG_MANAGEMENT_ROLE_UPDATED: events.roleUpdated,
    ORG_MANAGEMENT_ROLE_DELETED: events.roleDeleted,
    ORG_MANAGEMENT_TEAM_CREATED: events.teamCreated,
    ORG_MANAGEMENT_TEAM_UPDATED: events.teamUpdated,
    ORG_MANAGEMENT_TEAM_DELETED: events.teamDeleted,
  };
  return map[event] || event;
}

function split(value: string) {
  return value.split(",").map((v) => v.trim()).filter(Boolean);
}
