/**
 * SpaceNav — render the right nav structure for the current space.
 *
 * - Portal: single flat row of 7 business objects
 * - Ops:    grouped (Operations / Observability / Configuration)
 * - NOC:    single flat row of 6 governance items
 *
 * Renders inside the App header alongside the brand mark.
 */

import { NavLink } from "react-router-dom";
import { Cell, Typography } from "@nous-research/ui";
import { cn } from "@/lib/utils";
import type { NavGroup, NavItem } from "./nav";
import { useI18n } from "@/i18n";

interface SpaceNavProps {
  flat: NavItem[];
  groups?: NavGroup[];
  templateColumns: string;
  showLabels?: boolean;
}

export function SpaceNav({ flat, groups, templateColumns, showLabels = true }: SpaceNavProps) {
  const { t } = useI18n();

  // Render a single NavLink cell
  const renderCell = (item: NavItem) => {
    const isExact = item.path.endsWith("/portal") ||
      item.path.endsWith("/ops") ||
      item.path.endsWith("/noc");
    return (
      <Cell key={item.path} className="relative !p-0">
        <NavLink
          to={item.path}
          end={isExact}
          className={({ isActive }) =>
            cn(
              "group relative flex h-full w-full items-center gap-1.5",
              "px-2.5 sm:px-4 py-2",
              "font-mondwest text-[0.65rem] sm:text-[0.8rem] tracking-[0.12em]",
              "whitespace-nowrap transition-colors cursor-pointer",
              "focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-midground",
              isActive ? "text-midground" : "opacity-60 hover:opacity-100",
            )
          }
        >
          {({ isActive }) => (
            <>
              <item.icon className="h-3.5 w-3.5 shrink-0" />
              {showLabels && (
                <span className="hidden sm:inline">
                  {item.labelKey
                    ? ((t.app.nav as Record<string, string>)[item.labelKey] ?? item.label)
                    : item.label}
                </span>
              )}

              <span
                aria-hidden
                className="absolute inset-1 bg-midground opacity-0 pointer-events-none transition-opacity duration-200 group-hover:opacity-5"
              />
              {isActive && (
                <span
                  aria-hidden
                  className="absolute bottom-0 left-0 right-0 h-px bg-midground blend-lighter"
                />
              )}
            </>
          )}
        </NavLink>
      </Cell>
    );
  };

  if (groups && groups.length > 1) {
    // Ops: grouped nav — render each group with a tiny label above
    return (
      <div
        className="min-w-0 flex-1 overflow-x-auto scrollbar-none"
        style={{ display: "grid", gridTemplateColumns: templateColumns, alignItems: "stretch" }}
      >
        {groups.flatMap((group) => [
          <Cell key={`label-${group.label}`} className="!p-0 !px-3 flex items-center">
            <Typography
              mondwest
              className="text-[0.55rem] tracking-[0.18em] opacity-40"
            >
              {group.label}
            </Typography>
          </Cell>,
          ...group.items.map(renderCell),
        ])}
      </div>
    );
  }

  // Portal / NOC: single flat row
  return (
    <div
      className="min-w-0 flex-1 overflow-x-auto scrollbar-none"
      style={{ display: "grid", gridTemplateColumns: templateColumns, alignItems: "stretch" }}
    >
      {flat.map(renderCell)}
    </div>
  );
}
