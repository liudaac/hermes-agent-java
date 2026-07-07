/**
 * Scanline — the 1px purple line that crosses the HUD ring every 8s
 * (design.md §2.2.1 "扫描线: 1px 紫色水平线, 0.8s 穿越全环, 每 8s 触发").
 *
 * Keyframes are inlined (no external CSS dep).
 */
interface ScanlineProps {
  size?: number;
  className?: string;
  /** Override the animation duration (defaults to 8s cycle / 0.8s sweep). */
  cycleMs?: number;
}

const VIOLET = "oklch(0.65 0.20 290)";

export function Scanline({ size = 120, className, cycleMs = 8000 }: ScanlineProps) {
  const sweepMs = Math.round(cycleMs * 0.1); // 0.8s for 8s cycle → 10%
  const halfSize = size / 2;
  return (
    <>
      <style
        dangerouslySetInnerHTML={{
          __html: `@keyframes jarvis-scan-sweep {
            0% { transform: translateY(-${halfSize}px); opacity: 0; }
            20% { opacity: 1; }
            80% { opacity: 1; }
            100% { transform: translateY(${halfSize}px); opacity: 0; }
          }`,
        }}
      />
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        className={className}
        aria-hidden
      >
        <line
          x1="0"
          y1={size / 2}
          x2={size}
          y2={size / 2}
          stroke={VIOLET}
          strokeWidth="1"
          strokeOpacity="0.7"
          style={{
            animation: `jarvis-scan-sweep ${sweepMs}ms ease-in-out ${cycleMs - sweepMs}ms infinite`,
          }}
        />
      </svg>
    </>
  );
}
