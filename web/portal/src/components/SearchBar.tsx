/**
 * SearchBar - lightweight search input for list filtering.
 * Inline, glass-style, with a clear button.
 */
import { Search, X } from "lucide-react";
import { cn } from "@hermes/ui";

interface SearchBarProps {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  className?: string;
}

export function SearchBar({ value, onChange, placeholder = "搜索...", className }: SearchBarProps) {
  return (
    <div className={cn("relative", className)}>
      <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--color-text-muted)]" />
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded-xl border border-[oklch(0.35_0.02_50_/_0.4)] bg-[oklch(0.20_0.01_50_/_0.4)] py-2.5 pl-10 pr-9 text-[14px] text-[var(--color-text-primary)] placeholder:text-[var(--color-text-muted)] focus:outline-none focus:ring-1 focus:ring-[oklch(0.78_0.16_70)]"
      />
      {value && (
        <button
          type="button"
          onClick={() => onChange("")}
          className="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--color-text-muted)] hover:text-[var(--color-text-secondary)]"
          aria-label="清除"
        >
          <X className="h-4 w-4" />
        </button>
      )}
    </div>
  );
}
