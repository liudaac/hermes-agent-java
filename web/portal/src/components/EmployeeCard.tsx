import { Link } from "react-router-dom";
import { Users } from "lucide-react";
import { GlassCard } from "@/components/GlassCard";
import { cn } from "@hermes/ui";
import type { BusinessTeamCard } from "@/api/types-portal";
import type { HarnessInfo } from "@/hooks/useActiveHarnesses";

interface EmployeeCardProps {
  team: BusinessTeamCard;
  to?: string;
  harness?: HarnessInfo;
}

function statusClass(status: string | undefined, harness?: HarnessInfo): string {
  if (harness) {
    if (harness.status === "running") return "busy";
    if (harness.status === "paused_approval") return "busy";
    if (harness.status === "error") return "error";
  }
  const s = (status ?? "").toLowerCase();
  if (s === "online" || s === "active" || s === "ready" || s === "succeeded") return "online";
  if (s === "running" || s === "busy" || s === "executing" || s === "queued") return "busy";
  if (s === "error" || s === "failed" || s === "blocked") return "error";
  return "offline";
}

function statusLabel(status: string | undefined, harness?: HarnessInfo): string {
  if (harness) {
    if (harness.status === "running") return "执行中";
    if (harness.status === "paused_approval") return "等待审批";
    if (harness.status === "error") return "异常";
    if (harness.status === "idle") return "在线";
  }
  const s = (status ?? "").toLowerCase();
  if (s === "online" || s === "active" || s === "ready" || s === "succeeded") return "在线";
  if (s === "running" || s === "busy" || s === "executing") return "执行中";
  if (s === "queued") return "排队中";
  if (s === "error" || s === "failed" || s === "blocked") return "异常";
  return "离线";
}

function pickEmoji(name: string): string {
  const n = name.length;
  const palette = ["✨", "🌱", "🪴", "🛠", "🧭", "📣", "🧠", "🎯", "📊", "🤝"];
  return palette[n % palette.length] ?? "✨";
}

export function EmployeeCard({ team, to = `/teams/${team.teamId}`, harness }: EmployeeCardProps) {
  const initials = (team.name ?? "·").trim().slice(0, 2);
  const sc = statusClass(team.status, harness);
  const sl = statusLabel(team.status, harness);

  return (
    <Link to={to} className="block">
      <GlassCard
        tone="default"
        interactive
        padding="md"
        className="flex flex-col gap-3"
      >
        <div className="flex items-start gap-3">
          <div
            className={cn(
              "flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl",
              "bg-gradient-to-br from-[oklch(0.78_0.16_70_/_0.3)] to-[oklch(0.55_0.10_60_/_0.2)]",
              "border border-[oklch(0.55_0.10_65_/_0.35)]",
              "text-base font-semibold tracking-wide text-[var(--color-text-primary)]",
            )}
          >
            {pickEmoji(team.name ?? "") || initials}
          </div>

          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <h3 className="truncate text-[15px] font-semibold text-[var(--color-text-primary)]">
                {team.name}
              </h3>
              <span
                className={cn("status-dot shrink-0", sc)}
                aria-label={sl}
              />
            </div>
            <p className="mt-0.5 line-clamp-2 text-[12px] leading-snug text-[var(--color-text-secondary)]">
              {team.scenario ?? "数字员工"}
            </p>
          </div>
        </div>

        <div className="flex items-center justify-between text-[11px] text-[var(--color-text-muted)]">
          <span className="inline-flex items-center gap-1">
            <Users className="h-3 w-3" />
            {sl}
          </span>
          <span>v{team.activeVersion} · {team.versionCount} 个版本</span>
        </div>
      </GlassCard>
    </Link>
  );
}
