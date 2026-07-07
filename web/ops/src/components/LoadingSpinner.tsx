import { cn } from "@hermes/ui";

const SIZE_MAP = {
  sm: "h-4 w-4",
  md: "h-5 w-5",
  lg: "h-6 w-6",
};

/**
 * Standard loading spinner used across all pages.
 * Replaces the repeated inline spinner markup:
 *   <div className="flex items-center justify-center py-24">
 *     <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
 *   </div>
 */
export function LoadingSpinner({
  className,
  size = "lg",
  padding = "py-24",
}: {
  className?: string;
  size?: "sm" | "md" | "lg";
  padding?: string;
}) {
  return (
    <div className={cn("flex items-center justify-center", padding, className)}>
      <div className={cn("animate-spin rounded-full border-2 border-primary border-t-transparent", SIZE_MAP[size])} />
    </div>
  );
}
