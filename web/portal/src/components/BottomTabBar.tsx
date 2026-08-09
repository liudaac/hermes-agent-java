import { useLocation, useNavigate } from "react-router-dom";
import { useEffect, useState } from "react";
import { Home, Users, Activity, ShieldCheck, User } from "lucide-react";
import { cn } from "@hermes/ui";
import { useI18n } from "@/i18n";
import { portalApi } from "@/api/portal";

interface TabItem {
  to: string;
  labelKey: string;
  icon: typeof Home;
  exact?: boolean;
  badgeKey?: "approvals";
}

const TABS: TabItem[] = [
  { to: "/", labelKey: "nav.home", icon: Home, exact: true },
  { to: "/teams", labelKey: "nav.teams", icon: Users },
  { to: "/runs", labelKey: "nav.runs", icon: Activity },
  { to: "/approvals", labelKey: "nav.approvals", icon: ShieldCheck, badgeKey: "approvals" },
  { to: "/me", labelKey: "nav.profile", icon: User },
];

/**
 * BottomTabBar - H5-style 6-item bottom navigation. Sticky, glass-strong,
 * with a soft top edge. On wide screens collapses to a horizontal rail.
 *
 * Approvals tab shows a badge with pending count.
 */
export function BottomTabBar() {
  const { t } = useI18n();
  const location = useLocation();
  const navigate = useNavigate();
  const [pendingCount, setPendingCount] = useState(0);

  // Poll pending approvals count (shared, single request)
  useEffect(() => {
    let alive = true;
    const poll = () => {
      portalApi
        .getBusinessApprovals(undefined, "PENDING")
        .then((res) => {
          if (!alive) return;
          const count = (res as { approvals?: unknown[] }).approvals?.length ?? 0;
          setPendingCount(count);
        })
        .catch(() => {});
    };
    poll();
    const timer = setInterval(poll, 30_000); // 30s poll (not 5s - less aggressive)
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, []);

  return (
    <nav
      className={cn(
        "fixed bottom-0 inset-x-0 z-40",
        "glass-strong border-t border-[oklch(0.35_0.02_50_/_0.5)]",
        "pb-[env(safe-area-inset-bottom)]",
      )}
      aria-label="Primary"
    >
      <ul className="mx-auto grid max-w-3xl grid-cols-5">
        {TABS.map(({ to, labelKey, icon: Icon, exact, badgeKey }) => {
          const path = location.pathname;
          const active = exact ? path === to : path === to || path.startsWith(to + "/");
          const badge = badgeKey === "approvals" && pendingCount > 0 ? pendingCount : null;
          return (
            <li key={to}>
              <button
                type="button"
                onClick={() => navigate(to)}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "relative flex h-14 w-full flex-col items-center justify-center gap-0.5",
                  "transition-colors",
                  active
                    ? "text-[var(--color-accent)]"
                    : "text-[var(--color-text-muted)] hover:text-[var(--color-text-secondary)]",
                )}
              >
                <div className="relative">
                  <Icon
                    className={cn(
                      "h-5 w-5 transition-transform",
                      active && "scale-110",
                    )}
                    strokeWidth={active ? 2.4 : 1.8}
                  />
                  {badge !== null && (
                    <span
                      className={cn(
                        "absolute -right-2 -top-1.5 flex h-4 min-w-4 items-center justify-center",
                        "rounded-full bg-[oklch(0.68_0.20_25)] px-1 text-[9px] font-bold text-white",
                      )}
                    >
                      {badge > 99 ? "99+" : badge}
                    </span>
                  )}
                </div>
                <span
                  className={cn(
                    "text-[10px] tracking-wide",
                    active ? "font-semibold" : "font-medium opacity-80",
                  )}
                >
                  {t(labelKey)}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
