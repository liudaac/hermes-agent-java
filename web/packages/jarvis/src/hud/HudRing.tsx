/**
 * HudRing — the always-on HUD ring (design.md §5.1).
 *
 * Three orbital tracks at r=24, 32, 40 with 8 data points each.
 * Rendered as SVG so it scales cleanly on hi-DPI displays and
 * composites naturally on top of the Canvas2D particle field.
 */
interface HudRingProps {
  size?: number; // canvas size (centered)
  className?: string;
}

const TRACK_RADII = [24, 32, 40];
const POINTS_PER_TRACK = 8;
const PRIMARY = "oklch(0.85 0.10 200)"; // cyan-300 mapped to oklch
const SECONDARY = "oklch(0.70 0.18 280)"; // violet-500

export function HudRing({ size = 120, className }: HudRingProps) {
  const cx = size / 2;
  const cy = size / 2;
  const points: Array<{ track: number; idx: number; x: number; y: number; primary: boolean }> = [];
  TRACK_RADII.forEach((r, trackIdx) => {
    for (let i = 0; i < POINTS_PER_TRACK; i++) {
      const theta = (i / POINTS_PER_TRACK) * Math.PI * 2;
      points.push({
        track: trackIdx,
        idx: i,
        x: cx + Math.cos(theta) * r,
        y: cy + Math.sin(theta) * r,
        primary: trackIdx % 2 === 0,
      });
    }
  });

  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      className={className}
      aria-hidden
    >
      {/* Tracks */}
      {TRACK_RADII.map((r) => (
        <circle
          key={r}
          cx={cx}
          cy={cy}
          r={r}
          fill="none"
          stroke="currentColor"
          strokeOpacity="0.10"
          strokeWidth="0.5"
        />
      ))}
      {/* Data points */}
      {points.map((p) => (
        <circle
          key={`${p.track}-${p.idx}`}
          cx={p.x}
          cy={p.y}
          r="1.2"
          fill={p.primary ? PRIMARY : SECONDARY}
          fillOpacity="0.7"
        />
      ))}
    </svg>
  );
}
