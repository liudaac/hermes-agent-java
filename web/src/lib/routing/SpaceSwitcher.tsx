/**
 * Space switcher — button in the top-right that lets the user
 * jump between Ops, NOC, and the standalone Portal SPA.
 *
 * The Portal entry is a fully separate SPA at PORTAL_ENTRY, so clicking it
 * triggers a full-page navigation (not a router push). This is intentional:
 * the three spaces are isolated products, not tabs of the same shell.
 *
 * If the user has a manual theme override, this shows a "use space
 * default" affordance next to the switcher.
 */

import { useLocation, useNavigate } from "react-router-dom";
import { BriefcaseBusiness, TerminalSquare, ShieldCheck, type LucideIcon } from "lucide-react";
import { rememberSpace, SPACE_PATHS, type SpaceName } from "./spaces";
import { PORTAL_ENTRY } from "./nav";
import { useTheme } from "@/themes";
import { ReturnToSource } from "./CrossSpaceLink";

interface SpaceOption {
  key: SpaceName | "portal";
  label: string;
  icon: LucideIcon;
  /** true if this option exits the current SPA via full-page navigation. */
  external?: boolean;
}

const SPACE_OPTIONS: SpaceOption[] = [
  { key: "portal", label: "Portal", icon: BriefcaseBusiness, external: true },
  { key: "ops", label: "Ops", icon: TerminalSquare },
  { key: "noc", label: "NOC", icon: ShieldCheck },
];

export function SpaceSwitcher({ activeSpace }: { activeSpace: SpaceName }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { hasUserOverride, clearOverride } = useTheme();

  const goTo = (opt: SpaceOption) => {
    if (opt.external) {
      // Portal is a separate SPA — full-page nav.
      window.location.assign(PORTAL_ENTRY);
      return;
    }
    rememberSpace(opt.key as SpaceName);
    navigate(SPACE_PATHS[opt.key as SpaceName]);
  };

  // If the user is mid-path, jump to the space root rather than forcing
    // them through the same page in a different space.
  void location;

  return (
    <div className="flex items-center gap-1.5">
      <ReturnToSource />
      <div className="flex items-center gap-1 rounded-full border border-current/20 bg-background-base/40 px-1 py-0.5">
        {SPACE_OPTIONS.map((opt) => {
          const isActive = !opt.external && activeSpace === opt.key;
          const Icon = opt.icon;
          return (
            <button
              key={opt.key}
              type="button"
              onClick={() => goTo(opt)}
              title={opt.external ? `Open ${opt.label} (separate app)` : `Switch to ${opt.label}`}
              aria-current={isActive ? "page" : undefined}
              className={
                "flex items-center gap-1 rounded-full px-2 py-0.5 text-[0.65rem] tracking-[0.12em] transition-colors " +
                (isActive
                  ? "bg-midground text-background-base blend-lighter"
                  : "opacity-60 hover:opacity-100")
              }
            >
              <Icon className="h-3 w-3" />
              <span className="hidden md:inline">{opt.label}</span>
            </button>
          );
        })}
      </div>
      {hasUserOverride && (
        <button
          type="button"
          onClick={clearOverride}
          title="Resume using each space's default theme"
          className="text-[0.6rem] tracking-[0.12em] opacity-50 hover:opacity-100"
        >
          ↻ default
        </button>
      )}
    </div>
  );
}
