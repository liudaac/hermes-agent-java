import { useEffect, useState } from "react";
import { threeLayerApi, type OrgOverview } from "@/lib/api/three-layer";

export default function OrgAdminPage() {
  const [overview, setOverview] = useState<OrgOverview | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    threeLayerApi.getOrgOverview()
      .then(setOverview)
      .catch(() => null)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-6 text-muted-foreground">Loading…</div>;
  if (!overview) return <div className="p-6 text-muted-foreground">No data</div>;

  return (
    <div className="space-y-6 p-6">
      <h1 className="text-xl font-semibold">Organization</h1>

      <div>
        <h2 className="mb-3 text-sm font-medium text-muted-foreground">Spaces ({overview.spaces.length})</h2>
        <div className="space-y-2">
          {overview.spaces.length === 0 ? <p className="text-sm text-muted-foreground">No spaces</p> : overview.spaces.map((s) => (
            <div key={s.spaceId} className="rounded-lg border bg-card p-3">
              <div className="flex items-center justify-between">
                <span className="font-medium">{s.spaceName}</span>
                <span className="rounded bg-muted px-2 py-0.5 text-xs">{s.memberCount} members</span>
              </div>
              <div className="mt-2 flex gap-4 text-xs text-muted-foreground">
                <span>Skills {s.skillCount}</span>
                <span>Tools {s.toolCount}</span>
                <span>Knowledge {s.knowledgeCount}</span>
                <span>Templates {s.templateCount}</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <h2 className="mb-3 text-sm font-medium text-muted-foreground">Users ({overview.users.length})</h2>
        <div className="space-y-2">
          {overview.users.length === 0 ? <p className="text-sm text-muted-foreground">No users</p> : overview.users.map((u) => (
            <div key={u.userId} className="flex items-center justify-between rounded-lg border bg-card p-3">
              <span className="font-medium">{u.displayName}</span>
              <span className="rounded bg-muted px-2 py-0.5 text-xs">{u.spaceCount} spaces</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
