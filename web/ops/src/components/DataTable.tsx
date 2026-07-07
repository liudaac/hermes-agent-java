import { cn } from "@hermes/ui";

/* ------------------------------------------------------------------ */
/*  Generic table primitives — shared by AnalyticsPage tables.         */
/* ------------------------------------------------------------------ */

export function DataTable({ children }: { children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">{children}</table>
    </div>
  );
}

export function DataTableHeader({ children }: { children: React.ReactNode }) {
  return (
    <thead>
      <tr className="border-b border-border text-muted-foreground text-xs">
        {children}
      </tr>
    </thead>
  );
}

export function DataTableHead({
  children,
  align = "left",
  className,
}: {
  children: React.ReactNode;
  align?: "left" | "right";
  className?: string;
}) {
  return (
    <th
      className={cn(
        "py-2 font-medium",
        align === "right" ? "text-right px-4" : "text-left pr-4",
        className,
      )}
    >
      {children}
    </th>
  );
}

export function DataTableBody({ children }: { children: React.ReactNode }) {
  return <tbody>{children}</tbody>;
}

export function DataTableRow({ children }: { children: React.ReactNode }) {
  return (
    <tr className="border-b border-border/50 hover:bg-secondary/20 transition-colors">
      {children}
    </tr>
  );
}

export function DataTableCell({
  children,
  align = "left",
  className,
}: {
  children: React.ReactNode;
  align?: "left" | "right";
  className?: string;
}) {
  return (
    <td
      className={cn(
        "py-2",
        align === "right" ? "text-right px-4" : "pr-4",
        className,
      )}
    >
      {children}
    </td>
  );
}
