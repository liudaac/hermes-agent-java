import { lazy, Suspense } from "react";
import { Routes, Route, useLocation } from "react-router-dom";
import { BottomTabBar } from "@/components/BottomTabBar";
import { TopBar } from "@/components/TopBar";
import { PageSkeleton } from "@/components/Skeleton";
import { JarvisCore } from "@hermes/jarvis";

const Home = lazy(() => import("@/pages/Home"));
const Teams = lazy(() => import("@/pages/Teams"));
const TeamDetail = lazy(() => import("@/pages/TeamDetail"));
const Templates = lazy(() => import("@/pages/Templates"));
const Approvals = lazy(() => import("@/pages/Approvals"));
const Runs = lazy(() => import("@/pages/Runs"));
const RunDetail = lazy(() => import("@/pages/RunDetail"));
const UserAdmin = lazy(() => import("@/pages/UserAdmin"));
const SpaceAdmin = lazy(() => import("@/pages/SpaceAdmin"));
const OrgAdmin = lazy(() => import("@/pages/OrgAdmin"));

function PageFallback() {
  const location = useLocation();
  if (location.pathname.startsWith("/runs/")) return <PageSkeleton variant="detail" />;
  if (location.pathname.startsWith("/teams/")) return <PageSkeleton variant="detail" />;
  if (location.pathname.startsWith("/templates")) return <PageSkeleton variant="cards" />;
  if (location.pathname.startsWith("/teams")) return <PageSkeleton variant="cards" />;
  return <PageSkeleton variant="list" />;
}

/**
 * Portal router - 5 tab flat structure.
 *
 * 首页 / 员工 / 运行 / 审批 / 我的
 *
 * 「我的」aggregates user-layer concerns (profile, memory, capabilities,
 * preferences, sessions, improvement) and links to SpaceAdmin / OrgAdmin.
 */
export function PortalRouter() {
  const location = useLocation();
  const onHome = location.pathname === "/";

  return (
    <div className="min-h-screen">
      {!onHome && <TopBar title={titleFor(location.pathname)} back />}

      <Suspense fallback={<PageFallback />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/teams" element={<Teams />} />
          <Route path="/teams/:teamId" element={<TeamDetail />} />
          <Route path="/templates" element={<Templates />} />
          <Route path="/approvals" element={<Approvals />} />
          <Route path="/runs" element={<Runs />} />
          <Route path="/runs/:workspaceId/:runId" element={<RunDetail />} />
          {/* 「我的」- user layer hub */}
          <Route path="/me" element={<UserAdmin />} />
          {/* Three-layer admin sub-pages */}
          <Route path="/space-admin" element={<SpaceAdmin />} />
          <Route path="/org-admin" element={<OrgAdmin />} />
          <Route path="*" element={<Home />} />
        </Routes>
      </Suspense>

      <BottomTabBar />
      <JarvisCore />
    </div>
  );
}

function titleFor(pathname: string): string {
  if (pathname.startsWith("/teams")) return "数字员工";
  if (pathname.startsWith("/templates")) return "场景模板";
  if (pathname.startsWith("/approvals")) return "待审批";
  if (pathname.startsWith("/runs")) return "我的运行";
  if (pathname.startsWith("/me")) return "我的";
  if (pathname.startsWith("/space-admin")) return "空间管理";
  if (pathname.startsWith("/org-admin")) return "组织管理";
  return "Hermes";
}
