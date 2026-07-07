import { cn } from "@hermes/ui";

/**
 * Reusable two-column layout with a sticky sidebar.
 *
 * Used by ConfigPage, SkillsPage, LogsPage, etc.
 * Replaces the repeated inline pattern:
 *   <div className="flex flex-col sm:flex-row gap-4" style={{ minHeight: "calc(100vh - 180px)" }}>
 *     <div className="sm:w-52 sm:shrink-0"><div className="sm:sticky sm:top-[72px]">...</div></div>
 *     <div className="flex-1 min-w-0">...</div>
 *   </div>
 */
export function SidebarLayout({
  sidebar,
  children,
  className,
  sidebarWidth = "w-52",
}: {
  sidebar: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  sidebarWidth?: string;
}) {
  return (
    <div
      className={cn("flex flex-col sm:flex-row gap-4 layout-with-sidebar", className)}
    >
      <div className={cn("sm:shrink-0", sidebarWidth)}>
        <div className="sm:sticky sm:top-[72px]">{sidebar}</div>
      </div>
      <div className="flex-1 min-w-0">{children}</div>
    </div>
  );
}
