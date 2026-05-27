import { Badge } from "@/components/ui/badge";

/**
 * Animated "live" indicator badge with a pulsing dot.
 * Used for streaming, auto-refresh, and active-connection states.
 */
export function LiveBadge({ label = "live" }: { label?: string }) {
  return (
    <Badge variant="success" className="text-[10px]">
      <span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-current" />
      {label}
    </Badge>
  );
}
