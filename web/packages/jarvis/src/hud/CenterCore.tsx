/**
 * CenterCore — the 6-edge bipyramid at the heart of Jarvis.
 *
 * Per design.md §2.2.1:
 *   - 6-edge (hexagonal) bipyramid
 *   - height 12px, diameter 8px
 *   - self-rotation 0.05 rad/s  (one full turn every ~125s)
 *   - breathing: scale 0.92 → 1.08 → 0.92 over 4.6s
 *
 * The 中心核 is the *soul* — it never disappears during form
 * transitions (§22 设计原则 1).
 *
 * Keyframes are inlined (no external CSS dep) so the package can be
 * dropped into any SPA without theme coordination.
 */
interface CenterCoreProps {
  size?: number;
  className?: string;
}

const PRIMARY = "oklch(0.85 0.10 200)"; // cyan-300

export function CenterCore({ size = 14, className }: CenterCoreProps) {
  // Inline keyframes (one-shot injection per mount).
  const styleSheet = `
    @keyframes jarvis-core-rotate {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }
    @keyframes jarvis-core-breathe {
      0%, 100% { transform: scale(0.92); }
      50% { transform: scale(1.08); }
    }
  `;

  // 6-edge bipyramid outline: top point, then 6 mid-ring vertices,
  // then bottom point.
  const r = size / 2;
  const cy = size / 2;
  const top = { x: cy, y: cy - r };
  const bot = { x: cy, y: cy + r };
  const ring: Array<{ x: number; y: number }> = [];
  for (let i = 0; i < 6; i++) {
    const t = (i / 6) * Math.PI * 2;
    ring.push({ x: cy + Math.cos(t) * (r * 0.55), y: cy + Math.sin(t) * (r * 0.55) });
  }

  return (
    <>
      <style dangerouslySetInnerHTML={{ __html: styleSheet }} />
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        className={className}
        aria-hidden
        style={{
          animation: "jarvis-core-rotate 20s linear infinite",
          transformOrigin: "50% 50%",
        }}
      >
        <defs>
          <radialGradient id="jarvis-core" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor={PRIMARY} stopOpacity="0.95" />
            <stop offset="100%" stopColor={PRIMARY} stopOpacity="0.0" />
          </radialGradient>
        </defs>
        <g
          style={{
            animation: "jarvis-core-breathe 4.6s ease-in-out infinite",
            transformOrigin: "50% 50%",
          }}
        >
          {ring.map((v, i) => (
            <line
              key={`t-${i}`}
              x1={top.x}
              y1={top.y}
              x2={v.x}
              y2={v.y}
              stroke={PRIMARY}
              strokeWidth="0.8"
              strokeOpacity="0.9"
            />
          ))}
          {ring.map((v, i) => (
            <line
              key={`b-${i}`}
              x1={bot.x}
              y1={bot.y}
              x2={v.x}
              y2={v.y}
              stroke={PRIMARY}
              strokeWidth="0.8"
              strokeOpacity="0.9"
            />
          ))}
          <circle cx={cy} cy={cy} r={r * 0.45} fill="url(#jarvis-core)" />
        </g>
      </svg>
    </>
  );
}
