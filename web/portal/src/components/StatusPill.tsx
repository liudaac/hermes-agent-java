import { cn } from "@hermes/ui";

interface StatusPillProps {
  status: string | null | undefined;
  className?: string;
}

const MAPPING: Record<string, { label: string; tone: string }> = {
  queued: { label: "排队中", tone: "bg-[oklch(0.30_0.02_50_/_0.6)] text-[var(--color-text-secondary)]" },
  running: { label: "执行中", tone: "bg-[oklch(0.78_0.16_85_/_0.18)] text-[oklch(0.85_0.14_85)]" },
  succeeded: { label: "已完成", tone: "bg-[oklch(0.72_0.14_145_/_0.18)] text-[oklch(0.78_0.12_145)]" },
  failed: { label: "失败", tone: "bg-[oklch(0.68_0.20_25_/_0.18)] text-[oklch(0.75_0.18_25)]" },
  cancelled: { label: "已取消", tone: "bg-[oklch(0.30_0.02_50_/_0.6)] text-[var(--color-text-muted)]" },
  waiting_approval: { label: "待审批", tone: "bg-[oklch(0.70_0.16_280_/_0.18)] text-[oklch(0.78_0.14_280)]" },
  blocked: { label: "已拦截", tone: "bg-[oklch(0.68_0.20_25_/_0.18)] text-[oklch(0.75_0.18_25)]" },
};

export function StatusPill({ status, className }: StatusPillProps) {
  const key = (status ?? "").toLowerCase();
  const m = MAPPING[key] ?? {
    label: status ?? "—",
    tone: "bg-[oklch(0.30_0.02_50_/_0.6)] text-[var(--color-text-muted)]",
  };
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5",
        "text-[11px] font-medium tracking-wide",
        m.tone,
        className,
      )}
    >
      <span
        className={cn(
          "h-1.5 w-1.5 rounded-full",
          key === "running" && "bg-[oklch(0.85_0.14_85)] animate-pulse",
          key === "queued" && "bg-[var(--color-text-muted)]",
          key === "succeeded" && "bg-[oklch(0.78_0.12_145)]",
          key === "failed" && "bg-[oklch(0.75_0.18_25)]",
          key === "cancelled" && "bg-[var(--color-text-muted)]",
          key === "waiting_approval" && "bg-[oklch(0.78_0.14_280)]",
          key === "blocked" && "bg-[oklch(0.75_0.18_25)]",
        )}
      />
      {m.label}
    </span>
  );
}
