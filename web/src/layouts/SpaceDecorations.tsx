/**
 * SpaceDecorations — subtle per-space background/glow accents.
 *
 * Renders nothing visible to the user by default. Each space can opt
 * in to its own decoration by adding a component to this file.
 *
 * Aligned with the three-space refactor: each space has a unique
 * visual signature:
 *  - Portal:  subtle aurora gradient at top, glass card pattern
 *  - Ops:     minimal — terminal grid lines
 *  - NOC:     amber glow pulse, alert cadence indicator
 */

import type { SpaceName } from "@/lib/routing/spaces";

interface DecorationProps {
  active: boolean;
}

function PortalDecoration({ active }: DecorationProps) {
  if (!active) return null;
  return (
    <div
      aria-hidden
      className="pointer-events-none fixed inset-x-0 top-12 z-0 h-32 opacity-40"
      style={{
        background:
          "radial-gradient(ellipse 80% 100% at 50% 0%, rgba(249, 168, 212, 0.18), transparent 60%)",
      }}
    />
  );
}

function OpsDecoration({ active }: DecorationProps) {
  if (!active) return null;
  return (
    <div
      aria-hidden
      className="pointer-events-none fixed inset-0 z-0 opacity-[0.04]"
      style={{
        backgroundImage:
          "linear-gradient(to right, currentColor 1px, transparent 1px), linear-gradient(to bottom, currentColor 1px, transparent 1px)",
        backgroundSize: "32px 32px",
      }}
    />
  );
}

function NocDecoration({ active }: DecorationProps) {
  if (!active) return null;
  return (
    <div
      aria-hidden
      className="pointer-events-none fixed inset-0 z-0"
      style={{
        background:
          "radial-gradient(ellipse 100% 60% at 50% 100%, rgba(251, 191, 36, 0.12), transparent 70%)",
      }}
    />
  );
}

const DECORATIONS: Record<SpaceName, (p: DecorationProps) => React.JSX.Element | null> = {
  portal: PortalDecoration,
  ops: OpsDecoration,
  noc: NocDecoration,
};

export function SpaceDecorations({ space }: { space: SpaceName }) {
  return Object.entries(DECORATIONS).map(([key, Comp]) => (
    <Comp key={key} active={key === space} />
  ));
}
