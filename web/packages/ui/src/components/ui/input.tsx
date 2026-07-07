import { cn } from "../../lib/cn";

export function Input({ className, ...props }: React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        "flex h-9 w-full border border-border bg-background/40 px-3 py-1 font-courier text-sm rounded-sm",
        "placeholder:text-muted-foreground/60",
        "hover:border-border/60",
        "focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-midground/40 focus-visible:border-midground/40 focus-visible:bg-background/60",
        "disabled:cursor-not-allowed disabled:opacity-40",
        "transition-all duration-150",
        className,
      )}
      {...props}
    />
  );
}
