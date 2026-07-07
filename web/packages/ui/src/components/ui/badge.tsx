import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

const badgeVariants = cva(
  "inline-flex items-center border px-2 py-0.5 font-compressed text-[0.65rem] tracking-[0.15em] uppercase rounded-sm"
  + " transition-all duration-150 hover:brightness-110 cursor-default",
  {
    variants: {
      variant: {
        default: "border-foreground/20 bg-foreground/10 text-foreground",
        secondary: "border-border bg-secondary text-secondary-foreground",
        destructive: "border-destructive/30 bg-destructive/15 text-destructive hover:shadow-[0_0_8px_rgba(251,44,54,0.15)]",
        outline: "border-border text-muted-foreground hover:border-foreground/30 hover:text-foreground",
        success: "grain border-emerald-600/30 bg-emerald-950/70 text-emerald-400 hover:shadow-[0_0_8px_rgba(74,222,128,0.15)]",
        warning: "border-warning/30 bg-warning/15 text-warning hover:shadow-[0_0_8px_rgba(255,189,56,0.15)]",
        info: "border-info/30 bg-info/15 text-info hover:shadow-[0_0_8px_rgba(96,165,250,0.15)]",
        live: "border-success/30 bg-success/15 text-success badge-glow",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

export function Badge({
  className,
  variant,
  ...props
}: React.HTMLAttributes<HTMLDivElement> & VariantProps<typeof badgeVariants>) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}
