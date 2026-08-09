import { useEffect, useState, useCallback } from "react";
import { opsNocApi } from "@/lib/api/ops";

export default function DLQPage() {
  const [items, setItems] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(() => {
    opsNocApi.getDLQ().then((r) => setItems(r.items ?? [])).catch(() => {}).finally(() => setLoading(false));
  }, []);
  useEffect(() => { reload(); }, [reload]);

  const retry = (id: string) => opsNocApi.retryDLQItem(id).then(reload);
  const resolve = (id: string) => opsNocApi.resolveDLQItem(id).then(reload);

  return (
    <div className="space-y-4 p-6">
      <h1 className="text-xl font-semibold">Dead Letter Queue</h1>
      {loading ? <p className="text-sm text-muted-foreground">Loading…</p> :
       items.length === 0 ? <p className="text-sm text-muted-foreground">Queue is empty ✓</p> :
       <div className="space-y-2">
         {items.map((item, i) => {
           const it = item as { itemId?: string; runId?: string; taskTitle?: string; error?: string };
           return (
             <div key={i} className="rounded-lg border border-red-500/20 bg-red-500/5 p-3">
               <div className="flex items-center justify-between">
                 <span className="font-medium">{it.taskTitle ?? it.itemId ?? "Unknown"}</span>
                 <div className="flex gap-2">
                   <button onClick={() => retry(it.itemId!)} className="rounded bg-primary/10 px-2 py-1 text-xs text-primary">Retry</button>
                   <button onClick={() => resolve(it.itemId!)} className="rounded bg-muted px-2 py-1 text-xs">Resolve</button>
                 </div>
               </div>
               {it.error && <p className="mt-1 text-xs text-muted-foreground">{it.error}</p>}
               {it.runId && <p className="mt-0.5 text-xs text-muted-foreground">Run: {it.runId}</p>}
             </div>
           );
         })}
       </div>}
    </div>
  );
}
