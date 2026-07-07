import { useLocation, useNavigate } from "react-router-dom";
import { Home, Users, Layers, ShieldCheck, Activity, Sparkles } from "lucide-react";
import { cn } from "@/lib/cn";
import { useI18n } from "@/i18n";

interface TabItem {
  to: string;
  labelKey: string;
  icon: typeof Home;
  exact?: boolean;
}

const TABS: TabItem[] = [
  { to: "/", labelKey: "nav.home", icon: Home, exact: true },
  { to: "/teams", labelKey: "nav.teams", icon: Users },
  { to: "/templates", labelKey: "nav.templates", icon: Layers },
  { to: "/approvals", labelKey: "nav.approvals", icon: ShieldCheck },
  { to: "/runs", labelKey: "nav.runs", icon: Activity },
  { to: "/insights", labelKey: "nav.insights", icon: Sparkles },
];

/**
 * BottomTabBar — H5-style 6-item bottom navigation. Sticky, glass-strong,
 * with a soft top edge. On wide screens collapses to a horizontal rail.
 */
export function BottomTabBar() {
  const { t } = useI18n();
  const location = useLocation();
  const navigate = useNavigate();

  return (
    <nav
      className={cn(
        "fixed bottom-0 inset-x-0 z-40",
        "glass-strong border-t border-[oklch(0.35_0.02_50_/_0.5)]",
        // Respect iOS home-indicator safe-area.
        "pb-[env(safe-area-inset-bottom)]",
      )}
      aria-label="Primary"
    >
      <ul className="mx-auto grid max-w-3xl grid-cols-6">
        {TABS.map(({ to, labelKey, icon: Icon, exact }) => {
          const path = location.pathname;
          const active = exact ? path === to : path === to || path.startsWith(to + "/");
          return (
            <li key={to}>
              <button
                type="button"
                onClick={() => navigate(to)}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex h-14 w-full flex-col items-center justify-center gap-0.5",
                  "transition-colors",
                  active
                    ? "text-[var(--color-accent)]"
                    : "text-[var(--color-text-muted)] hover:text-[var(--color-text-secondary)]",
                )}
              >
                <Icon
                  className={cn(
                    "h-5 w-5 transition-transform",
                    active && "scale-110",
                  )}
                  strokeWidth={active ? 2.4 : 1.8}
                />
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
