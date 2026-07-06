/**
 * Space-scoped layout — adjusts the outer container and applies
 * space-specific data-space attributes so the CSS can branch
 * (padding density, font tone, alert banner visibility).
 *
 * Aligned with the three-space refactor:
 *  - Portal:  airy padding, AI-SaaS feel (BusinessPortalStandalonePage
 *             provides its own header — this layout only nudges the
 *             surrounding chrome)
 *  - Ops:     tighter padding, terminal/DS feel, classic Hermes look
 *  - NOC:     full-width dark feel, amber alert banner, glow accents
 *
 * All three variants share the same <main>/<footer> shape so the
 * App header above them stays a single source of truth for nav.
 */

import { useEffect, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { type SpaceName } from "@/lib/routing/spaces";
import { pathToSpace } from "@/lib/routing/nav";

export interface SpaceLayoutProps {
  children: ReactNode;
}

export function SpaceLayout({ children }: SpaceLayoutProps) {
  const location = useLocation();
  const space: SpaceName =
    pathToSpace(location.pathname) ?? "portal";

  useEffect(() => {
    // Reflect the active space on <html> so the global CSS can branch.
    // Using data-attribute on documentElement so descendants can scope by
    // `[data-space="..."]` without re-rendering.
    const html = document.documentElement;
    html.dataset.space = space;
    return () => {
      // On unmount (e.g. during tests) reset; subsequent renders set it again.
      if (html.dataset.space === space) {
        delete html.dataset.space;
      }
    };
  }, [space]);

  return <div data-space={space}>{children}</div>;
}
