import { cn } from "@/lib/utils";

interface SkeletonProps {
  className?: string;
  lines?: number;
}

/**
 * Animated skeleton placeholder using the global shimmer animation.
 */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "shimmer-bg rounded-sm",
        className,
      )}
    />
  );
}

/**
 * Multi-line skeleton block for card/list placeholders.
 */
export function SkeletonBlock({ className, lines = 3 }: SkeletonProps) {
  return (
    <div className={cn("space-y-2", className)}>
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton
          key={i}
          className={cn(
            "h-3",
            i === lines - 1 ? "w-2/3" : "w-full",
          )}
        />
      ))}
    </div>
  );
}

/**
 * Skeleton card placeholder.
 */
export function SkeletonCard({ className }: { className?: string }) {
  return (
    <div className={cn("border border-border bg-card/40 rounded-sm p-4 space-y-3", className)}>
      <Skeleton className="h-4 w-1/3" />
      <SkeletonBlock lines={3} />
    </div>
  );
}
