import { useState, type ReactNode } from "react";
import { ChevronDown, type LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

interface SectionAccordionProps {
  /** Icon shown left of the title */
  icon?: LucideIcon;
  /** Section title (left aligned) */
  title: string;
  /** Optional subtitle/hint right of the title */
  hint?: string;
  /** Optional right-side accessory (badge, count, action) */
  accessory?: ReactNode;
  /** Mobile-only default open state. Desktop is always open. */
  defaultOpen?: boolean;
  /** Children = the section body */
  children: ReactNode;
  /** Tighter outer chrome */
  bordered?: boolean;
}

/**
 * SectionAccordion
 *
 * Mobile-first behavior:
 *  - On <md screens, the body is collapsible (defaults to open or closed via prop).
 *  - On ≥md screens, the body is always visible and the toggle is hidden.
 *
 * Designed to keep the desktop dashboard layout untouched while letting the
 * mobile experience fold long pages into manageable accordions. Aurora subtle
 * tint on the header.
 */
export default function SectionAccordion({
  icon: Icon,
  title,
  hint,
  accessory,
  defaultOpen = true,
  children,
  bordered = true,
}: SectionAccordionProps) {
  const [openMobile, setOpenMobile] = useState(defaultOpen);

  return (
    <section
      className={cn(
        "rounded-xl",
        bordered && "border border-border/60 bg-background/40 backdrop-blur-sm",
      )}
    >
      <header
        className={cn(
          "section-header-tint flex items-center gap-2 px-3 py-2.5 md:px-4",
          bordered && "rounded-t-xl",
        )}
      >
        {Icon && <Icon className="h-4 w-4 shrink-0 text-muted-foreground" />}
        <h2 className="flex min-w-0 items-baseline gap-2 text-sm font-semibold tracking-tight">
          <span className="truncate">{title}</span>
          {hint && (
            <span className="hidden truncate text-xs font-normal text-muted-foreground sm:inline">
              {hint}
            </span>
          )}
        </h2>
        <div className="ml-auto flex items-center gap-2">
          {accessory}
          {/* Toggle: only visible on <md */}
          <button
            type="button"
            onClick={() => setOpenMobile((o) => !o)}
            className={cn(
              "inline-flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-foreground/5 hover:text-foreground md:hidden",
            )}
            aria-expanded={openMobile}
            aria-label={openMobile ? "Collapse section" : "Expand section"}
          >
            <ChevronDown
              className={cn("h-4 w-4 transition-transform", !openMobile && "-rotate-90")}
            />
          </button>
        </div>
      </header>
      <div
        className={cn(
          "px-3 pb-3 md:!block md:px-4 md:pb-4",
          // Mobile: hide when closed
          !openMobile && "hidden",
        )}
      >
        {/* Subtle hairline above content */}
        <div className="hairline mb-3" aria-hidden />
        {children}
      </div>
    </section>
  );
}
