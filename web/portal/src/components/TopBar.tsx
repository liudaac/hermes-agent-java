import type { ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Home as HomeIcon } from "lucide-react";
import { cn } from "@hermes/ui";

interface TopBarProps {
  title: string;
  subtitle?: string;
  back?: boolean | string;
  right?: ReactNode;
  transparent?: boolean;
  showHub?: boolean;
  className?: string;
}

export function TopBar({ title, subtitle, back, right, transparent: _transparent = false, showHub = true, className }: TopBarProps) {
  const navigate = useNavigate();

  return (
    <header
      className={cn(
        "sticky top-0 z-30",
        "border-b border-border bg-card",
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
            className="flex h-9 w-9 items-center justify-center rounded-full text-muted-foreground hover:bg-muted active:scale-95 transition"
            aria-label="Back"
          >
            <ArrowLeft className="h-4.5 w-4.5" />
          </button>
        )}
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-[15px] font-semibold tracking-tight text-foreground">
            {title}
          </h1>
          {subtitle && (
            <p className="truncate text-[11px] text-muted-foreground">
              {subtitle}
            </p>
          )}
        </div>
        {right && <div className="flex items-center gap-1.5">{right}</div>}
        {showHub && (
          <a
            href="/"
            className="flex h-9 w-9 items-center justify-center rounded-full text-muted-foreground hover:bg-muted hover:text-foreground active:scale-95 transition"
            aria-label="返回 Hub"
            title="返回 Hub"
          >
            <HomeIcon className="h-4 w-4" />
          </a>
        )}
      </div>
    </header>
  );
}
