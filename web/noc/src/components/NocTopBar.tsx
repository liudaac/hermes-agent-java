import { NavLink } from "react-router-dom";
import { type LucideIcon, ExternalLink, BriefcaseBusiness, TerminalSquare } from "lucide-react";
import { cn } from "@hermes/ui";
import { NOC_NAV, CROSS_PRODUCT_LINKS } from "@/lib/nav";

/**
 * NocTopBar — NOC console header. Flat nav (6 items) plus cross-product
 * switcher (Portal, Ops). The NOC is a dark, alert-priority surface:
 * amber pulse indicator next to the brand mark signals "this is the
 * control center" without stealing the page's content.
 */
export function NocTopBar() {
  return (
    <header className="sticky top-0 z-40 border-b border-[oklch(0.78_0.16_85_/_0.25)] bg-background-base/90 backdrop-blur-sm">
      <div className="mx-auto flex h-12 max-w-[1600px] items-center gap-2 px-3 sm:px-5">
        <div className="flex shrink-0 items-center gap-2 pr-3 sm:pr-5">
          <span className="noc-pulse-dot" aria-hidden />
          <div className="font-mondwest text-[1.0625rem] sm:text-[1.125rem] font-bold leading-[0.95] tracking-[0.0525rem] text-[oklch(0.95_0.10_85)] blend-lighter">
            Hermes
            <br />
            NOC
          </div>
        </div>
        <nav className="min-w-0 flex-1 overflow-x-auto scrollbar-none">
          <ul className="flex items-center gap-0">
            {NOC_NAV.map((item) => (
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
                    isActive
                      ? "text-[oklch(0.95_0.10_85)]"
                      : "opacity-60 hover:opacity-100 text-midground",
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
                        className="absolute bottom-0 left-0 right-0 h-px bg-[oklch(0.78_0.16_85)]"
                      />
                    )}
                  </>
                )}
              </NavLink>
            ))}
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
      <CrossPill href={CROSS_PRODUCT_LINKS.ops} label="Ops" icon={TerminalSquare} title="Open Ops (separate app)" />
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
