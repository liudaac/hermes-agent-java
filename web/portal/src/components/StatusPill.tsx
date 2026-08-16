import { cn } from "@hermes/ui";

const STATUS_STYLES: Record<string, string> = {
  running: "bg-primary/10 text-primary",
  succeeded: "bg-success/10 text-success",
  failed: "bg-destructive/10 text-destructive",
  queued: "bg-muted text-muted-foreground",
  cancelled: "bg-muted text-muted-foreground",
  waiting_approval: "bg-warning/10 text-warning",
  blocked: "bg-destructive/10 text-destructive",
};

export function StatusPill({ status, className }: { status: string | null | undefined; className?: string }) {
  const key = (status ?? "").toLowerCase();
  return (
    <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-[11px] font-medium", STATUS_STYLES[key] ?? STATUS_STYLES.queued, className)}>
      {status}
    </span>
  );
}
