import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap font-mondwest text-xs tracking-[0.1em] uppercase cursor-pointer"
  + " disabled:pointer-events-none disabled:opacity-40 disabled:grayscale"
  + " transition-all duration-150 ease-out active:scale-[0.97] active:brightness-90",
  {
    variants: {
      variant: {
        default: "bg-foreground/90 text-background hover:bg-foreground hover:shadow-glow hover:shadow-[var(--shadow-glow)]",
        destructive: "bg-destructive text-destructive-foreground hover:bg-destructive/90 hover:shadow-[0_0_12px_rgba(251,44,54,0.25)]",
        outline: "border border-border bg-transparent hover:bg-foreground/10 hover:text-foreground hover:border-foreground/30",
        secondary: "bg-secondary text-secondary-foreground hover:bg-secondary/80 hover:shadow-sm",
        ghost: "hover:bg-foreground/10 hover:text-foreground",
        link: "text-foreground underline-offset-4 hover:underline",
        success: "bg-success/15 text-success border border-success/30 hover:bg-success/25 hover:shadow-[0_0_12px_rgba(74,222,128,0.15)]",
        warning: "bg-warning/15 text-warning border border-warning/30 hover:bg-warning/25 hover:shadow-[0_0_12px_rgba(255,189,56,0.15)]",
        info: "bg-info/15 text-info border border-info/30 hover:bg-info/25 hover:shadow-[0_0_12px_rgba(96,165,250,0.15)]",
      },
      size: {
        default: "h-9 px-4 py-2 rounded-sm",
        sm: "h-7 px-3 text-[0.65rem] rounded-sm",
        lg: "h-10 px-8 rounded-sm",
        icon: "h-9 w-9 rounded-sm",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

export function Button({
  className,
  variant,
  size,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof buttonVariants>) {
  return <button className={cn(buttonVariants({ variant, size }), className)} {...props} />;
}
