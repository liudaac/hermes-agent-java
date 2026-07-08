import { lazy, Suspense } from "react";
import { Routes, Route, useLocation } from "react-router-dom";
import { BottomTabBar } from "@/components/BottomTabBar";
import { TopBar } from "@/components/TopBar";
import { useI18n } from "@/i18n";
import { JarvisCore } from "@hermes/jarvis";

const Home = lazy(() => import("@/pages/Home"));
const Teams = lazy(() => import("@/pages/Teams"));
const TeamDetail = lazy(() => import("@/pages/TeamDetail"));
const Templates = lazy(() => import("@/pages/Templates"));
const Approvals = lazy(() => import("@/pages/Approvals"));
const Runs = lazy(() => import("@/pages/Runs"));
const RunDetail = lazy(() => import("@/pages/RunDetail"));
const Insights = lazy(() => import("@/pages/Insights"));

function PageFallback() {
  return (
    <div className="flex h-[60vh] items-center justify-center text-[12px] tracking-[0.2em] uppercase text-[var(--color-text-muted)]">
      ...
    </div>
  );
}

/**
 * Top-level route table. Flat: depth ≤ 2. No nested layouts. Each page
 * owns its own visual treatment.
 */
export function PortalRouter() {
  const { t } = useI18n();
  const location = useLocation();
  const onHome = location.pathname === "/";

  return (
    <div className="min-h-screen">
      {!onHome && <TopBar title={titleFor(location.pathname, t)} back />}

      <Suspense fallback={<PageFallback />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/teams" element={<Teams />} />
          <Route path="/teams/:teamId" element={<TeamDetail />} />
          <Route path="/templates" element={<Templates />} />
          <Route path="/approvals" element={<Approvals />} />
          <Route path="/runs" element={<Runs />} />
          <Route path="/runs/:workspaceId/:runId" element={<RunDetail />} />
          <Route path="/insights" element={<Insights />} />
          {/* Unknown paths inside the portal render Home instead of
              navigating away. This matters because the portal's entry
              URL is /portal/index.html — when the user opens that
              directly, the router sees the path "/portal/index.html"
              and would otherwise treat it as an unknown route, firing
              the catch-all and bouncing the user back to the hub. */}
          <Route path="*" element={<Home />} />
        </Routes>
      </Suspense>

      <BottomTabBar />

      {/* Cross-space dialogue shell — design.md §0 */}
      <JarvisCore />
    </div>
  );
}

function titleFor(pathname: string, t: (k: string) => string): string {
  if (pathname.startsWith("/teams")) return t("nav.teams");
  if (pathname.startsWith("/templates")) return t("nav.templates");
  if (pathname.startsWith("/approvals")) return t("nav.approvals");
  if (pathname.startsWith("/runs")) return t("nav.runs");
  if (pathname.startsWith("/insights")) return t("nav.insights");
  return t("app.name");
}
