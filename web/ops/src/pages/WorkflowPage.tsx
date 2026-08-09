import { useEffect, useState, useCallback } from "react";
import { opsNocApi } from "@/lib/api/ops";

export default function WorkflowPage() {
  const [workflows, setWorkflows] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(() => {
    opsNocApi.getWorkflows().then((r) => setWorkflows(r.workflows ?? [])).catch(() => {}).finally(() => setLoading(false));
  }, []);
  useEffect(() => { reload(); }, [reload]);

  const approve = (id: string, decision: string) => opsNocApi.approveWorkflowCheckpoint(id, decision).then(reload);

  return (
    <div className="space-y-4 p-6">
      <h1 className="text-xl font-semibold">Workflows</h1>
      {loading ? <p className="text-sm text-muted-foreground">Loading…</p> :
       workflows.length === 0 ? <p className="text-sm text-muted-foreground">No active workflows</p> :
       <div className="space-y-2">
         {workflows.map((wf, i) => {
           const w = wf as { workflowId?: string; title?: string; status?: string; pendingCheckpoint?: string };
           return (
             <div key={i} className="rounded-lg border bg-card p-3">
               <div className="flex items-center justify-between">
                 <span className="font-medium">{w.title ?? w.workflowId ?? "Unknown"}</span>
                 <span className="rounded bg-muted px-2 py-0.5 text-xs">{w.status ?? "unknown"}</span>
               </div>
               {w.pendingCheckpoint && (
                 <div className="mt-2 flex gap-2">
                   <button onClick={() => approve(w.workflowId!, "approve")} className="rounded bg-green-500/10 px-2 py-1 text-xs text-green-600">Approve</button>
                   <button onClick={() => approve(w.workflowId!, "reject")} className="rounded bg-red-500/10 px-2 py-1 text-xs text-red-600">Reject</button>
                 </div>
               )}
             </div>
           );
         })}
       </div>}
    </div>
  );
}
