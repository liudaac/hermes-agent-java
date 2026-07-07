import type { ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { cn } from "@hermes/ui";

interface TopBarProps {
  title: string;
  subtitle?: string;
  /** Show a back button — uses history.back() by default. */
  back?: boolean | string;
  right?: ReactNode;
  /** Make the bar transparent until scrolled (hero overlay style). */
  transparent?: boolean;
  className?: string;
}

/**
 * TopBar — H5-style sticky header. Two flavors:
 *   - default: solid glass-strong, used for content pages
 *   - transparent: floats over hero, transitions to glass-strong on scroll
 */
export function TopBar({ title, subtitle, back, right, transparent = false, className }: TopBarProps) {
  const navigate = useNavigate();

  return (
    <header
      className={cn(
        "sticky top-0 z-30",
        "border-b border-[oklch(0.35_0.02_50_/_0.4)]",
        transparent ? "glass" : "glass-strong",
        // iOS notch safe-area.
        "pt-[env(safe-area-inset-top)]",
        className,
      )}
    >
      <div className="mx-auto flex h-12 max-w-3xl items-center gap-2 px-3 sm:px-4">
        {back && (
          <button
            type="button"
            onClick={() => {
              if (typeof back === "string") navigate(back);
              else if (window.history.length > 1) navigate(-1);
              else navigate("/");
            }}
            className="flex h-9 w-9 items-center justify-center rounded-full text-[var(--color-text-secondary)] hover:bg-[oklch(0.30_0.02_50_/_0.4)] active:scale-95 transition"
            aria-label="Back"
          >
            <ArrowLeft className="h-4.5 w-4.5" />
          </button>
        )}
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-[15px] font-semibold tracking-tight text-[var(--color-text-primary)]">
            {title}
          </h1>
          {subtitle && (
            <p className="truncate text-[11px] text-[var(--color-text-muted)]">
              {subtitle}
            </p>
          )}
        </div>
        {right && <div className="flex items-center gap-1.5">{right}</div>}
      </div>
    </header>
  );
}
