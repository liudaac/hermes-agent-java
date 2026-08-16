import { cn } from "@hermes/ui";

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded-lg bg-muted", className)} />;
}

export function CardSkeleton({ height = "h-32" }: { height?: string }) {
  return <Skeleton className={cn(height, "rounded-2xl")} />;
}

export function ListItemSkeleton() {
  return (
    <div className="flex items-center gap-3 px-2 py-3">
      <Skeleton className="h-4 w-4 rounded-full" />
      <div className="flex-1 space-y-1.5">
        <Skeleton className="h-3.5 w-2/3" />
        <Skeleton className="h-2.5 w-1/3" />
      </div>
      <Skeleton className="h-5 w-14 rounded-full" />
    </div>
  );
}

export function PageSkeleton({ variant = "list" }: { variant?: "list" | "cards" | "detail" }) {
  if (variant === "cards") {
    return (
      <div className="mx-auto max-w-3xl px-4 pb-24 pt-6 space-y-3">
        <Skeleton className="h-10 w-48 rounded-xl" />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {[0, 1, 2, 3].map((i) => (
            <CardSkeleton key={i} />
          ))}
        </div>
      </div>
    );
  }
  if (variant === "detail") {
    return (
      <div className="mx-auto max-w-3xl px-4 pb-24 pt-6 space-y-3">
        <CardSkeleton height="h-28" />
        <CardSkeleton height="h-20" />
        <CardSkeleton height="h-20" />
        <CardSkeleton height="h-20" />
      </div>
    );
  }
  return (
    <div className="mx-auto max-w-3xl px-4 pb-24 pt-6 space-y-2">
      <Skeleton className="h-10 w-48 rounded-xl" />
      {[0, 1, 2, 3].map((i) => (
        <Skeleton key={i} className="h-16 rounded-2xl" />
      ))}
    </div>
  );
}
