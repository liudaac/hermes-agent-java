import { lazy, Suspense } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import { OpsTopBar } from "@/components/OpsTopBar";
import { JarvisCore } from "@hermes/jarvis";

const StatusPage = lazy(() => import("@/pages/StatusPage"));
const PlaygroundPage = lazy(() => import("@/pages/PlaygroundPage"));
const ComparePage = lazy(() => import("@/pages/ComparePage"));
const SessionsPage = lazy(() => import("@/pages/SessionsPage"));
const AnalyticsPage = lazy(() => import("@/pages/AnalyticsPage"));
const LogsPage = lazy(() => import("@/pages/LogsPage"));
const CronPage = lazy(() => import("@/pages/CronPage"));
const SkillsPage = lazy(() => import("@/pages/SkillsPage"));
const ToolsPage = lazy(() => import("@/pages/ToolsPage"));
const TenantsPage = lazy(() => import("@/pages/TenantsPage"));
const ConfigPage = lazy(() => import("@/pages/ConfigPage"));
const EnvPage = lazy(() => import("@/pages/EnvPage"));
const OrgPage = lazy(() => import("@/pages/OrgPage"));

function PageLoading() {
  return (
    <div className="flex h-64 items-center justify-center text-sm tracking-[0.12em] opacity-70">
      Loading...
    </div>
  );
}

/**
 * Ops router. Paths are root-relative inside the ops SPA.
 *
 * Note: the ops SPA is a fully independent entry — there are no longer
 * any cross-space /portal/* or /noc/* paths. Cross-product links are
 * full-page navigations via <a href> in the topbar.
 */
export function OpsRouter() {
  return (
    <div className="min-h-screen bg-background text-foreground antialiased">
      <OpsTopBar />
      <main className="mx-auto w-full max-w-[1600px] flex-1 px-3 sm:px-6 pt-4 sm:pt-6 pb-4 sm:pb-8">
        <Suspense fallback={<PageLoading />}>
          <Routes>
            <Route path="/" element={<StatusPage />} />
            <Route path="/playground" element={<PlaygroundPage />} />
            <Route path="/compare" element={<ComparePage />} />
            <Route path="/sessions" element={<SessionsPage />} />
            <Route path="/analytics" element={<AnalyticsPage />} />
            <Route path="/logs" element={<LogsPage />} />
            <Route path="/cron" element={<CronPage />} />
            <Route path="/skills" element={<SkillsPage />} />
            <Route path="/tools" element={<ToolsPage />} />
            <Route path="/tenants" element={<TenantsPage />} />
            <Route path="/config" element={<ConfigPage />} />
            <Route path="/env" element={<EnvPage />} />
            <Route path="/org" element={<OrgPage />} />
            <Route path="/sla" element={<AnalyticsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </main>

      {/* Cross-space dialogue shell — design.md §0 */}
      <JarvisCore />
    </div>
  );
}
