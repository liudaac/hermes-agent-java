/**
 * SuggestionTicker — compact ribbon that shows recent proactive
 * suggestions from the Jarvis SSE stream (SLA breaches, DLQ entries,
 * new approvals, run status changes).
 *
 * Clicking a suggestion navigates to linkTo (same-tab anchor so the
 * SPA router can pick it up). Severe unread items pulse.
 */
import { Bell, AlertTriangle, Siren, Info, ExternalLink } from "lucide-react";
import { useJarvisStore } from "../hooks/useJarvisStore";

const SEVERITY_STYLES: Record<string, {
  icon: typeof Info;
  border: string;
  bg: string;
  text: string;
  dot: string;
}> = {
  info: {
    icon: Info,
    border: "border-[oklch(0.65_0.14_220/_0.3)]",
    bg: "bg-[oklch(0.60_0.12_220/_0.08)]",
    text: "text-[oklch(0.82_0.10_220)]",
    dot: "bg-[oklch(0.72_0.14_220)]",
  },
  warning: {
    icon: AlertTriangle,
    border: "border-[oklch(0.72_0.16_80/_0.4)]",
    bg: "bg-[oklch(0.70_0.14_80/_0.08)]",
    text: "text-[oklch(0.85_0.14_80)]",
    dot: "bg-[oklch(0.78_0.16_85)]",
  },
  critical: {
    icon: Siren,
    border: "border-[oklch(0.65_0.22_25/_0.5)]",
    bg: "bg-[oklch(0.60_0.20_25/_0.10)]",
    text: "text-[oklch(0.85_0.18_25)]",
    dot: "bg-[oklch(0.72_0.22_25)] animate-pulse",
  },
};

export function SuggestionTicker() {
  const suggestions = useJarvisStore((s) => s.suggestions);

  // Show at most the 5 most recent, reversed so newest is on top.
  const recent = [...suggestions].slice(-5).reverse();
  const unreadCount = suggestions.filter((s) => !s.read).length;

  if (recent.length === 0) return null;

  return (
    <div className="mb-2 space-y-1.5 border-b border-[oklch(0.70_0.15_200/_0.15)] pb-2.5">
      <div className="flex items-center gap-1.5 text-[10px] tracking-[0.2em] uppercase text-[oklch(0.55_0.10_200)]">
        <Bell className="h-3 w-3" />
        <span>Alerts</span>
        {unreadCount > 0 && (
          <span className="ml-auto rounded bg-[oklch(0.78_0.16_85/_0.2)] px-1.5 py-0.5 text-[oklch(0.85_0.14_85)]">
            {unreadCount} new
          </span>
        )}
      </div>
      <div className="max-h-[180px] space-y-1 overflow-y-auto pr-1">
        {recent.map((s) => {
          const st = SEVERITY_STYLES[s.severity] ?? SEVERITY_STYLES.info;
          const Icon = st.icon;
          return (
            <a
              key={s.id}
              href={s.linkTo ?? "#"}
              onClick={(e) => {
                if (!s.linkTo) e.preventDefault();
              }}
              className={[
                "group flex items-start gap-2 rounded border px-2 py-1.5 text-[11px]",
                "transition-colors hover:bg-white/5",
                st.border, st.bg,
                !s.read ? "ring-1 ring-inset ring-[oklch(0.78_0.18_200/_0.15)]" : "opacity-75",
              ].join(" ")}
            >
              <span className={`mt-0.5 inline-block h-1.5 w-1.5 shrink-0 rounded-full ${st.dot}`} />
              <Icon className={`mt-0.5 h-3 w-3 shrink-0 ${st.text}`} />
              <div className="min-w-0 flex-1 leading-snug">
                <div className={`font-semibold ${st.text}`}>{s.title}</div>
                {s.body && (
                  <div className="text-[oklch(0.75_0.05_200)]">{s.body}</div>
                )}
              </div>
              {s.linkTo && (
                <ExternalLink className="mt-0.5 h-3 w-3 shrink-0 opacity-0 transition-opacity group-hover:opacity-60" />
              )}
            </a>
          );
        })}
      </div>
    </div>
  );
}
