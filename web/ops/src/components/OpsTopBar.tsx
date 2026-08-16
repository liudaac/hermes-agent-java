import { NavLink } from "react-router-dom";
import { Activity, Wrench, Settings, type LucideIcon } from "lucide-react";
import { cn } from "@hermes/ui";
import { OPS_NAV, type OpsNavItem } from "@/lib/nav";
import { ThemeSwitcher } from "@/components/ThemeSwitcher";
import { LanguageSwitcher } from "@/components/LanguageSwitcher";

const GROUPS: Array<{ key: OpsNavItem["group"]; label: string; icon: LucideIcon }> = [
  { key: "operations", label: "Operations", icon: Activity },
  { key: "observability", label: "Observability", icon: Wrench },
  { key: "tools", label: "Tools", icon: Settings },
];

/**
 * OpsTopBar - header for the ops console. Three sections:
 *   - Brand mark (left)
 *   - Grouped nav (center): Operations / Observability / Tools
 *   - Utilities (right): theme + language switchers
 *
 * Cross-product jumps (Portal/Admin/DevPortal) intentionally live only on the
 * hub page - each product stays focused.
 */
export function OpsTopBar() {
  return (
    <header className="sticky top-0 z-40 border-b border-current/20 bg-background-base/90 backdrop-blur-sm">
      <div className="mx-auto flex h-12 max-w-[1600px] items-center gap-2 px-3 sm:px-5">
        <div className="flex shrink-0 items-center gap-2 pr-3 sm:pr-5">
          <div className="font-mondwest text-[1.0625rem] sm:text-[1.125rem] font-bold leading-[0.95] tracking-[0.0525rem] text-midground blend-lighter">
            Hermes
            <br />
            Agent
          </div>
        </div>
        <nav className="min-w-0 flex-1 overflow-x-auto scrollbar-none">
          <ul className="flex items-center gap-0">
            {GROUPS.map((group) => {
              const items = OPS_NAV.filter((i) => i.group === group.key);
              return (
                <li key={group.key} className="flex items-center">
                  <span className="px-2 text-[0.55rem] tracking-[0.18em] opacity-40 uppercase">
                    {group.label}
                  </span>
                  {items.map((item) => (
                    <NavLink
                      key={item.path}
                      to={item.path}
                      end={item.path === "/"}
                      className={({ isActive }) =>
                        cn(
                          "group relative flex items-center gap-1.5 px-2.5 sm:px-3 py-2",
                          "font-mondwest text-[0.65rem] sm:text-[0.8rem] tracking-[0.12em] whitespace-nowrap",
                          "transition-colors cursor-pointer",
                          "focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-midground",
                          isActive ? "text-midground" : "opacity-60 hover:opacity-100",
                        )
                      }
                    >
                      {({ isActive }) => (
                        <>
                          <item.icon className="h-3.5 w-3.5 shrink-0" />
                          <span className="hidden sm:inline">{item.label}</span>
                          {isActive && (
                            <span
                              aria-hidden
                              className="absolute bottom-0 left-0 right-0 h-px bg-midground blend-lighter"
                            />
                          )}
                        </>
                      )}
                    </NavLink>
                  ))}
                </li>
              );
            })}
          </ul>
        </nav>
        <div className="flex shrink-0 items-center gap-0.5 pl-1">
          <ThemeSwitcher />
          <LanguageSwitcher />
        </div>
      </div>
    </header>
  );
}
