import type { ReactNode } from "react";

export function AuroraBackground({ children }: { children: ReactNode }) {
  return <div className="min-h-screen bg-background">{children}</div>;
}
