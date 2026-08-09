import { useEffect, useState, useCallback } from "react";
import { opsNocApi } from "@/lib/api/ops";

export default function HumanLoopPage() {
  const [takeovers, setTakeovers] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(() => {
    opsNocApi.getTakeovers().then((r) => setTakeovers(r.takeovers ?? [])).catch(() => {}).finally(() => setLoading(false));
  }, []);
  useEffect(() => { reload(); }, [reload]);

  const confirm = (id: string) => opsNocApi.confirmTakeover(id).then(reload);
  const release = (id: string) => opsNocApi.releaseTakeover(id).then(reload);

  return (
    <div className="space-y-4 p-6">
      <h1 className="text-xl font-semibold">Human-in-the-Loop</h1>
      {loading ? <p className="text-sm text-muted-foreground">Loading…</p> :
       takeovers.length === 0 ? <p className="text-sm text-muted-foreground">No active takeovers</p> :
       <div className="space-y-2">
         {takeovers.map((t, i) => {
           const ts = t as { takeoverId?: string; runId?: string; operatorId?: string; status?: string };
           return (
             <div key={i} className="rounded-lg border border-amber-500/20 bg-amber-500/5 p-3">
               <div className="flex items-center justify-between">
                 <div>
                   <span className="font-medium">Takeover {ts.takeoverId?.substring(0, 8)}</span>
                   {ts.operatorId && <span className="ml-2 text-xs text-muted-foreground">by {ts.operatorId}</span>}
                 </div>
                 <span className="rounded bg-amber-500/10 px-2 py-0.5 text-xs text-amber-600">{ts.status ?? "pending"}</span>
               </div>
               {ts.runId && <p className="mt-1 text-xs text-muted-foreground">Run: {ts.runId}</p>}
               <div className="mt-2 flex gap-2">
                 <button onClick={() => confirm(ts.takeoverId!)} className="rounded bg-primary/10 px-2 py-1 text-xs text-primary">Confirm</button>
                 <button onClick={() => release(ts.takeoverId!)} className="rounded bg-muted px-2 py-1 text-xs">Release</button>
               </div>
             </div>
           );
         })}
       </div>}
    </div>
  );
}
