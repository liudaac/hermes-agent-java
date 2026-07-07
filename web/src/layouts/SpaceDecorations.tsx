/**
 * SpaceDecorations — subtle per-space background/glow accents.
 *
 * Renders nothing visible to the user by default. Each space can opt
 * in to its own decoration by adding a component to this file.
 *
 * Portal is now a separate SPA — it carries its own decorations there
 * (see web/portal/src/theme.css `.aurora` / `.grain`). The combined
 * dashboard only renders Ops and NOC decorations.
 *
 *  - Ops:  minimal — terminal grid lines
 *  - NOC:  amber glow pulse, alert cadence indicator
 */

import type { SpaceName } from "@/lib/routing/spaces";

interface DecorationProps {
  active: boolean;
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
  ops: OpsDecoration,
  noc: NocDecoration,
};

export function SpaceDecorations({ space }: { space: SpaceName }) {
  return Object.entries(DECORATIONS).map(([key, Comp]) => (
    <Comp key={key} active={key === space} />
  ));
}
