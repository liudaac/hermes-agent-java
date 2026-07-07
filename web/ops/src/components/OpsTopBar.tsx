import { NavLink } from "react-router-dom";
import { Activity, Wrench, type LucideIcon, ExternalLink, BriefcaseBusiness, ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";
import { OPS_NAV, CROSS_PRODUCT_LINKS, type OpsNavItem } from "@/lib/nav";

const GROUPS: Array<{ key: OpsNavItem["group"]; label: string; icon: LucideIcon }> = [
  { key: "operations", label: "Operations", icon: Activity },
  { key: "observability", label: "Observability", icon: Wrench },
  { key: "configuration", label: "Configuration", icon: Wrench },
];

/**
 * OpsTopBar — combined header for the ops console. Three sections:
 *   - Brand mark (left)
 *   - Grouped nav (center): Operations / Observability / Configuration
 *   - Cross-product switcher (right): Portal / NOC pills
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
        <CrossProductSwitcher />
      </div>
    </header>
  );
}

function CrossProductSwitcher() {
  return (
    <div className="flex shrink-0 items-center gap-1 rounded-full border border-current/20 bg-background-base/40 px-1 py-0.5">
      <CrossPill href={CROSS_PRODUCT_LINKS.portal} label="Portal" icon={BriefcaseBusiness} title="Open Portal (separate app)" />
      <CrossPill href={CROSS_PRODUCT_LINKS.noc} label="NOC" icon={ShieldCheck} title="Open NOC (separate app)" />
    </div>
  );
}

function CrossPill({
  href,
  label,
  icon: Icon,
  title,
}: {
  href: string;
  label: string;
  icon: LucideIcon;
  title: string;
}) {
  return (
    <a
      href={href}
      title={title}
      className="flex items-center gap-1 rounded-full px-2 py-0.5 text-[0.65rem] tracking-[0.12em] opacity-60 hover:opacity-100 transition-colors"
    >
      <Icon className="h-3 w-3" />
      <span className="hidden md:inline">{label}</span>
      <ExternalLink className="h-2.5 w-2.5 opacity-50" />
    </a>
  );
}
