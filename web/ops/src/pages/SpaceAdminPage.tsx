import { useEffect, useState, useCallback } from "react";
import { threeLayerApi, type SpaceOverview, type SpaceMember, type KnowledgeEntry, type SpacePolicy, type SpaceCapability } from "@/lib/api/three-layer";

const SPACE = "default";

export default function SpaceAdminPage() {
  const [overview, setOverview] = useState<SpaceOverview | null>(null);
  const [members, setMembers] = useState<SpaceMember[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeEntry[]>([]);
  const [policy, setPolicy] = useState<SpacePolicy | null>(null);
  const [capabilities, setCapabilities] = useState<SpaceCapability | null>(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<"overview" | "knowledge" | "members" | "policy" | "capabilities">("overview");

  const reload = useCallback(() => {
    Promise.all([
      threeLayerApi.getSpaceOverview(SPACE).catch(() => null),
      threeLayerApi.getSpaceMembers(SPACE).catch(() => ({ members: [] as SpaceMember[] })),
      threeLayerApi.getSpaceKnowledge(SPACE).catch(() => ({ entries: [] as KnowledgeEntry[] })),
      threeLayerApi.getSpacePolicy(SPACE).catch(() => null),
      threeLayerApi.getSpaceCapabilities(SPACE).catch(() => null),
    ]).then(([ov, mem, know, pol, cap]) => {
      setOverview(ov?.overview ?? null);
      setMembers(mem?.members ?? []);
      setKnowledge(know?.entries ?? []);
      setPolicy(pol?.policy ?? null);
      setCapabilities(cap?.capabilities ?? null);
      setLoading(false);
    });
  }, []);

  useEffect(() => { reload(); }, [reload]);
  if (loading) return <div className="p-6 text-muted-foreground">Loading…</div>;

  const tabs = [
    { key: "overview", label: "Overview" },
    { key: "knowledge", label: "Knowledge" },
    { key: "members", label: "Members" },
    { key: "policy", label: "Policy" },
    { key: "capabilities", label: "Capabilities" },
  ] as const;

  return (
    <div className="space-y-4 p-6">
      <h1 className="text-xl font-semibold">Space Admin</h1>
      <div className="flex gap-2 border-b">
        {tabs.map((t) => (
          <button key={t.key} onClick={() => setTab(t.key)}
            className={`px-3 py-2 text-sm border-b-2 transition-colors ${
              tab === t.key ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground"
            }`}>{t.label}</button>
        ))}
      </div>

      {tab === "overview" && overview && (
        <div className="grid grid-cols-5 gap-4">
          {[
            { label: "Members", value: overview.memberCount },
            { label: "Knowledge", value: overview.knowledgeCount },
            { label: "Skills", value: overview.skillCount },
            { label: "Tools", value: overview.toolCount },
            { label: "Templates", value: overview.templateCount },
          ].map((m) => (
            <div key={m.label} className="rounded-lg border bg-card p-4 text-center">
              <div className="text-2xl font-bold text-primary">{m.value}</div>
              <div className="text-xs text-muted-foreground">{m.label}</div>
            </div>
          ))}
        </div>
      )}

      {tab === "knowledge" && (
        <div className="space-y-2">
          {knowledge.length === 0 ? <p className="text-sm text-muted-foreground">No entries</p> : knowledge.map((k) => (
            <div key={k.id} className="rounded-lg border bg-card p-3">
              <div className="flex justify-between">
                <span className="font-medium">{k.title}</span>
                <span className="rounded bg-muted px-2 py-0.5 text-xs">{k.category}</span>
              </div>
              <p className="mt-1 text-sm text-muted-foreground line-clamp-2">{k.content}</p>
            </div>
          ))}
        </div>
      )}

      {tab === "members" && (
        <div className="space-y-2">
          {members.length === 0 ? <p className="text-sm text-muted-foreground">No members</p> : members.map((m) => (
            <div key={m.userId} className="flex items-center justify-between rounded-lg border bg-card p-3">
              <div><span className="font-medium">{m.displayName}</span><span className="ml-2 text-xs text-muted-foreground">{m.userId}</span></div>
              <span className="rounded bg-muted px-2 py-0.5 text-xs">{m.role}</span>
            </div>
          ))}
        </div>
      )}

      {tab === "policy" && policy && (
        <div className="space-y-3">
          <div><h3 className="mb-2 text-sm font-medium text-muted-foreground">Approval Modes</h3>
            <div className="space-y-1">{Object.entries(policy.approvalModes).map(([k, v]) => (
              <div key={k} className="flex justify-between rounded border bg-card p-2 text-sm"><span>{k}</span><span className="font-mono text-xs">{v}</span></div>
            ))}</div>
          </div>
          <div className="flex gap-4">
            <span className="rounded border bg-card px-3 py-1 text-sm">Sandbox: {policy.sandboxEnforced ? "On" : "Off"}</span>
            <span className="rounded border bg-card px-3 py-1 text-sm">Decay: {policy.decayPolicy}</span>
            <span className="rounded border bg-card px-3 py-1 text-sm">Max Runs: {policy.maxConcurrentRuns}</span>
          </div>
        </div>
      )}

      {tab === "capabilities" && capabilities && (
        <div className="space-y-4">
          <div><h3 className="mb-2 text-sm font-medium text-muted-foreground">Skills ({capabilities.installedSkills.length})</h3>
            <div className="flex flex-wrap gap-1">{capabilities.installedSkills.length === 0 ? <span className="text-sm text-muted-foreground">None</span> :
              capabilities.installedSkills.map((s) => <span key={s} className="rounded bg-muted px-2 py-0.5 text-xs">{s}</span>)}</div>
          </div>
          <div><h3 className="mb-2 text-sm font-medium text-muted-foreground">Tools ({capabilities.enabledTools.length})</h3>
            <div className="flex flex-wrap gap-1">{capabilities.enabledTools.length === 0 ? <span className="text-sm text-muted-foreground">None</span> :
              capabilities.enabledTools.map((t) => <span key={t} className="rounded bg-primary/10 px-2 py-0.5 text-xs text-primary">{t}</span>)}</div>
          </div>
        </div>
      )}
    </div>
  );
}
