/**
 * DataOverlay — text/data overlay shown above the HUD ring
 * (design.md §5.3 "文字 + 数据浮层").
 *
 * The 1-line status text is rendered as plain DOM (not SVG) for
 * accessibility. Position is the top-right corner of the 60×60
 * particle area, with a tiny gap from the ring.
 */
interface DataOverlayProps {
  text: string;
  className?: string;
}

export function DataOverlay({ text, className }: DataOverlayProps) {
  return (
    <div
      className={cn(
        "absolute -top-1 right-0",
        "font-mono text-[8px] tracking-[0.18em] uppercase",
        "text-[oklch(0.78_0.12_200)] opacity-70",
        "select-none pointer-events-none",
        className,
      )}
      aria-hidden
    >
      {text}
    </div>
  );
}

function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(" ");
}
